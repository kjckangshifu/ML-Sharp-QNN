package com.sharp.qnn.ui.home

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sharp.qnn.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ImageItem(
    val uri: Uri,
    val id: Long
)

@Composable
fun ImagePickerDialog(
    title: String,
    closeContentDescription: String,
    onDismiss: () -> Unit,
    onImageSelected: (Uri) -> Unit,
    imageDirs: Set<String> = emptySet()
) {
    val context = LocalContext.current
    var images by remember { mutableStateOf<List<ImageItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(imageDirs) {
        isLoading = true
        images = withContext(Dispatchers.IO) {
            val result = mutableListOf<ImageItem>()
            val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val selection = buildString {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    append("${MediaStore.Images.Media.IS_PENDING} = 0")
                }
                if (imageDirs.isNotEmpty()) {
                    if (isNotEmpty()) append(" AND ")
                    append("(")
                    append(imageDirs.joinToString(" OR ") { "${MediaStore.Images.Media.DATA} LIKE '$it/%'" })
                    append(")")
                }
            }.ifBlank { null }
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            runCatching {
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    null,
                    sortOrder
                )
            }.getOrNull()?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                    )
                    result.add(ImageItem(uri, id))
                }
            }
            result
        }
        isLoading = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = closeContentDescription)
                    }
                }

                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = Spacing.xs, vertical = Spacing.xs)
                    ) {
                        items(images) { item ->
                            ThumbnailCell(
                                uri = item.uri,
                                onClick = { onImageSelected(item.uri) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThumbnailCell(uri: Uri, onClick: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(uri, Size(256, 256), null)
            }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(4.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    }
}
