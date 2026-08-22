package com.sharp.qnn

import android.app.Application
import android.content.Context
import com.sharp.qnn.data.ModelStore
import com.sharp.qnn.data.SettingsRepository
import com.sharp.qnn.data.SettingsRepository.Language
import com.sharp.qnn.pipeline.PipelineManager
import com.sharp.qnn.service.LogRecorderService
import com.sharp.qnn.util.LocaleUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Application entry point.
 *
 * 在 onCreate 中加载 native 库 `libsharp_qnn.so`，并以懒加载方式创建应用级
 * 单例（设置仓库、模型仓库、Pipeline 编排器），供各 ViewModel 共享。
 * Loads the native library `libsharp_qnn.so` in onCreate and lazily creates the
 * app-level singletons (settings repository, model store, pipeline orchestrator)
 * shared by the ViewModels.
 *
 * 注意：DataStore 与 QNN 运行时必须为进程内单例，故在此统一持有。
 * Note: the DataStore and QNN runtime must be process-wide singletons, so they
 * are held here.
 * 语言: 启动时按已保存的语言设置包装 base context (attachBaseContext),
 * 使非 Compose 取字符串的路径 (通知、服务) 也跟随用户语言; 运行期切换由
 * 根 Composable 通过 CompositionLocal 覆盖完成。
 * Language: the base context is wrapped with the saved language in
 * attachBaseContext, so non-Compose string lookups (notifications, services)
 * follow it too; runtime switching is handled by the root composable via a
 * CompositionLocal override.
 */
class SHARPApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        /** SharedPreferences 名, 用于 attachBaseContext 阶段读取语言 (DataStore 此时不可用) */
        /** SharedPreferences name for reading the language during attachBaseContext
         *  (DataStore is not available at this point) */
        const val LANG_PREFS = "sharp_language"
        const val KEY_LANGUAGE = "language"
    }

    override fun attachBaseContext(base: Context) {
        // attachBaseContext 阶段 Application 尚未完全初始化, DataStore 不可用
        // During attachBaseContext the Application is not fully initialized and
        // DataStore is unavailable. Use SharedPreferences to read the language.
        val prefs = base.getSharedPreferences(LANG_PREFS, Context.MODE_PRIVATE)
        val language = Language.fromKey(prefs.getString(KEY_LANGUAGE, null))
        super.attachBaseContext(LocaleUtil.wrap(base, language))
    }

    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("sharp_qnn")

        // Auto-start the recording service on cold start when the toggle is on
        // (not when restored from background)
        appScope.launch {
            if (settingsRepository.settingsFlow.first().logRecording) {
                LogRecorderService.start(this@SHARPApplication)
            }
        }
    }

    /** Settings repository: storage locations, PLY save dir, HTP perf scheduling, etc. */
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

    /** Model store: import, compile and persist the five slots PE / IE / REST_A / REST_B / REST_C */
    val modelStore: ModelStore by lazy { ModelStore(this, settingsRepository) }

    /** Pipeline 编排器：执行完整推理流程并暴露进度状态 */
    /** Pipeline orchestrator: runs the full inference flow and exposes progress state */
    val pipelineManager: PipelineManager by lazy {
        PipelineManager(this, modelStore, settingsRepository)
    }
}
