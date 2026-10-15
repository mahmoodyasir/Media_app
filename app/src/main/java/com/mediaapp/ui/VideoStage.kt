package com.mediaapp.ui

import android.Manifest
import android.content.ComponentName
import android.media.AudioManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PictureInPicture
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.mediaapp.data.media.formatDuration
import com.mediaapp.data.model.GalleryEntry
import com.mediaapp.player.PlaybackService
import com.mediaapp.player.PlayerBridge
import com.mediaapp.player.enterPlayerPip
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun VideoStage(
    entry: GalleryEntry,
    resumePosition: Long,
    allowPip: Boolean,
    onSave: (Long, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val player = rememberSessionPlayer()
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var controls by remember { mutableStateOf(true) }
    var hint by remember { mutableStateOf<String?>(null) }
    var resize by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var lockedRotation by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<TrackSheet?>(null) }
    var subtitleUri by remember(entry.id) { mutableStateOf<android.net.Uri?>(null) }
    var lastTapAt by remember { mutableLongStateOf(0L) }
    var armToggle by remember { mutableLongStateOf(0L) }
    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    var speed by remember { mutableFloatStateOf(1f) }

    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            subtitleUri = uri
        }
    }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    LaunchedEffect(player, entry.id, subtitleUri) {
        val active = player ?: return@LaunchedEffect
        val kept = if (subtitleUri != null) active.currentPosition else resumePosition
        val item = MediaItem.Builder().setUri(entry.uri).setMediaId(entry.id).apply {
            val uri = subtitleUri
            if (uri != null) {
                setSubtitleConfigurations(
                    listOf(
                        MediaItem.SubtitleConfiguration.Builder(uri)
                            .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                            .setLanguage("und")
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build(),
                    ),
                )
            }
        }.build()
        active.setMediaItem(item)
        active.prepare()
        active.setPlaybackSpeed(speed)
        if (kept > 1_000L) active.seekTo(kept)
        active.play()
    }

    DisposableEffect(player) {
        val active = player ?: return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onEvents(player: Player, events: Player.Events) {
                duration = player.duration.coerceAtLeast(0L)
                position = player.currentPosition
            }
        }
        active.addListener(listener)
        onDispose {
            onSave(active.currentPosition, active.duration)
            active.removeListener(listener)
        }
    }

    LaunchedEffect(playing, player) {
        val active = player ?: return@LaunchedEffect
        while (playing) {
            position = active.currentPosition
            duration = active.duration.coerceAtLeast(0L)
            delay(400)
        }
    }

    LaunchedEffect(controls, playing) {
        if (controls && playing) {
            delay(3_200)
            controls = false
            hint = null
        }
    }
    LaunchedEffect(armToggle) {
        val token = armToggle
        if (token == 0L) return@LaunchedEffect
        delay(280)
        if (lastTapAt == token) controls = !controls
    }

    Box(modifier) {
        if (player == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Opening player…", color = Color.White)
            }
        } else {
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        useController = false
                        this.player = player
                        resizeMode = resize
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    }
                },
                update = {
                    it.player = player
                    it.resizeMode = resize
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(player) {
                    val widthPx = size.width.toFloat()
                    val heightPx = size.height.toFloat()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.position
                        val pointer = down.id
                        val active = player ?: return@awaitEachGesture
                        val startPosition = active.currentPosition
                        val activity = context.findActivity()
                        val window = activity?.window
                        val initialBrightness = window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
                        val audio = context.getSystemService(AudioManager::class.java)
                        val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        val startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                        var mode = 0
                        var totalX = 0f
                        var totalY = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointer } ?: break
                            if (!change.pressed) {
                                val moved = (change.position - start).getDistance()
                                val elapsed = change.uptimeMillis - down.uptimeMillis
                                if (mode == 0 && moved < 30f && elapsed < 250) {
                                    val now = change.uptimeMillis
                                    if (now - lastTapAt < 280) {
                                        val skip = if (start.x < widthPx / 2f) -10_000L else 10_000L
                                        val target = (active.currentPosition + skip).coerceAtLeast(0L)
                                        active.seekTo(target)
                                        hint = if (skip < 0) "Back 10 seconds" else "Forward 10 seconds"
                                        controls = true
                                        lastTapAt = 0L
                                    } else {
                                        lastTapAt = now
                                        armToggle = now
                                    }
                                }
                                break
                            }
                            val delta = change.position - change.previousPosition
                            totalX += delta.x
                            totalY += delta.y
                            if (mode == 0 && (kotlin.math.abs(totalX) > 36f || kotlin.math.abs(totalY) > 36f)) {
                                mode = if (kotlin.math.abs(totalX) > kotlin.math.abs(totalY)) 1
                                else if (start.x < widthPx / 2f) 2 else 3
                            }
                            when (mode) {
                                1 -> {
                                    val span = active.duration.takeIf { it > 0 } ?: 60_000L
                                    val target = (startPosition + (totalX / widthPx) * span).toLong().coerceIn(0L, span)
                                    active.seekTo(target)
                                    hint = formatDuration(target)
                                    controls = true
                                }
                                2 -> {
                                    val next = (initialBrightness - totalY / heightPx).coerceIn(0.01f, 1f)
                                    window?.let { host ->
                                        val attributes = host.attributes
                                        attributes.screenBrightness = next
                                        host.attributes = attributes
                                    }
                                    hint = "Brightness ${(next * 100).toInt()}%"
                                    controls = true
                                }
                                3 -> {
                                    val steps = (-totalY / heightPx * maxVolume).toInt()
                                    val next = (startVolume + steps).coerceIn(0, maxVolume)
                                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                                    hint = "Volume ${(next * 100 / maxVolume)}%"
                                    controls = true
                                }
                            }
                            if (mode != 0) change.consume()
                        }
                    }
                },
        )
        hint?.let { label ->
            Text(
                label,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (controls) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).padding(8.dp)) {
                Text(
                    "${formatDuration(position)}  /  ${formatDuration(duration)}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (playing) player?.pause() else player?.play() }) {
                        Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, contentDescription = "Play or pause", tint = Color.White)
                    }
                    Box {
                        IconButton(onClick = { speedMenu = true }) {
                            Icon(Icons.Outlined.Speed, contentDescription = "Playback speed", tint = Color.White)
                        }
                        DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                            speeds.forEach { value ->
                                DropdownMenuItem(
                                    text = { Text(if (value == 1f) "Normal" else "${value}×") },
                                    onClick = {
                                        speed = value
                                        player?.setPlaybackSpeed(value)
                                        speedMenu = false
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = {
                        resize = when (resize) {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    }) {
                        Icon(Icons.Outlined.AspectRatio, contentDescription = "Aspect", tint = Color.White)
                    }
                    IconButton(onClick = {
                        lockedRotation = !lockedRotation
                        context.findActivity()?.requestedOrientation = if (lockedRotation) {
                            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
                        } else {
                            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                    }) {
                        Icon(Icons.Outlined.ScreenRotation, contentDescription = "Rotation lock", tint = if (lockedRotation) MaterialTheme.colorScheme.primary else Color.White)
                    }
                    IconButton(onClick = { sheet = TrackSheet.Audio }) {
                        Icon(Icons.Outlined.Audiotrack, contentDescription = "Audio track", tint = Color.White)
                    }
                    IconButton(onClick = { sheet = TrackSheet.Text }) {
                        Icon(Icons.Outlined.Subtitles, contentDescription = "Subtitles", tint = Color.White)
                    }
                    if (allowPip) {
                        IconButton(onClick = { context.findActivity()?.enterPlayerPip() }) {
                            Icon(Icons.Outlined.PictureInPicture, contentDescription = "Picture in picture", tint = Color.White)
                        }
                    }
                }
            }
        }
    }

    val activePlayer = player
    if (sheet != null && activePlayer != null) {
        ModalBottomSheet(onDismissRequest = { sheet = null }) {
            val type = if (sheet == TrackSheet.Audio) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
            Text(
                if (sheet == TrackSheet.Audio) "Audio" else "Subtitles",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            if (sheet == TrackSheet.Text) {
                TextButton(onClick = {
                    activePlayer.trackSelectionParameters = activePlayer.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                    sheet = null
                }) { Text("Off") }
                TextButton(onClick = {
                    sheet = null
                    subtitlePicker.launch(arrayOf("application/x-subrip", "text/*", "application/octet-stream"))
                }) { Text("Open an .srt file") }
            }
            activePlayer.currentTracks.groups.forEach { group ->
                if (group.type != type) return@forEach
                for (index in 0 until group.length) {
                    if (!group.isTrackSupported(index)) continue
                    val format = group.getTrackFormat(index)
                    val label = format.label ?: format.language ?: "Track ${index + 1}"
                    TextButton(onClick = {
                        activePlayer.trackSelectionParameters = activePlayer.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(type, false)
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                            .build()
                        sheet = null
                    }) { Text(label) }
                }
            }
            TextButton(onClick = { sheet = null }, modifier = Modifier.padding(bottom = 24.dp)) { Text("Close") }
        }
    }
}

private enum class TrackSheet { Audio, Text }

@OptIn(UnstableApi::class)
@Composable
private fun rememberSessionPlayer(): Player? {
    val context = LocalContext.current
    var player by remember { mutableStateOf<Player?>(null) }
    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            { player = runCatching { future.get() }.getOrNull() },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            PlayerBridge.allowPip = false
            runCatching { MediaController.releaseFuture(future) }
            player = null
        }
    }
    return player
}
