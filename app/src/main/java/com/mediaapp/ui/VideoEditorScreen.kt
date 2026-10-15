package com.mediaapp.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.mediaapp.data.media.formatDuration
import com.mediaapp.editor.FilterPreset
import com.mediaapp.editor.image.ShapeKind
import com.mediaapp.editor.timelineSliderRange
import com.mediaapp.editor.timelineSliderValue
import com.mediaapp.editor.video.FrameAspect
import com.mediaapp.editor.video.OverlayKind
import com.mediaapp.editor.video.previewLookEffects
import com.mediaapp.editor.video.timelineDuration
import com.mediaapp.editor.video.ClipRange
import kotlinx.coroutines.delay

private val videoInk = listOf(
    0xFFFFFFFF.toInt(),
    0xFF16171A.toInt(),
    0xFFD6F25C.toInt(),
    0xFFFF6B6B.toInt(),
    0xFF7EB6FF.toInt(),
    0xFFFFC56E.toInt(),
)

@OptIn(ExperimentalLayoutApi::class, UnstableApi::class)
@Composable
fun VideoEditorRoute(snackbar: SnackbarHostState, onBack: () -> Unit) {
    val vm: VideoEditorViewModel = hiltViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val consent by vm.consent.collectAsStateWithLifecycle()
    val showSave = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val seekTo = remember { androidx.compose.runtime.mutableStateOf<(Long) -> Unit>({}) }
    CollectMessages(vm.messages, snackbar)
    ConsentEffect(consent, vm::onConsent)
    DisposableEffect(ui.vault) {
        if (ui.vault) WindowSecurity.acquire()
        onDispose { if (ui.vault) WindowSecurity.release() }
    }
    val addClip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.addClip(uri, uri.lastPathSegment ?: "Clip")
    }
    val addAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.attachAudio(uri, uri.lastPathSegment ?: "Audio")
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
            Text("Edit video", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = vm::undo, enabled = ui.canUndo) { Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "Undo") }
            IconButton(onClick = vm::redo, enabled = ui.canRedo) { Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = "Redo") }
            TextButton(onClick = { showSave.value = true }, enabled = ui.ready && !ui.saving) { Text("Save") }
        }
        val clip = ui.document.clips.getOrNull(ui.document.selected)
        val ratio = (ui.document.aspect.ratio ?: ui.sourceAspect).coerceIn(0.4f, 2.4f)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                ui.error != null && !ui.ready -> Text(ui.error ?: "", textAlign = TextAlign.Center)
                !ui.ready || clip == null -> CircularProgressIndicator()
                else -> BoxWithConstraints(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(ratio)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Black),
                ) {
                    val canvasW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                    val canvasH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                    ClipPreview(
                        uri = clip.uri,
                        startMs = clip.startMs,
                        endMs = clip.endMs,
                        effects = previewLookEffects(ui.document.filter, ui.document.aspect, ui.sourceAspect),
                        onPosition = vm::playhead,
                        onReady = { seekTo.value = it },
                    )
                    ui.document.marks.forEach { mark ->
                        val color = Color(mark.color)
                        Box(
                            Modifier
                                .offset(x = maxWidth * mark.nx, y = maxHeight * mark.ny)
                                .size(width = maxWidth * mark.nw, height = maxHeight * mark.nh)
                                .border(
                                    2.dp,
                                    color,
                                    if (mark.kind == OverlayKind.Circle) CircleShape else RoundedCornerShape(4.dp),
                                )
                                .pointerInput(mark.id) {
                                    detectDragGestures(
                                        onDragStart = {
                                            vm.beginGesture()
                                            vm.selectMark(mark.id)
                                        },
                                        onDrag = { _, drag -> vm.previewMove(mark.id, drag.x / canvasW, drag.y / canvasH) },
                                        onDragEnd = { vm.endGesture() },
                                        onDragCancel = { vm.endGesture() },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (mark.kind == OverlayKind.Text) {
                                Text(mark.text, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            } else if (mark.kind == OverlayKind.Line || mark.shape == ShapeKind.Line) {
                                Box(Modifier.fillMaxWidth().height(2.dp).background(color))
                            }
                        }
                    }
                }
            }
        }
        if (clip != null) {
            Text(
                "${formatDuration(ui.playheadMs)} / ${formatDuration((clip.endMs - clip.startMs).coerceAtLeast(0L))}",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelLarge,
            )
            val span = (clip.endMs - clip.startMs).coerceAtLeast(1L)
            Slider(
                value = timelineSliderValue(ui.playheadMs, span),
                onValueChange = { seekTo.value(vm.scrub(it.toLong(), span)) },
                onValueChangeFinished = vm::endScrub,
                valueRange = timelineSliderRange(span),
            )
        }
        FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VideoTool.entries.forEach { tool ->
                FilterChip(selected = ui.tool == tool, onClick = { vm.tool(tool) }, label = { Text(tool.label) })
            }
        }
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            when (ui.tool) {
                VideoTool.Trim -> TrimPanel(ui, vm)
                VideoTool.Crop -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FrameAspect.entries.forEach { aspect ->
                        FilterChip(
                            selected = ui.document.aspect == aspect,
                            onClick = { vm.aspect(aspect) },
                            label = { Text(aspect.label) },
                        )
                    }
                }
                VideoTool.Merge -> MergePanel(ui, vm) { addClip.launch(arrayOf("video/*")) }
                VideoTool.Filters -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = ui.document.filter == preset,
                            onClick = { vm.filter(preset) },
                            label = { Text(preset.label) },
                        )
                    }
                }
                VideoTool.Audio -> AudioPanel(ui, vm) { addAudio.launch(arrayOf("audio/*")) }
                VideoTool.Overlays -> OverlayPanel(ui, vm)
            }
        }
    }
    if (showSave.value) {
        AlertDialog(
            onDismissRequest = { showSave.value = false },
            title = { Text("Save video") },
            text = {
                Text(
                    if (ui.vault) {
                        "A copy goes to Movies/Media App. You can also replace this locked file, or save another encrypted copy into Locked."
                    } else {
                        "A new MP4 is written to Movies/Media App. Replacing the original needs Android's permission. Save to vault encrypts a copy."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSave.value = false
                    vm.save(SaveTarget.Copy)
                }) { Text("Save copy") }
            },
            dismissButton = {
                Column {
                    TextButton(onClick = {
                        showSave.value = false
                        vm.save(SaveTarget.Overwrite)
                    }) { Text(if (ui.vault) "Replace locked original" else "Overwrite original") }
                    TextButton(onClick = {
                        showSave.value = false
                        vm.save(SaveTarget.Vault)
                    }) { Text("Save to vault") }
                }
            },
        )
    }
    if (ui.saving) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Exporting") },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { ui.progress.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${ui.progress}%", modifier = Modifier.padding(top = 12.dp))
                    Text(
                        "Clips are joined in order. You can cancel before the file is written.",
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = vm::cancelExport) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TrimPanel(ui: VideoEditUi, vm: VideoEditorViewModel) {
    val clip = ui.document.clips.getOrNull(ui.document.selected) ?: return
    val duration = clip.durationMs.coerceAtLeast(1L)
    Text("Preview shows this clip. Export joins every clip in order.", style = MaterialTheme.typography.bodyMedium)
    Text("Start ${formatDuration(clip.startMs)}", style = MaterialTheme.typography.labelLarge)
    Slider(
        value = timelineSliderValue(clip.startMs, duration),
        onValueChange = {
            vm.beginGesture()
            vm.previewTrim(it.toLong(), clip.endMs)
        },
        onValueChangeFinished = vm::endGesture,
        valueRange = timelineSliderRange(duration),
    )
    Text("End ${formatDuration(clip.endMs)}", style = MaterialTheme.typography.labelLarge)
    Slider(
        value = timelineSliderValue(clip.endMs, duration),
        onValueChange = {
            vm.beginGesture()
            vm.previewTrim(clip.startMs, it.toLong())
        },
        onValueChangeFinished = vm::endGesture,
        valueRange = timelineSliderRange(duration),
    )
}

@Composable
private fun MergePanel(ui: VideoEditUi, vm: VideoEditorViewModel, onAdd: () -> Unit) {
    val total = timelineDuration(ui.document.clips.map { ClipRange(it.startMs, it.endMs) })
    ScrollingOptions(maxHeight = 320.dp) {
        Text("Timeline ${formatDuration(total)}. Drag order with the arrows.", style = MaterialTheme.typography.bodyMedium)
        ui.document.clips.forEachIndexed { index, clip ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = ui.document.selected == index,
                    onClick = { vm.selectClip(index) },
                    label = { Text("${index + 1}. ${clip.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
                TextButton(onClick = { vm.moveClip(index, -1) }, enabled = index > 0) { Text("Up") }
                TextButton(onClick = { vm.moveClip(index, 1) }, enabled = index < ui.document.clips.lastIndex) { Text("Down") }
                TextButton(onClick = { vm.removeClip(index) }, enabled = ui.document.clips.size > 1) { Text("Remove") }
            }
        }
        TextButton(onClick = onAdd) { Text("Add clip") }
    }
}

@Composable
private fun AudioPanel(ui: VideoEditUi, vm: VideoEditorViewModel, onPick: () -> Unit) {
    ScrollingOptions {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Remove original audio", modifier = Modifier.weight(1f))
            Switch(checked = ui.document.removeAudio || ui.document.audioUri != null, onCheckedChange = vm::removeAudio, enabled = ui.document.audioUri == null)
        }
        TextButton(onClick = onPick) { Text(if (ui.document.audioUri == null) "Replace with a file" else "Change soundtrack") }
        if (ui.document.audioUri != null) {
            Text(ui.document.audioName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("The picked file replaces the soundtrack for the whole export. Silence fills the gap before it starts.", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (ui.document.audioUri != null) {
        val timeline = timelineDuration(ui.document.clips.map { ClipRange(it.startMs, it.endMs) }).coerceAtLeast(1L)
        Text("Plays from ${formatDuration(ui.document.audioStartMs)} to ${formatDuration(ui.document.audioEndMs)}", style = MaterialTheme.typography.bodyMedium)
        Text("Range start", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = timelineSliderValue(ui.document.audioStartMs, timeline),
            onValueChange = {
                vm.beginGesture()
                vm.previewAudio(it.toLong(), ui.document.audioEndMs)
            },
            onValueChangeFinished = vm::endGesture,
            valueRange = timelineSliderRange(timeline),
        )
        Text("Range end", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = timelineSliderValue(ui.document.audioEndMs, timeline),
            onValueChange = {
                vm.beginGesture()
                vm.previewAudio(ui.document.audioStartMs, it.toLong())
            },
            onValueChangeFinished = vm::endGesture,
            valueRange = timelineSliderRange(timeline),
        )
        TextButton(onClick = vm::clearAudio) { Text("Remove soundtrack") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverlayPanel(ui: VideoEditUi, vm: VideoEditorViewModel) {
    ScrollingOptions {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverlayKind.entries.forEach { kind ->
                FilterChip(selected = ui.overlayKind == kind, onClick = { vm.overlayKind(kind) }, label = { Text(kind.name) })
            }
        }
        if (ui.overlayKind == OverlayKind.Text) {
            OutlinedTextField(ui.draftText, vm::draft, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
        }
        VideoColorRow(ui.color, vm::color)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Whole timeline", modifier = Modifier.weight(1f))
            Switch(checked = ui.wholeClip, onCheckedChange = vm::wholeClip)
        }
        TextButton(onClick = vm::addMark) { Text("Add overlay") }
    }
    val mark = ui.document.marks.firstOrNull { it.id == ui.selectedMark }
    if (mark != null && !mark.wholeClip) {
        val timeline = timelineDuration(ui.document.clips.map { ClipRange(it.startMs, it.endMs) }).coerceAtLeast(1L)
        Text("From ${formatDuration(mark.startMs)} to ${formatDuration(mark.endMs)}", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = timelineSliderValue(mark.startMs, timeline),
            onValueChange = {
                vm.beginGesture()
                vm.previewMarkRange(it.toLong(), mark.endMs)
            },
            onValueChangeFinished = vm::endGesture,
            valueRange = timelineSliderRange(timeline),
        )
        Slider(
            value = timelineSliderValue(mark.endMs, timeline),
            onValueChange = {
                vm.beginGesture()
                vm.previewMarkRange(mark.startMs, it.toLong())
            },
            onValueChangeFinished = vm::endGesture,
            valueRange = timelineSliderRange(timeline),
        )
    }
    Text("Drag a mark on the preview to place it. It is burned in on export.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun VideoColorRow(selected: Int, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        videoInk.forEach { color ->
            Box(
                Modifier
                    .background(Color(color), CircleShape)
                    .border(if (color == selected) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    .padding(14.dp)
                    .clickable { onPick(color) },
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ClipPreview(
    uri: String,
    startMs: Long,
    endMs: Long,
    effects: List<androidx.media3.common.Effect>,
    onPosition: (Long) -> Unit,
    onReady: ((Long) -> Unit) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().also { it.playWhenReady = false }
    }
    DisposableEffect(player) {
        onReady { player.seekTo(it) }
        onDispose { player.release() }
    }
    LaunchedEffect(uri, startMs, endMs, effects) {
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs)
                        .setEndPositionMs(endMs.coerceAtLeast(startMs + 1))
                        .build(),
                )
                .build(),
        )
        player.setVideoEffects(effects)
        player.prepare()
        player.seekTo(0)
    }
    LaunchedEffect(player) {
        while (true) {
            if (player.playbackState != Player.STATE_IDLE) onPosition(player.currentPosition)
            delay(200)
        }
    }
    AndroidView(
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                this.player = player
                useController = false
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}
