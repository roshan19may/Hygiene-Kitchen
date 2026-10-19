package com.kitchenlens.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage

/** Row of small square thumbnails; tapping one opens the full-screen viewer. */
@Composable
fun PhotoThumbs(urls: List<String>, title: String, max: Int = 4, thumbs: List<String> = emptyList()) {
    if (urls.isEmpty()) return
    var viewerAt by remember { mutableStateOf<Int?>(null) }
    val shown = urls.take(max)
    val extra = urls.size - shown.size
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            shown.forEachIndexed { i, u ->
                Box(
                    Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { viewerAt = i },
                ) {
                    AsyncImage(
                        // Small preview copy when there is one (much faster than the full photo)
                        model = thumbs.getOrNull(i)?.takeIf { it.isNotBlank() } ?: u,
                        contentDescription = "Enlarge $title photo ${i + 1}",
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    )
                    if (i == shown.lastIndex && extra > 0) {
                        Box(Modifier.fillMaxSize().background(Color(0x9E10231A)), contentAlignment = Alignment.Center) {
                            Text("+$extra", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        Text("Tap a photo to enlarge", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }
    viewerAt?.let { start -> PhotoViewer(urls, title, start, thumbs) { viewerAt = null } }
}

/**
 * Full-screen photo viewer: pinch or double-tap to zoom, drag to pan when zoomed,
 * arrows (or swipe when not zoomed) to move between photos.
 */
@Composable
fun PhotoViewer(urls: List<String>, title: String, start: Int, thumbs: List<String> = emptyList(), onClose: () -> Unit) {
    var index by remember { mutableIntStateOf(start.coerceIn(0, urls.lastIndex)) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Load the next and previous photos in the background so swiping is instant
    androidx.compose.runtime.LaunchedEffect(index) {
        listOf(index + 1, index - 1).map { (it + urls.size) % urls.size }.distinct().filter { it != index }.forEach { i ->
            coil.Coil.imageLoader(context).enqueue(coil.request.ImageRequest.Builder(context).data(urls[i]).build())
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var swipe by remember { mutableFloatStateOf(0f) }

    fun go(delta: Int) {
        index = (index + delta + urls.size) % urls.size
        scale = 1f; offset = Offset.Zero
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF0B1410)).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 1)
                if (urls.size > 1) Text("${index + 1} / ${urls.size}", color = Color.White.copy(alpha = 0.8f))
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close photo viewer", tint = Color.White) }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(index) {
                        detectTapGestures(onDoubleTap = {
                            if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                        })
                    }
                    .pointerInput(index) {
                        detectTransformGestures(
                            onGesture = { _, pan, zoom, _ ->
                                val newScale = (scale * zoom).coerceIn(1f, 5f)
                                if (newScale > 1f) {
                                    offset += pan
                                } else {
                                    offset = Offset.Zero
                                    swipe += pan.x
                                    if (swipe > 160f && urls.size > 1) { swipe = 0f; go(-1) }
                                    else if (swipe < -160f && urls.size > 1) { swipe = 0f; go(1) }
                                }
                                scale = newScale
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                val layer = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                }
                // The small preview (already loaded for the thumbnail) shows at once; the full photo fades in over it
                thumbs.getOrNull(index)?.takeIf { it.isNotBlank() }?.let { t ->
                    AsyncImage(model = t, contentDescription = null, contentScale = ContentScale.Fit, modifier = layer)
                }
                AsyncImage(
                    model = coil.request.ImageRequest.Builder(context).data(urls[index]).crossfade(true).build(),
                    contentDescription = "$title photo ${index + 1}",
                    contentScale = ContentScale.Fit,
                    modifier = layer,
                )
                if (urls.size > 1) {
                    val arrowColors = IconButtonDefaults.iconButtonColors(containerColor = Color(0x33FFFFFF), contentColor = Color.White)
                    IconButton(onClick = { go(-1) }, colors = arrowColors, modifier = Modifier.align(Alignment.CenterStart).padding(8.dp)) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous photo")
                    }
                    IconButton(onClick = { go(1) }, colors = arrowColors, modifier = Modifier.align(Alignment.CenterEnd).padding(8.dp)) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next photo")
                    }
                }
            }
            Text(
                "Pinch or double-tap to zoom · swipe for the next photo",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(12.dp),
            )
            Spacer(Modifier.size(4.dp))
        }
    }
}
