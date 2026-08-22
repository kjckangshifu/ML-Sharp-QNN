package com.sharp.qnn.util

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File

/**
 * 文件工具: SAF URI 处理、目录管理、格式化等。
 * File utilities: SAF URI handling, directory management, formatting, etc.
 */
object FileUtil {

    /**
     * 将 SAF URI 内容复制到目标文件。
     * Copies the content of a SAF URI to the destination file.
     * true on success
     */
    fun copyUriToFile(context: Context, uri: Uri, destFile: File): Boolean {
        return try {
            ensureDir(destFile.parentFile)
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            } ?: return false
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 从 SAF URI 获取文件名。优先查询 _display_name，失败时回退到 URI 最后路径段。
     * Resolves the file name from a SAF URI, preferring the _display_name column
     * and falling back to the URI's last path segment.
     *
     * Android 13+ Photo Picker 返回的 URI 其 _display_name 是"媒体ID.后缀"
     * (如 1000000041.jpg) 而非真实文件名, 此处尝试经 MediaStore 反查真实名。
     * On Android 13+, URIs returned by the Photo Picker have a _display_name of
     * "media-id.extension" (e.g. 1000000041.jpg) instead of the real file name;
     * a MediaStore reverse lookup is attempted to recover the real name.
     */
    fun getFileNameFromUri(context: Context, uri: Uri): String {
        var name: String? = null
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    name = cursor.getString(idx)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("FileUtil", "query failed", e)
        }
        if (name.isNullOrBlank()) {
            name = uri.lastPathSegment ?: "unknown"
        }
        // Photo Picker digit-ID names (e.g. "1000000041.jpg"): look up the real
        // MediaStore display name
        val realName = queryRealDisplayName(context, uri, name)
        return realName ?: name
    }

    /** Reverse-look up the real MediaStore name for the media ID (requires
     * READ_MEDIA_IMAGES; returns null on failure) */
    private fun queryRealDisplayName(context: Context, uri: Uri, displayName: String): String? {
        val base = displayName.substringBeforeLast('.')
        if (base.isEmpty() || !base.all { it.isDigit() }) return null
        val id = uri.lastPathSegment?.toLongOrNull() ?: return null
        return try {
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
                "${MediaStore.Images.Media._ID} = ?",
                arrayOf(id.toString()),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            android.util.Log.e("FileUtil", "lookup failed", e)
            null
        }
    }

    /**
     * 从 SAF URI 获取文件大小 (字节)。查询失败时返回 -1。
     * Returns the file size (bytes) from a SAF URI, or -1 on failure.
     */
    fun getFileSize(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && cursor.moveToFirst()) {
                    return cursor.getLong(idx)
                }
            }
            -1L
        } catch (_: Exception) {
            -1L
        }
    }

    /** Ensure the directory exists, creating it if missing */
    fun ensureDir(dir: File?): Boolean {
        if (dir == null) return false
        if (dir.exists()) return dir.isDirectory
        return dir.mkdirs()
    }

    /** Recursively delete a directory and its contents */
    fun deleteRecursively(dir: File): Boolean {
        return dir.deleteRecursively()
    }

    /** Total size of a file or directory in bytes (recursive) */
    fun sizeOf(file: File): Long {
        if (file.isFile) return file.length()
        if (!file.isDirectory) return 0
        return file.walkBottomUp().sumOf { if (it.isFile) it.length() else 0L }
    }

    /**
     * 格式化耗时: 毫秒 → "Xs Yms" 或 "Yms"。
     * Format elapsed time: milliseconds → "Xs Yms" or "Yms".
     */
    fun formatDuration(ms: Long): String {
        if (ms < 1000) return "${ms}ms"
        val seconds = ms / 1000
        val millis = ms % 1000
        return if (millis == 0L) "${seconds}s" else "${seconds}s ${millis}ms"
    }

    /**
     * "X KB" / "X B"。
     * Format file size: bytes → "X.XX MB" / "X KB" / "X B".
     */
    fun formatFileSize(bytes: Long): String {
        if (bytes < 0) return "?"
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes < kb -> "$bytes B"
            bytes < mb -> String.format("%.1f KB", bytes / kb)
            bytes < gb -> String.format("%.2f MB", bytes / mb)
            else -> String.format("%.2f GB", bytes / gb)
        }
    }
}
