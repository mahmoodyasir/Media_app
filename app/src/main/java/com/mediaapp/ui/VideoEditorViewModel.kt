package com.mediaapp.ui

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediaapp.data.media.MediaRepository
import com.mediaapp.data.media.StoreAction
import com.mediaapp.data.model.GalleryEntry
import com.mediaapp.data.vault.VaultRepository
import com.mediaapp.editor.EditorLaunchStore
import com.mediaapp.editor.EditorStore
import com.mediaapp.editor.FilterPreset
import com.mediaapp.editor.UndoStack
import com.mediaapp.editor.playheadPosition
import com.mediaapp.editor.image.ShapeKind
import com.mediaapp.editor.video.AudioWindow
import com.mediaapp.editor.video.ClipRange
import com.mediaapp.editor.video.ExportClip
import com.mediaapp.editor.video.ExportRequest
import com.mediaapp.editor.video.FrameAspect
import com.mediaapp.editor.video.OverlayKind
import com.mediaapp.editor.video.TimedMark
import com.mediaapp.editor.video.VideoExporter
import com.mediaapp.editor.video.VideoPlan
import com.mediaapp.editor.video.clampedAudio
import com.mediaapp.editor.video.timelineDuration
import com.mediaapp.editor.video.validatePlan
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class VideoTool(val label: String) {
    Trim("Trim"),
    Crop("Crop"),
    Merge("Merge"),
    Filters("Filters"),
    Audio("Audio"),
    Overlays("Overlays"),
}

data class EditorClip(
    val id: String,
    val uri: String,
    val name: String,
    val durationMs: Long,
    val startMs: Long,
    val endMs: Long,
    val width: Int,
    val height: Int,
)

data class VideoDocument(
    val clips: List<EditorClip> = emptyList(),
    val filter: FilterPreset = FilterPreset.None,
    val aspect: FrameAspect = FrameAspect.Original,
    val removeAudio: Boolean = false,
    val audioUri: String? = null,
    val audioName: String = "",
    val audioStartMs: Long = 0L,
    val audioEndMs: Long = 0L,
    val marks: List<TimedMark> = emptyList(),
    val selected: Int = 0,
)

data class VideoEditUi(
    val ready: Boolean = false,
    val document: VideoDocument = VideoDocument(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val tool: VideoTool = VideoTool.Trim,
    val saving: Boolean = false,
    val progress: Int = 0,
    val vault: Boolean = false,
    val name: String = "Edited video",
    val error: String? = null,
    val sourceAspect: Float = 16f / 9f,
    val draftText: String = "Title",
    val overlayKind: OverlayKind = OverlayKind.Text,
    val color: Int = 0xFFD6F25C.toInt(),
    val wholeClip: Boolean = true,
    val selectedMark: String? = null,
    val playheadMs: Long = 0L,
)

@HiltViewModel
class VideoEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vault: VaultRepository,
    private val media: MediaRepository,
    private val store: EditorStore,
    private val exporter: VideoExporter,
    launches: EditorLaunchStore,
) : ViewModel() {
    private val entry: GalleryEntry? = launches.video
    private val stack = UndoStack(VideoDocument())
    private var progressJob: Job? = null
    private var exportFile: File? = null
    private var userCancelled = false
    private var materialized = false
    private var scrubbing = false

    private val _ui = MutableStateFlow(VideoEditUi(vault = entry?.vault == true, name = entry?.displayName ?: "Edited video"))
    val ui = _ui.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()
    private val _consent = MutableStateFlow<StoreAction.NeedsConsent?>(null)
    val consent = _consent.asStateFlow()

    init {
        val current = entry
        if (current == null) {
            _ui.value = _ui.value.copy(error = "Nothing to edit")
        } else {
            viewModelScope.launch {
                val opened = withContext(Dispatchers.IO) { openSource(current) }
                if (opened == null) {
                    _ui.value = _ui.value.copy(error = "Couldn't open that video")
                } else {
                    val (uri, probe) = opened
                    val duration = probe.durationMs.takeIf { it > 0L } ?: current.durationMs.coerceAtLeast(1_000L)
                    val width = probe.width.takeIf { it > 0 } ?: current.width.coerceAtLeast(1)
                    val height = probe.height.takeIf { it > 0 } ?: current.height.coerceAtLeast(1)
                    val clip = EditorClip(
                        id = UUID.randomUUID().toString(),
                        uri = uri,
                        name = current.displayName,
                        durationMs = duration,
                        startMs = 0L,
                        endMs = duration,
                        width = width,
                        height = height,
                    )
                    stack.reset(VideoDocument(clips = listOf(clip), audioEndMs = duration))
                    _ui.value = snapshot().copy(
                        ready = true,
                        sourceAspect = width.toFloat() / height.coerceAtLeast(1),
                    )
                }
            }
        }
    }

    fun tool(value: VideoTool) {
        _ui.value = _ui.value.copy(tool = value)
    }

    fun selectClip(index: Int) {
        val clips = stack.current.clips
        if (index !in clips.indices) return
        change { it.copy(selected = index) }
    }

    fun beginGesture() = stack.beginGesture()

    fun previewTrim(startMs: Long, endMs: Long) {
        val index = stack.current.selected
        stack.preview(stack.current.copy(clips = stack.current.clips.mapIndexed { i, clip ->
            if (i != index) clip
            else {
                val start = startMs.coerceIn(0L, (clip.durationMs - 1).coerceAtLeast(0L))
                val end = endMs.coerceIn(start + 1, clip.durationMs.coerceAtLeast(start + 1))
                clip.copy(startMs = start, endMs = end)
            }
        }))
        _ui.value = snapshot()
    }

    fun endGesture() {
        stack.endGesture()
        _ui.value = snapshot()
    }

    fun aspect(value: FrameAspect) = change { it.copy(aspect = value) }

    fun filter(preset: FilterPreset) = change { it.copy(filter = preset) }

    fun removeAudio(enabled: Boolean) = change { it.copy(removeAudio = enabled) }

    fun playhead(ms: Long) {
        if (scrubbing) return
        _ui.value = _ui.value.copy(playheadMs = ms.coerceAtLeast(0L))
    }

    fun scrub(ms: Long, durationMs: Long): Long {
        scrubbing = true
        val clamped = playheadPosition(ms, durationMs)
        _ui.value = _ui.value.copy(playheadMs = clamped)
        return clamped
    }

    fun endScrub() {
        scrubbing = false
    }

    fun previewAudio(startMs: Long, endMs: Long) {
        val timeline = timelineMs()
        val window = clampedAudio(AudioWindow(startMs, endMs.coerceAtLeast(startMs + 1)), timeline.coerceAtLeast(1L))
        stack.preview(stack.current.copy(audioStartMs = window.startMs, audioEndMs = window.endMs))
        _ui.value = snapshot()
    }

    fun attachAudio(uri: Uri, name: String) {
        rememberRead(uri)
        val timeline = timelineMs().coerceAtLeast(1L)
        change {
            it.copy(
                audioUri = uri.toString(),
                audioName = name.ifBlank { "Soundtrack" },
                audioStartMs = 0L,
                audioEndMs = timeline,
                removeAudio = true,
            )
        }
    }

    fun clearAudio() = change { it.copy(audioUri = null, audioName = "", audioStartMs = 0L, audioEndMs = 0L) }

    fun addClip(uri: Uri, name: String) {
        rememberRead(uri)
        viewModelScope.launch {
            val probe = withContext(Dispatchers.IO) { probe(uri) }
            if (probe.durationMs <= 0L) {
                _messages.emit("Couldn't read that clip")
                return@launch
            }
            val clip = EditorClip(
                id = UUID.randomUUID().toString(),
                uri = uri.toString(),
                name = name.ifBlank { "Clip" },
                durationMs = probe.durationMs,
                startMs = 0L,
                endMs = probe.durationMs,
                width = probe.width.coerceAtLeast(1),
                height = probe.height.coerceAtLeast(1),
            )
            change { doc -> doc.copy(clips = doc.clips + clip, selected = doc.clips.size) }
        }
    }

    fun removeClip(index: Int) {
        if (stack.current.clips.size <= 1) return
        change { doc ->
            val next = doc.clips.filterIndexed { i, _ -> i != index }
            doc.copy(clips = next, selected = doc.selected.coerceAtMost(next.lastIndex))
        }
    }

    fun moveClip(index: Int, delta: Int) {
        val to = index + delta
        val clips = stack.current.clips
        if (index !in clips.indices || to !in clips.indices) return
        change { doc ->
            val next = doc.clips.toMutableList()
            val item = next.removeAt(index)
            next.add(to, item)
            doc.copy(clips = next, selected = to)
        }
    }

    fun draft(text: String) {
        _ui.value = _ui.value.copy(draftText = text)
    }

    fun color(value: Int) {
        _ui.value = _ui.value.copy(color = value)
    }

    fun overlayKind(kind: OverlayKind) {
        _ui.value = _ui.value.copy(overlayKind = kind)
    }

    fun wholeClip(enabled: Boolean) {
        _ui.value = _ui.value.copy(wholeClip = enabled)
        val id = _ui.value.selectedMark ?: return
        change { doc ->
            doc.copy(marks = doc.marks.map { if (it.id == id) it.copy(wholeClip = enabled) else it })
        }
    }

    fun addMark() {
        val timeline = timelineMs().coerceAtLeast(1L)
        val at = timelinePlayhead().coerceIn(0L, timeline)
        val whole = _ui.value.wholeClip
        val kind = _ui.value.overlayKind
        val mark = TimedMark(
            id = UUID.randomUUID().toString(),
            kind = kind,
            text = _ui.value.draftText.ifBlank { "Title" },
            shape = kind.toShape(),
            startMs = if (whole) 0L else at,
            endMs = if (whole) timeline else (at + 2_500L).coerceAtMost(timeline).coerceAtLeast(at + 1),
            nx = 0.18f,
            ny = 0.18f,
            nw = 0.46f,
            nh = 0.22f,
            color = _ui.value.color,
            wholeClip = whole,
        )
        change { it.copy(marks = it.marks + mark) }
        _ui.value = _ui.value.copy(selectedMark = mark.id)
    }

    fun selectMark(id: String) {
        val mark = stack.current.marks.firstOrNull { it.id == id } ?: return
        _ui.value = _ui.value.copy(selectedMark = id, wholeClip = mark.wholeClip, draftText = mark.text.ifBlank { _ui.value.draftText })
    }

    fun previewMove(id: String, dx: Float, dy: Float) {
        stack.preview(stack.current.copy(marks = stack.current.marks.map {
            if (it.id == id) it.copy(
                nx = (it.nx + dx).coerceIn(0f, 0.9f),
                ny = (it.ny + dy).coerceIn(0f, 0.9f),
            ) else it
        }))
        _ui.value = snapshot()
    }

    fun previewMarkRange(startMs: Long, endMs: Long) {
        val id = _ui.value.selectedMark ?: return
        val timeline = timelineMs().coerceAtLeast(1L)
        val start = startMs.coerceIn(0L, timeline - 1)
        val end = endMs.coerceIn(start + 1, timeline)
        stack.preview(stack.current.copy(marks = stack.current.marks.map {
            if (it.id == id) it.copy(startMs = start, endMs = end, wholeClip = false) else it
        }))
        _ui.value = _ui.value.copy(wholeClip = false)
        _ui.value = snapshot()
    }

    fun undo() {
        if (stack.undo()) _ui.value = snapshot()
    }

    fun redo() {
        if (stack.redo()) _ui.value = snapshot()
    }

    fun save(target: SaveTarget) {
        val current = entry ?: return
        val doc = stack.current
        val plan = planOf(doc)
        val problem = validatePlan(plan)
        if (problem != null) {
            viewModelScope.launch { _messages.emit(problem) }
            return
        }
        val output = File(context.cacheDir, "export/${UUID.randomUUID()}.mp4")
        output.parentFile?.mkdirs()
        exportFile = output
        userCancelled = false
        _ui.value = _ui.value.copy(saving = true, progress = 0)
        val request = exportRequest(doc, plan)
        exporter.start(
            request,
            output,
            onDone = { viewModelScope.launch { finishExport(target, current, output, plan) } },
            onError = { message ->
                viewModelScope.launch {
                    progressJob?.cancel()
                    _ui.value = _ui.value.copy(saving = false, progress = 0)
                    if (!userCancelled) _messages.emit(message.ifBlank { "Export failed" })
                    else _messages.emit("Export cancelled")
                    if (userCancelled) output.delete()
                }
            },
        )
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (isActive && _ui.value.saving) {
                _ui.value = _ui.value.copy(progress = exporter.progress().coerceIn(0, 100))
                delay(250)
            }
        }
    }

    fun cancelExport() {
        userCancelled = true
        exporter.cancel()
    }

    fun onConsent(accepted: Boolean) {
        val pending = _consent.value ?: return
        _consent.value = null
        val file = exportFile ?: return
        val current = entry ?: return
        if (!accepted) {
            file.delete()
            return
        }
        viewModelScope.launch {
            val action = withContext(Dispatchers.IO) { store.overwriteVideo(current.uri, file) }
            when (action) {
                is StoreAction.Done -> {
                    _messages.emit(action.message)
                    media.refresh()
                    file.delete()
                }
                is StoreAction.Failed -> _messages.emit(action.message)
                is StoreAction.NeedsConsent -> _consent.value = action
            }
        }
    }

    override fun onCleared() {
        progressJob?.cancel()
        exporter.cancel()
        exportFile?.delete()
        if (materialized) entry?.let { vault.deleteMaterialized(it.id) }
    }

    private suspend fun finishExport(target: SaveTarget, current: GalleryEntry, file: File, plan: VideoPlan) {
        progressJob?.cancel()
        if (userCancelled || !file.exists() || file.length() == 0L) {
            _ui.value = _ui.value.copy(saving = false, progress = 0)
            if (!userCancelled) _messages.emit("Export produced an empty file")
            return
        }
        val message = deliver(target, current, file, plan)
        _ui.value = _ui.value.copy(saving = false, progress = if (message == null) _ui.value.progress else 100)
        if (message != null) _messages.emit(message)
        if (message?.startsWith("Saved") == true || message?.startsWith("Replaced") == true) media.refresh()
    }

    private suspend fun deliver(target: SaveTarget, current: GalleryEntry, file: File, plan: VideoPlan): String? {
        val (width, height) = outputSize(stack.current, plan.aspect)
        val duration = timelineDuration(plan.clips)
        return try {
            when (target) {
                SaveTarget.Copy -> {
                    withContext(Dispatchers.IO) { store.saveVideo(file, current.displayName) }
                    file.delete()
                    "Saved a new video in Movies/Media App"
                }
                SaveTarget.Overwrite -> {
                    if (current.vault) {
                        val action = vault.replaceFile(current.id, file, width, height, duration)
                        file.delete()
                        action.message()
                    } else {
                        val action = withContext(Dispatchers.IO) { store.overwriteVideo(current.uri, file) }
                        if (action is StoreAction.NeedsConsent) {
                            _consent.value = action
                            null
                        } else {
                            file.delete()
                            action.message()
                        }
                    }
                }
                SaveTarget.Vault -> {
                    val action = vault.importFile(
                        file,
                        "Edited ${current.displayName}",
                        "video/mp4",
                        true,
                        width,
                        height,
                        duration,
                    )
                    file.delete()
                    action.message()
                }
            }
        } catch (error: Exception) {
            error.message ?: "Couldn't save the video"
        }
    }

    private fun exportRequest(doc: VideoDocument, plan: VideoPlan): ExportRequest {
        val audio = plan.audio
        val first = doc.clips.first()
        return ExportRequest(
            clips = doc.clips.map { ExportClip(it.uri, it.startMs, it.endMs) },
            filter = doc.filter,
            aspect = doc.aspect,
            sourceAspect = first.width.toFloat() / first.height.coerceAtLeast(1),
            removeAudio = doc.removeAudio,
            audioUri = doc.audioUri,
            audioStartMs = audio?.startMs ?: 0L,
            audioEndMs = audio?.endMs ?: 0L,
            marks = doc.marks,
        )
    }

    private fun planOf(doc: VideoDocument): VideoPlan {
        val clips = doc.clips.map { ClipRange(it.startMs, it.endMs) }
        val timeline = timelineDuration(clips).coerceAtLeast(0L)
        val audio = doc.audioUri?.let { clampedAudio(AudioWindow(doc.audioStartMs, doc.audioEndMs), timeline.coerceAtLeast(1L)) }
        return VideoPlan(
            clips = clips,
            filter = doc.filter,
            aspect = doc.aspect,
            removeAudio = doc.removeAudio || doc.audioUri != null,
            audio = audio,
            marks = doc.marks,
        )
    }

    private fun timelineMs(): Long = timelineDuration(stack.current.clips.map { ClipRange(it.startMs, it.endMs) })

    private fun timelinePlayhead(): Long {
        var offset = 0L
        stack.current.clips.forEachIndexed { index, clip ->
            val length = (clip.endMs - clip.startMs).coerceAtLeast(0L)
            if (index == stack.current.selected) {
                return offset + _ui.value.playheadMs.coerceIn(0L, length)
            }
            offset += length
        }
        return _ui.value.playheadMs
    }

    private fun change(block: (VideoDocument) -> VideoDocument) {
        stack.push(block(stack.current))
        _ui.value = snapshot()
    }

    private fun snapshot(): VideoEditUi {
        val current = _ui.value
        return current.copy(
            document = stack.current,
            canUndo = stack.canUndo,
            canRedo = stack.canRedo,
        )
    }

    private fun openSource(current: GalleryEntry): Pair<String, Probe>? {
        return if (current.vault) {
            val file = runCatching { vault.materialize(current.id) }.getOrNull() ?: return null
            materialized = true
            val uri = Uri.fromFile(file)
            uri.toString() to probe(uri)
        } else {
            val uri = Uri.parse(current.uri)
            current.uri to probe(uri)
        }
    }

    private fun probe(uri: Uri): Probe {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            Probe(
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
            )
        } catch (_: Exception) {
            Probe(0L, 0, 0)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun rememberRead(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun outputSize(doc: VideoDocument, aspect: FrameAspect): Pair<Int, Int> {
        val clip = doc.clips.getOrNull(doc.selected) ?: doc.clips.firstOrNull()
        val width = clip?.width?.coerceAtLeast(1) ?: 1280
        val height = clip?.height?.coerceAtLeast(1) ?: 720
        val ratio = aspect.ratio ?: return width to height
        val source = width.toFloat() / height
        return if (source > ratio) {
            (height * ratio).toInt().coerceAtLeast(2) to height
        } else {
            width to (width / ratio).toInt().coerceAtLeast(2)
        }
    }

    private fun OverlayKind.toShape(): ShapeKind? = when (this) {
        OverlayKind.Text -> null
        OverlayKind.Rectangle -> ShapeKind.Rectangle
        OverlayKind.Circle -> ShapeKind.Circle
        OverlayKind.Line -> ShapeKind.Line
        OverlayKind.Arrow -> ShapeKind.Arrow
    }

    private fun StoreAction.message(): String = when (this) {
        is StoreAction.Done -> message
        is StoreAction.Failed -> message
        is StoreAction.NeedsConsent -> "Android needs permission to replace that video"
    }

    private data class Probe(val durationMs: Long, val width: Int, val height: Int)
}
