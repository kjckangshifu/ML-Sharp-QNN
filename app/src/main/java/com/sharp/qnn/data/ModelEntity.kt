package com.sharp.qnn.data

import androidx.annotation.StringRes
import com.sharp.qnn.R
import org.json.JSONObject

/**
 * Model types: the 5 model parts.
 *
 * - PE     : Patch Encoder / 图块编码
 * - PE     : Patch Encoder
 * - IE     : Image Encoder / 图像编码
 * - IE     : Image Encoder
 * - REST_A : REST Seg A / 特征融合 (6 输入 → 6 上采样特征)
 * - REST_A : REST Seg A / feature fusion (6 inputs → 6 upsampled features)
 * - REST_B : REST Seg B / 视差估计 (3 特征 → disparity)
 * - REST_B : REST Seg B / disparity estimation (3 features → disparity)
 * - REST_C : REST Seg C / 高斯增量 (image+disparity+特征 → delta)
 * - REST_C : REST Seg C / gaussian delta (image+disparity+features → delta)
 */
enum class ModelType(
    val displayName: String,
    val code: String,
    @StringRes val nameRes: Int
) {
    PE("Patch Encoder", "pe", R.string.model_pe),
    IE("Image Encoder", "ie", R.string.model_ie),
    REST_A("Feature Fusion", "rest_a", R.string.model_rest_a),
    REST_B("Disparity Estimation", "rest_b", R.string.model_rest_b),
    REST_C("Gaussian Delta", "rest_c", R.string.model_rest_c);

    companion object {
        fun fromCode(code: String): ModelType? = entries.firstOrNull { it.code == code }
    }
}

/** Model format: precompiled .bin / DLC */
enum class ModelFormat(val ext: String) {
    BIN("bin"),
    DLC("dlc")
}

/** Model status */
enum class ModelStatus(
    val label: String,
    @StringRes val labelRes: Int
) {
    NOT_IMPORTED("Not Imported", R.string.status_not_imported),
    COMPILED("Compiled", R.string.status_compiled),
    UNCOMPILED("Uncompiled", R.string.status_uncompiled),
    COMPILING("Compiling", R.string.status_compiling)
}

/**
 * Model metadata.
 *
 * IE / REST_A / REST_B / REST_C)
 * @param type            model type (PE / IE / REST_A / REST_B / REST_C)
 * DLC)
 * @param format          original format as imported (BIN / DLC)
 * @param sourcePath      原始文件路径 (.bin 或 .dlc)
 * @param sourcePath      path of the source file (.bin or .dlc)
 * @param sourceName      原始文件名
 * @param sourceName      original file name
 * @param compiledBinPath 编译后 .bin 路径 (DLC 编译后才有；BIN 直接指向 sourcePath)
 * @param compiledBinPath compiled .bin path (only after DLC compilation; BIN points directly to sourcePath)
 * @param status          当前状态
 * @param status          current status
 * @param fileSize        原始文件大小 (字节)
 * @param fileSize        source file size (bytes)
 * @param importTime      导入时间戳 (毫秒)
 * @param importTime      import timestamp (milliseconds)
 */
data class ModelEntry(
    val type: ModelType,
    val format: ModelFormat,
    val sourcePath: String,
    val sourceName: String,
    val compiledBinPath: String?,
    val status: ModelStatus,
    val fileSize: Long,
    val importTime: Long
) {

    /** The .bin path actually loaded at runtime (compiled artifact first, raw .bin second) */
    val runtimeBinPath: String? get() = compiledBinPath ?: sourcePath.takeIf { format == ModelFormat.BIN }

    /** Serialize to JSON */
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.code)
        put("format", format.name)
        put("sourcePath", sourcePath)
        put("sourceName", sourceName)
        put("compiledBinPath", compiledBinPath)
        put("status", status.name)
        put("fileSize", fileSize)
        put("importTime", importTime)
    }

    companion object {
        /** Deserialize from JSON (returns null on malformed input) */
        fun fromJson(json: JSONObject): ModelEntry? {
            return try {
                val type = ModelType.fromCode(json.getString("type")) ?: return null
                ModelEntry(
                    type = type,
                    format = ModelFormat.valueOf(json.getString("format")),
                    sourcePath = json.getString("sourcePath"),
                    sourceName = json.getString("sourceName"),
                    compiledBinPath = json.optString("compiledBinPath").ifBlank { null },
                    status = ModelStatus.valueOf(json.getString("status")),
                    fileSize = json.getLong("fileSize"),
                    importTime = json.getLong("importTime")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
