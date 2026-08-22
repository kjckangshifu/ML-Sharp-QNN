package com.sharp.qnn.pipeline

import android.content.Context
import android.net.Uri
import com.sharp.qnn.R
import com.sharp.qnn.data.ModelEntry
import com.sharp.qnn.data.ModelFormat
import com.sharp.qnn.data.ModelStatus
import com.sharp.qnn.data.ModelType
import com.sharp.qnn.data.SettingsRepository
import com.sharp.qnn.data.ModelStore
import com.sharp.qnn.util.FileUtil
import com.sharp.qnn.util.FileUtil.ensureDir
import com.sharp.qnn.util.LocaleUtil
import com.sharp.qnn.util.MsgKey
import com.sharp.qnn.util.SkelExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Pipeline 编排器。
 * Pipeline orchestrator.
 *
 * Chains the full inference flow:
 * prepImage → runPre → PE → IE → Merge → REST(A/B/C) → Post(PLY)
 *
 * 通过 [ProgressCallback] 接收 native 层进度，并以 [StateFlow] 暴露给 UI。
 * Receives native progress through [ProgressCallback] and exposes it to the
 * UI as a [StateFlow].
 * 每步检查模型是否已编译并加载，DLC 需先编译。
 * Each step checks that models are compiled and loaded; DLCs must be compiled first.
 */
class PipelineManager(
    private val context: Context,
    private val modelStore: ModelStore,
    private val settings: SettingsRepository
) : ProgressCallback {

    private val _state = MutableStateFlow(PipelineState())
    val state: StateFlow<PipelineState> = _state.asStateFlow()

    private val runMutex = Mutex()

    // Runtime state
    @Volatile
    private var initialized = false
    private val loadedModels = mutableSetOf<ModelType>()
    private val loadedModelsLock = Any()
    private var pipelineStartTime = 0L
    private var stageStartTime = 0L
    private var compileStageStartTime = 0L

    // Locale-wrapped context for this run (consistent for the whole run,
    // even if the language setting changes mid-run)
    private var langCtx: Context = context

    // prepImage output (used by the later Post stage)
    private var prepMeta: FloatArray? = null

    /** Localized model name for the current run's language */
    private fun modelName(type: ModelType): String = LocaleUtil.modelName(langCtx, type)

    /**
     * 运行完整 Pipeline。
     * Run the full pipeline.
     * @param imageUri 用户选择的图片 URI
     * @param imageUri the image URI selected by the user
     * @return 成功与否
     * @return success/failure
     */
    suspend fun runPipeline(imageUri: Uri): Result<Unit> = runMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                // Pin the language for this run (detail messages use it)
                val plySettings = settings.settingsFlow.first()
                langCtx = LocaleUtil.wrap(context, plySettings.language)

                // Reset state (loadedModels is kept; already-loaded models are reused)
                pipelineStartTime = System.currentTimeMillis()
                val hasUncompiled = ModelType.entries.any { type ->
                    modelStore.getModel(type)?.let {
                        it.format == ModelFormat.DLC && it.status != ModelStatus.COMPILED
                    } == true
                }
                val stages = {
                    val base = if (hasUncompiled) {
                        val initIdx = DEFAULT_STAGES.indexOfFirst { it.id == PREPARE_STAGE_ID }
                        DEFAULT_STAGES.toMutableList().apply {
                            add(initIdx + 1, StageState(id = COMPILE_STAGE_ID, name = "Model Compilation", nameRes = R.string.stage_compile))
                        }
                    } else {
                        DEFAULT_STAGES.toMutableList()
                    }
                    // Decide PLY optimization inclusion based on settings, and set stage name dynamically
                    if (plySettings.plyOptimize) {
                        val parts = mutableListOf<String>()
                        if (plySettings.plyPruneThreshold > 0.0) parts.add("Prune")
                        if (plySettings.plySorNeighbors > 0) parts.add("SOR")
                        if (plySettings.plyMergeRatio in 0.001..0.999) parts.add("kNN")
                        base[base.indexOfFirst { it.id == PLY_OPTIMIZE_STAGE_ID }] =
                            StageState(id = PLY_OPTIMIZE_STAGE_ID, name = "PLY Optimize", nameRes = R.string.stage_ply_optimize, total = parts.size.coerceAtLeast(1))
                    } else {
                        base.removeAll { it.id == PLY_OPTIMIZE_STAGE_ID }
                    }
                    base
                }()
                _state.value = PipelineState(stages = stages, isRunning = true)

                // Start the init stage immediately so the user sees progress
                onStageStart(PREPARE_STAGE_ID, "Initialization")
                onProgress(PREPARE_STAGE_ID, 0, ModelType.entries.size, 0, MsgKey.DETAIL_INIT_QNN)

                // Register the callback
                runCatching { QnnJni.setProgressCallback(this@PipelineManager) }

                // 1. Initialize the QNN runtime and ensure models are ready
                ensureReady()

                // Init stage done
                onStageComplete(PREPARE_STAGE_ID, "Initialization", System.currentTimeMillis() - pipelineStartTime)

                // 2. Prepare the work directory
                val workDir = File(context.cacheDir, "sharp_work").also {
                    if (it.exists()) FileUtil.deleteRecursively(it)
                    ensureDir(it)
                }

                // 3. Copy the input image
                val fileName = FileUtil.getFileNameFromUri(context, imageUri)
                android.util.Log.i(TAG, "=== Pipeline started, image=$fileName ===")
                val ext = fileName.substringAfterLast('.', "jpg").lowercase()
                val inputFile = File(workDir, "input.$ext")
                if (!FileUtil.copyUriToFile(context, imageUri, inputFile)) {
                    fail(MsgKey.ERR_COPY_IMAGE_FAILED)
                    return@withContext Result.failure(IOException("copy image failed"))
                }

                // 4. Stage 0: decode the image (prepImage)
                val imageRaw = File(workDir, "image.raw")
                val meta = runStage(0) {
                    QnnJni.prepImage(inputFile.absolutePath, imageRaw.absolutePath)
                } ?: run {
                    fail(MsgKey.ERR_PREP_NULL)
                    return@withContext Result.failure(IOException("prepImage failed"))
                }
                prepMeta = meta

                // 5. Stage 1: pre-process and split patches (runPre)
                if (!runStage(1) { QnnJni.runPre(imageRaw.absolutePath, workDir.absolutePath) }) {
                    fail(MsgKey.ERR_PRE_FAILED)
                    return@withContext Result.failure(IOException("runPre failed"))
                }

                // 6. Stage 2: PE inference
                if (!runStage(2) { QnnJni.runPatchEncoder(workDir.absolutePath) }) {
                    fail(MsgKey.ERR_PE_INFER_FAILED)
                    return@withContext Result.failure(IOException("runPatchEncoder failed"))
                }
                // All PE outputs are on disk and the model is no longer needed:
                // release its context memory immediately (QnnContext_free)
                android.util.Log.i(TAG, "PE done, releasing PE model")
                runCatching { QnnJni.freeModel(ModelType.PE.code) }
                synchronized(loadedModelsLock) { loadedModels.remove(ModelType.PE) }

                // 7. Stage 3: IE inference
                if (!runStage(3) { QnnJni.runImageEncoder(workDir.absolutePath) }) {
                    fail(MsgKey.ERR_IE_INFER_FAILED)
                    return@withContext Result.failure(IOException("runImageEncoder failed"))
                }
                android.util.Log.i(TAG, "IE done, releasing IE model")
                runCatching { QnnJni.freeModel(ModelType.IE.code) }
                synchronized(loadedModelsLock) { loadedModels.remove(ModelType.IE) }

                // 8. Stage 4: Merge
                // 8. Stage 4: Merge
                // Note: directory names must match the JNI layer (sharp_jni.cpp): out_pe / out_ie
                val peOutDir = File(workDir, "out_pe")
                val ieOutDir = File(workDir, "out_ie")
                if (!runStage(4) { QnnJni.runMerge(workDir.absolutePath, peOutDir.absolutePath, ieOutDir.absolutePath) }) {
                    fail(MsgKey.ERR_MERGE_FAILED)
                    return@withContext Result.failure(IOException("runMerge failed"))
                }

                // 9. Stage 5: REST Seg A (feature fusion)
                if (!runStage(5) { QnnJni.runRestSegA(workDir.absolutePath) }) {
                    fail(MsgKey.ERR_REST_A_FAILED)
                    return@withContext Result.failure(IOException("runRestSegA failed"))
                }
                android.util.Log.i(TAG, "REST A done, releasing REST_A model")
                runCatching { QnnJni.freeModel(ModelType.REST_A.code) }
                synchronized(loadedModelsLock) { loadedModels.remove(ModelType.REST_A) }

                // 10. Stage 6: REST Seg B (disparity estimation)
                if (!runStage(6) { QnnJni.runRestSegB(workDir.absolutePath) }) {
                    fail(MsgKey.ERR_REST_B_FAILED)
                    return@withContext Result.failure(IOException("runRestSegB failed"))
                }
                android.util.Log.i(TAG, "REST B done, releasing REST_B model")
                runCatching { QnnJni.freeModel(ModelType.REST_B.code) }
                synchronized(loadedModelsLock) { loadedModels.remove(ModelType.REST_B) }

                // 11. Stage 7: REST Seg C (Gaussian increment)
                val fpx = prepMeta?.getOrNull(0) ?: 0f
                val origW = (prepMeta?.getOrNull(2) ?: 0f).toInt()
                val origH = (prepMeta?.getOrNull(3) ?: 0f).toInt()
                if (!runStage(7) { QnnJni.runRestSegC(workDir.absolutePath, fpx, origW) }) {
                    fail(MsgKey.ERR_REST_C_FAILED)
                    return@withContext Result.failure(IOException("runRestSegC failed"))
                }
                // REST C output is on disk (delta.raw); the model is no longer needed
                android.util.Log.i(TAG, "REST C done, releasing REST_C model")
                runCatching { QnnJni.freeModel(ModelType.REST_C.code) }
                synchronized(loadedModelsLock) { loadedModels.remove(ModelType.REST_C) }

                // 12. Stage 8: Post (PLY generation)
                val plyPath = File(workDir, "output.ply").absolutePath
                if (!runStage(8) { QnnJni.runPost(workDir.absolutePath, fpx, origW, origH, plyPath) }) {
                    fail(MsgKey.ERR_POST_FAILED)
                    return@withContext Result.failure(IOException("runPost failed"))
                }

                // SOR / kNN Merge)
                if (plySettings.plyOptimize) {
                    android.util.Log.i(TAG, "PLY optimization enabled, running GaussSimplify...")
                    if (!runStage(9) {
                        QnnJni.nativeOptimizePly(
                            plyPath = plyPath,
                            mergeK = plySettings.plyMergeK,
                            mergeRatio = plySettings.plyMergeRatio,
                            mergeCap = plySettings.plyMergeCap,
                            pruneThreshold = plySettings.plyPruneThreshold,
                            sorNeighbors = plySettings.plySorNeighbors,
                            sorStdRatio = plySettings.plySorStdRatio
                        )
                    }) {
                        android.util.Log.w(TAG, "PLY optimization failed, but PLY is still usable")
                        // Optimization failure is non-fatal; the PLY is still usable
                    }
                } else {
                    android.util.Log.i(TAG, "PLY optimization disabled, skipping")
                }

                // Done
                val totalElapsed = System.currentTimeMillis() - pipelineStartTime
                android.util.Log.i(TAG, "=== Pipeline done, total ${totalElapsed}ms, PLY=$plyPath ===")
                _state.value = _state.value.copy(
                    isRunning = false,
                    totalElapsedMs = totalElapsed,
                    errorMessage = null
                )
                Result.success(Unit)
            } catch (e: Exception) {
                fail(e.message ?: e.toString())
                Result.failure(e)
            }
        }
    }

    /** Cancel and reset the current pipeline state */
    fun reset() {
        _state.value = PipelineState()
        // Release the HTP memory of loaded models
        synchronized(loadedModelsLock) {
            for (modelType in loadedModels) {
                runCatching { QnnJni.freeModel(modelType.code) }
            }
            loadedModels.clear()
        }
        runCatching { QnnJni.clearRestCache() }
    }

    /** Path of the PLY file from the latest run (for export) */
    fun getLastPlyFile(): File? {
        val workDir = File(context.cacheDir, "sharp_work")
        val ply = File(workDir, "output.ply")
        return if (ply.exists()) ply else null
    }

    // ======  ======
    // ====== Internals: initialization and model readiness ======

    /** Ensure the QNN runtime is initialized (idempotent). Also required before
     * standalone compilation from the models tab. */
    suspend fun ensureQnnInitialized() {
        if (initialized) return
        // Notify the init stage (only effective in pipeline context; harmless otherwise)
        onProgress(PREPARE_STAGE_ID, 0, ModelType.entries.size, 0, MsgKey.DETAIL_INIT_QNN)
        // Pin the language for this call (compile progress details use it)
        langCtx = LocaleUtil.wrap(context, settings.settingsFlow.first().language)
        val libDir = context.applicationInfo.nativeLibraryDir
        // Pick the Skel from the probe result: the skel must match the device
        // architecture exactly, so a failed probe is a hard error
        val arch = QnnJni.probeHtpArch(libDir)
        if (arch.isBlank()) {
            throw IOException(MsgKey.ERR_QNN_INIT)
        }
        // The HTP perf config must be sent before nativeInit (it applies when
        // the shared backend/device is created)
        val s = settings.settingsFlow.first()
        QnnJni.setPerfConfig(
            type = s.perfType,
            lockedCorner = s.perfLockedCorner,
            minCorner = s.perfRangeMin,
            targetCorner = s.perfRangeTarget,
            maxCorner = s.perfRangeMax,
            dcvsMode = s.perfDcvsMode
        )
        val skelDir = SkelExtractor.extractSkel(context, arch)
            ?: throw IOException(MsgKey.k(MsgKey.ERR_SKEL, arch))
        val ok = QnnJni.nativeInit(libDir, skelDir, arch)
        if (!ok) throw IOException(MsgKey.ERR_QNN_NATIVE_INIT)
        initialized = true
    }

    private suspend fun ensureReady() {
        ensureQnnInitialized()

        // Models to compile (DLC and not yet compiled)
        val toCompile = ModelType.entries.filter { type ->
            val e = modelStore.getModel(type)
            e != null && e.format == ModelFormat.DLC && e.status != ModelStatus.COMPILED
        }
        val total = toCompile.size

        // Ensure every model is compiled and loaded
        if (total > 0) {
            compileStageStartTime = System.currentTimeMillis()
            onStageStart(COMPILE_STAGE_ID, "Model Compilation")
        }
        var loadedCount = 0
        for (type in ModelType.entries) {
            val compileIndex = toCompile.indexOf(type) // -1 = nothing to compile
            val alreadyLoaded = synchronized(loadedModelsLock) { loadedModels.contains(type) }
            ensureModelReady(type, compileIndex, total)
            if (!alreadyLoaded) {
                loadedCount++
                onProgress(
                    PREPARE_STAGE_ID,
                    loadedCount, ModelType.entries.size,
                    System.currentTimeMillis() - pipelineStartTime,
                    MsgKey.k(MsgKey.DETAIL_LOADING_MODEL, modelName(type))
                )
            }
        }
        if (total > 0) {
            onStageComplete(COMPILE_STAGE_ID, "Model Compilation", System.currentTimeMillis() - compileStageStartTime)
        }
    }

    private suspend fun ensureModelReady(type: ModelType, compileIndex: Int, compileTotal: Int) {
        synchronized(loadedModelsLock) { if (loadedModels.contains(type)) return }

        val entry: ModelEntry = modelStore.getModel(type)
            ?: throw IllegalStateException(MsgKey.k(MsgKey.ERR_MODEL_NOT_IMPORTED, modelName(type)))

        // Compile first if the DLC is not compiled yet
        if (entry.status != ModelStatus.COMPILED) {
            // Compilation start: progress shows x/total done (current model not counted yet)
            onProgress(
                COMPILE_STAGE_ID,
                compileIndex, compileTotal,
                0,
                MsgKey.k(MsgKey.DETAIL_COMPILING, modelName(type))
            )
            val compileResult = modelStore.compileModel(type)
            if (compileResult.isFailure) {
                throw IOException(
                    MsgKey.k(MsgKey.ERR_MODEL_COMPILE_FAILED, modelName(type), compileResult.exceptionOrNull()?.message ?: "")
                )
            }
            // Compilation done: update the count and elapsed time
            onProgress(
                COMPILE_STAGE_ID,
                compileIndex + 1, compileTotal,
                System.currentTimeMillis() - compileStageStartTime,
                MsgKey.k(MsgKey.DETAIL_COMPILED, modelName(type))
            )
        }

        // Re-read the entry (it may have been updated by compilation)
        val ready = modelStore.getModel(type) ?: throw IllegalStateException(MsgKey.k(MsgKey.ERR_MODEL_MISSING, modelName(type)))
        val binPath = ready.runtimeBinPath
            ?: throw IllegalStateException(MsgKey.k(MsgKey.ERR_MODEL_NO_BIN, modelName(type)))

        val ok = QnnJni.loadContextBinary(type.code, binPath)
        if (!ok) throw IOException(MsgKey.k(MsgKey.ERR_MODEL_LOAD_FAILED, modelName(type)))
        android.util.Log.i(TAG, "Model loaded: ${type.displayName} (${type.code})")
        synchronized(loadedModelsLock) { loadedModels.add(type) }
    }

    // ======  ======
    // ====== Internals: stage execution ======

    /** Run a single stage, emitting start / complete callbacks automatically */
    private fun <T> runStage(stageId: Int, block: () -> T): T {
        val stage = _state.value.stages.first { it.id == stageId }
        stageStartTime = System.currentTimeMillis()
        android.util.Log.i(TAG, "Stage ${stage.id} start: ${stage.name}")
        onStageStart(stageId, stage.name)
        try {
            val result = block()
            val elapsed = System.currentTimeMillis() - stageStartTime
            android.util.Log.i(TAG, "Stage ${stage.id} done: ${stage.name} (${elapsed}ms)")
            // Only mark the stage complete while the pipeline is still running
            if (_state.value.isRunning) {
                onStageComplete(stageId, stage.name, elapsed)
            }
            return result
        } catch (e: Exception) {
            // Mark the stage as failed on exception
            if (_state.value.isRunning) {
                fail(MsgKey.k(MsgKey.ERR_STAGE_EXCEPTION, stageId, e.message ?: e.toString()))
            }
            throw e
        }
    }

    private fun fail(message: String) {
        android.util.Log.e(TAG, "Pipeline failed: $message")
        // Release HTP memory of loaded models after failure to avoid leaks
        synchronized(loadedModelsLock) {
            for (modelType in loadedModels) {
                runCatching { QnnJni.freeModel(modelType.code) }
            }
            loadedModels.clear()
        }
        runCatching { QnnJni.clearRestCache() }
        // Reset every running stage so the state stays consistent after
        // cancellation or an exception
        val current = _state.value
        _state.value = current.copy(
            isRunning = false,
            errorMessage = message,
            stages = current.stages.map { s ->
                if (s.isRunning) s.copy(isRunning = false, detail = if (s.id == COMPILE_STAGE_ID) MsgKey.DETAIL_CANCELLED else s.detail)
                else s
            }
        )
    }

    // ====== ProgressCallback Implementation ======
    // ====== ProgressCallback implementation ======

    override fun onStageStart(stageId: Int, stageName: String) {
        updateStage(stageId) { it.copy(isRunning = true, isComplete = false, detail = "") }
    }

    override fun onProgress(stageId: Int, current: Int, total: Int, elapsedMs: Long, detail: String) {
        updateStage(stageId) {
            val effTotal = if (total > 0) total else it.total
            it.copy(
                current = current.coerceAtMost(effTotal),
                total = effTotal,
                elapsedMs = if (elapsedMs > 0) elapsedMs else it.elapsedMs,
                detail = detail
            )
        }
    }

    override fun onStageComplete(stageId: Int, stageName: String, elapsedMs: Long) {
        updateStage(stageId) {
            val total = if (it.total > 0) it.total else it.current
            it.copy(
                isRunning = false,
                isComplete = true,
                current = if (total > 0) total else it.current,
                total = total,
                elapsedMs = if (elapsedMs > 0) elapsedMs else it.elapsedMs,
                detail = ""
            )
        }
    }

    override fun onLog(level: Int, message: String) {
        // Log lines may be forwarded to Logcat or a UI log panel here (reserved)
        android.util.Log.println(logPriority(level), TAG, message)
    }

    override fun onError(stageId: Int, message: String) {
        fail(MsgKey.k(MsgKey.ERR_STAGE_ERROR, stageId, message))
    }

    // ======  ======
    // ====== Internals: state updates ======

    private fun updateStage(stageId: Int, transform: (StageState) -> StageState) {
        _state.update { current ->
            val idx = current.stages.indexOfFirst { it.id == stageId }
            if (idx < 0) return@update current
            val newStages = current.stages.toMutableList()
            newStages[idx] = transform(newStages[idx])
            current.copy(stages = newStages)
        }
    }

    private fun logPriority(level: Int): Int = when (level) {
        0 -> android.util.Log.DEBUG
        1 -> android.util.Log.INFO
        2 -> android.util.Log.WARN
        3 -> android.util.Log.ERROR
        else -> android.util.Log.INFO
    }

    companion object {
        private const val TAG = "PipelineManager"

        // Init stage (QNN runtime + model loading, runs first)
        const val PREPARE_STAGE_ID = -2

        // Model compilation stage (inserted at the head of stages; does not collide with 0-8)
        const val COMPILE_STAGE_ID = -1

        // Removed)
        const val PLY_OPTIMIZE_STAGE_ID = 9
    }
}
