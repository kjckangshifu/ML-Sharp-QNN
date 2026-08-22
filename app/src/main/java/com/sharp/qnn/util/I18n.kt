package com.sharp.qnn.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.sharp.qnn.R
import com.sharp.qnn.data.ModelType
import com.sharp.qnn.data.SettingsRepository.Language
import java.util.Locale

/**
 * 语言与本地化工具: 按用户语言设置包装 Context, 并提供消息键 → stringResource 解析。
 * Locale & localization utilities: wraps a Context with the user's language
 * setting, and resolves message keys into localized string resources.
 *
 * 消息键约定 (native ↔ Kotlin ↔ UI):
 * Message-key convention (native ↔ Kotlin ↔ UI):
 * - 跨层传递的文本一律使用 ASCII 键 (见 [MsgKey]), 需要参数时以 '|' 分隔: key|arg1|arg2
 * - Text crossing layers always uses ASCII keys (see [MsgKey]); parameters are
 *   appended with '|': key|arg1|arg2
 * 35") 原样透传
 * - Raw text without a key (e.g. native "patch 21/35") passes through as-is
 */
object LocaleUtil {

    /** User language → Android Locale (SYSTEM uses the device locale) */
    fun locale(language: Language): Locale = when (language) {
        Language.ZH -> Locale.SIMPLIFIED_CHINESE
        Language.EN -> Locale.ENGLISH
        Language.SYSTEM ->
            Resources.getSystem().configuration.locales[0] ?: Locale.getDefault()
    }

    /** Wrap a Context for the given language (resources follow it; SYSTEM passes through) */
    fun wrap(base: Context, language: Language): Context {
        if (language == Language.SYSTEM) return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale(language))
        return base.createConfigurationContext(config)
    }

    /** Localized string for non-composable call sites (ctx must be wrapped, see [wrap]) */
    fun string(ctx: Context, @StringRes res: Int, vararg args: Any): String =
        if (args.isEmpty()) ctx.getString(res) else ctx.getString(res, *args)

    /** Localized model name (for non-composable message construction) */
    fun modelName(ctx: Context, type: ModelType): String = string(ctx, type.nameRes)
}

/** Message keys shared by native (sharp_jni.cpp) and Kotlin; each maps to a strings.xml msg/err entry */
object MsgKey {
    const val SEP = "|"

    // native cbError keys
    const val ERR_PREP_IMAGE_FAILED = "err_prep_image_failed"
    const val ERR_PRE_FAILED = "err_pre_failed"
    const val ERR_PE_NOT_LOADED = "err_pe_not_loaded"
    const val ERR_PE_TENSOR_INFO_EMPTY = "err_pe_tensor_info_empty"
    const val ERR_READ_PATCH_FAILED = "err_read_patch_failed"
    const val ERR_PE_INFER_FAILED = "err_pe_infer_failed"
    const val ERR_PE_WRITE_FAILED = "err_pe_write_failed"
    const val ERR_IE_NOT_LOADED = "err_ie_not_loaded"
    const val ERR_READ_X2_FAILED = "err_read_x2_failed"
    const val ERR_IE_INFER_FAILED = "err_ie_infer_failed"
    const val ERR_IE_WRITE_FAILED = "err_ie_write_failed"
    const val ERR_MERGE_FAILED = "err_merge_failed"
    const val ERR_RUNTIME_NOT_READY = "err_runtime_not_ready"
    const val ERR_TENSOR_INFO_EMPTY = "err_tensor_info_empty"
    const val ERR_TENSOR_READ_FAILED = "err_tensor_read_failed"
    const val ERR_TENSOR_INPUT_MISSING = "err_tensor_input_missing"
    const val ERR_INFER_FAILED = "err_infer_failed"
    const val ERR_OUTPUT_MISSING = "err_output_missing"
    const val ERR_OUTPUT_META_MISSING = "err_output_meta_missing"
    const val ERR_WRITE_FAILED = "err_write_failed"
    const val ERR_DISPARITY_COPY_FAILED = "err_disparity_copy_failed"
    const val ERR_DISPARITY_OUTPUT_MISSING = "err_disparity_output_missing"
    const val ERR_DELTA_COPY_FAILED = "err_delta_copy_failed"
    const val ERR_DELTA_OUTPUT_MISSING = "err_delta_output_missing"
    const val ERR_POINTCLOUD_FAILED = "err_pointcloud_failed"

    // Kotlin-side keys
    const val ERR_COPY_IMAGE_FAILED = "err_copy_image_failed"
    const val ERR_PREP_NULL = "err_prep_null"
    const val ERR_REST_A_FAILED = "err_rest_a_failed"
    const val ERR_REST_B_FAILED = "err_rest_b_failed"
    const val ERR_REST_C_FAILED = "err_rest_c_failed"
    const val ERR_POST_FAILED = "err_post_failed"
    const val ERR_STAGE_ERROR = "err_stage_error"
    const val ERR_STAGE_EXCEPTION = "err_stage_exception"
    const val ERR_QNN_INIT = "err_qnn_init"
    const val ERR_SKEL = "err_skel"
    const val ERR_QNN_NATIVE_INIT = "err_qnn_native_init"
    const val ERR_QNN_INIT_DEFAULT = "err_qnn_init_default"
    const val ERR_MODEL_NOT_IMPORTED = "err_model_not_imported"
    const val ERR_MODEL_MISSING = "err_model_missing"
    const val ERR_MODEL_NO_BIN = "err_model_no_bin"
    const val ERR_MODEL_LOAD_FAILED = "err_model_load_failed"
    const val ERR_MODEL_COMPILE_FAILED = "err_model_compile_failed"
    const val ERR_COMPILE_CANCEL = "err_compile_cancel"
    const val ERR_DLC_NATIVE = "err_dlc_native"
    const val ERR_IMPORT_FORMAT = "err_import_format"
    const val ERR_IMPORT_COPY = "err_import_copy"
    const val ERR_IMPORT_MOVE = "err_import_move"
    const val ERR_IMPORT_VALIDATE = "err_import_validate"
    const val ERR_IMPORT_EMPTY = "err_import_empty"
    const val ERR_DLC_ONLY = "err_dlc_only"
    const val ERR_PLY_MISSING = "err_ply_missing"
    const val ERR_EXPORT_CREATE = "err_export_create"
    const val ERR_EXPORT_STREAM = "err_export_stream"
    const val ERR_EXPORT_FAIL = "err_export_fail"
    const val MSG_EXPORT_OK = "msg_export_ok"
    const val MSG_IMPORT_OK = "msg_import_ok"
    const val ERR_IMPORT_FAIL = "err_import_fail"
    const val MSG_COMPILE_OK = "msg_compile_ok"
    const val ERR_COMPILE_FAIL = "err_compile_fail"
    const val MSG_REMOVE_OK = "msg_remove_ok"
    const val ERR_REMOVE_FAIL = "err_remove_fail"
    const val MSG_CACHE_CLEARED = "msg_cache_cleared"
    const val MSG_CACHE_EMPTY = "msg_cache_empty"
    const val MSG_DELETED = "msg_deleted"

    // Model download
    const val MSG_DOWNLOAD_COMPLETE = "msg_download_complete"
    const val ERR_DOWNLOAD_FAIL = "err_download_fail"

    // progress-detail keys
    const val DETAIL_INIT_QNN = "detail_init_qnn"
    const val DETAIL_LOADING_MODEL = "detail_loading_model"
    const val DETAIL_COMPILING = "detail_compiling"
    const val DETAIL_COMPILED = "detail_compiled"
    const val DETAIL_CANCELLED = "detail_cancelled"
    const val STAGE_PLY_PRUNE = "stage_ply_prune"
    const val STAGE_PLY_SOR = "stage_ply_sor"
    const val STAGE_PLY_KNN = "stage_ply_knn"

    /** Build a parameterized key: key|arg1|arg2 */
    fun k(key: String, vararg args: Any): String =
        if (args.isEmpty()) key else "$key$SEP${args.joinToString(SEP)}"
}

/** Message-key → string-resource map (keys match the names in values/strings.xml) */
internal val messageResIds: Map<String, Int> = mapOf(
    MsgKey.ERR_PREP_IMAGE_FAILED to R.string.err_prep_image_failed,
    MsgKey.ERR_PRE_FAILED to R.string.err_pre_failed,
    MsgKey.ERR_PE_NOT_LOADED to R.string.err_pe_not_loaded,
    MsgKey.ERR_PE_TENSOR_INFO_EMPTY to R.string.err_pe_tensor_info_empty,
    MsgKey.ERR_READ_PATCH_FAILED to R.string.err_read_patch_failed,
    MsgKey.ERR_PE_INFER_FAILED to R.string.err_pe_infer_failed,
    MsgKey.ERR_PE_WRITE_FAILED to R.string.err_pe_write_failed,
    MsgKey.ERR_IE_NOT_LOADED to R.string.err_ie_not_loaded,
    MsgKey.ERR_READ_X2_FAILED to R.string.err_read_x2_failed,
    MsgKey.ERR_IE_INFER_FAILED to R.string.err_ie_infer_failed,
    MsgKey.ERR_IE_WRITE_FAILED to R.string.err_ie_write_failed,
    MsgKey.ERR_MERGE_FAILED to R.string.err_merge_failed,
    MsgKey.ERR_RUNTIME_NOT_READY to R.string.err_runtime_not_ready,
    MsgKey.ERR_TENSOR_INFO_EMPTY to R.string.err_tensor_info_empty,
    MsgKey.ERR_TENSOR_READ_FAILED to R.string.err_tensor_read_failed,
    MsgKey.ERR_TENSOR_INPUT_MISSING to R.string.err_tensor_input_missing,
    MsgKey.ERR_INFER_FAILED to R.string.err_infer_failed,
    MsgKey.ERR_OUTPUT_MISSING to R.string.err_output_missing,
    MsgKey.ERR_OUTPUT_META_MISSING to R.string.err_output_meta_missing,
    MsgKey.ERR_WRITE_FAILED to R.string.err_write_failed,
    MsgKey.ERR_DISPARITY_COPY_FAILED to R.string.err_disparity_copy_failed,
    MsgKey.ERR_DISPARITY_OUTPUT_MISSING to R.string.err_disparity_output_missing,
    MsgKey.ERR_DELTA_COPY_FAILED to R.string.err_delta_copy_failed,
    MsgKey.ERR_DELTA_OUTPUT_MISSING to R.string.err_delta_output_missing,
    MsgKey.ERR_POINTCLOUD_FAILED to R.string.err_pointcloud_failed,
    MsgKey.ERR_COPY_IMAGE_FAILED to R.string.err_copy_image_failed,
    MsgKey.ERR_PREP_NULL to R.string.err_prep_null,
    MsgKey.ERR_REST_A_FAILED to R.string.err_rest_a_failed,
    MsgKey.ERR_REST_B_FAILED to R.string.err_rest_b_failed,
    MsgKey.ERR_REST_C_FAILED to R.string.err_rest_c_failed,
    MsgKey.ERR_POST_FAILED to R.string.err_post_failed,
    MsgKey.ERR_STAGE_ERROR to R.string.err_stage_error,
    MsgKey.ERR_STAGE_EXCEPTION to R.string.err_stage_exception,
    MsgKey.ERR_QNN_INIT to R.string.err_qnn_init,
    MsgKey.ERR_SKEL to R.string.err_skel,
    MsgKey.ERR_QNN_NATIVE_INIT to R.string.err_qnn_native_init,
    MsgKey.ERR_QNN_INIT_DEFAULT to R.string.err_qnn_init_default,
    MsgKey.ERR_MODEL_NOT_IMPORTED to R.string.err_model_not_imported,
    MsgKey.ERR_MODEL_MISSING to R.string.err_model_missing,
    MsgKey.ERR_MODEL_NO_BIN to R.string.err_model_no_bin,
    MsgKey.ERR_MODEL_LOAD_FAILED to R.string.err_model_load_failed,
    MsgKey.ERR_MODEL_COMPILE_FAILED to R.string.err_model_compile_failed,
    MsgKey.ERR_COMPILE_CANCEL to R.string.err_compile_cancel,
    MsgKey.ERR_DLC_NATIVE to R.string.err_dlc_native,
    MsgKey.ERR_IMPORT_FORMAT to R.string.err_import_format,
    MsgKey.ERR_IMPORT_COPY to R.string.err_import_copy,
    MsgKey.ERR_IMPORT_MOVE to R.string.err_import_move,
    MsgKey.ERR_IMPORT_VALIDATE to R.string.err_import_validate,
    MsgKey.ERR_IMPORT_EMPTY to R.string.err_import_empty,
    MsgKey.ERR_DLC_ONLY to R.string.err_dlc_only,
    MsgKey.ERR_PLY_MISSING to R.string.err_ply_missing,
    MsgKey.ERR_EXPORT_CREATE to R.string.err_export_create,
    MsgKey.ERR_EXPORT_STREAM to R.string.err_export_stream,
    MsgKey.ERR_EXPORT_FAIL to R.string.err_export_fail,
    MsgKey.MSG_EXPORT_OK to R.string.msg_export_ok,
    MsgKey.MSG_IMPORT_OK to R.string.msg_import_ok,
    MsgKey.ERR_IMPORT_FAIL to R.string.err_import_fail,
    MsgKey.MSG_COMPILE_OK to R.string.msg_compile_ok,
    MsgKey.ERR_COMPILE_FAIL to R.string.err_compile_fail,
    MsgKey.MSG_REMOVE_OK to R.string.msg_remove_ok,
    MsgKey.ERR_REMOVE_FAIL to R.string.err_remove_fail,
    MsgKey.MSG_CACHE_CLEARED to R.string.msg_cache_cleared,
    MsgKey.MSG_CACHE_EMPTY to R.string.msg_cache_empty,
    MsgKey.MSG_DELETED to R.string.msg_deleted,
    MsgKey.MSG_DOWNLOAD_COMPLETE to R.string.msg_download_complete,
    MsgKey.ERR_DOWNLOAD_FAIL to R.string.err_download_fail,
    MsgKey.DETAIL_INIT_QNN to R.string.detail_init_qnn,
    MsgKey.DETAIL_LOADING_MODEL to R.string.detail_loading_model,
    MsgKey.DETAIL_COMPILING to R.string.detail_compiling,
    MsgKey.DETAIL_COMPILED to R.string.detail_compiled,
    MsgKey.DETAIL_CANCELLED to R.string.detail_cancelled,
    MsgKey.STAGE_PLY_PRUNE to R.string.stage_ply_prune,
    MsgKey.STAGE_PLY_SOR to R.string.stage_ply_sor,
    MsgKey.STAGE_PLY_KNN to R.string.stage_ply_knn
)

/**
 * 本地化消息解析: 键 (或键|arg1|arg2) → stringResource。
 * Localized message resolution: a key (or key|arg1|arg2) → stringResource.
 * Raw text without a matching key is returned as-is.
 */
@Composable
fun i18nMessage(raw: String): String {
    if (raw.isBlank()) return raw
    val parts = raw.split(MsgKey.SEP, limit = 3)
    val res = messageResIds[parts[0]] ?: return raw
    return if (parts.size > 1) stringResource(res, *(parts.drop(1).toTypedArray()))
    else stringResource(res)
}

/**
 * 非 Composable 场景的消息解析 (ctx 需已按语言包装, 见 [LocaleUtil.wrap])。
 * Non-composable message resolution (ctx must be locale-wrapped, see [LocaleUtil.wrap]).
 * Returns the raw input when the key is unknown or the text is not a key.
 */
fun resolveMessage(ctx: Context, keyOrRaw: String): String {
    if (keyOrRaw.isBlank()) return keyOrRaw
    val parts = keyOrRaw.split(MsgKey.SEP, limit = 3)
    val res = messageResIds[parts[0]] ?: return keyOrRaw
    return if (parts.size > 1) LocaleUtil.string(ctx, res, *(parts.drop(1).toTypedArray()))
    else LocaleUtil.string(ctx, res)
}
