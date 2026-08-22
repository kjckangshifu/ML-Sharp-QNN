package com.sharp.qnn.ui.home

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.Manifest
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState
import com.sharp.qnn.SHARPApplication
import com.sharp.qnn.R
import com.sharp.qnn.data.ModelType
import com.sharp.qnn.data.ModelFormat
import com.sharp.qnn.data.ModelStatus
import com.sharp.qnn.data.SettingsRepository
import com.sharp.qnn.ui.components.ProgressCard
import com.sharp.qnn.util.FileUtil
import com.sharp.qnn.util.FileUtil.formatDuration
import com.sharp.qnn.util.FileUtil.formatFileSize
import com.sharp.qnn.util.MsgKey
import com.sharp.qnn.util.i18nMessage
import com.sharp.qnn.util.resolveMessage
import com.sharp.qnn.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主页 ViewModel：持有 Pipeline 状态、模型就绪情况与选中的图片。
 * Home view model: holds pipeline state, model readiness and the selected image.
 *
 * 图片 Uri 持有在 ViewModel 中, 切换页面不会丢失。
 * The image Uri lives in the ViewModel, so it survives page switches.
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val sharpApp = app as SHARPApplication

    val pipelineState = sharpApp.pipelineManager.state
    val models = sharpApp.modelStore.models
    val settingsFlow = sharpApp.settingsRepository.settingsFlow

    // Selected image Uri and file name (kept in the ViewModel across page switches)
    private val _selectedImageUri = mutableStateOf<Uri?>(null)
    val selectedImageUri: State<Uri?> = _selectedImageUri

    private val _selectedImageName = mutableStateOf<String?>(null)
    val selectedImageName: State<String?> = _selectedImageName

    // Detailed image info
    data class ImageDetails(
        val width: Int,
        val height: Int,
        val format: String,
        val focalLength: Float? = null,  // mm
        val fileSize: Long = 0
    )

    private val _imageDetails = mutableStateOf<ImageDetails?>(null)
    val imageDetails: State<ImageDetails?> = _imageDetails

    // Image-load generation: incremented on each selection, used to drop stale async results
    @Volatile
    private var imageLoadGeneration = 0L

    /** Sets the selected image. */
    fun setSelectedImage(uri: Uri?) {
        _selectedImageUri.value = uri
        _selectedImageName.value = uri?.let { FileUtil.getFileNameFromUri(sharpApp, it) }
        _imageDetails.value = null
        _exportMessage.value = null
        _exporting.value = false

        // Clear previous inference artifacts (excluding exported PLY) when re-selecting an image
        // Clear previous inference products and remnants when reselecting
        // (exported PLY files are not touched). Only reset when idle.
        if (!pipelineState.value.isRunning) {
            viewModelScope.launch(Dispatchers.IO) {
                sharpApp.pipelineManager.reset()
                val workDir = File(sharpApp.cacheDir, "sharp_work")
                if (workDir.exists()) {
                    FileUtil.deleteRecursively(workDir)
                }
            }
        }

        val gen = ++imageLoadGeneration
        if (uri != null) {
            viewModelScope.launch(Dispatchers.IO) {
                val details = loadImageDetails(uri)
                // Publish details only if this selection is still the latest; stale results are dropped
                if (gen == imageLoadGeneration) _imageDetails.value = details
            }
        }
    }

    private suspend fun loadImageDetails(uri: Uri): ImageDetails? = withContext(Dispatchers.IO) {
        try {
            val resolver = sharpApp.contentResolver
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null

            // Size + MIME type
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            val width = opts.outWidth
            val height = opts.outHeight
            val format = opts.outMimeType?.substringAfter("/")?.uppercase() ?: "UNKNOWN"

            // File size
            val size = bytes.size.toLong()

            // EXIF focal length (JPEG only)
            var focal: Float? = null
            if (format == "JPEG" || format == "JPG") {
                val exif = ExifInterface(java.io.ByteArrayInputStream(bytes))
                val focalVal = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
                if (focalVal > 0.0) focal = focalVal.toFloat()
            }

            ImageDetails(width, height, format, focal, size)
        } catch (e: Exception) {
            null
        }
    }

    /** Runs the full inference pipeline. */
    fun runPipeline(imageUri: Uri) {
        viewModelScope.launch {
            sharpApp.pipelineManager.runPipeline(imageUri)
        }
    }

    // PLY export message
    private val _exportMessage = mutableStateOf<String?>(null)
    val exportMessage: State<String?> = _exportMessage

    // Exporting flag (show progress)
    private val _exporting = mutableStateOf(false)
    val exporting: State<Boolean> = _exporting

    /** Sets the PLY save directory (SAF tree Uri string). */
    fun setPlySaveLocation(uriString: String) {
        viewModelScope.launch { sharpApp.settingsRepository.setPlySaveLocation(uriString) }
    }

    /** Exports the PLY to the chosen directory (direct file write). */
    fun exportPly(dirPath: String) {
        if (_exporting.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _exporting.value = true
            _exportMessage.value = null
            val src = sharpApp.pipelineManager.getLastPlyFile()
                ?: run {
                    _exportMessage.value = MsgKey.ERR_PLY_MISSING
                    _exporting.value = false
                    return@launch
                }
            try {
                val baseName = _selectedImageName.value
                    ?.substringBeforeLast('.')
                    ?.takeIf { it.isNotBlank() }
                    ?: "sharp"
                val name = "${baseName}_ply.ply"
                val dest = File(dirPath, name)
                src.inputStream().use { input ->
                    dest.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                }
                _exportMessage.value = MsgKey.k(MsgKey.MSG_EXPORT_OK, name)
            } catch (e: Exception) {
                _exportMessage.value = MsgKey.k(MsgKey.ERR_EXPORT_FAIL, e.message ?: "")
            } finally {
                _exporting.value = false
            }
        }
    }

    fun clearExportMessage() {
        _exportMessage.value = null
    }
}

@Composable
fun HomeScreen(
    vm: HomeViewModel = viewModel(),
    snackbarHostState: SnackbarHostState = androidx.compose.runtime.remember { SnackbarHostState() }
) {
    val pipelineState by vm.pipelineState.collectAsState()
    val models by vm.models.collectAsState()
    val settings by vm.settingsFlow.collectAsState(initial = SettingsRepository.DEFAULTS)
    val selectedImageUri by vm.selectedImageUri
    val selectedImageName by vm.selectedImageName
    val imageDetails by vm.imageDetails
    val exportMessage by vm.exportMessage
    val exporting by vm.exporting
    val context = LocalContext.current

    // Export result -> snackbar (short duration so it does not linger; keys are resolved in the current language)
    LaunchedEffect(exportMessage) {
        exportMessage?.let { msg ->
            snackbarHostState.showSnackbar(resolveMessage(context, msg), duration = SnackbarDuration.Short)
            vm.clearExportMessage()
        }
    }

    // Custom image picker (reads MediaStore directly, faster than system gallery)
    // Needs READ_MEDIA_IMAGES (Android 13+) or READ_EXTERNAL_STORAGE (Android 12)
    var showCustomPicker by remember { mutableStateOf(false) }

    val storagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showCustomPicker = true
        }
    }

    // Manage all files permission (API 30+ for PLY export)
    val manageStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // User returned from settings, nothing to handle
    }

    fun openImagePicker() {
        if (ContextCompat.checkSelfPermission(context, storagePermission) == PackageManager.PERMISSION_GRANTED) {
            showCustomPicker = true
        } else {
            permissionLauncher.launch(storagePermission)
        }
    }

    val canRun = ModelType.entries.all { type ->
        val model = models[type]
        model != null && (model.format == ModelFormat.BIN || model.status == ModelStatus.COMPILED)
    } && !pipelineState.isRunning && selectedImageUri != null

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        // Subtitle (the main title lives in the top app bar)
        item {
            Text(
                text = stringResource(R.string.pipeline_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Image selection and preview
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Text(
                        text = stringResource(R.string.home_input_image),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(onClick = { openImagePicker() }) {
                            Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null)
                            Spacer(Modifier.size(Spacing.sm))
                            Text(stringResource(R.string.home_select_image))
                        }
                        Spacer(Modifier.size(Spacing.md))
                        Text(
                            text = selectedImageName ?: stringResource(R.string.home_no_image),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    // AnimatedContent: cross-fade between selected/unselected states (MD3 emphasized)
                    AnimatedContent(
                        targetState = selectedImageUri,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(300)) togetherWith
                                fadeOut(animationSpec = tween(200))
                        },
                        label = "imagePreview"
                    ) { uri ->
                        if (uri != null) {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                ImagePreview(
                                    uri = uri,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(260.dp)
                                        .clip(MaterialTheme.shapes.medium)
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                )
                                // Image details (shown when enabled in settings)
                                if (settings.showImageDetails) {
                                    imageDetails?.let { d ->
                                        Card(
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                                            ),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(Spacing.md),
                                                verticalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.home_image_info),
                                                    style = MaterialTheme.typography.labelLarge,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                                )
                                                Text(
                                                    text = stringResource(R.string.home_image_size, d.width, d.height),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                                )
                                                Text(
                                                    text = stringResource(R.string.home_image_format, d.format),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                                )
                                                d.focalLength?.let { f ->
                                                    Text(
                                                        text = stringResource(R.string.home_image_focal, "%.1f".format(f)),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                                    )
                                                }
                                                if (d.fileSize > 0) {
                                                    Text(
                                                        text = stringResource(R.string.home_image_file_size, formatFileSize(d.fileSize)),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                                    )
                                                }
                                            }
                                        }
                                    } ?: Text(
                                        text = stringResource(R.string.home_loading_image_info),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(260.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Filled.AddPhotoAlternate,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.outline
                                    )
                                    Spacer(Modifier.size(Spacing.sm))
                                    Text(
                                        text = stringResource(R.string.home_no_image),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Run button
        item {
            Text(
                text = stringResource(R.string.home_run_section),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.size(Spacing.xs))
            Button(
                onClick = { selectedImageUri?.let { vm.runPipeline(it) } },
                enabled = canRun,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                AnimatedContent(
                    targetState = pipelineState.isRunning,
                    transitionSpec = {
                        if (targetState) {
                            (fadeIn(tween(300)) + scaleIn(tween(300))) togetherWith
                                (fadeOut(tween(200)) + scaleOut(tween(200)))
                        } else {
                            (fadeIn(tween(300)) + scaleIn(tween(300))) togetherWith
                                (fadeOut(tween(200)) + scaleOut(tween(200)))
                        }
                    }
                ) { running ->
                    if (running) {
                        val infiniteTransition = rememberInfiniteTransition()
                        val pulseAlpha by infiniteTransition.animateFloat(
                            initialValue = 0.4f,
                            targetValue = 1.0f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(800),
                                repeatMode = RepeatMode.Reverse
                            )
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.graphicsLayer { alpha = pulseAlpha }
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.size(Spacing.sm))
                            Text(stringResource(R.string.home_running))
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.size(Spacing.sm))
                            Text(stringResource(R.string.home_run))
                        }
                    }
                }
            }
            val missing = ModelType.entries.filter { !models.containsKey(it) }
            if (missing.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.home_missing_models,
                        missing.map { stringResource(it.nameRes) }.joinToString()
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Spacing.xs)
                )
            }
        }

        // Error banner (message keys are resolved in the current language)
        pipelineState.errorMessage?.let { msg ->
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(Spacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.BrokenImage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = i18nMessage(msg),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        // Inference progress (only visible during or after inference)
        val showProgress = pipelineState.isRunning || pipelineState.totalElapsedMs > 0 || pipelineState.errorMessage != null
        if (showProgress) {
            item {
                Text(
                    text = stringResource(R.string.home_progress_section),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Per-stage progress cards (only visible during or after inference)
        if (showProgress) {
            items(pipelineState.stages) { stage ->
                ProgressCard(stage = stage)
            }
        }

        // Total time (shown only after inference completes, with fade-in animation)
        val showTotal = !pipelineState.isRunning && pipelineState.totalElapsedMs > 0 && pipelineState.errorMessage == null
        if (showTotal) {
            item {
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(tween(400)) + expandVertically(tween(400))
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
                            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                        ) {
                            Text(
                                text = stringResource(R.string.home_done_total),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                text = formatDuration(pipelineState.totalElapsedMs),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
            }
        }

        // PLY export (available after inference; exports to the directory set in settings)
        item {
            Text(
                text = stringResource(R.string.home_export_section),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.size(Spacing.xs))
            val plyReady = !pipelineState.isRunning &&
                    pipelineState.totalElapsedMs > 0 &&
                    pipelineState.errorMessage == null

            // Export to the SAF directory from settings; pick a directory first if unset
            val hasSaveDir = settings.plySaveLocation.isNotBlank()

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Button(
                        onClick = {
                            if (hasSaveDir) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                                    !Environment.isExternalStorageManager()
                                ) {
                                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    manageStorageLauncher.launch(intent)
                                } else {
                                    vm.exportPly(settings.plySaveLocation)
                                }
                            }
                        },
                        enabled = plyReady && !exporting,
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        AnimatedContent(
                            targetState = exporting,
                            transitionSpec = {
                                if (targetState) {
                                    (fadeIn(tween(300)) + scaleIn(tween(300))) togetherWith
                                        (fadeOut(tween(200)) + scaleOut(tween(200)))
                                } else {
                                    (fadeIn(tween(300)) + scaleIn(tween(300))) togetherWith
                                        (fadeOut(tween(200)) + scaleOut(tween(200)))
                                }
                            }
                        ) { isExporting ->
                            if (isExporting) {
                                val infiniteTransition = rememberInfiniteTransition()
                                val pulseAlpha by infiniteTransition.animateFloat(
                                    initialValue = 0.4f,
                                    targetValue = 1.0f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(800),
                                        repeatMode = RepeatMode.Reverse
                                    )
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.graphicsLayer { alpha = pulseAlpha }
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.size(Spacing.sm))
                                    Text(stringResource(R.string.home_exporting))
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Save, contentDescription = null)
                                    Spacer(Modifier.size(Spacing.sm))
                                    Text(if (hasSaveDir) stringResource(R.string.home_export_ply) else stringResource(R.string.home_choose_dir))
                                }
                            }
                        }
                    }
                    Text(
                        text = if (hasSaveDir)
                            stringResource(R.string.home_save_to, SettingsRepository.plySaveDisplayPath(settings.plySaveLocation))
                        else
                            stringResource(R.string.home_no_save_dir),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (!plyReady) {
                        Text(
                            text = stringResource(R.string.home_export_after_done),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showCustomPicker) {
        ImagePickerDialog(
            title = stringResource(R.string.picker_title),
            closeContentDescription = stringResource(R.string.picker_close),
            onDismiss = { showCustomPicker = false },
            onImageSelected = { uri ->
                vm.setSelectedImage(uri)
                showCustomPicker = false
            },
            imageDirs = settings.imageDirectories
        )
    }
}

/**
 * 图片预览: 从 Uri 解码 (自适应采样) 并以 fit 模式显示在矩形框内。
 * Image preview: decodes from the Uri (with adaptive sampling) and fits it inside the box.
 * 比例不同时留黑边 (letterbox), 不裁剪填满。
 * Aspect mismatches are letterboxed instead of cropped.
 *
 * 采样策略: 先用 inJustDecodeBounds 获取原始尺寸, 再根据目标视图高度 (targetHeightDp)
 * 计算最优 inSampleSize (2 的幂), 避免加载过大图片浪费内存。
 * Sampling strategy: first reads the original dimensions via inJustDecodeBounds,
 * then computes the optimal inSampleSize (power of 2) based on the target view
 * height (targetHeightDp), avoiding excessive memory usage from oversized images.
 *
 * @param targetHeightDp 目标视图高度 (dp), 用于计算采样率, 默认 260dp
 * @param targetHeightDp target view height (dp) for computing sample size, default 260dp
 */
@Composable
private fun ImagePreview(uri: Uri, modifier: Modifier = Modifier, targetHeightDp: Int = 260) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var isLoading by remember(uri) { mutableStateOf(true) }

    // Recycle the old bitmap when switching images or leaving composition
    DisposableEffect(uri) {
        onDispose { bitmap?.recycle() }
    }

    LaunchedEffect(uri) {
        isLoading = true
        // Recycle the previous bitmap before loading a new one
        bitmap?.recycle()
        bitmap = null

        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val targetHeightPx = (targetHeightDp * context.resources.displayMetrics.density).toInt()
                context.contentResolver.openInputStream(uri)?.use { input ->
                    // Step 1: decode bounds only, no pixel allocation
                    val opts = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    BitmapFactory.decodeStream(input, null, opts)
                    input.close()

                    // Step 2: compute optimal sample size (power of 2, decoded height >= target)
                    val sampleSize = calculateSampleSize(opts.outHeight, targetHeightPx)

                    // Step 3: decode with the computed sample size
                    val decodeOpts = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                    }
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream, null, decodeOpts)
                    }
                }
            }.getOrNull()
        }
        isLoading = false
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading -> CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                strokeWidth = 3.dp
            )
            bitmap != null -> {
                val bmp = bitmap!!
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = stringResource(R.string.home_image_preview_cd),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Filled.BrokenImage,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.size(Spacing.sm))
                Text(
                    text = stringResource(R.string.home_image_load_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

/**
 * 计算最优 inSampleSize (2 的幂), 保证解码后高度 >= targetHeight。
 * Computes the optimal inSampleSize (power of 2), ensuring decoded height >= targetHeight.
 *
 * 800=5 → 取 4 → 解码后 1000px, 足够清晰且省内存。
 * E.g. original 4000px, target 800px: 4000/800=5 → use 4 → decoded 1000px, sharp enough.
 */
private fun calculateSampleSize(originalHeight: Int, targetHeight: Int): Int {
    if (targetHeight <= 0 || originalHeight <= targetHeight) return 1
    var sampleSize = 1
    while (originalHeight / (sampleSize * 2) >= targetHeight) {
        sampleSize *= 2
    }
    return sampleSize
}
