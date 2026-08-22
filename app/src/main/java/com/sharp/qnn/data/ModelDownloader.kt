package com.sharp.qnn.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/**
 * 预转换模型下载器 (P2P 分块并行下载)。
 * Pre-converted model downloader (P2P chunked parallel download).
 *
 * 从 HuggingFace 或国内镜像站 (HF Mirror) 下载 DLC 模型文件。
 * Downloads DLC model files from HuggingFace or HF Mirror (for Chinese users).
 *
 * File list (5 models):
 *   pe.dlc, ie.dlc, rest_a.dlc, rest_b.dlc, rest_c.dlc
 *
 * ML-Sharp-QNN
 * Repo path: kjcpc/ML-Sharp-QNN
 */
class ModelDownloader(private val context: Context) {

    companion object {
        /** Chunk count per file (parallel download) */
        private const val CHUNK_COUNT = 4

        private const val CONNECT_TIMEOUT_MS = 15_000

        private const val READ_TIMEOUT_MS = 30_000

        private const val BUFFER_SIZE = 8192

        /** Chunked download threshold: files smaller than this use simple streaming */
        private const val CHUNK_THRESHOLD = 4 * 1024 * 1024L // 4 MB

        /** HuggingFace official URL */
        private const val HG_BASE_URL = "https://huggingface.co/kjcpc/ML-Sharp-QNN/resolve/main"

        /** HF Mirror (recommended for Chinese users) */
        private const val HM_BASE_URL = "https://hf-mirror.com/kjcpc/ML-Sharp-QNN/resolve/main"

        private const val PRECISION_DIR = "dlc/w8a16"

        /** 5 个 DLC 模型文件名 */
        /** 5 DLC model file names */
        val MODEL_FILES = listOf(
            "pe.dlc",
            "ie.dlc",
            "rest_a.dlc",
            "rest_b.dlc",
            "rest_c.dlc"
        )

        /**
         * 根据下载源获取基础 URL。
         * Get base URL based on download source.
         */
        fun getBaseUrl(source: SettingsRepository.DownloadSource): String =
            when (source) {
                SettingsRepository.DownloadSource.HG -> HG_BASE_URL
                SettingsRepository.DownloadSource.HM -> HM_BASE_URL
            }

        /**
         * 获取模型文件的完整 URL。
         * Get the full URL for a model file.
         */
        fun getModelUrl(source: SettingsRepository.DownloadSource, fileName: String): String =
            "${getBaseUrl(source)}/$PRECISION_DIR/$fileName"
    }

    /**
     * 取消令牌 (volatile, 确保跨协程可见)。
     * Cancellation token (volatile for cross-coroutine visibility).
     */
    @Volatile
    private var cancelled = false

    /**
     * Cancel the current download.
     */
    fun cancel() {
        cancelled = true
    }

    /**
     * 目录。
     * Download all 5 model files to the dlc/ directory.
     *
     * 注意: onComplete 始终会被调用 (即使取消), 调用方在此统一重置状态。
     * Note: onComplete is always called (even on cancel), so callers reset state there.
     *
     * download source
     * file-level progress callback (fileName, currentIndex, total)
     * byte-level progress callback (downloaded bytes, total bytes)
     * completion callback (successCount, totalCount) — always called
     * single-file error callback (error message) — does NOT reset overall state
     */
    suspend fun downloadAll(
        source: SettingsRepository.DownloadSource,
        onProgress: (fileName: String, current: Int, total: Int) -> Unit = { _, _, _ -> },
        onBytesProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
        onComplete: (successCount: Int, totalCount: Int) -> Unit = { _, _ -> },
        onError: (message: String) -> Unit = {}
    ) {
        cancelled = false
        // Use the same model root directory as ModelStore (external private dir preferred)
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dlcDir = File(base, "sharp_models/dlc")
        if (!dlcDir.exists()) {
            dlcDir.mkdirs()
        }

        val total = MODEL_FILES.size
        var successCount = 0

        // Collect all file sizes upfront (HEAD requests) for byte-level progress
        val fileSizes = LongArray(total)
        coroutineScope {
            val sizeJobs = MODEL_FILES.mapIndexed { index, fileName ->
                launch {
                    if (!cancelled) {
                        val url = getModelUrl(source, fileName)
                        fileSizes[index] = getFileSize(url)
                    }
                }
            }
            sizeJobs.forEach { it.join() }
        }
        val totalBytes = fileSizes.filter { it > 0 }.sum()

        // Cumulative downloaded bytes (across files)
        val cumulativeBytes = AtomicLong(0L)

        for ((index, fileName) in MODEL_FILES.withIndex()) {
            if (cancelled) {
                break // 跳出循环, 确保 onComplete 被调用
                // break to ensure onComplete is called
            }

            onProgress(fileName, index + 1, total)

            val url = getModelUrl(source, fileName)
            val destFile = File(dlcDir, fileName)

            try {
                downloadFile(url, destFile) { fileBytes ->
                    // Cumulative byte progress across files
                    val prevBytes = (0 until index).sumOf { fileSizes[it] }
                    onBytesProgress(prevBytes + fileBytes, totalBytes)
                }
                successCount++
                cumulativeBytes.addAndGet(fileSizes[index])
            } catch (e: Exception) {
                if (cancelled) {
                    break
                }
                onError("${e.message ?: "Unknown error"}")
                // Delete partial/failed download file
                destFile.delete()
            }
        }

        // Always call onComplete so the caller resets state
        onComplete(successCount, total)
    }

    /**
     * HEAD 请求获取文件大小。
     * HEAD request to get the file size.
     */
    private suspend fun getFileSize(urlStr: String): Long = withContext(Dispatchers.IO) {
        try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "HEAD"
            conn.connect()
            val size = conn.contentLengthLong
            conn.disconnect()
            if (size > 0) size else 0L
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * 分块下载)。
     * Download a single file (HEAD probe + streaming/chunked download).
     *
     * 先用 HEAD 请求获取文件大小, 再决定用简单流式还是分块并行下载。
     * First uses HEAD request to get file size, then decides simple or chunked download.
     *
     * byte-level progress (bytes downloaded for this file)
     */
    private suspend fun downloadFile(
        urlStr: String, destFile: File,
        onBytesProgress: (fileBytes: Long) -> Unit = {}
    ): Unit = withContext(Dispatchers.IO) {
        // Check cancellation token
        if (cancelled) throw RuntimeException("Download cancelled")

        // HEAD request to get file size (no body, avoids waste)
        val headConn = URL(urlStr).openConnection() as HttpURLConnection
        headConn.connectTimeout = CONNECT_TIMEOUT_MS
        headConn.readTimeout = READ_TIMEOUT_MS
        headConn.requestMethod = "HEAD"
        headConn.connect()

        val responseCode = headConn.responseCode
        val contentLength = headConn.contentLength
        headConn.disconnect()

        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw RuntimeException("HTTP $responseCode for $urlStr")
        }

        // Small files (< 4MB) or unknown size → simple streaming download
        if (contentLength <= 0 || contentLength < CHUNK_THRESHOLD) {
            downloadSimple(urlStr, destFile, onBytesProgress)
            return@withContext
        }

        // Large files → try chunked parallel download; fall back to streaming if server doesn't support Range
        val chunkedSuccess = tryDownloadChunked(urlStr, destFile, contentLength, onBytesProgress)
        if (!chunkedSuccess) {
            // Server doesn't support Range (returned 200 instead of 206), fall back to streaming
            downloadSimple(urlStr, destFile, onBytesProgress)
        }
    }

    /**
     * 尝试分块并行下载, 返回是否成功 (false 表示服务器不支持 Range)。
     * Try chunked parallel download, returns whether successful (false = server doesn't support Range).
     */
    private suspend fun tryDownloadChunked(
        urlStr: String, destFile: File, contentLength: Int,
        onBytesProgress: (fileBytes: Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        // Send a Range probe request first to confirm server supports 206 Partial Content
        val probeConn = URL(urlStr).openConnection() as HttpURLConnection
        probeConn.connectTimeout = CONNECT_TIMEOUT_MS
        probeConn.readTimeout = READ_TIMEOUT_MS
        probeConn.requestMethod = "GET"
        probeConn.setRequestProperty("Range", "bytes=0-0")
        probeConn.connect()

        val probeCode = probeConn.responseCode
        probeConn.disconnect()

        // Server doesn't support Range → fall back to streaming
        if (probeCode != HttpURLConnection.HTTP_PARTIAL) {
            return@withContext false
        }

        // Server supports Range → chunked parallel download
        val tmpDir = File(destFile.parentFile, ".tmp_${destFile.name}")
        if (tmpDir.exists()) tmpDir.deleteRecursively()
        tmpDir.mkdirs()

        try {
            val chunkSize = contentLength / CHUNK_COUNT
            val chunkFiles = mutableListOf<File>()
            val chunkBytes = AtomicLong(0L)

            coroutineScope {
                for (i in 0 until CHUNK_COUNT) {
                    val start = (i * chunkSize).toLong()
                    val end = (if (i == CHUNK_COUNT - 1) contentLength - 1L else (i + 1L) * chunkSize - 1L)
                    val chunkFile = File(tmpDir, "chunk_$i")
                    chunkFiles.add(chunkFile)

                    launch {
                        downloadChunk(urlStr, chunkFile, start, end) { delta ->
                            onBytesProgress(chunkBytes.addAndGet(delta))
                        }
                    }
                }
            }

            mergeChunks(chunkFiles, destFile)
            return@withContext true
        } finally {
            // Clean up temp directory
            tmpDir.deleteRecursively()
        }
    }

    /**
     * Simple streaming download (single thread, full file).
     */
    private suspend fun downloadSimple(
        urlStr: String, destFile: File,
        onBytesProgress: (fileBytes: Long) -> Unit = {}
    ): Unit = withContext(Dispatchers.IO) {
        if (cancelled) throw RuntimeException("Download cancelled")

        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.requestMethod = "GET"
        conn.connect()

        val responseCode = conn.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            conn.disconnect()
            throw RuntimeException("HTTP $responseCode for $urlStr")
        }

        try {
            conn.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    var totalRead = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (cancelled) {
                            throw RuntimeException("Download cancelled")
                        }
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        onBytesProgress(totalRead)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 下载单个分块 (Range 请求)。
     * Download a single chunk (Range request).
     */
    private suspend fun downloadChunk(
        urlStr: String, chunkFile: File, start: Long, end: Long,
        onBytesProgress: (delta: Long) -> Unit = {}
    ): Unit =
        withContext(Dispatchers.IO) {
            if (cancelled) throw RuntimeException("Download cancelled")

            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("Range", "bytes=$start-$end")
            conn.connect()

            val responseCode = conn.responseCode
            if (responseCode != HttpURLConnection.HTTP_PARTIAL) {
                conn.disconnect()
                throw RuntimeException("HTTP $responseCode for chunk $urlStr (expected 206)")
            }

            try {
                conn.inputStream.use { input ->
                    FileOutputStream(chunkFile).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            if (cancelled) {
                                throw RuntimeException("Download cancelled")
                            }
                            output.write(buffer, 0, bytesRead)
                            onBytesProgress(bytesRead.toLong())
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
        }

    /**
     * Merge chunk files into a single complete file.
     */
    private suspend fun mergeChunks(chunkFiles: List<File>, destFile: File): Unit = withContext(Dispatchers.IO) {
        if (cancelled) throw RuntimeException("Download cancelled")

        FileOutputStream(destFile).use { output ->
            val buffer = ByteArray(BUFFER_SIZE)
            for (chunkFile in chunkFiles) {
                if (cancelled) {
                    throw RuntimeException("Download cancelled")
                }
                chunkFile.inputStream().use { input ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
            }
        }
    }
}