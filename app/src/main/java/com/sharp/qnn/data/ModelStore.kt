package com.sharp.qnn.data

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.sharp.qnn.pipeline.QnnJni
import com.sharp.qnn.util.MsgKey
import com.sharp.qnn.util.FileUtil
import com.sharp.qnn.util.FileUtil.ensureDir
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Model metadata DataStore (singleton) */
private val Context.modelDataStore: DataStore<Preferences> by preferencesDataStore(name = "sharp_models")

/**
 * IE / REST_A / REST_B / REST_C 五个固定槽位。
 * Model store: manages the five fixed slots PE / IE / REST_A / REST_B / REST_C.
 *
 * - 元数据通过 DataStore + JSON 序列化持久化；
 * - Metadata is persisted via DataStore with JSON serialization.
 * - 目录结构 (固定, 不支持自定义):
 * - Fixed directory layout (not customizable):
 *   <modelRoot>/
 *     bin/  ← .bin 模型 + DLC 编译产物
 *     bin/  ← .bin models + DLC compilation artifacts
 *     dlc/  ← .dlc 模型
 *     dlc/  ← .dlc models
 * bin/<type>_<hash>.bin
 * - Compiled artifact path: <modelRoot>/bin/<type>_<hash>.bin
 */
class ModelStore(
    private val context: Context,
    private val settings: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _models = MutableStateFlow<Map<ModelType, ModelEntry>>(emptyMap())
    val models: StateFlow<Map<ModelType, ModelEntry>> = _models.asStateFlow()

    // Compilation cancel flag (native graphFinalize cannot be interrupted; this
    // flag is used to discard the result once it returns)
    private val cancelRequested = ConcurrentHashMap.newKeySet<ModelType>()

    init {
        // On init, restore persisted metadata (keeping importTime etc.) first,
        // then scan directories to align with actual files
        scope.launch {
            loadFromDisk()
            scanModelDirectory()
        }
    }

    /** Model root directory: /storage/emulated/0/Android/data/<pkg>/files/sharp_models */
    fun modelRootDir(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "sharp_models").also { ensureDir(it) }
    }

    /** bin 目录: .bin 模型 + DLC 编译产物 */
    /** bin directory: .bin models + DLC compilation artifacts */
    private fun binDir(): File = File(modelRootDir(), "bin").also { ensureDir(it) }

    /** dlc 目录: .dlc 模型 */
    /** dlc directory: .dlc models */
    private fun dlcDir(): File = File(modelRootDir(), "dlc").also { ensureDir(it) }

    /** Get the model info of a slot */
    fun getModel(type: ModelType): ModelEntry? = _models.value[type]

    /** Import a model (.bin or .dlc). Returns success/failure with an error message. */
    suspend fun importModel(type: ModelType, uri: Uri): Result<Unit> = mutex.withLock {
        try {
            val fileName = FileUtil.getFileNameFromUri(context, uri)
            val size = FileUtil.getFileSize(context, uri)
            android.util.Log.i(TAG, "Import model ${type.displayName}: $fileName (${size}B)")
            val ext = fileName.substringAfterLast('.', "").lowercase()
            val format = when (ext) {
                "bin" -> ModelFormat.BIN
                "dlc" -> ModelFormat.DLC
                else -> return@withLock Result.failure(IllegalArgumentException(MsgKey.k(MsgKey.ERR_IMPORT_FORMAT, ext)))
            }

            // Copy to a temp file first; the old model stays intact until the
            // new file is fully written
            val storageDir = if (format == ModelFormat.BIN) binDir() else dlcDir()
            val destFile = File(storageDir, fileName)
            val tmpFile = File(storageDir, "$fileName.partial")
            if (!FileUtil.copyUriToFile(context, uri, tmpFile)) {
                runCatching { tmpFile.delete() }
                return@withLock Result.failure(IOException(MsgKey.ERR_IMPORT_COPY))
            }
            // The new file is complete; now remove the old model and swap atomically
            removeInternal(type)
            runCatching { if (destFile.exists()) destFile.delete() }
            if (!tmpFile.renameTo(destFile)) {
                // rename within the same directory normally succeeds; a plain
                // copy is used as a fallback
                val moved = runCatching { tmpFile.copyTo(destFile, overwrite = true) }.isSuccess
                runCatching { tmpFile.delete() }
                if (!moved) return@withLock Result.failure(IOException(MsgKey.ERR_IMPORT_MOVE))
            }

            // Validate the model file integrity (corrupted / format mismatch / empty)
            val validateErr = QnnJni.validateModelFile(destFile.absolutePath, format.name.lowercase())
            if (validateErr != null) {
                // The file was copied but validation failed; remove the invalid file and report
                runCatching { destFile.delete() }
                return@withLock Result.failure(IOException("${MsgKey.ERR_IMPORT_VALIDATE}: $validateErr"))
            }

            val realSize = if (size > 0) size else destFile.length()
            val entry = ModelEntry(
                type = type,
                format = format,
                sourcePath = destFile.absolutePath,
                sourceName = fileName,
                compiledBinPath = if (format == ModelFormat.BIN) destFile.absolutePath else null,
                status = if (format == ModelFormat.BIN) ModelStatus.COMPILED else ModelStatus.UNCOMPILED,
                fileSize = realSize,
                importTime = System.currentTimeMillis()
            )
            updateEntry(type, entry)
            android.util.Log.i(TAG, "Import done: ${type.displayName} -> ${entry.sourcePath}")
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Import failed: ${type.displayName}", e)
            Result.failure(e)
        }
    }

    /**
     * 编译 DLC → .bin。仅当原始格式为 DLC 且未编译时可用。
     * Compile a DLC into a .bin. Only available when the source format is DLC
     * and it has not been compiled yet.
     * bin/<type>_<hash>.bin
     * Compiled artifact path: <modelRoot>/bin/<type>_<hash>.bin
     *
     * native 在编译步骤间隙响应 [cancelCompile] (阻塞的 graphFinalize 返回后立即生效),
     * The native side reacts to [cancelCompile] between compilation steps (right
     * after the blocking graphFinalize returns) and frees resources on cancel;
     * here the cancel flag is re-checked and the result discarded.
     */
    suspend fun compileModel(type: ModelType): Result<Unit> = mutex.withLock {
        try {
            val entry = _models.value[type]
                ?: return@withLock Result.failure(IllegalStateException(MsgKey.ERR_IMPORT_EMPTY))
            if (entry.format != ModelFormat.DLC)
                return@withLock Result.failure(IllegalStateException(MsgKey.ERR_DLC_ONLY))
            if (entry.status == ModelStatus.COMPILED && entry.compiledBinPath != null)
                return@withLock Result.success(Unit)

            // Clear the stale cancel flag and mark as compiling
            cancelRequested.remove(type)
            updateEntry(type, entry.copy(status = ModelStatus.COMPILING))

            val hash = shortHash(entry.sourcePath + entry.importTime)
            val outBin = File(binDir(), "${type.code}_$hash.bin")
            android.util.Log.i(TAG, "Compile start ${type.displayName}: ${entry.sourcePath}")

            // QnnJni.compileDlc is a blocking JNI call (may take tens of seconds),
            // so it must run on an IO thread
            val compileStart = System.currentTimeMillis()
            val ok = withContext(Dispatchers.IO) {
                QnnJni.compileDlc(type.code, entry.sourcePath, outBin.absolutePath)
            }
            val elapsed = System.currentTimeMillis() - compileStart

            // Check whether compilation was cancelled
            if (cancelRequested.remove(type)) {
                runCatching { if (outBin.exists()) outBin.delete() }
                updateEntry(type, entry.copy(status = ModelStatus.UNCOMPILED))
                android.util.Log.i(TAG, "Compile cancelled: ${type.displayName}")
                return@withLock Result.failure(IOException(MsgKey.ERR_COMPILE_CANCEL))
            }

            if (!ok) {
                // Compilation failed: clean up a possibly partial artifact
                runCatching { if (outBin.exists()) outBin.delete() }
                updateEntry(type, entry.copy(status = ModelStatus.UNCOMPILED))
                return@withLock Result.failure(IOException(MsgKey.ERR_DLC_NATIVE))
            }

            updateEntry(type, entry.copy(
                compiledBinPath = outBin.absolutePath,
                status = ModelStatus.COMPILED
            ))
            android.util.Log.i(TAG, "Compile done: ${type.displayName} (${elapsed}ms, ${outBin.length()}B)")
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Compile exception: ${type.displayName}", e)
            cancelRequested.remove(type)
            _models.value[type]?.let { cur ->
                if (cur.status == ModelStatus.COMPILING) {
                    updateEntry(type, cur.copy(status = ModelStatus.UNCOMPILED))
                }
            }
            Result.failure(e)
        }
    }

    /** Request cancellation of the current compilation: notify native to abort
     * and mark the result as discarded */
    fun cancelCompile(type: ModelType) {
        if (_models.value[type]?.status == ModelStatus.COMPILING) {
            cancelRequested.add(type)
            QnnJni.cancelCompile()
        }
    }

    /** Remove the model of a slot (source file and compiled artifacts) */
    suspend fun removeModel(type: ModelType): Result<Unit> = mutex.withLock {
        try {
            removeInternal(type)
            persistEntry(type, null)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 清除 APP 缓存 (cacheDir: 推理工作目录、临时文件等), 不影响模型文件与编译产物。
     * Clear the app cache (cacheDir: inference work dir, temp files, etc.)
     * without touching model files or compiled artifacts.
     * 目录数, 释放的字节数)
     * @return (number of cleared files/dirs, freed bytes)
     */
    suspend fun clearAppCache(): Result<Pair<Int, Long>> = mutex.withLock {
        try {
            val cacheDir = context.cacheDir
            var count = 0
            var bytes = 0L
            cacheDir.listFiles()?.forEach { f ->
                bytes += FileUtil.sizeOf(f)
                if (FileUtil.deleteRecursively(f)) count++
            }
            Result.success(count to bytes)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

        // ====== Internals ======

    /**
     * 目录, 将模型文件一一对应到 5 个槽位。
     * Scans bin/ and dlc/ and maps model files to the 5 slots.
     *
     * Matching rules (persisted metadata wins, keeps the imported file name/format):
     * 文件名, 仅刷新大小与编译产物状态
     * - persisted sourcePath still exists → reuse its format/name, refresh size and artifact status
     * - 否则按命名约定识别新文件:
     * - otherwise recognize new files by naming convention:
     *   - bin/ 下 pe.bin (精确) → 已编译 BIN
     *   - pe.bin (exact) under bin/ → compiled BIN
     *   有对应编译产物 (pe_*.bin) 则视为已编译
     *   - pe.dlc (exact) under dlc/ → DLC; compiled if a matching artifact (pe_*.bin) exists in bin/
     *   - bin/ 下 <code>_ 前缀 .bin (独立编译产物) → BIN
     *   - <code>_-prefixed .bin under bin/ (standalone artifact) → BIN
     *
     * Triggers: app start / entering the models tab / file-manager operations.
     */
    suspend fun scanModelDirectory() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val binFiles = binDir().listFiles()?.filter { it.isFile } ?: emptyList()
            val dlcFiles = dlcDir().listFiles()?.filter { it.isFile } ?: emptyList()

            val map = mutableMapOf<ModelType, ModelEntry>()
            for (type in ModelType.entries) {
                val entry = scanEntry(type, binFiles, dlcFiles)
                if (entry == null) {
                    persistEntry(type, null)
                    continue
                }
                map[type] = entry
                persistEntry(type, entry)
            }
            _models.value = map
        }
    }

    /** Compute the model entry of a single slot */
    private fun scanEntry(
        type: ModelType,
        binFiles: List<File>,
        dlcFiles: List<File>
    ): ModelEntry? {
        val persisted = _models.value[type]

 
        // 0. Persisted metadata first: keeps the imported file name/format so an
        //    artifact does not shadow the source
        if (persisted != null) {
            val srcFile = File(persisted.sourcePath).takeIf { it.exists() }
            if (srcFile != null) {
                val compiled = persisted.compiledBinPath?.let { File(it) }?.takeIf { it.exists() }
                // COMPILING only exists mid-compilation; seeing it during a scan
                // (e.g. after app restart) means compilation was interrupted, so reset
                val status = when {
                    persisted.format == ModelFormat.BIN -> ModelStatus.COMPILED
                    compiled != null -> ModelStatus.COMPILED
                    else -> ModelStatus.UNCOMPILED
                }
                return ModelEntry(
                    type, persisted.format, srcFile.absolutePath, persisted.sourceName,
                    compiledBinPath = compiled?.absolutePath,
                    status = status,
                    fileSize = srcFile.length(), importTime = persisted.importTime
                )
            }
        }

        // The rules below only recognize manually copied files (no persisted record)

        val binExact = binFiles.firstOrNull { it.name.equals("${type.code}.bin", ignoreCase = true) }
        val dlcExact = dlcFiles.firstOrNull { it.name.equals("${type.code}.dlc", ignoreCase = true) }
        val binCompiled = binFiles.firstOrNull {
            it.extension.equals("bin", ignoreCase = true) &&
                    it.name.substringBeforeLast('.').lowercase().startsWith("${type.code}_")
        }

        // 1. bin/ 精确 .bin: 直接可用
        // 1. Exact .bin under bin/: directly usable
        if (binExact != null) {
            return ModelEntry(
                type, ModelFormat.BIN, binExact.absolutePath, binExact.name,
                compiledBinPath = binExact.absolutePath, status = ModelStatus.COMPILED,
                fileSize = binExact.length(), importTime = System.currentTimeMillis()
            )
        }

        // 2. dlc/ 精确 .dlc: 优先于独立编译产物
        // 2. Exact .dlc under dlc/: takes precedence over standalone artifacts
        if (dlcExact != null) {
            val compiled = binCompiled?.takeIf { it.exists() }
            return ModelEntry(
                type, ModelFormat.DLC, dlcExact.absolutePath, dlcExact.name,
                compiledBinPath = compiled?.absolutePath,
                status = if (compiled != null) ModelStatus.COMPILED else ModelStatus.UNCOMPILED,
                fileSize = dlcExact.length(), importTime = System.currentTimeMillis()
            )
        }

        // 3. bin/ 前缀 .bin: 独立编译产物 (手动拷入, 无对应 DLC)
        // 3. Prefixed .bin under bin/: standalone artifact (manually copied, no DLC)
        if (binCompiled != null) {
            return ModelEntry(
                type, ModelFormat.BIN, binCompiled.absolutePath, binCompiled.name,
                compiledBinPath = binCompiled.absolutePath, status = ModelStatus.COMPILED,
                fileSize = binCompiled.length(), importTime = System.currentTimeMillis()
            )
        }

        return null
    }

    private fun removeInternal(type: ModelType) {
        _models.value[type]?.let { e ->
            // Delete the source file (only when it differs from the artifact)
            runCatching { File(e.sourcePath).takeIf { it.exists() && it.absolutePath != e.compiledBinPath }?.delete() }
            // Delete the compiled artifact
            runCatching { e.compiledBinPath?.let { File(it) }?.takeIf { it.exists() }?.delete() }
        }
        _models.value = _models.value - type
    }

    private suspend fun updateEntry(type: ModelType, entry: ModelEntry) {
        _models.value = _models.value + (type to entry)
        persistEntry(type, entry)
    }

    private suspend fun persistEntry(type: ModelType, entry: ModelEntry?) {
        context.modelDataStore.edit { prefs ->
            val key = stringPreferencesKey(type.code)
            if (entry == null) prefs.remove(key)
            else prefs[key] = entry.toJson().toString()
        }
    }

    private suspend fun loadFromDisk() {
        val prefs = context.modelDataStore.data.first()
        val map = mutableMapOf<ModelType, ModelEntry>()
        for (type in ModelType.entries) {
            val key = stringPreferencesKey(type.code)
            prefs[key]?.let { jsonStr ->
                runCatching { ModelEntry.fromJson(JSONObject(jsonStr)) }.getOrNull()?.let {
                    map[type] = it
                }
            }
        }
        _models.value = map
    }

    private fun shortHash(input: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(8)
    }

    companion object {
        private const val TAG = "ModelStore"
    }
}
