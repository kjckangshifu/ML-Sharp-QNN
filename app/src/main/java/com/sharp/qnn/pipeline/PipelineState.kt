package com.sharp.qnn.pipeline

import androidx.annotation.StringRes
import com.sharp.qnn.R
import com.sharp.qnn.data.ModelType

/**
 * Per-stage progress state.
 *
 * @param id         阶段 id (0..8, -1 为模型编译阶段)
 * @param id         stage id (0..8, -1 is the model compilation stage)
 * 消息用)
 * @param name       stage name (canonical English; logs & messages)
 * @param nameRes    本地化阶段名 (UI 用; 0 = 无, 回退 name)
 * @param nameRes    localized stage name (UI; 0 = none, falls back to name)
 * @param current    当前进度
 * @param current    current progress
 * @param total      总数
 * @param total      total count
 * @param elapsedMs  已耗时 (毫秒)
 * @param elapsedMs  elapsed time (ms)
 * @param detail     进度详情文本 (消息键或原始文本)
 * @param detail     progress detail text (message key or raw text)
 * @param isRunning  是否正在运行
 * @param isRunning  whether the stage is running
 * @param isComplete 是否已完成
 * @param isComplete whether the stage is complete
 */
data class StageState(
    val id: Int,
    val name: String,
    @StringRes val nameRes: Int = 0,
    val current: Int = 0,
    val total: Int = 0,
    val elapsedMs: Long = 0,
    val detail: String = "",
    val isRunning: Boolean = false,
    val isComplete: Boolean = false
) {
    /** Progress ratio 0..1 */
    val progress: Float
        get() = if (total > 0) (current.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/**
 * Pipeline 整体状态。
 * Overall pipeline state.
 *
 * @param stages         各阶段状态
 * @param stages         per-stage states
 * @param isRunning      整体是否运行中
 * @param isRunning      whether the whole pipeline is running
 * @param errorMessage   错误信息 (null 表示无错误)
 * @param errorMessage   error message (null when no error)
 * @param totalElapsedMs 总耗时 (毫秒)
 * @param totalElapsedMs total elapsed time (ms)
 */
data class PipelineState(
    val stages: List<StageState> = DEFAULT_STAGES,
    val isRunning: Boolean = false,
    val errorMessage: String? = null,
    val totalElapsedMs: Long = 0
) {
    /** Whether every stage is complete */
    val isAllComplete: Boolean get() = stages.isNotEmpty() && stages.all { it.isComplete }
}

/**
 * The default 11 pipeline stages:
 * -2. 初始化 (QNN 运行时 + 模型加载)
 * -2. initialization (QNN runtime + model loading)
 * 0. 解码图片
 * 0. image decode
 * 1. 预处理切 Patch
 * 1. pre-processing and patch splitting
 * 2. 图块编码 (PE 推理, 35 个 patch)
 * 2. patch encoding (PE inference, 35 patches)
 * 3. 图像编码 (IE 推理, 1 次)
 * 3. image encoding (IE inference, once)
 * 4. 特征合并 (Merge, 6 个尺度)
 * 4. feature merge (6 scales)
 * 5. 特征融合 (REST Seg A)
 * 5. feature fusion (REST Seg A)
 * 6. 视差估计 (REST Seg B)
 * 6. disparity estimation (REST Seg B)
 * 7. 高斯增量 (REST Seg C)
 * 7. gaussian delta (REST Seg C)
 * 8. 点云生成 (Post → PLY)
 * 8. point cloud generation (Post → PLY)
 * SOR/kNN合并)
 * 9. PLY optimization (opacity prune/SOR/kNN merge)
 */
val DEFAULT_STAGES: List<StageState> = listOf(
    StageState(id = -2, name = "Initialization", nameRes = R.string.stage_init, total = ModelType.entries.size),
    StageState(id = 0, name = "Decode Image", nameRes = R.string.stage_decode, total = 4),
    StageState(id = 1, name = "Preprocess & Split Patches", nameRes = R.string.stage_pre, total = 5),
    StageState(id = 2, name = "Patch Encoding", nameRes = R.string.stage_pe, total = 35),
    StageState(id = 3, name = "Image Encoding", nameRes = R.string.stage_ie, total = 1),
    StageState(id = 4, name = "Feature Merge", nameRes = R.string.stage_merge, total = 6),
    StageState(id = 5, name = "Feature Fusion", nameRes = R.string.stage_rest_a, total = 1),
    StageState(id = 6, name = "Disparity Estimation", nameRes = R.string.stage_rest_b, total = 1),
    StageState(id = 7, name = "Gaussian Delta", nameRes = R.string.stage_rest_c, total = 1),
    StageState(id = 8, name = "Point Cloud Generation", nameRes = R.string.stage_post, total = 5),
    StageState(id = 9, name = "PLY Optimization", nameRes = R.string.stage_ply_optimize, total = 1)
)
