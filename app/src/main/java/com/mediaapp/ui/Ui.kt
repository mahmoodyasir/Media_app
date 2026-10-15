package com.mediaapp.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import com.mediaapp.data.media.formatDuration
import com.mediaapp.data.model.GalleryEntry
import com.mediaapp.data.model.SortOrder
import com.mediaapp.data.model.dayGroups
import com.mediaapp.data.model.groupsByDay
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

object RouteCodec {
    fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    fun decode(value: String): String =
        String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
}

fun viewIntentUri(intent: Intent?): Uri? {
    if (intent?.action != Intent.ACTION_VIEW) return null
    return intent.data
}

@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }
}

fun Modifier.pinchZoom(state: ZoomState): Modifier = pointerInput(state) {
    detectTapGestures(onDoubleTap = {
        if (state.scale > 1.05f) state.reset() else state.scale = 2.6f
    })
}.pointerInput(state) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                state.scale = (state.scale * zoom).coerceIn(1f, 5f)
                state.offset = if (state.scale <= 1.01f) Offset.Zero else state.offset + pan
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            } else if (pressed.size == 1 && state.scale > 1.05f) {
                state.offset += event.calculatePan()
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
            if (event.changes.none { it.pressed }) break
        }
        if (state.scale <= 1.01f) state.reset()
    }
}

fun Modifier.pinchColumns(columns: Int, onChange: (Int) -> Unit): Modifier = pointerInput(columns) {
    awaitEachGesture {
        var zoom = 1f
        var changed = false
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.count { it.pressed }
            if (pressed >= 2) {
                zoom *= event.calculateZoom()
                if (!changed && zoom > 1.18f) {
                    onChange((columns - 1).coerceAtLeast(2))
                    changed = true
                } else if (!changed && zoom < 0.84f) {
                    onChange((columns + 1).coerceAtMost(6))
                    changed = true
                }
                event.changes.forEach { it.consume() }
            }
            if (event.changes.none { it.pressed }) break
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGrid(
    items: List<GalleryEntry>,
    columns: Int,
    sort: SortOrder,
    selected: Set<String>,
    selecting: Boolean,
    onOpen: (GalleryEntry) -> Unit,
    onToggle: (GalleryEntry) -> Unit,
    onStartSelect: (GalleryEntry) -> Unit,
    onColumns: (Int) -> Unit,
    thumb: @Composable (GalleryEntry) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    sharedElement: @Composable Modifier.(GalleryEntry) -> Modifier = { this },
) {
    LazyColumn(
        state = listState,
        modifier = modifier.pinchColumns(columns, onColumns),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (groupsByDay(sort)) {
            items.dayGroups(sort).forEach { group ->
                stickyHeader(key = "day-${group.date}") {
                    Text(
                        group.label,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.94f))
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val rows = group.items.chunked(columns)
                items(rows, key = { row -> "r-${row.first().id}" }) { row ->
                    ThumbRow(row, columns, selected, selecting, onOpen, onToggle, onStartSelect, thumb, sharedElement)
                }
            }
        } else {
            val rows = items.chunked(columns)
            items(rows, key = { row -> "f-${row.first().id}" }) { row ->
                ThumbRow(row, columns, selected, selecting, onOpen, onToggle, onStartSelect, thumb, sharedElement)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThumbRow(
    row: List<GalleryEntry>,
    columns: Int,
    selected: Set<String>,
    selecting: Boolean,
    onOpen: (GalleryEntry) -> Unit,
    onToggle: (GalleryEntry) -> Unit,
    onStartSelect: (GalleryEntry) -> Unit,
    thumb: @Composable (GalleryEntry) -> Unit,
    sharedElement: @Composable Modifier.(GalleryEntry) -> Modifier,
) {
    Row(
        modifier = Modifier.padding(horizontal = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        row.forEach { entry ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .sharedElement(entry)
                    .combinedClickable(
                        onClick = { if (selecting) onToggle(entry) else onOpen(entry) },
                        onLongClick = { onStartSelect(entry) },
                    ),
            ) {
                thumb(entry)
                if (entry.isVideo) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(formatDuration(entry.durationMs), color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (selected.contains(entry.id)) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(22.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("✓", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
fun LibraryThumb(entry: GalleryEntry, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(Uri.parse(entry.uri))
            .size(Size(420, 420))
            .crossfade(true)
            .build(),
        contentDescription = entry.displayName,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxSize()
            .background(placeholderColor(entry.id)),
    )
}

@Composable
fun VaultThumb(entry: GalleryEntry, loader: suspend (GalleryEntry) -> Bitmap?, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = null, entry.id) {
        value = withContext(Dispatchers.IO) { runCatching { loader(entry) }.getOrNull() }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = entry.displayName,
            contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxSize(),
        )
    } else {
        Box(modifier.fillMaxSize().background(placeholderColor(entry.id)), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun FullImage(entry: GalleryEntry, bytes: Bitmap?, modifier: Modifier = Modifier) {
    if (entry.vault) {
        if (bytes == null) {
            Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            Image(bytes.asImageBitmap(), entry.displayName, modifier = modifier, contentScale = ContentScale.Fit)
        }
    } else {
        val context = androidx.compose.ui.platform.LocalContext.current
        AsyncImage(
            model = ImageRequest.Builder(context).data(Uri.parse(entry.uri)).crossfade(true).build(),
            contentDescription = entry.displayName,
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
    }
}

fun decodeContentBitmap(context: Context, uri: Uri, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
}

private fun placeholderColor(id: String): Color {
    val hue = (id.hashCode() and 0xFF) / 255f * 360f
    return Color.hsl(hue, 0.22f, 0.18f)
}

fun scrim(): Brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)))

@Composable
fun CountLine(images: Int, videos: Int, overflow: TextOverflow = TextOverflow.Ellipsis) {
    val parts = buildList {
        if (images > 0) add(if (images == 1) "1 photo" else "$images photos")
        if (videos > 0) add(if (videos == 1) "1 video" else "$videos videos")
        if (isEmpty()) add("Empty")
    }
    Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = overflow)
}
