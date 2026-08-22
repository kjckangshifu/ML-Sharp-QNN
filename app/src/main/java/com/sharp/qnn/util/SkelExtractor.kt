package com.sharp.qnn.util

import android.content.Context
import com.sharp.qnn.util.FileUtil.ensureDir
import java.io.File

/**
 * Skel 提取器。
 * Skel extractor.
 *
 * 目录下
 * (由 Gradle `copyQnnSkel` 任务复制)。运行时需要提取到文件系统，以便 native
 * 层通过路径 dlopen 加载。
 * The Qualcomm Hexagon DSP Skel .so files are packaged under
 * assets/qnn_skel/<version>/ (copied by the Gradle `copyQnnSkel` task). At
 * runtime they are extracted to the file system so the native layer can
 * dlopen them by path.
 */
object SkelExtractor {

    /** assets 中 Skel 根目录 */
    /** Skel root directory inside assets */
    private const val ASSET_ROOT = "qnn_skel"

    /** Extraction target root directory (filesDir/qnn_skel) */
    fun skelRootDir(context: Context): File =
        File(context.filesDir, ASSET_ROOT)

    /**
     * 根据 HTP 架构版本提取对应的 libQnnHtpV*Skel.so。
     * Extracts the libQnnHtpV*Skel.so matching the HTP architecture version.
     *
     * "V68" / "V81" (由 native probeHtpArch 探测得到)
     * @param arch HTP version such as "V79" / "V68" / "V81" (reported by native probeHtpArch)
     * @return 提取后 Skel .so 所在目录；失败返回 null。
     * @return directory containing the extracted Skel .so, or null on failure.
     */
    fun extractSkel(context: Context, arch: String): String? {
        val ver = normalizeVersion(arch)
        val assetFile = "$ASSET_ROOT/$ver/libQnnHtp${ver.replaceFirstChar { it.uppercase() }}Skel.so"
        val outDir = File(skelRootDir(context), ver)
        ensureDir(outDir)
        val outFile = File(outDir, "libQnnHtp${ver.replaceFirstChar { it.uppercase() }}Skel.so")

        // Reuse a previously extracted non-empty file
        if (outFile.exists() && outFile.length() > 0) {
            return outDir.absolutePath
        }

        return try {
            context.assets.open(assetFile).use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            outDir.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            // The architecture must match exactly: a mismatched skel cannot be
            // loaded by HTP, so fail rather than substitute
            null
        }
    }

    /** List the available HTP versions in assets (lowercase, e.g. [v68, v79, ...]) */
    fun listAvailableVersions(context: Context): List<String> {
        return try {
            context.assets.list(ASSET_ROOT)?.toList()?.sorted() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Normalize "V79" / "v79" to the lowercase version "v79" */
    private fun normalizeVersion(arch: String): String {
        return arch.lowercase()
    }
}
