package com.sharp.qnn.pipeline

/**
 * QNN JNI 接口。
 * QNN JNI interface.
 *
 * native 库 `libsharp_qnn.so` 在 [com.sharp.qnn.SHARPApplication] 中加载，
 * 此处声明对应的 external 方法供 Kotlin 层调用。
 * The native library `libsharp_qnn.so` is loaded in
 * [com.sharp.qnn.SHARPApplication]; this object declares the matching
 * external methods for the Kotlin layer.
 *
 * 注意: 所有方法均在 native 线程中执行耗时操作，调用方需在协程中调度。
 * Note: all methods run heavy work on native threads; callers must schedule
 * them in coroutines.
 */
object QnnJni {

        // ====== Lifecycle ======

    /**
     * 探测设备实际的 HTP 架构 (QnnDevice_getPlatformInfo)。
     * Probe the device's actual HTP architecture (QnnDevice_getPlatformInfo).
     * @param libDir QNN .so 目录 (app nativeLibraryDir)
     * @param libDir QNN .so directory (app nativeLibraryDir)
     * @return 架构字符串如 "V79"; 探测失败返回空串
     * @return architecture string such as "V79"; empty string on failure
     */
    external fun probeHtpArch(libDir: String): String

    /**
     * 初始化 QNN 运行时。
     * Initialize the QNN runtime.
     * @param libDir  QNN .so 目录 (app nativeLibraryDir)
     * @param libDir  QNN .so directory (app nativeLibraryDir)
     * @param skelDir Skel 目录 (按探测到的架构提取)
     * @param skelDir Skel directory (extracted for the probed architecture)
     * @param arch    HTP 版本, 如 "V79" (来自 [probeHtpArch])
     * @param arch    HTP version, e.g. "V79" (from [probeHtpArch])
     * @return true 表示初始化成功
     * @return true on success
     */
    external fun nativeInit(libDir: String, skelDir: String, arch: String): Boolean

    /** Destroy the QNN runtime and release all resources */
    external fun nativeDestroy()

    /**
     * 设置 HTP 性能配置。
     * Set the HTP performance configuration.
     *
     * target/max + dcvsMode)
     * @param type          0=locked-corner mode (uses [lockedCorner]), 1=range mode (uses min/target/max + dcvsMode)
     * @param lockedCorner  锁角模式的电压角 (0x20 MIN ~ 0xA0 MAX, 见 SettingsRepository.VoltageCorner)
     * @param lockedCorner  voltage corner for locked mode (0x20 MIN ~ 0xA0 MAX, see SettingsRepository.VoltageCorner)
     * @param minCorner     自动调角的最小角 (DCVS 允许下调的最低角)
     * @param minCorner     minimum corner for range mode (lowest corner DCVS may drop to)
     * @param targetCorner  自动调角的目标角 (投票初始请求点, 须 min < target <= max)
     * @param targetCorner  target corner for range mode (initial voting point; min < target <= max)
     * @param maxCorner     自动调角的最大角 (DCVS 允许上调的最高角, 须 min != max)
     * @param maxCorner     maximum corner for range mode (highest corner DCVS may raise to; min != max)
     * @param dcvsMode      自动调角的 DCVS 调节策略 (0x1~0x20, 见 SettingsRepository.DcvsMode)
     * @param dcvsMode      DCVS policy for range mode (0x1~0x20, see SettingsRepository.DcvsMode)
     *
     * 必须在 [nativeInit] 之前调用, 本次进程初始化后更改将于下次生效。
     * Must be called before [nativeInit]; changes take effect on the next process init.
     */
    external fun setPerfConfig(
        type: Int, lockedCorner: Int, minCorner: Int,
        targetCorner: Int, maxCorner: Int, dcvsMode: Int
    )

        // ====== Model management ======

    /**
     * 加载预编译 .bin 上下文。
     * Load a precompiled .bin context.
     * "ie" / "rest_a" / "rest_b" / "rest_c" (见 ModelType.code)
     * @param modelType model slot: "pe" / "ie" / "rest_a" / "rest_b" / "rest_c" (see ModelType.code)
     * @param binPath   .bin 文件路径
     * @param binPath   path of the .bin file
     */
    external fun loadContextBinary(modelType: String, binPath: String): Boolean

    /**
     * 编译 DLC → .bin。
     * Compile a DLC into a .bin.
     * "ie" / "rest_a" / "rest_b" / "rest_c" (见 ModelType.code)
     * @param modelType  model slot: "pe" / "ie" / "rest_a" / "rest_b" / "rest_c" (see ModelType.code)
     * @param dlcPath    输入 .dlc 路径
     * @param dlcPath    input .dlc path
     * @param outBinPath 输出 .bin 路径
     * @param outBinPath output .bin path
     */
    external fun compileDlc(modelType: String, dlcPath: String, outBinPath: String): Boolean

    /**
     * 请求取消当前 DLC 编译 (线程安全)。
     * Request cancellation of the current DLC compilation (thread-safe).
     * 注意: graphFinalize 为阻塞调用, 取消在编译步骤间隙生效;
     * native 侧取消时已释放资源, 不会产生残留产物。
     * Note: graphFinalize is blocking, so cancellation takes effect between
     * compilation steps; the native side frees resources on cancel.
     */
    external fun cancelCompile()

    /** Release the context of a model */
    external fun freeContext(modelType: String)

    /**
     * Validate model file integrity: checks existence, reasonable size, and basic format header.
     * @param path   模型文件路径
     * @param path   model file path
     * @param format "bin" 或 "dlc"
     * @param format "bin" or "dlc"
     * @return null 表示合法, 否则返回错误消息
     * @return null if valid, an error message otherwise
     */
    external fun validateModelFile(path: String, format: String): String?

    /** Unload a model's context after inference to free HTP memory */
    external fun freeModel(modelType: String)

    /** Clear the REST inter-stage memory cache */
    external fun clearRestCache()

    // ======  ======
    // ====== Inference pipeline ======

    /**
     * 预处理图片: 解码 + EXIF 修正 + resize → image.raw。
     * Preprocess an image: decode + EXIF correction + resize → image.raw.
     * @return floatArrayOf(fpx, dfactor, origW, origH)，失败返回 null
     * @return floatArrayOf(fpx, dfactor, origW, origH), null on failure
     */
    external fun prepImage(imagePath: String, outRawPath: String): FloatArray?

    /** pre 阶段: 读取 image.raw, 切分 patch */
    /** Pre stage: read image.raw and split it into patches */
    external fun runPre(imageRawPath: String, workDir: String): Boolean

    /** PE 推理 (patch encoder, 共 35 个 patch) */
    /** PE inference (patch encoder, 35 patches) */
    external fun runPatchEncoder(workDir: String): Boolean

    /** IE 推理 (image encoder, 1 次) */
    /** IE inference (image encoder, once) */
    external fun runImageEncoder(workDir: String): Boolean

    /** IE 输出 (6 个尺度) */
    /** Merge stage: combine PE / IE outputs (6 scales) */
    external fun runMerge(workDir: String, peOutDir: String, ieOutDir: String): Boolean

    /** REST Seg A: 特征融合 (6 输入 → 6 上采样特征) */
    /** REST Seg A: feature fusion (6 inputs → 6 upsampled features) */
    external fun runRestSegA(workDir: String): Boolean

    /** REST Seg B: 视差估计 (3 特征 → disparity) */
    /** REST Seg B: disparity estimation (3 features → disparity) */
    external fun runRestSegB(workDir: String): Boolean

    /** REST Seg C: 高斯增量 (image+disparity+特征 → delta) */
    /** REST Seg C: gaussian delta (image+disparity+features → delta) */
    external fun runRestSegC(workDir: String, fpx: Float, origW: Int): Boolean

    /** post 阶段: 生成 PLY 点云 */
    /** Post stage: generate the PLY point cloud */
    external fun runPost(
        workDir: String,
        fpx: Float,
        origW: Int,
        origH: Int,
        outPlyPath: String
    ): Boolean

    /**
     * kNN 合并。
     * Optimize a PLY file: opacity pruning / SOR outlier removal / kNN merge via GaussSimplify.
     * @param plyPath        PLY 文件路径 (原地修改)
     * @param plyPath        PLY file path (modified in-place)
     * @param mergeK         kNN 近邻数 (默认 16)
     * @param mergeK         kNN neighbors (default 16)
     * @param mergeRatio     目标比例 0.0~1.0 (默认 0.5)
     * @param mergeRatio     target ratio 0.0~1.0 (default 0.5)
     * @param pruneThreshold 透明度剪枝阈值 (默认 0.1)
     * @param pruneThreshold opacity prune threshold (default 0.1)
     * @param sorNeighbors   SOR 近邻数, 0=关闭 (默认 10)
     * @param sorNeighbors   SOR neighbors, 0=disabled (default 10)
     * SOR std multiplier (default 2.0)
     * merge cap per pass (default 0.5)
     * @return true 表示优化成功
     * @return true on success
     */
    external fun nativeOptimizePly(
        plyPath: String,
        mergeK: Int,
        mergeRatio: Double,
        mergeCap: Double,
        pruneThreshold: Double,
        sorNeighbors: Int,
        sorStdRatio: Double
    ): Boolean

        // ====== Callbacks ======

    /** Set the progress callback (a JVM callback object) */
    external fun setProgressCallback(callback: ProgressCallback)
}

/**
 * Pipeline 进度回调。
 * Pipeline progress callback.
 *
 * 由 native 层在推理过程中回调，用于更新 UI 进度。
 * Invoked by the native layer during inference to update UI progress.
 */
interface ProgressCallback {
    /** Stage start */
    fun onStageStart(stageId: Int, stageName: String)

    /** In-stage progress update */
    fun onProgress(stageId: Int, current: Int, total: Int, elapsedMs: Long, detail: String)

    /** Stage complete */
    fun onStageComplete(stageId: Int, stageName: String, elapsedMs: Long)

    /** Log (level: 0=DEBUG, 1=INFO, 2=WARN, 3=ERROR) */
    fun onLog(level: Int, message: String)

    /** Stage error */
    fun onError(stageId: Int, message: String)
}
