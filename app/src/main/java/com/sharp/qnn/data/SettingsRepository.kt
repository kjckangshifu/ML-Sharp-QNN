package com.sharp.qnn.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sharp.qnn.R
import com.sharp.qnn.SHARPApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** App-level DataStore (Preferences), a process-wide singleton */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "sharp_settings")

/**
 * 设置仓库：基于 DataStore Preferences 管理用户偏好。
 * Settings repository: manages user preferences via DataStore Preferences.
 *
 * Managed items:
 * - plySaveLocation:      PLY 保存目录 (SAF tree Uri 字符串, 空 = 未设置)
 * - plySaveLocation:      PLY save directory (SAF tree Uri string; empty = not set)
 * - showImageDetails:     是否显示图片详细信息 (焦距、格式等)
 * - showImageDetails:     whether to show detailed image info (focal length, format, ...)
 * - logRecording:         是否记录日志到下载目录 sharp_log/
 * - logRecording:         whether to record logs into Download/sharp_log/
 * ZH / EN, 默认跟随系统)
 * - language:             UI language (SYSTEM / ZH / EN; SYSTEM follows the device by default)
 * HM, 默认HG; 中国用户可选择HM镜像站加速)
 * - downloadSource:      model download source (HG / HM, defaults to HG; Chinese users may prefer HM mirror for faster speed)
 * lockedCorner/rangeMin/rangeTarget/rangeMax/dcvsMode)
 * - HTP performance scheduling (perfType/lockedCorner/rangeMin/rangeTarget/rangeMax/dcvsMode)
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val PLY_SAVE = stringPreferencesKey("ply_save_location")
        val SHOW_IMAGE_DETAILS = booleanPreferencesKey("show_image_details")
        val LOG_RECORDING = booleanPreferencesKey("log_recording")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val LANGUAGE = stringPreferencesKey("language")
        val PERF_TYPE = intPreferencesKey("perf_type")
        val PERF_LOCKED_CORNER = intPreferencesKey("perf_locked_corner")
        val PERF_RANGE_MIN = intPreferencesKey("perf_range_min")
        val PERF_RANGE_TARGET = intPreferencesKey("perf_range_target")
        val PERF_RANGE_MAX = intPreferencesKey("perf_range_max")
        val PERF_DCVS_MODE = intPreferencesKey("perf_dcvs_mode")
        val DOWNLOAD_SOURCE = stringPreferencesKey("download_source")
        val PLY_OPTIMIZE = booleanPreferencesKey("ply_optimize")
        val PLY_MERGE_K = intPreferencesKey("ply_merge_k")
        val PLY_MERGE_RATIO = doublePreferencesKey("ply_merge_ratio")
        val PLY_PRUNE_THRESHOLD = doublePreferencesKey("ply_prune_threshold")
        val PLY_SOR_NEIGHBORS = intPreferencesKey("ply_sor_neighbors")
        val PLY_SOR_STD_RATIO = doublePreferencesKey("ply_sor_std_ratio")
        val PLY_MERGE_CAP = doublePreferencesKey("ply_merge_cap")
        val IMAGE_DIRECTORIES = stringPreferencesKey("image_directories")
    }

    /** HTP 调度类型 */
    /** HTP scheduling type */
    object PerfType {
        const val LOCKED = 0    // Locked mode (default): fixed voltage corner, constant frequency
        const val RANGE = 1     // Auto-adjust mode: DCVS dynamically adjusts between min and max corners
    }

    /**
     * HTP 电压角 (取值与 QNN QnnHtpPerfInfrastructure_VoltageCorner_t 一致, 0x20~0xA0;
     * 不含 DISABLE 0x10 与 UNKNOWN)。列表按电压从低到高排列。
     * HTP voltage corners (values match QNN QnnHtpPerfInfrastructure_VoltageCorner_t,
     * 0x20~0xA0; DISABLE 0x10 and UNKNOWN excluded). Listed from lowest to highest voltage.
     */
    object VoltageCorner {
        const val MIN = 0x20    // MIN (SVS2, lowest platform corner)
        const val SVS2 = 0x30
        const val SVS = 0x40
        const val SVS_PLUS = 0x50
        const val NOM = 0x60
        const val NOM_PLUS = 0x70
        const val TURBO = 0x80
        const val TURBO_PLUS = 0x90
        const val TURBO_L2 = 0x92
        const val TURBO_L3 = 0x93
        const val TURBO_L4 = 0x94
        const val TURBO_L5 = 0x95
        const val MAX = 0xA0    // MAX (highest platform corner)

        /** All selectable corners, low to high (shared with dropdowns) */
        val ALL: List<Int> = listOf(
            MIN, SVS2, SVS, SVS_PLUS, NOM, NOM_PLUS,
            TURBO, TURBO_PLUS, TURBO_L2, TURBO_L3, TURBO_L4, TURBO_L5, MAX
        )

        fun name(corner: Int): String = when (corner) {
            MIN -> "MIN (lowest)"
            SVS2 -> "SVS2"
            SVS -> "SVS"
            SVS_PLUS -> "SVS_PLUS"
            NOM -> "NOM"
            NOM_PLUS -> "NOM_PLUS"
            TURBO -> "TURBO"
            TURBO_PLUS -> "TURBO_PLUS"
            TURBO_L2 -> "TURBO_L2"
            TURBO_L3 -> "TURBO_L3"
            TURBO_L4 -> "TURBO_L4"
            TURBO_L5 -> "TURBO_L5"
            MAX -> "MAX (highest)"
            else -> "0x%02X".format(corner)
        }

        fun nameResId(corner: Int): Int = when (corner) {
            MIN -> R.string.corner_min
            SVS2 -> R.string.corner_svs2
            SVS -> R.string.corner_svs
            SVS_PLUS -> R.string.corner_svs_plus
            NOM -> R.string.corner_nom
            NOM_PLUS -> R.string.corner_nom_plus
            TURBO -> R.string.corner_turbo
            TURBO_PLUS -> R.string.corner_turbo_plus
            TURBO_L2 -> R.string.corner_turbo_l2
            TURBO_L3 -> R.string.corner_turbo_l3
            TURBO_L4 -> R.string.corner_turbo_l4
            TURBO_L5 -> R.string.corner_turbo_l5
            MAX -> R.string.corner_max
            else -> 0
        }
    }

    /**
     * DCVS 调节模式 (取值与 QNN QnnHtpPerfInfrastructure_PowerMode_t 一致, 0x1~0x20;
     * 官方语义见 HAP_DCVS_V2)。
     * DCVS adjustment modes (values match QNN QnnHtpPerfInfrastructure_PowerMode_t,
     * 0x1~0x20; official semantics in HAP_DCVS_V2).
     */
    object DcvsMode {
        const val ADJUST_UP_DOWN = 0x1             // allow both up and down (default)
        const val ADJUST_ONLY_UP = 0x2             // up only, avoids oscillation
        const val POWER_SAVER = 0x4                // power saver: high ramp threshold, low-frequency bias
        const val POWER_SAVER_AGGRESSIVE = 0x8     // aggressive power saver: faster frequency reduction
        const val PERFORMANCE = 0x10               // performance: low ramp threshold, stays high-frequency
        const val DUTY_CYCLE = 0x20                // duty cycle: scales with HVX activity (streaming workloads)

        /** All selectable DCVS modes */
        val ALL: List<Int> = listOf(
            ADJUST_UP_DOWN, ADJUST_ONLY_UP, POWER_SAVER,
            POWER_SAVER_AGGRESSIVE, PERFORMANCE, DUTY_CYCLE
        )

        fun name(mode: Int): String = when (mode) {
            ADJUST_UP_DOWN -> "ADJUST_UP_DOWN (adjust both)"
            ADJUST_ONLY_UP -> "ADJUST_ONLY_UP (up only)"
            POWER_SAVER -> "POWER_SAVER (power saver)"
            POWER_SAVER_AGGRESSIVE -> "POWER_SAVER_AGGRESSIVE (aggressive saver)"
            PERFORMANCE -> "PERFORMANCE_MODE (performance)"
            DUTY_CYCLE -> "DUTY_CYCLE (duty cycle/streaming)"
            else -> "0x%02X".format(mode)
        }
    }

    /** Default performance config: locked mode + highest corner (user-specified default) */
    object PerfDefaults {
        const val TYPE = PerfType.LOCKED
        const val LOCKED_CORNER = VoltageCorner.MAX
        const val RANGE_MIN = VoltageCorner.NOM
        const val RANGE_TARGET = VoltageCorner.MAX
        const val RANGE_MAX = VoltageCorner.MAX
        const val DCVS_MODE = DcvsMode.ADJUST_UP_DOWN
    }

    /** UI language: follow system / Chinese / English */
    enum class Language(@StringRes val labelRes: Int, val key: String) {
        SYSTEM(R.string.settings_language_system, "system"),
        ZH(R.string.settings_language_zh, "zh"),
        EN(R.string.settings_language_en, "en");

        companion object {
            fun fromKey(key: String?): Language =
                entries.firstOrNull { it.key == key } ?: SYSTEM
        }
    }

    /**
     * 模型下载源: HuggingFace 官方 (HG) 或国内镜像站 (HM)。
     * Model download source: HuggingFace official (HG) or HF Mirror (HM) for Chinese users.
     * /hf-mirror.com
     * Mirror URL: https://hf-mirror.com
     */
    enum class DownloadSource(val key: String) {
        HG("hg"),   // HuggingFace official (default)
        HM("hm");   // HF Mirror (recommended for Chinese users)

        companion object {
            fun fromKey(key: String?): DownloadSource =
                entries.firstOrNull { it.key == key } ?: HG
        }
    }

    /**
     * Settings snapshot.
     */
    data class Settings(
        val plySaveLocation: String,
        val showImageDetails: Boolean,
        val logRecording: Boolean,
        val perfType: Int,
        val perfLockedCorner: Int,
        val perfRangeMin: Int,
        val perfRangeTarget: Int,
        val perfRangeMax: Int,
        val perfDcvsMode: Int,
        val dynamicColor: Boolean,
        val language: Language = Language.SYSTEM,
        val downloadSource: DownloadSource = DownloadSource.HG,
        val plyOptimize: Boolean = true,
        val plyMergeK: Int = 16,
        val plyMergeRatio: Double = 0.5,
        val plyPruneThreshold: Double = 0.1,
        val plySorNeighbors: Int = 10,
        val plySorStdRatio: Double = 2.0,
        val plyMergeCap: Double = 0.5,
        val imageDirectories: Set<String> = emptySet()
    )

    val settingsFlow: Flow<Settings> = context.settingsDataStore.data.map { prefs ->
        Settings(
            plySaveLocation = prefs[Keys.PLY_SAVE] ?: DEFAULTS.plySaveLocation,
            showImageDetails = prefs[Keys.SHOW_IMAGE_DETAILS] ?: DEFAULTS.showImageDetails,
            logRecording = prefs[Keys.LOG_RECORDING] ?: DEFAULTS.logRecording,
            perfType = prefs[Keys.PERF_TYPE] ?: PerfDefaults.TYPE,
            perfLockedCorner = prefs[Keys.PERF_LOCKED_CORNER] ?: PerfDefaults.LOCKED_CORNER,
            perfRangeMin = prefs[Keys.PERF_RANGE_MIN] ?: PerfDefaults.RANGE_MIN,
            perfRangeTarget = prefs[Keys.PERF_RANGE_TARGET] ?: PerfDefaults.RANGE_TARGET,
            perfRangeMax = prefs[Keys.PERF_RANGE_MAX] ?: PerfDefaults.RANGE_MAX,
            perfDcvsMode = prefs[Keys.PERF_DCVS_MODE] ?: PerfDefaults.DCVS_MODE,
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: DEFAULTS.dynamicColor,
            language = Language.fromKey(prefs[Keys.LANGUAGE]),
            downloadSource = DownloadSource.fromKey(prefs[Keys.DOWNLOAD_SOURCE]),
            plyOptimize = prefs[Keys.PLY_OPTIMIZE] ?: DEFAULTS.plyOptimize,
            plyMergeK = prefs[Keys.PLY_MERGE_K] ?: DEFAULTS.plyMergeK,
            plyMergeRatio = prefs[Keys.PLY_MERGE_RATIO] ?: DEFAULTS.plyMergeRatio,
            plyPruneThreshold = prefs[Keys.PLY_PRUNE_THRESHOLD] ?: DEFAULTS.plyPruneThreshold,
            plySorNeighbors = prefs[Keys.PLY_SOR_NEIGHBORS] ?: DEFAULTS.plySorNeighbors,
            plySorStdRatio = prefs[Keys.PLY_SOR_STD_RATIO] ?: DEFAULTS.plySorStdRatio,
            plyMergeCap = prefs[Keys.PLY_MERGE_CAP] ?: DEFAULTS.plyMergeCap,
            imageDirectories = prefs[Keys.IMAGE_DIRECTORIES]?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        )
    }

    suspend fun setPlySaveLocation(uriString: String) {
        context.settingsDataStore.edit { it[Keys.PLY_SAVE] = uriString }
    }

    suspend fun setShowImageDetails(show: Boolean) {
        context.settingsDataStore.edit { it[Keys.SHOW_IMAGE_DETAILS] = show }
    }

    suspend fun setLogRecording(enable: Boolean) {
        context.settingsDataStore.edit { it[Keys.LOG_RECORDING] = enable }
    }

    suspend fun setDynamicColor(enable: Boolean) {
        context.settingsDataStore.edit { it[Keys.DYNAMIC_COLOR] = enable }
    }

    /** Set the UI language (SYSTEM follows the device) */
    suspend fun setLanguage(language: Language) {
        context.settingsDataStore.edit { it[Keys.LANGUAGE] = language.key }
        // Also write to SharedPreferences for cold-start attachBaseContext
        context.getSharedPreferences(SHARPApplication.LANG_PREFS, Context.MODE_PRIVATE)
            .edit().putString(SHARPApplication.KEY_LANGUAGE, language.key).apply()
    }

    /**
     * Set the model download source.
     * download source (HG=official, HM=mirror)
     */
    suspend fun setDownloadSource(source: DownloadSource) {
        context.settingsDataStore.edit { it[Keys.DOWNLOAD_SOURCE] = source.key }
    }

    suspend fun setPerfType(type: Int) {
        context.settingsDataStore.edit { it[Keys.PERF_TYPE] = type }
    }

    suspend fun setPerfLockedCorner(corner: Int) {
        context.settingsDataStore.edit { it[Keys.PERF_LOCKED_CORNER] = corner }
    }

    suspend fun setPerfRangeMin(corner: Int) {
        context.settingsDataStore.edit { it[Keys.PERF_RANGE_MIN] = corner }
    }

    suspend fun setPerfRangeTarget(corner: Int) {
        context.settingsDataStore.edit { it[Keys.PERF_RANGE_TARGET] = corner }
    }

    suspend fun setPerfRangeMax(corner: Int) {
        context.settingsDataStore.edit { it[Keys.PERF_RANGE_MAX] = corner }
    }

    suspend fun setPerfDcvsMode(mode: Int) {
        context.settingsDataStore.edit { it[Keys.PERF_DCVS_MODE] = mode }
    }

    suspend fun setPlyOptimize(enable: Boolean) {
        context.settingsDataStore.edit { it[Keys.PLY_OPTIMIZE] = enable }
    }

    suspend fun setPlyMergeK(k: Int) {
        context.settingsDataStore.edit { it[Keys.PLY_MERGE_K] = k }
    }

    suspend fun setPlyMergeRatio(ratio: Double) {
        context.settingsDataStore.edit { it[Keys.PLY_MERGE_RATIO] = ratio }
    }

    suspend fun setPlyPruneThreshold(threshold: Double) {
        context.settingsDataStore.edit { it[Keys.PLY_PRUNE_THRESHOLD] = threshold }
    }

    suspend fun setPlySorNeighbors(n: Int) {
        context.settingsDataStore.edit { it[Keys.PLY_SOR_NEIGHBORS] = n }
    }

    suspend fun setPlySorStdRatio(ratio: Double) {
        context.settingsDataStore.edit { it[Keys.PLY_SOR_STD_RATIO] = ratio }
    }

    suspend fun setPlyMergeCap(cap: Double) {
        context.settingsDataStore.edit { it[Keys.PLY_MERGE_CAP] = cap }
    }

    suspend fun setImageDirectories(dirs: Set<String>) {
        context.settingsDataStore.edit { it[Keys.IMAGE_DIRECTORIES] = dirs.joinToString(",") }
    }

    companion object {
        /** Default settings snapshot (used as the initial value for collectAsState) */
        val DEFAULTS = Settings(
            plySaveLocation = "",
            showImageDetails = false,
            logRecording = false,
            perfType = PerfDefaults.TYPE,
            perfLockedCorner = PerfDefaults.LOCKED_CORNER,
            perfRangeMin = PerfDefaults.RANGE_MIN,
            perfRangeTarget = PerfDefaults.RANGE_TARGET,
            perfRangeMax = PerfDefaults.RANGE_MAX,
            perfDcvsMode = PerfDefaults.DCVS_MODE,
            dynamicColor = true,
            language = Language.SYSTEM,
            downloadSource = DownloadSource.HG,
            plyOptimize = true,
            plyMergeK = 16,
            plyMergeRatio = 0.5,
            plyPruneThreshold = 0.1,
            plySorNeighbors = 10,
            plySorStdRatio = 2.0,
            plyMergeCap = 0.5,
            imageDirectories = emptySet()
        )

        /** Convert a SAF tree Uri to a human-readable path (e.g. /storage/emulated/0/Download/sharp_ply) */
        fun plySaveDisplayPath(uriString: String): String {
            if (uriString.isBlank()) return ""
            // Plain file path: return as-is
            if (!uriString.startsWith("content://")) {
                return uriString
            }
            return try {
                val uri = Uri.parse(uriString)
                val docId = DocumentsContract.getTreeDocumentId(uri)
                val decoded = Uri.decode(docId)
                val primary = decoded.substringAfter("primary:", "")
                if (primary != decoded) "/storage/emulated/0/$primary" else decoded
            } catch (e: Exception) {
                uriString
            }
        }

        fun displayPath(value: String): String =
            if (value.startsWith("content://")) plySaveDisplayPath(value) else value
    }
}
