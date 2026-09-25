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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.input.KeyboardType
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
import com.sharp.qnn.pipeline.QnnJni
import com.sharp.qnn.util.MsgKey
import com.sharp.qnn.util.i18nMessage
import com.sharp.qnn.util.resolveMessage
import com.sharp.qnn.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import java.io.DataOutputStream

/**
 * 主页 ViewModel：持�?Pipeline 状态、模型就绪情况与选中的图片�?
 * Home view model: holds pipeline state, model readiness and the selected image.
 *
 * 图片 Uri 持有�?ViewModel �? 切换页面不会丢失�?
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
        val focalLength: Float? = null,      // actual mm from EXIF
        val focalLength35mm: Float? = null,  // 35mm-equivalent from EXIF
        val fileSize: Long = 0
    )

    // Focal length unit for the editable field
    enum class FocalUnit { MM, PX }

    // Focal length editor state �?persisted across photo changes
    // 焦距编辑器状�?�?换图时重�?
    private val _focalValue = mutableStateOf("")          // current text in the field
    val focalValue: State<String> = _focalValue
    private val _focalUnit = mutableStateOf(FocalUnit.MM) // current unit
    val focalUnit: State<FocalUnit> = _focalUnit
    private val _focalIsManual = mutableStateOf(false)    // true when user edited the value
    val focalIsManual: State<Boolean> = _focalIsManual
    private var _imageWidth = 0   // for mm↔px conversion
    private var _imageHeight = 0  // for mm↔px conversion

    // Original EXIF focal length (saved for restore)
    private var _savedExifFocalMm: Float? = null

    // AnyCalib AI suggest running state
    private val _anyCalibRunning = mutableStateOf(false)
    val anyCalibRunning: State<Boolean> = _anyCalibRunning

    companion object {
        /** 全画幅对角线 (mm) = sqrt(36² + 24²) �?matches C side compute_fpx() */
        /** Full-frame diagonal (mm) = sqrt(36² + 24²) �?matches C side compute_fpx() */
        private const val FULL_FRAME_DIAGONAL_MM = 43.2666
        /** Default focal length when EXIF is unavailable */
        /** �?EXIF 数据时的默认焦距 */
        private const val DEFAULT_FOCAL_MM = 30.0
    }

    /** Convert mm focal length to pixel focal length using image dimensions
     *  and the actual sensor diagonal (not full-frame). */
    /** 用图片尺寸和实际传感器对角线�?mm 焦距转为像素焦距�?*/
    private fun mmToPx(mm: Double, w: Int, h: Int): Double {
        if (w <= 0 || h <= 0) return mm * 1000.0  // degenerate fallback
        val diagPx = kotlin.math.sqrt((w * w + h * h).toDouble())
        return mm * diagPx / computeSensorDiagMm()
    }

    /** Convert pixel focal length to mm focal length using image dimensions
     *  and the actual sensor diagonal (not full-frame). */
    /** 用图片尺寸和实际传感器对角线把像素焦距转�?mm 焦距�?*/
    private fun pxToMm(px: Double, w: Int, h: Int): Double {
        if (w <= 0 || h <= 0) return px / 1000.0
        val diagPx = kotlin.math.sqrt((w * w + h * h).toDouble())
        return px * computeSensorDiagMm() / diagPx
    }

    /** Toggle the focal unit between mm and px, converting the displayed value in-place. */
    /** �?mm �?px 之间切换焦距单位, 同时转换显示值�?*/
    fun toggleFocalUnit() {
        val current = _focalValue.value.toDoubleOrNull() ?: return
        val w = _imageWidth
        val h = _imageHeight
        if (_focalUnit.value == FocalUnit.MM) {
            val px = mmToPx(current, w, h)
            _focalValue.value = "%.2f".format(px)
            _focalUnit.value = FocalUnit.PX
        } else {
            val mm = pxToMm(current, w, h)
            _focalValue.value = "%.2f".format(mm)
            _focalUnit.value = FocalUnit.MM
        }
    }

    /** Called when the user types in the focal field. */
    /** 用户在输入框中修改焦距时调用�?*/
    fun setFocalValue(value: String) {
        _focalValue.value = value
        _focalIsManual.value = true
    }

    /** Returns the user-overridden f_px, or null if the value is unchanged / invalid. */
    /** 返回用户覆盖的像素焦�? 如果未修改或值无效则返回 null�?*/
    fun getOverrideFpx(): Float? {
        if (!_focalIsManual.value) return null
        val v = _focalValue.value.toDoubleOrNull() ?: return null
        if (v <= 0) return null
        val w = _imageWidth
        val h = _imageHeight
        return if (_focalUnit.value == FocalUnit.MM) {
            mmToPx(v, w, h).toFloat()
        } else {
            v.toFloat()
        }
    }

    /** AnyCalib AI 建议焦距：用 AnyCalib 模型预测焦距并填入编辑框�?*/
    /** AI Suggest: run AnyCalib model to predict focal length and fill editor. */
    fun onAiSuggestFocal() {
        if (_anyCalibRunning.value) return
        val uri = _selectedImageUri.value ?: return

        val anycalib = sharpApp.modelStore.getModel(ModelType.ANYCALIB)
        val anycalibBin = anycalib?.runtimeBinPath
        if (anycalibBin == null) {
            _exportMessage.value = MsgKey.k(MsgKey.ERR_ANYCALIB_NOT_FOUND)
            return
        }

        _anyCalibRunning.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 确保 QNN 运行时已初始化（AnyCalib 需要原生运行时�?
                // Ensure QNN runtime is initialized (AnyCalib needs the native runtime)
                sharpApp.pipelineManager.ensureQnnInitialized()

                val rawPath = prepareAnyCalibRaw(uri)
                if (rawPath == null) {
                    withContext(Dispatchers.Main) {
                        _anyCalibRunning.value = false
                        _exportMessage.value = MsgKey.ERR_PREP_NULL
                    }
                    return@launch
                }

                val result = QnnJni.runAnyCalib(rawPath, anycalibBin)
                File(rawPath).delete()

                if (result != null && result.size >= 2 && result[0] > 0 && result[1] > 0) {
                    val fx = result[0]
                    val fy = result[1]
                    val focalPx322 = (fx + fy) / 2f
                    val w = _imageWidth
                    val h = _imageHeight
                    val cropSize = minOf(w, h)
                    val focalPxImage = focalPx322 * cropSize / 322f
                    val sensorDiagMm = computeSensorDiagMm()
                    val diagPx = kotlin.math.sqrt((w * w + h * h).toDouble())
                    val focalMm = focalPxImage * sensorDiagMm / diagPx
                    withContext(Dispatchers.Main) {
                        _focalValue.value = "%.2f".format(focalMm)
                        _focalUnit.value = FocalUnit.MM
                        _focalIsManual.value = true
                        _anyCalibRunning.value = false
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        _anyCalibRunning.value = false
                        _exportMessage.value = MsgKey.k(MsgKey.ERR_ANYCALIB_INFER_FAILED, "null result")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _anyCalibRunning.value = false
                    _exportMessage.value = MsgKey.k(MsgKey.ERR_ANYCALIB_INFER_FAILED, e.message ?: "")
                }
            }
        }
    }

    /** Compute actual sensor diagonal (mm) from EXIF. Falls back to full-frame when EXIF
     *  FocalLength / FocalLengthIn35mmFilm are unavailable. */
    private fun computeSensorDiagMm(): Float {
        val d = _imageDetails.value ?: return FULL_FRAME_DIAGONAL_MM.toFloat()
        val a = d.focalLength ?: return FULL_FRAME_DIAGONAL_MM.toFloat()
        val e = d.focalLength35mm ?: return FULL_FRAME_DIAGONAL_MM.toFloat()
        if (e <= 0f) return FULL_FRAME_DIAGONAL_MM.toFloat()
        return a / e * FULL_FRAME_DIAGONAL_MM.toFloat()
    }

    /** 恢复焦距为图片默认值（EXIF �?30mm）�?*/
    /** Restore focal length to image default (EXIF or 30mm). */
    fun onRestoreDefaultFocal() {
        val mm = _savedExifFocalMm ?: DEFAULT_FOCAL_MM.toFloat()
        _focalValue.value = "%.2f".format(mm)
        _focalUnit.value = FocalUnit.MM
        _focalIsManual.value = false
    }

    /** Preprocess image to 322×322 NCHW raw for AnyCalib. Returns raw file path or null. */
    private fun prepareAnyCalibRaw(uri: Uri): String? {
        val resolver = sharpApp.contentResolver
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val src = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null

        // Center crop to square, then resize to 322×322 (matching training preprocessing)
        val w = src.width
        val h = src.height
        val s = minOf(w, h)
        val cropped = Bitmap.createBitmap(src, (w - s) / 2, (h - s) / 2, s, s)
        src.recycle()
        val scaled = Bitmap.createScaledBitmap(cropped, 322, 322, true)
        cropped.recycle()

        val pixels = IntArray(322 * 322)
        scaled.getPixels(pixels, 0, 322, 0, 0, 322, 322)
        scaled.recycle()

        val outFile = File(sharpApp.cacheDir, "anycalib_input.raw")
        val buf = java.nio.ByteBuffer.allocate(3 * 322 * 322 * 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (c in 0 until 3) {
            val shift = 16 - c * 8
            for (i in pixels.indices) {
                val v = ((pixels[i] shr shift) and 0xFF) / 255.0f
                buf.putFloat(v)
            }
        }
        outFile.outputStream().use { os -> os.write(buf.array()) }
        return outFile.absolutePath
    }

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
        // Reset focal length editor when switching images
        _focalValue.value = ""
        _focalUnit.value = FocalUnit.MM
        _focalIsManual.value = false
        _imageWidth = 0
        _imageHeight = 0
        _savedExifFocalMm = null
        _anyCalibRunning.value = false

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
                // Publish details and set focal state; stale results are dropped
                if (gen == imageLoadGeneration) {
                    _imageDetails.value = details

                    // Initialise focal editor from image info
                    details?.let { d ->
                        _imageWidth = d.width
                        _imageHeight = d.height
                        _savedExifFocalMm = d.focalLength?.takeIf { it > 0 }
                        if (d.focalLength != null && d.focalLength > 0) {
                            _focalValue.value = "%.2f".format(d.focalLength)
                            _focalUnit.value = FocalUnit.MM
                        } else {
                            _focalValue.value = "%.2f".format(DEFAULT_FOCAL_MM)
                            _focalUnit.value = FocalUnit.MM
                        }
                        _focalIsManual.value = false
                    }
                }
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
            var focal35mm: Float? = null
            if (format == "JPEG" || format == "JPG") {
                val exif = ExifInterface(java.io.ByteArrayInputStream(bytes))
                val focalVal = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
                if (focalVal > 0.0) focal = focalVal.toFloat()
                val focal35Val = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0.0)
                if (focal35Val > 0.0) focal35mm = focal35Val.toFloat()
            }

            ImageDetails(width, height, format, focal, focal35mm, size)
        } catch (e: Exception) {
            null
        }
    }

    /** Runs the full inference pipeline, forwarding any user-overridden focal length. */
    /** 启动推理管线, 如果用户覆盖了焦距则传入�?*/
    fun runPipeline(imageUri: Uri) {
        viewModelScope.launch {
            val overrideFpx = getOverrideFpx()
            sharpApp.pipelineManager.runPipeline(imageUri, overrideFpx)
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

    val canRun = ModelType.coreTypes.all { type ->
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
                                                // Focal length editor with unit toggle (mm �?px)
                                                FocalLengthField(
                                                    value = vm.focalValue.value,
                                                    unit = vm.focalUnit.value,
                                                    onValueChange = { vm.setFocalValue(it) },
                                                    onToggleUnit = { vm.toggleFocalUnit() }
                                                )
                                                // No-EXIF hint
                                                if (d.focalLength == null) {
                                                    Text(
                                                        text = stringResource(R.string.home_image_focal_no_exif),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.outline
                                                    )
                                                }
                                                // AI Suggest + Restore Default buttons
                                                val anycalibRunning = vm.anyCalibRunning.value
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                                                ) {
                                                    Button(
                                                        onClick = { vm.onAiSuggestFocal() },
                                                        enabled = !anycalibRunning,
                                                        modifier = Modifier.weight(1f).height(36.dp),
                                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.sm)
                                                    ) {
                                                        if (anycalibRunning) {
                                                            CircularProgressIndicator(
                                                                modifier = Modifier.size(16.dp),
                                                                strokeWidth = 2.dp,
                                                                color = MaterialTheme.colorScheme.onPrimary
                                                            )
                                                        } else {
                                                            Text(
                                                                text = stringResource(R.string.home_image_focal_ai_suggest),
                                                                style = MaterialTheme.typography.labelMedium
                                                            )
                                                        }
                                                    }
                                                    Button(
                                                        onClick = { vm.onRestoreDefaultFocal() },
                                                        enabled = !anycalibRunning,
                                                        modifier = Modifier.weight(1f).height(36.dp),
                                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.sm)
                                                    ) {
                                                        Text(
                                                            text = stringResource(R.string.home_image_focal_restore_default),
                                                            style = MaterialTheme.typography.labelMedium
                                                        )
                                                    }
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
            val missing = ModelType.coreTypes.filter { !models.containsKey(it) }
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
 * 图片预览: �?Uri 解码 (自适应采样) 并以 fit 模式显示在矩形框内�?
 * Image preview: decodes from the Uri (with adaptive sampling) and fits it inside the box.
 * 比例不同时留黑边 (letterbox), 不裁剪填满�?
 * Aspect mismatches are letterboxed instead of cropped.
 *
 * 采样策略: 先用 inJustDecodeBounds 获取原始尺寸, 再根据目标视图高�?(targetHeightDp)
 * 计算最�?inSampleSize (2 的幂), 避免加载过大图片浪费内存�?
 * Sampling strategy: first reads the original dimensions via inJustDecodeBounds,
 * then computes the optimal inSampleSize (power of 2) based on the target view
 * height (targetHeightDp), avoiding excessive memory usage from oversized images.
 *
 * @param targetHeightDp 目标视图高度 (dp), 用于计算采样�? 默认 260dp
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
 * 焦距编辑栏：带单位的 OutlinedTextField + mm/px 切换 FilterChip�?
 * Focal length editor: OutlinedTextField with mm/px unit toggle chips.
 *
 * 输入只允许数字与小数�? 点击未选中的单�?chips 触发转换�?
 * Input only allows digits and decimal point; tapping the unselected chip triggers conversion.
 *
 * @param value 当前焦距数�?(字符�?
 * @param unit  当前单位
 * @param onValueChange 用户输入回调
 * @param onToggleUnit  切换单位回调
 */
@Composable
private fun FocalLengthField(
    value: String,
    unit: HomeViewModel.FocalUnit,
    onValueChange: (String) -> Unit,
    onToggleUnit: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { newVal ->
                if (newVal.isEmpty() || newVal.matches(Regex("^\\d*\\.?\\d{0,2}$"))) {
                    onValueChange(newVal)
                }
            },
            modifier = Modifier.weight(1f),
            singleLine = true,
            label = { Text(stringResource(R.string.home_image_focal_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = MaterialTheme.typography.bodySmall,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedLabelColor = MaterialTheme.colorScheme.primary,
                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        )
        FilterChip(
            selected = unit == HomeViewModel.FocalUnit.MM,
            onClick = { if (unit != HomeViewModel.FocalUnit.MM) onToggleUnit() },
            label = { Text(stringResource(R.string.home_image_focal_unit_mm)) },
            colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
            )
        )
        FilterChip(
            selected = unit == HomeViewModel.FocalUnit.PX,
            onClick = { if (unit != HomeViewModel.FocalUnit.PX) onToggleUnit() },
            label = { Text(stringResource(R.string.home_image_focal_unit_px)) },
            colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
            )
        )
    }
}

/**
 * 计算最�?inSampleSize (2 的幂), 保证解码后高�?>= targetHeight�?
 * Computes the optimal inSampleSize (power of 2), ensuring decoded height >= targetHeight.
 *
 * 800=5 �?�?4 �?解码�?1000px, 足够清晰且省内存�?
 * E.g. original 4000px, target 800px: 4000/800=5 �?use 4 �?decoded 1000px, sharp enough.
 */
private fun calculateSampleSize(originalHeight: Int, targetHeight: Int): Int {
    if (targetHeight <= 0 || originalHeight <= targetHeight) return 1
    var sampleSize = 1
    while (originalHeight / (sampleSize * 2) >= targetHeight) {
        sampleSize *= 2
    }
    return sampleSize
}