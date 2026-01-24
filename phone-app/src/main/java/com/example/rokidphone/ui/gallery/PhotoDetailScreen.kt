package com.example.rokidphone.ui.gallery

import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.rokidphone.R
import com.example.rokidphone.service.photo.PhotoData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Photo Detail Screen - full screen photo viewer with zoom and swipe
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoDetailScreen(
    photos: List<PhotoData>,
    initialPhoto: PhotoData,
    onBack: () -> Unit,
    onDelete: (PhotoData) -> Unit,
    onShare: (PhotoData) -> Unit,
    loadBitmap: suspend (PhotoData, Int?) -> Bitmap?,
    modifier: Modifier = Modifier
) {
    val initialIndex = photos.indexOfFirst { it.id == initialPhoto.id }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialIndex) { photos.size }
    val currentPhoto = photos.getOrNull(pagerState.currentPage) ?: initialPhoto
    var showInfo by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Photo pager
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val photo = photos.getOrNull(page)
            if (photo != null) {
                ZoomablePhoto(
                    photoData = photo,
                    loadBitmap = loadBitmap,
                    onTap = { showControls = !showControls }
                )
            }
        }
        
        // Top bar with controls
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "${pagerState.currentPage + 1} / ${photos.size}",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                        Text(
                            text = currentPhoto.formattedTimestamp,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showInfo = true }) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = stringResource(R.string.gallery_info),
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.6f)
                )
            )
        }
        
        // Bottom action bar
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(onClick = { onShare(currentPhoto) }) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = stringResource(R.string.share),
                            tint = Color.White
                        )
                    }
                    Text(
                        text = stringResource(R.string.share),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
                
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.delete),
                            tint = Color.White
                        )
                    }
                    Text(
                        text = stringResource(R.string.delete),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }
        }
        
        // Page indicator dots
        if (photos.size > 1 && showControls) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 100.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(minOf(photos.size, 10)) { index ->
                    val actualIndex = if (photos.size > 10) {
                        (pagerState.currentPage / 10) * 10 + index
                    } else index
                    
                    if (actualIndex < photos.size) {
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .size(if (actualIndex == pagerState.currentPage) 8.dp else 6.dp)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (actualIndex == pagerState.currentPage) 
                                        Color.White 
                                    else Color.White.copy(alpha = 0.4f)
                                )
                        )
                    }
                }
            }
        }
    }
    
    // Photo info dialog
    if (showInfo) {
        PhotoInfoDialog(
            photoData = currentPhoto,
            onDismiss = { showInfo = false }
        )
    }
    
    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text(stringResource(R.string.gallery_delete_title)) },
            text = { Text(stringResource(R.string.gallery_delete_single_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        onDelete(currentPhoto)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun ZoomablePhoto(
    photoData: PhotoData,
    loadBitmap: suspend (PhotoData, Int?) -> Bitmap?,
    onTap: () -> Unit
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    
    // Zoom and pan state
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    
    val scope = rememberCoroutineScope()
    
    LaunchedEffect(photoData.id) {
        isLoading = true
        bitmap = withContext(Dispatchers.IO) {
            loadBitmap(photoData, null)  // Full size
        }
        isLoading = false
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        // Toggle zoom on double tap
                        scale = if (scale > 1.5f) 1f else 2.5f
                        offset = Offset.Zero
                    }
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    if (scale > 1f) {
                        offset = Offset(
                            x = (offset.x + pan.x).coerceIn(-500f * (scale - 1), 500f * (scale - 1)),
                            y = (offset.y + pan.y).coerceIn(-500f * (scale - 1), 500f * (scale - 1))
                        )
                    } else {
                        offset = Offset.Zero
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading -> {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(48.dp)
                )
            }
            bitmap != null -> {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                    contentScale = ContentScale.Fit
                )
            }
            else -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.BrokenImage,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = Color.White.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.gallery_load_error),
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoInfoDialog(
    photoData: PhotoData,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Default.Info, contentDescription = null)
        },
        title = { Text(stringResource(R.string.gallery_photo_info)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                InfoRow(
                    label = stringResource(R.string.gallery_info_date),
                    value = photoData.formattedTimestamp
                )
                InfoRow(
                    label = stringResource(R.string.gallery_info_dimensions),
                    value = photoData.formattedDimensions
                )
                InfoRow(
                    label = stringResource(R.string.gallery_info_size),
                    value = photoData.formattedSize
                )
                if (photoData.transferTimeMs > 0) {
                    InfoRow(
                        label = stringResource(R.string.gallery_info_transfer_time),
                        value = "${photoData.transferTimeMs}ms"
                    )
                }
                if (photoData.analysisResult != null) {
                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.gallery_info_analysis),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = photoData.analysisResult!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
private fun InfoRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
