package com.mediaapp.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediaapp.data.media.formatBytes
import com.mediaapp.data.media.formatDateTime
import com.mediaapp.data.media.formatDuration
import com.mediaapp.data.model.GalleryEntry
import com.mediaapp.player.PlayerBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerRoute(
    snackbar: SnackbarHostState,
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedContentScope,
    onBack: () -> Unit,
    onEdit: (GalleryEntry) -> Unit,
) {
    val vm: ViewerViewModel = hiltViewModel()
    val items by vm.items.collectAsStateWithLifecycle()
    val request by vm.requests.request.collectAsStateWithLifecycle()
    val consent by vm.consent.collectAsStateWithLifecycle()
    val context = LocalContext.current
    CollectMessages(vm.messages, snackbar)
    ConsentEffect(consent, vm::onConsent)

    val vault = request?.origin == ViewerOrigin.Vault
    val trash = request?.origin == ViewerOrigin.Trash
    DisposableEffect(vault) {
        if (vault) WindowSecurity.acquire()
        onDispose { if (vault) WindowSecurity.release() }
    }

    var sawItems by remember { mutableStateOf(false) }
    LaunchedEffect(items) {
        if (items.isNotEmpty()) sawItems = true
        else if (sawItems) onBack()
    }
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("Opening…", color = Color.White)
        }
        return
    }

    val start = items.indexOfFirst { it.id == request?.startId }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = start, pageCount = { items.size })
    var zoomed by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    val page = items[pager.currentPage.coerceIn(0, items.lastIndex)]

    DisposableEffect(page.id, page.isVideo, page.vault) {
        PlayerBridge.allowPip = page.isVideo && !page.vault && !page.trashed
        onDispose { PlayerBridge.allowPip = false }
    }
    BackHandler(onBack = onBack)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            userScrollEnabled = !zoomed,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
        ) { index ->
            val entry = items[index]
            val zoom = remember(entry.id) { ZoomState() }
            if (index == pager.currentPage) {
                SideEffect { zoomed = zoom.scale > 1.05f }
            }
            if (entry.isVideo && index == pager.currentPage) {
                var resume by remember(entry.id) { mutableLongStateOf(-1L) }
                LaunchedEffect(entry.id) {
                    vm.position(entry.id) { resume = it }
                }
                if (resume >= 0L) {
                    VideoStage(
                        entry = entry,
                        resumePosition = resume,
                        allowPip = !entry.vault && !entry.trashed,
                        onSave = { position, duration -> vm.savePosition(entry.id, position, duration) },
                        modifier = Modifier.fillMaxSize().sharedMedia(sharedScope, animatedScope, entry.id),
                    )
                }
            } else {
                val bitmap by produceState<Bitmap?>(initialValue = null, entry.id, entry.vault) {
                    value = if (entry.vault) withContext(Dispatchers.IO) { vm.decode(entry) } else null
                }
                FullImage(
                    entry = entry,
                    bytes = bitmap,
                    modifier = Modifier
                        .fillMaxSize()
                        .sharedMedia(sharedScope, animatedScope, entry.id)
                        .pinchZoom(zoom)
                        .graphicsLayer {
                            scaleX = zoom.scale
                            scaleY = zoom.scale
                            translationX = zoom.offset.x
                            translationY = zoom.offset.y
                        },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Color.White) }
            Text(
                page.displayName,
                modifier = Modifier.weight(1f),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(onClick = { info = true }) { Icon(Icons.Outlined.Info, contentDescription = "Info", tint = Color.White) }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            if (!page.isVideo) {
                IconButton(onClick = { vm.share(context, page) }) { Icon(Icons.Outlined.Share, contentDescription = "Share", tint = Color.White) }
                IconButton(onClick = { vm.wallpaper(context, page) }) { Icon(Icons.Outlined.Wallpaper, contentDescription = "Set as wallpaper", tint = Color.White) }
            } else if (!page.vault) {
                IconButton(onClick = { vm.share(context, page) }) { Icon(Icons.Outlined.Share, contentDescription = "Share", tint = Color.White) }
            } else {
                IconButton(onClick = { vm.share(context, page) }) { Icon(Icons.Outlined.Share, contentDescription = "Share", tint = Color.White) }
            }
            if (!trash) {
                IconButton(onClick = { onEdit(page) }) { Icon(Icons.Outlined.Edit, contentDescription = "Edit", tint = Color.White) }
            }
            if (!trash && !vault) {
                IconButton(onClick = { vm.trash(page) }) { Icon(Icons.Outlined.Delete, contentDescription = "Move to bin", tint = Color.White) }
            }
            if (vault) {
                IconButton(onClick = { vm.restoreVault(page) }) { Icon(Icons.Outlined.LockOpen, contentDescription = "Restore", tint = Color.White) }
            }
        }
    }

    if (info) {
        ModalBottomSheet(onDismissRequest = { info = false }) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).padding(bottom = 28.dp)) {
                Text(page.displayName, style = MaterialTheme.typography.titleLarge)
                InfoLine("Source", page.sourceLabel)
                InfoLine("Path", page.relativePath ?: "—")
                InfoLine("Taken", formatDateTime(page.dateMillis))
                InfoLine("Size", formatBytes(page.sizeBytes))
                InfoLine("Resolution", if (page.width > 0) "${page.width} × ${page.height}" else "—")
                if (page.isVideo) InfoLine("Duration", formatDuration(page.durationMs))
                InfoLine("Type", page.mimeType)
                if (page.vault) {
                    Text(
                        "This file is encrypted in app-private storage. Restoring writes it back to its original folder.",
                        modifier = Modifier.padding(top = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Modifier.sharedMedia(
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedContentScope,
    id: String,
): Modifier = with(sharedScope) {
    sharedElement(
        rememberSharedContentState(key = "media-$id"),
        animatedVisibilityScope = animatedScope,
    )
}
