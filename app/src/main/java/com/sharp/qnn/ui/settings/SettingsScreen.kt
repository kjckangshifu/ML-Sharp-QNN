package com.sharp.qnn.ui.settings

import android.app.Application
import android.os.Environment
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sharp.qnn.R
import com.sharp.qnn.SHARPApplication
import com.sharp.qnn.data.SettingsRepository
import com.sharp.qnn.data.SettingsRepository.DownloadSource
import com.sharp.qnn.data.SettingsRepository.Language
import com.sharp.qnn.service.LogRecorderService
import com.sharp.qnn.util.FileUtil
import com.sharp.qnn.util.FileUtil.formatFileSize
import com.sharp.qnn.util.MsgKey
import com.sharp.qnn.util.i18nMessage
import com.sharp.qnn.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * 设置页 ViewModel。
 * Settings view model.
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val sharpApp = app as SHARPApplication

    val settingsFlow = sharpApp.settingsRepository.settingsFlow

    fun setPlySaveLocation(uriString: String) {
        viewModelScope.launch { sharpApp.settingsRepository.setPlySaveLocation(uriString) }
    }

    fun setShowImageDetails(show: Boolean) {
        viewModelScope.launch { sharpApp.settingsRepository.setShowImageDetails(show) }
    }

    /** UI language (applies at runtime, no restart needed). */
    fun setLanguage(language: Language) {
        viewModelScope.launch { sharpApp.settingsRepository.setLanguage(language) }
    }

    /** Model download source (HG=official, HM=mirror for China). */
    fun setDownloadSource(source: DownloadSource) {
        viewModelScope.launch { sharpApp.settingsRepository.setDownloadSource(source) }
    }

    /** Log recording toggle: starts the foreground recording service, or stops it. */
    fun setLogRecording(enable: Boolean) {
        viewModelScope.launch {
            sharpApp.settingsRepository.setLogRecording(enable)
            if (enable) LogRecorderService.start(getApplication())
            else LogRecorderService.stop(getApplication())
        }
    }

    /** System theme color toggle (effective on Android 12+). */
    fun setDynamicColor(enable: Boolean) {
        viewModelScope.launch { sharpApp.settingsRepository.setDynamicColor(enable) }
    }

    /** HTP 调度类型: 0=锁角, 1=自动调角 */
    /** HTP scheduling type: 0=locked corner, 1=auto range. */
    fun setPerfType(type: Int) {
        viewModelScope.launch { sharpApp.settingsRepository.setPerfType(type) }
    }

    /** Voltage corner for locked mode. */
    fun setPerfLockedCorner(corner: Int) {
        viewModelScope.launch { sharpApp.settingsRepository.setPerfLockedCorner(corner) }
    }

    /**
     * 规范化自动调角三元组, 保持不变量 min < target <= max 且 min != max:
     * Normalizes the auto-range triple, keeping the invariant min < target <= max and min != max:
     * target <= min 时推到 min 的上一档; max < target 时 max 提升到 target。
     * If target <= min, target is pushed to the step above min; if max < target, max is raised to target.
     * 返回 null 表示 min 已是最高档、无法满足不变量 (调用方应放弃本次修改)。
     * Returns null when min is already the top step and the invariant cannot hold (callers should discard the change).
     */
    private fun fixRange(min: Int, target: Int, max: Int): Triple<Int, Int, Int>? {
        var m = min
        var t = target
        var x = max
        if (m >= t) {
            val h = higherCorner(m) ?: return null
            t = h
        }
        if (t > x) x = t
        if (t <= m) {
            val h = higherCorner(m) ?: return null
            t = h
        }
        if (t > x) x = t
        return Triple(m, t, x)
    }

    /** Auto-range min corner (keeps the invariant with target/max). */
    fun setPerfRangeMin(corner: Int) {
        viewModelScope.launch {
            val repo = sharpApp.settingsRepository
            val s = repo.settingsFlow.first()
            val r = fixRange(corner, s.perfRangeTarget, s.perfRangeMax) ?: return@launch
            repo.setPerfRangeMin(r.first)
            if (r.second != s.perfRangeTarget) repo.setPerfRangeTarget(r.second)
            if (r.third != s.perfRangeMax) repo.setPerfRangeMax(r.third)
        }
    }

    /** Auto-range target corner (clamped to (min, max]; keeps the invariant with max). */
    fun setPerfRangeTarget(corner: Int) {
        viewModelScope.launch {
            val repo = sharpApp.settingsRepository
            val s = repo.settingsFlow.first()
            val r = fixRange(s.perfRangeMin, corner, s.perfRangeMax) ?: return@launch
            repo.setPerfRangeTarget(r.second)
            if (r.third != s.perfRangeMax) repo.setPerfRangeMax(r.third)
        }
    }

    /** Auto-range max corner (keeps the invariant with min/target). */
    fun setPerfRangeMax(corner: Int) {
        viewModelScope.launch {
            val repo = sharpApp.settingsRepository
            val s = repo.settingsFlow.first()
            val r = fixRange(s.perfRangeMin, s.perfRangeTarget, corner) ?: return@launch
            repo.setPerfRangeMax(r.third)
            if (r.first != s.perfRangeMin) repo.setPerfRangeMin(r.first)
            if (r.second != s.perfRangeTarget) repo.setPerfRangeTarget(r.second)
        }
    }

    /** DCVS adjustment mode for auto-range. */
    fun setPerfDcvsMode(mode: Int) {
        viewModelScope.launch { sharpApp.settingsRepository.setPerfDcvsMode(mode) }
    }

    fun setPlyOptimize(enable: Boolean) {
        viewModelScope.launch { sharpApp.settingsRepository.setPlyOptimize(enable) }
    }

    fun setPlyMergeK(k: Int) {
        viewModelScope.launch { sharpApp.settingsRepository.setPlyMergeK(k) }
    }

    fun setPlyMergeRatio(ratio: Double) {
        viewModelScope.launch { sharpApp.settingsRepository.setPlyMergeRatio(ratio) }
    }

    fun setPlyPruneThreshold(threshold: Double) {
        viewModelScope.launch { sharpApp.settingsRepository.setPlyPruneThreshold(threshold) }
    }

    fun setPlySorNeighbors(n: Int) {
        viewModelScope.launch { sharpApp.settingsRepository.setPlySorNeighbors(n) }
    }

    fun setPlySorStdRatio(ratio: Double) {
        viewModelScope.launch { sharpApp.settingsRepository.setPlySorStdRatio(ratio) }
    }

    fun setPlyMergeCap(cap: Double) {
        viewModelScope.launch { sharpApp.settingsRepository.setPlyMergeCap(cap) }
    }

    fun setImageDirectories(dirs: Set<String>) {
        viewModelScope.launch { sharpApp.settingsRepository.setImageDirectories(dirs) }
    }

    /** Re-scans the directory after file manager actions to realign slots. */
    fun scanModels() {
        viewModelScope.launch { sharpApp.modelStore.scanModelDirectory() }
    }

    /** Model root directory (sharp_models). */
    fun modelRootDir(): File = sharpApp.modelStore.modelRootDir()

    fun clearCache(onResult: (Pair<Int, Long>) -> Unit) {
        viewModelScope.launch {
            val result = sharpApp.modelStore.clearAppCache()
            onResult(result.getOrElse { 0 to 0L })
        }
    }
}

@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    val settings by vm.settingsFlow.collectAsState(initial = SettingsRepository.DEFAULTS)

    // Model file manager dialog toggle
    var showModelManager by remember { mutableStateOf(false) }

    // PLY directory picker dialog
    var showPlyPicker by remember { mutableStateOf(false) }

    var cacheMessage by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
                // ====== UI language ======
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = stringResource(R.string.settings_language_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LanguagePanel(
                        current = settings.language,
                        onChange = { vm.setLanguage(it) }
                    )
                }
            }
        }

                // ====== Model download source ======
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(R.string.settings_download_source), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = stringResource(R.string.settings_download_source_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    DownloadSourcePanel(
                        current = settings.downloadSource,
                        onChange = { vm.setDownloadSource(it) }
                    )
                }
            }
        }

                // ====== Model file manager ======
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(R.string.settings_model_manager), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = stringResource(R.string.settings_model_manager_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FilledTonalButton(onClick = { showModelManager = true }) {
                        Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(Spacing.sm))
                        Text(stringResource(R.string.settings_open_manager))
                    }
                }
            }
        }

        // ====== PLY Save Location ======
        // ====== PLY save location ======
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(R.string.settings_ply_location), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (settings.plySaveLocation.isBlank())
                            stringResource(R.string.settings_ply_not_set)
                        else
                            stringResource(R.string.settings_ply_current, SettingsRepository.plySaveDisplayPath(settings.plySaveLocation)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FilledTonalButton(onClick = { showPlyPicker = true }) {
                        Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(Spacing.sm))
                        Text(stringResource(R.string.settings_choose_dir))
                    }
                }
            }
        }

                // ====== Image details ======
        item {
            SwitchSettingRow(
                title = stringResource(R.string.settings_image_details),
                description = stringResource(R.string.settings_image_details_desc),
                checked = settings.showImageDetails,
                onCheckedChange = { vm.setShowImageDetails(it) }
            )
        }

                // ====== Image directory filter ======
        item {
            ImageDirPanel(
                dirs = settings.imageDirectories,
                onDirsChange = { vm.setImageDirectories(it) }
            )
        }

        // ====== PLY Optimization ======
        // ====== PLY optimization ======
        item {
            SwitchSettingRow(
                title = stringResource(R.string.settings_ply_optimize),
                description = stringResource(R.string.settings_ply_optimize_desc),
                checked = settings.plyOptimize,
                onCheckedChange = { vm.setPlyOptimize(it) }
            )
        }
        item {
            AnimatedVisibility(
                visible = settings.plyOptimize,
                enter = fadeIn(spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)) +
                    expandVertically(spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)),
                exit = fadeOut(spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)) +
                    shrinkVertically(spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium))
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    PlyPrunePanel(
                        threshold = settings.plyPruneThreshold.toFloat(),
                        onThresholdChange = { vm.setPlyPruneThreshold(it.toDouble()) }
                    )
                    PlySorPanel(
                        neighbors = settings.plySorNeighbors,
                        stdRatio = settings.plySorStdRatio.toFloat(),
                        onNeighborsChange = { vm.setPlySorNeighbors(it) },
                        onStdRatioChange = { vm.setPlySorStdRatio(it.toDouble()) }
                    )
                    PlyMergePanel(
                        mergeK = settings.plyMergeK,
                        mergeRatio = settings.plyMergeRatio.toFloat(),
                        mergeCap = settings.plyMergeCap.toFloat(),
                        onMergeKChange = { vm.setPlyMergeK(it) },
                        onMergeRatioChange = { vm.setPlyMergeRatio(it.toDouble()) },
                        onMergeCapChange = { vm.setPlyMergeCap(it.toDouble()) }
                    )
                }
            }
        }

        // ====== HTP Performance ======
        // ====== HTP performance scheduling ======
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(Spacing.lg).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(stringResource(R.string.settings_htp_perf), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = stringResource(R.string.settings_htp_perf_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val haptic = LocalHapticFeedback.current

                    // ----- Locked corner mode (default) -----
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = settings.perfType == SettingsRepository.PerfType.LOCKED,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                vm.setPerfType(SettingsRepository.PerfType.LOCKED)
                            }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_locked_mode), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = stringResource(R.string.settings_locked_mode_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (settings.perfType == SettingsRepository.PerfType.LOCKED) {
                        LockedCornerPanel(
                            corner = settings.perfLockedCorner,
                            onChange = { vm.setPerfLockedCorner(it) }
                        )
                    }

                    // ----- Auto-range mode -----
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = settings.perfType == SettingsRepository.PerfType.RANGE,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                vm.setPerfType(SettingsRepository.PerfType.RANGE)
                            }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_range_mode), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = stringResource(R.string.settings_range_mode_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (settings.perfType == SettingsRepository.PerfType.RANGE) {
                        RangePanel(
                            minCorner = settings.perfRangeMin,
                            targetCorner = settings.perfRangeTarget,
                            maxCorner = settings.perfRangeMax,
                            dcvsMode = settings.perfDcvsMode,
                            onMinChange = { vm.setPerfRangeMin(it) },
                            onTargetChange = { vm.setPerfRangeTarget(it) },
                            onMaxChange = { vm.setPerfRangeMax(it) },
                            onDcvsModeChange = { vm.setPerfDcvsMode(it) }
                        )
                    }
                }
            }
        }

                // ====== System theme color ======
        item {
            SwitchSettingRow(
                title = stringResource(R.string.settings_dynamic_color),
                description = stringResource(R.string.settings_dynamic_color_desc),
                checked = settings.dynamicColor,
                onCheckedChange = { vm.setDynamicColor(it) }
            )
        }

                // ====== Log recording ======
        item {
            SwitchSettingRow(
                title = stringResource(R.string.settings_logging),
                description = stringResource(R.string.settings_logging_desc),
                checked = settings.logRecording,
                onCheckedChange = { vm.setLogRecording(it) }
            )
        }

                // ====== Clear cache ======
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(R.string.settings_cache), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = stringResource(R.string.settings_cache_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = {
                        vm.clearCache { (count, bytes) ->
                            cacheMessage = if (count > 0)
                                MsgKey.k(MsgKey.MSG_CACHE_CLEARED, count.toString(), FileUtil.formatFileSize(bytes))
                            else
                                MsgKey.MSG_CACHE_EMPTY
                        }
                    }) {
                        Icon(Icons.Filled.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(Spacing.sm))
                        Text(stringResource(R.string.settings_clear_cache))
                    }
                    cacheMessage?.let {
                        Text(i18nMessage(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }

    // Model file manager dialog (standalone window, outside the LazyColumn)
    if (showModelManager) {
        ModelFileManagerDialog(
            root = vm.modelRootDir(),
            onDismiss = { showModelManager = false },
            onDeleted = { vm.scanModels() }
        )
    }

    // PLY save directory picker (unified BasicAlertDialog style)
    if (showPlyPicker) {
        PlyDirPickerDialog(
            initialPath = settings.plySaveLocation,
            onSelected = {
                vm.setPlySaveLocation(it)
                showPlyPicker = false
            },
            onDismiss = { showPlyPicker = false }
        )
    }
}

/** Next step up the voltage corner list (higher voltage); null at the top. */
private fun higherCorner(corner: Int): Int? {
    val idx = SettingsRepository.VoltageCorner.ALL.indexOf(corner)
    if (idx < 0 || idx >= SettingsRepository.VoltageCorner.ALL.size - 1) return null
    return SettingsRepository.VoltageCorner.ALL[idx + 1]
}

/** Previous step down the voltage corner list (lower voltage); null at the bottom. */
private fun lowerCorner(corner: Int): Int? {
    val idx = SettingsRepository.VoltageCorner.ALL.indexOf(corner)
    if (idx <= 0) return null
    return SettingsRepository.VoltageCorner.ALL[idx - 1]
}

/** Index of a corner in the list (for discrete slider mapping). */
private fun idxOfCorner(corner: Int): Int {
    val idx = SettingsRepository.VoltageCorner.ALL.indexOf(corner)
    return if (idx >= 0) idx else SettingsRepository.VoltageCorner.ALL.size - 1
}

/**
 * MD3 面板容器: 以 tonal surface (surfaceContainerLow) 分层,
 * MD3 sub-panel container: layered with tonal surfaces (surfaceContainerLow),
 * 遵循 MD3 "以色调表面表达层级, 而非阴影" 的规范
 * following the MD3 guideline "express elevation with tonal surfaces, not shadows".
 */
@Composable
private fun PerfSubPanel(
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(4.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(Spacing.md),
        verticalArrangement = verticalArrangement
    ) { content() }
}

/** Discrete voltage corner slider (13 steps, MIN~MAX). */
@Composable
private fun LockedCornerPanel(
    corner: Int,
    onChange: (Int) -> Unit
) {
    val corners = SettingsRepository.VoltageCorner.ALL
    val maxIdx = corners.size - 1
    var draft by remember { mutableStateOf(idxOfCorner(corner)) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(corner) { draft = idxOfCorner(corner) }

    PerfSubPanel(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.settings_corner_step), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                text = stringResource(SettingsRepository.VoltageCorner.nameResId(corners[draft])),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        Slider(
            value = draft.toFloat(),
            onValueChange = { raw ->
                val newIdx = raw.roundToInt()
                if (newIdx != draft) {
                    draft = newIdx
                    // Haptic tick per step skipped (M3 discrete slider spec)
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            },
            onValueChangeFinished = { onChange(corners[draft]) },
            valueRange = 0f..maxIdx.toFloat(),
            steps = maxIdx - 1,
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "MIN",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "MAX",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = stringResource(R.string.settings_corner_default_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** Auto-range panel: RangeSlider for the interval + target slider + DCVS FilterChips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RangePanel(
    minCorner: Int,
    targetCorner: Int,
    maxCorner: Int,
    dcvsMode: Int,
    onMinChange: (Int) -> Unit,
    onTargetChange: (Int) -> Unit,
    onMaxChange: (Int) -> Unit,
    onDcvsModeChange: (Int) -> Unit
) {
    val corners = SettingsRepository.VoltageCorner.ALL
    val maxIdx = corners.size - 1
    var rangeDraft by remember {
        mutableStateOf(idxOfCorner(minCorner).toFloat()..idxOfCorner(maxCorner).toFloat())
    }
    var targetDraft by remember { mutableStateOf(idxOfCorner(targetCorner).toFloat()) }
    val haptic = LocalHapticFeedback.current

    // Sync the local drafts when external persisted values change (e.g. after switching modes)
    LaunchedEffect(minCorner, maxCorner, targetCorner) {
        rangeDraft = idxOfCorner(minCorner).toFloat()..idxOfCorner(maxCorner).toFloat()
        targetDraft = idxOfCorner(targetCorner).toFloat().coerceIn(rangeDraft.start, rangeDraft.endInclusive)
    }

    PerfSubPanel {
        Text(stringResource(R.string.settings_freq_range), style = MaterialTheme.typography.labelLarge)
        RangeSlider(
            value = rangeDraft,
            onValueChange = { raw ->
                // Haptic tick whenever either handle skips a step
                if (raw.start.roundToInt() != rangeDraft.start.roundToInt() ||
                    raw.endInclusive.roundToInt() != rangeDraft.endInclusive.roundToInt()
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                rangeDraft = raw
            },
            onValueChangeFinished = {
                var lo = rangeDraft.start.roundToInt()
                var hi = rangeDraft.endInclusive.roundToInt()
                // Enforce min != max: push a handle apart when they overlap
                if (lo == hi) {
                    if (lo >= maxIdx) lo-- else hi++
                }
                onMinChange(corners[lo])
                onMaxChange(corners[hi])
            },
            valueRange = 0f..maxIdx.toFloat(),
            steps = maxIdx - 1,
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.settings_min, stringResource(SettingsRepository.VoltageCorner.nameResId(corners[rangeDraft.start.roundToInt()]))),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.settings_max, stringResource(SettingsRepository.VoltageCorner.nameResId(corners[rangeDraft.endInclusive.roundToInt()]))),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.settings_target_corner), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                text = stringResource(SettingsRepository.VoltageCorner.nameResId(corners[targetDraft.roundToInt()])),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        Slider(
            value = targetDraft.coerceIn(rangeDraft.start, rangeDraft.endInclusive),
            onValueChange = { raw ->
                val newIdx = raw.roundToInt()
                if (newIdx != targetDraft.roundToInt()) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                targetDraft = raw
            },
            onValueChangeFinished = { onTargetChange(corners[targetDraft.roundToInt()]) },
            // Track the draft interval so the range and target sliders never cross (persisted values not yet committed)
            valueRange = rangeDraft.start..rangeDraft.endInclusive,
            steps = (rangeDraft.endInclusive - rangeDraft.start).toInt().coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth()
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text(stringResource(R.string.settings_dcvs_policy), style = MaterialTheme.typography.labelLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            DcvsModeChips.forEach { (mode, labelRes) ->
                FilterChip(
                    selected = dcvsMode == mode,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onDcvsModeChange(mode)
                    },
                    label = { Text(stringResource(labelRes)) }
                )
            }
        }
        Text(
            text = dcvsModeHint(dcvsMode),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.settings_dcvs_rule),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** DCVS 模式短名 (FilterChip 标签, 资源 id) 与官方语义提示 */
/** DCVS mode short labels (FilterChip text, resource ids) with official semantics below. */
private val DcvsModeChips = listOf(
    SettingsRepository.DcvsMode.ADJUST_UP_DOWN to R.string.settings_dcvs_chip_up_down,
    SettingsRepository.DcvsMode.ADJUST_ONLY_UP to R.string.settings_dcvs_chip_only_up,
    SettingsRepository.DcvsMode.POWER_SAVER to R.string.settings_dcvs_chip_saver,
    SettingsRepository.DcvsMode.POWER_SAVER_AGGRESSIVE to R.string.settings_dcvs_chip_saver_aggressive,
    SettingsRepository.DcvsMode.PERFORMANCE to R.string.settings_dcvs_chip_performance,
    SettingsRepository.DcvsMode.DUTY_CYCLE to R.string.settings_dcvs_chip_duty
)

@Composable
private fun dcvsModeHint(mode: Int): String = when (mode) {
    SettingsRepository.DcvsMode.ADJUST_UP_DOWN -> stringResource(R.string.settings_dcvs_hint_up_down)
    SettingsRepository.DcvsMode.ADJUST_ONLY_UP -> stringResource(R.string.settings_dcvs_hint_only_up)
    SettingsRepository.DcvsMode.POWER_SAVER -> stringResource(R.string.settings_dcvs_hint_saver)
    SettingsRepository.DcvsMode.POWER_SAVER_AGGRESSIVE -> stringResource(R.string.settings_dcvs_hint_saver_aggressive)
    SettingsRepository.DcvsMode.PERFORMANCE -> stringResource(R.string.settings_dcvs_hint_performance)
    SettingsRepository.DcvsMode.DUTY_CYCLE -> stringResource(R.string.settings_dcvs_hint_duty)
    else -> stringResource(R.string.settings_dcvs_unknown, "0x%02X".format(mode))
}

/** Language picker: System / Chinese / English (FilterChip row). */
@Composable
private fun LanguagePanel(
    current: Language,
    onChange: (Language) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Language.entries.forEach { lang ->
            FilterChip(
                selected = current == lang,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onChange(lang)
                },
                label = { Text(stringResource(lang.labelRes)) }
            )
        }
    }
}

/**
 * HM 国内镜像)。
 * Model download source selection panel (HG official / HM mirror for China).
 *
 * 中国用户可选择 HM 镜像站 (hf-mirror.com) 获得更快的下载速度。
 * Chinese users can select the HM mirror (hf-mirror.com) for faster download speeds.
 */
@Composable
private fun DownloadSourcePanel(
    current: DownloadSource,
    onChange: (DownloadSource) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DownloadSource.entries.forEach { source ->
            FilterChip(
                selected = current == source,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onChange(source)
                },
                label = {
                    Text(
                        when (source) {
                            DownloadSource.HG -> stringResource(R.string.settings_download_source_hg)
                            DownloadSource.HM -> stringResource(R.string.settings_download_source_hm)
                        }
                    )
                }
            )
        }
    }
}

/**
 * MD3 设置卡片中的开关行: 标题 + 描述 + Switch。
 * MD3 switch row for settings cards: title + description + Switch.
 *
 * 抽象重复的 Row + Column + Switch 模式, 统一排版与色调。
 * Abstracts the repeated Row + Column + Switch pattern with consistent layout and tones.
 * 卡片容器使用 surfaceContainerLow (MD3 tonal surface 层级规范)。
 * Card containers use surfaceContainerLow (MD3 tonal surface hierarchy).
 */
@Composable
private fun SwitchSettingRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(Spacing.lg).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

/**
 * Opacity Pruning 子面板: 剔除低透明度的高斯点。
 * Opacity Pruning sub-panel: removes low-opacity Gaussians.
 */
@Composable
private fun PlyPrunePanel(
    threshold: Float,
    onThresholdChange: (Float) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                stringResource(R.string.settings_ply_prune),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.settings_ply_prune_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.settings_ply_prune_threshold),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = String.format("%.2f", threshold),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Slider(
                value = threshold,
                onValueChange = onThresholdChange,
                valueRange = 0f..0.5f,
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("0.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("0.5", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * SOR (Statistical Outlier Removal) 子面板: 剔除空间孤立的漂浮点。
 * SOR sub-panel: removes spatially isolated floaters.
 */
@Composable
private fun PlySorPanel(
    neighbors: Int,
    stdRatio: Float,
    onNeighborsChange: (Int) -> Unit,
    onStdRatioChange: (Float) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var draftNb by remember(neighbors) { mutableStateOf(neighbors.toFloat()) }
    LaunchedEffect(neighbors) { draftNb = neighbors.toFloat() }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                stringResource(R.string.settings_ply_sor),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.settings_ply_sor_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Neighbors
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.settings_ply_sor_nb),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (neighbors == 0) stringResource(R.string.settings_off) else neighbors.toString(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Slider(
                value = draftNb,
                onValueChange = { raw ->
                    val newVal = raw.roundToInt()
                    if (newVal != draftNb.roundToInt()) {
                        draftNb = raw
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                },
                onValueChangeFinished = { onNeighborsChange(draftNb.roundToInt()) },
                valueRange = 0f..40f,
                steps = 19,
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("40", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Std Ratio
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.settings_ply_sor_std),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = String.format("%.1f", stdRatio),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Slider(
                value = stdRatio,
                onValueChange = onStdRatioChange,
                valueRange = 0.5f..5f,
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("0.5", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("5.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * kNN Merge 子面板: 近邻合并参数。
 * kNN Merge sub-panel: neighbor merge parameters.
 */
@Composable
private fun PlyMergePanel(
    mergeK: Int,
    mergeRatio: Float,
    mergeCap: Float,
    onMergeKChange: (Int) -> Unit,
    onMergeRatioChange: (Float) -> Unit,
    onMergeCapChange: (Float) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var draftK by remember(mergeK) { mutableStateOf(mergeK.toFloat()) }
    LaunchedEffect(mergeK) { draftK = mergeK.toFloat() }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                stringResource(R.string.settings_ply_knn_merge),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.settings_ply_knn_merge_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // k neighbors
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.settings_ply_k),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = mergeK.toString(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Slider(
                value = draftK,
                onValueChange = { raw ->
                    val newVal = raw.roundToInt()
                    if (newVal != draftK.roundToInt()) {
                        draftK = raw
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                },
                onValueChangeFinished = { onMergeKChange(draftK.roundToInt()) },
                valueRange = 4f..32f,
                steps = 13,
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("4", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("32", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                stringResource(R.string.settings_ply_k_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            // Merge ratio
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.settings_ply_merge_ratio),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${(mergeRatio * 100).roundToInt()}%",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Slider(
                value = mergeRatio,
                onValueChange = onMergeRatioChange,
                valueRange = 0.05f..1f,
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("5%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("100%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // merge cap per pass
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.settings_ply_merge_cap),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${(mergeCap * 100).roundToInt()}%",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Slider(
                value = mergeCap,
                onValueChange = onMergeCapChange,
                valueRange = 0.05f..1f,
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("5%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("100%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                stringResource(R.string.settings_ply_merge_cap_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/**
 * Image directory filter panel: browse folders from internal storage root; tap to enter, long-press to multi-select.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ImageDirPanel(
    dirs: Set<String>,
    onDirsChange: (Set<String>) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    // Summary card (selected dirs + add button)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                stringResource(R.string.settings_image_dirs),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.settings_image_dirs_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (dirs.isEmpty()) {
                Text(
                    stringResource(R.string.settings_image_dirs_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    dirs.forEach { dir ->
                        val name = dir.substringAfterLast("/").ifEmpty { dir }
                        FilterChip(
                            selected = true,
                            onClick = {
                                onDirsChange(dirs - dir)
                            },
                            label = { Text(name) }
                        )
                    }
                }
            }

            FilledTonalButton(onClick = { showDialog = true }) {
                Text(stringResource(R.string.settings_image_dirs_add))
            }
        }
    }

    // Directory picker (file-system browsing, tap to enter, long-press to multi-select, stores full paths)
    if (showDialog) {
        ImageDirPickerDialog(
            initialDirs = dirs,
            onDirsChange = onDirsChange,
            onDismiss = { showDialog = false }
        )
    }
}

/**
 * Image directory picker: file-system browsing; tap to enter, long-press to multi-select; stores full paths.
 * 路径用于 ImagePickerDialog 中通过 DATA LIKE 过滤, 确保选中的目录及其子目录下所有图片都被包含。
 * Paths are used in ImagePickerDialog via DATA LIKE filtering, so all images in the selected
 * directory and its subdirectories are included.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ImageDirPickerDialog(
    initialDirs: Set<String>,
    onDirsChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val root = remember { Environment.getExternalStorageDirectory() }
    val stack = remember { mutableStateListOf(root) }
    val current = stack.last()

    var selectMode by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }

    var refreshTick by remember { mutableIntStateOf(0) }

    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        selected.clear()
        selected.addAll(initialDirs)
    }

    LaunchedEffect(current, refreshTick) {
        loading = true
        entries = withContext(Dispatchers.IO) {
            (current.listFiles() ?: emptyArray<File>())
                .filter { it.isDirectory && !it.name.startsWith(".") }
                .sortedWith(compareBy({ it.name.lowercase() }))
                .toList()
        }
        loading = false
    }

    fun enter(dir: File) {
        selectMode = false
        stack.add(dir)
    }

    fun back() {
        if (selectMode) {
            selectMode = false
        } else if (stack.size > 1) {
            stack.removeAt(stack.size - 1)
        }
    }

    fun toggle(dir: File) {
        val path = dir.absolutePath
        if (selected.contains(path)) {
            selected.remove(path)
        } else {
            selected.add(path)
        }
    }

    val localizedContext = LocalContext.current

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f),
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        CompositionLocalProvider(LocalContext provides localizedContext) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(Spacing.md)) {
                // Title bar
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    if (stack.size > 1 || selectMode) {
                        IconButton(onClick = { back() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.fm_back_cd))
                        }
                    } else {
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp).padding(12.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_image_dirs),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = relativeStoragePath(root, current),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(onClick = {
                        selectMode = !selectMode
                        if (!selectMode) {
                            selected.clear()
                            selected.addAll(initialDirs)
                        }
                    }) {
                        Icon(
                            imageVector = if (selectMode) Icons.Filled.CheckCircle else Icons.Filled.SelectAll,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text(if (selectMode) stringResource(R.string.fm_done) else stringResource(R.string.fm_multiselect))
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.fm_close_cd))
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.xs))

                // Directory list
                if (loading) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                } else if (entries.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.fm_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        items(entries, key = { it.absolutePath }) { dir ->
                            ImageDirRow(
                                dir = dir,
                                selectMode = selectMode,
                                selected = selected.contains(dir.absolutePath),
                                onClick = {
                                    if (selectMode) {
                                        toggle(dir)
                                    } else {
                                        enter(dir)
                                    }
                                },
                                onLongClick = {
                                    if (!selectMode) {
                                        selectMode = true
                                        toggle(dir)
                                    }
                                },
                                onCheckedChange = { toggle(dir) }
                            )
                        }
                    }
                }

                // Bottom buttons
                if (selectMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(onClick = {
                            selectMode = false
                            selected.clear()
                            selected.addAll(initialDirs)
                        }) {
                            Text(stringResource(android.R.string.cancel))
                        }
                        Spacer(Modifier.width(Spacing.sm))
                        Button(
                            onClick = {
                                onDirsChange(selected.toSet())
                                onDismiss()
                            }
                        ) {
                            Text(stringResource(R.string.settings_image_dirs_confirm))
                        }
                    }
                }
            }
        }
        } // CompositionLocalProvider
    }
}

/** Single directory row (long-press for multi-select, tap to enter/toggle). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageDirRow(
    dir: File,
    selectMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCheckedChange: () -> Unit
) {
    ListItem(
        leadingContent = {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        },
        headlineContent = {
            Text(
                text = dir.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailingContent = if (selectMode) {
            {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onCheckedChange() }
                )
            }
        } else null,
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    )
}

/** Display path relative to storage root. */
private fun relativeStoragePath(root: File, current: File): String {
    val rel = root.toPath().relativize(current.toPath()).toString()
    return if (rel.isEmpty()) root.name else "${root.name}/$rel"
}

/**
 * PLY 保存目录选择器弹窗: 从内部储存根目录浏览, 点击进入文件夹, 点击"选择此目录"确认。
 * PLY save directory picker: browse from internal storage root; tap to enter, tap "Select Directory" to confirm.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlyDirPickerDialog(
    initialPath: String,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val root = remember { Environment.getExternalStorageDirectory() }
    val stack = remember { mutableStateListOf(root) }
    val current = stack.last()

    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(current) {
        loading = true
        entries = withContext(Dispatchers.IO) {
            (current.listFiles() ?: emptyArray<File>())
                .filter { it.isDirectory && !it.name.startsWith(".") }
                .sortedWith(compareBy({ it.name.lowercase() }))
                .toList()
        }
        loading = false
    }

    fun enter(dir: File) { stack.add(dir) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.size - 1) }

    // Capture the outer localized Context: BasicAlertDialog creates a separate window
    // that resets LocalContext; re-inject it so language switches take effect
    val plyDirLocalizedContext = LocalContext.current

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f),
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        CompositionLocalProvider(LocalContext provides plyDirLocalizedContext) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(Spacing.md)) {
                // Title bar
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    if (stack.size > 1) {
                        IconButton(onClick = { back() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.fm_back_cd))
                        }
                    } else {
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp).padding(12.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_ply_location),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = relativeStoragePath(root, current),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.fm_close_cd))
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.xs))

                // Directory list
                if (loading) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                } else if (entries.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.fm_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        items(entries, key = { it.absolutePath }) { dir ->
                            ListItem(
                                leadingContent = {
                                    Icon(
                                        imageVector = Icons.Filled.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                },
                                headlineContent = {
                                    Text(
                                        text = dir.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                modifier = Modifier.clickable { enter(dir) }
                            )
                        }
                    }
                }

                // Bottom buttons: select current directory
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(onClick = onDismiss) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    Spacer(Modifier.width(Spacing.sm))
                    Button(onClick = { onSelected(current.absolutePath) }) {
                        Text(stringResource(R.string.settings_choose_dir))
                    }
                }
            }
        }
        } // CompositionLocalProvider
    }
}
