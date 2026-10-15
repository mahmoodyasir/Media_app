package com.mediaapp.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediaapp.data.media.MediaRepository
import com.mediaapp.data.media.StoreAction
import com.mediaapp.data.model.GalleryEntry
import com.mediaapp.data.vault.VaultRepository
import com.mediaapp.editor.CropSliderRange
import com.mediaapp.editor.DefaultStroke
import com.mediaapp.editor.DefaultTextSize
import com.mediaapp.editor.EditorLaunchStore
import com.mediaapp.editor.EditorStore
import com.mediaapp.editor.FilterPreset
import com.mediaapp.editor.UndoStack
import com.mediaapp.editor.adjustStroke
import com.mediaapp.editor.adjustTextSize
import com.mediaapp.editor.image.CollageTemplate
import com.mediaapp.editor.image.CropAspect
import com.mediaapp.editor.image.ImageDocument
import com.mediaapp.editor.image.NormRect
import com.mediaapp.editor.image.ShapeKind
import com.mediaapp.editor.image.ShapeStamp
import com.mediaapp.editor.image.TextStamp
import com.mediaapp.editor.image.centeredCrop
import com.mediaapp.editor.image.orientedAspect
import com.mediaapp.editor.image.renderCollage
import com.mediaapp.editor.image.renderEdited
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ImageTool { Filters, Crop, Text, Shapes, Collage }

enum class SaveTarget { Copy, Overwrite, Vault }

data class ImageEditUi(
    val ready: Boolean = false,
    val document: ImageDocument = ImageDocument(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val tool: ImageTool = ImageTool.Filters,
    val collage: Boolean = false,
    val template: CollageTemplate = CollageTemplate.Grid,
    val saving: Boolean = false,
    val vault: Boolean = false,
    val name: String = "Edited photo",
    val error: String? = null,
    val selectedText: String? = null,
    val draftText: String = "Hello",
    val shapeKind: ShapeKind = ShapeKind.Rectangle,
    val color: Int = 0xFFD6F25C.toInt(),
    val stroke: Float = DefaultStroke,
    val textSize: Float = DefaultTextSize,
)

@HiltViewModel
class ImageEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vault: VaultRepository,
    private val media: MediaRepository,
    private val store: EditorStore,
    launches: EditorLaunchStore,
) : ViewModel() {
    private val entry: GalleryEntry? = launches.image
    private val stack = UndoStack(ImageDocument())
    private var source: Bitmap? = null
    private var sourceAspect = 1f
    private var renderJob: Job? = null
    private var renderKey = ""
    private var collageSlots: List<Bitmap?> = emptyList()
    private var pendingBitmap: Bitmap? = null

    private val _ui = MutableStateFlow(ImageEditUi(vault = entry?.vault == true, name = entry?.displayName ?: "Edited photo"))
    val ui = _ui.asStateFlow()
    private val _base = MutableStateFlow<Bitmap?>(null)
    val base = _base.asStateFlow()
    private val _collagePreview = MutableStateFlow<Bitmap?>(null)
    val collagePreview = _collagePreview.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()
    private val _consent = MutableStateFlow<StoreAction.NeedsConsent?>(null)
    val consent = _consent.asStateFlow()

    val entryId: String? get() = entry?.id

    init {
        val current = entry
        if (current == null) {
            _ui.value = _ui.value.copy(error = "Nothing to edit")
        } else {
            viewModelScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    if (current.vault) vault.decodeImage(current.id, 2200)
                    else decodeContentBitmap(context, Uri.parse(current.uri), 2200)
                }
                if (bitmap == null) {
                    _ui.value = _ui.value.copy(error = "Couldn't open that photo")
                } else {
                    source = bitmap
                    sourceAspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
                    _ui.value = snapshot().copy(ready = true)
                    schedule(force = true)
                }
            }
        }
    }

    fun tool(value: ImageTool) {
        _ui.value = _ui.value.copy(tool = value, collage = value == ImageTool.Collage)
        if (value == ImageTool.Collage) renderCollagePreview()
    }

    fun filter(preset: FilterPreset) {
        change({ it.copy(filter = preset) }, render = true)
        if (_ui.value.collage) renderCollagePreview()
    }

    fun aspect(aspect: CropAspect) {
        val doc = stack.current
        val imageAspect = orientedAspect(sourceAspect, doc.quarterTurns)
        val ratio = if (aspect == CropAspect.Original) imageAspect else aspect.ratio
        val crop = if (aspect == CropAspect.Free) doc.crop else centeredCrop(imageAspect, ratio)
        change({ it.copy(aspect = aspect, crop = crop) }, render = true)
    }

    fun rotate() {
        change({ doc ->
            val turns = (doc.quarterTurns + 1) % 4
            val imageAspect = orientedAspect(sourceAspect, turns)
            val ratio = if (doc.aspect == CropAspect.Original) imageAspect else doc.aspect.ratio
            val crop = if (doc.aspect == CropAspect.Free) doc.crop else centeredCrop(imageAspect, ratio)
            doc.copy(quarterTurns = turns, crop = crop)
        }, render = true)
    }

    fun flipHorizontal() = change({ it.copy(flipHorizontal = !it.flipHorizontal) }, render = true)
    fun flipVertical() = change({ it.copy(flipVertical = !it.flipVertical) }, render = true)

    fun beginGesture() = stack.beginGesture()

    fun panCrop(dx: Float, dy: Float) {
        stack.preview(stack.current.let { doc ->
            val width = doc.crop.width
            val height = doc.crop.height
            val left = (doc.crop.left - dx).coerceIn(0f, 1f - width)
            val top = (doc.crop.top - dy).coerceIn(0f, 1f - height)
            doc.copy(crop = NormRect(left, top, left + width, top + height))
        })
        _ui.value = snapshot()
    }

    fun endGesture(render: Boolean) {
        stack.endGesture()
        _ui.value = snapshot()
        if (render) schedule(force = true)
    }

    fun tightness(scale: Float) {
        val doc = stack.current
        val floor = CropSliderRange.start
        val width = (doc.crop.width.coerceAtLeast(floor) * scale).coerceIn(floor, CropSliderRange.endInclusive)
        val height = (doc.crop.height.coerceAtLeast(floor) * scale).coerceIn(floor, CropSliderRange.endInclusive)
        val cx = doc.crop.left + doc.crop.width / 2f
        val cy = doc.crop.top + doc.crop.height / 2f
        val left = (cx - width / 2f).coerceIn(0f, 1f - width)
        val top = (cy - height / 2f).coerceIn(0f, 1f - height)
        stack.preview(doc.copy(crop = NormRect(left, top, left + width, top + height)))
        _ui.value = snapshot()
    }

    fun draft(text: String) {
        _ui.value = _ui.value.copy(draftText = text)
    }

    fun color(value: Int) {
        _ui.value = _ui.value.copy(color = value)
    }

    fun shapeKind(kind: ShapeKind) {
        _ui.value = _ui.value.copy(shapeKind = kind)
    }

    fun addText() {
        val stamp = TextStamp(
            UUID.randomUUID().toString(),
            _ui.value.draftText.ifBlank { "Hello" },
            0.12f,
            0.2f,
            _ui.value.textSize,
            _ui.value.color,
        )
        change({ it.copy(texts = it.texts + stamp) }, render = false)
        _ui.value = _ui.value.copy(selectedText = stamp.id)
    }

    fun selectText(id: String) {
        val text = stack.current.texts.firstOrNull { it.id == id }
        _ui.value = _ui.value.copy(
            selectedText = id,
            draftText = text?.text ?: _ui.value.draftText,
            textSize = text?.size ?: _ui.value.textSize,
        )
    }

    fun moveText(id: String, dx: Float, dy: Float) {
        stack.preview(stack.current.copy(texts = stack.current.texts.map {
            if (it.id == id) it.copy(nx = (it.nx + dx).coerceIn(0f, 0.9f), ny = (it.ny + dy).coerceIn(0.05f, 0.98f)) else it
        }))
        _ui.value = snapshot()
    }

    fun resizeText(size: Float) {
        val adjusted = adjustTextSize(stack.current.texts, _ui.value.selectedText, size)
        if (adjusted.texts == stack.current.texts) {
            _ui.value = _ui.value.copy(textSize = adjusted.size)
            return
        }
        beginGesture()
        stack.preview(stack.current.copy(texts = adjusted.texts))
        _ui.value = snapshot().copy(textSize = adjusted.size)
    }

    fun recolorText() {
        val id = _ui.value.selectedText ?: return
        change({ doc -> doc.copy(texts = doc.texts.map { if (it.id == id) it.copy(color = _ui.value.color, text = _ui.value.draftText) else it }) }, render = false)
    }

    fun addShape() {
        val stamp = ShapeStamp(
            UUID.randomUUID().toString(),
            _ui.value.shapeKind,
            0.2f,
            0.35f,
            0.4f,
            0.18f,
            _ui.value.color,
            _ui.value.stroke,
        )
        change({ it.copy(shapes = it.shapes + stamp) }, render = false)
    }

    fun moveShape(id: String, dx: Float, dy: Float) {
        stack.preview(stack.current.copy(shapes = stack.current.shapes.map {
            if (it.id == id) it.copy(nx = (it.nx + dx).coerceIn(0f, 0.95f), ny = (it.ny + dy).coerceIn(0f, 0.95f)) else it
        }))
        _ui.value = snapshot()
    }

    fun stroke(value: Float) {
        val adjusted = adjustStroke(stack.current.shapes, value, _ui.value.color)
        if (adjusted.shapes == stack.current.shapes) {
            _ui.value = _ui.value.copy(stroke = adjusted.width)
            return
        }
        beginGesture()
        stack.preview(stack.current.copy(shapes = adjusted.shapes))
        _ui.value = snapshot().copy(stroke = adjusted.width)
    }

    fun template(value: CollageTemplate) {
        _ui.value = _ui.value.copy(template = value)
        renderCollagePreview()
    }

    fun loadCollage(uris: List<Uri>) {
        viewModelScope.launch {
            val bitmaps = withContext(Dispatchers.IO) {
                uris.take(4).map { decodeContentBitmap(context, it, 1400) }
            }
            collageSlots = bitmaps
            renderCollagePreview()
        }
    }

    fun undo() {
        if (stack.undo()) {
            _ui.value = snapshot()
            schedule(force = true)
            if (_ui.value.collage) renderCollagePreview()
        }
    }

    fun redo() {
        if (stack.redo()) {
            _ui.value = snapshot()
            schedule(force = true)
            if (_ui.value.collage) renderCollagePreview()
        }
    }

    fun save(target: SaveTarget) {
        val current = entry ?: return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(saving = true)
            val bitmap = withContext(Dispatchers.Default) { outputBitmap() }
            if (bitmap == null) {
                _ui.value = _ui.value.copy(saving = false, error = "Nothing to save")
                return@launch
            }
            pendingBitmap = bitmap
            val message = deliver(target, current, bitmap)
            _ui.value = _ui.value.copy(saving = false)
            if (message != null) _messages.emit(message)
            if (message?.startsWith("Saved") == true || message?.startsWith("Replaced") == true) media.refresh()
        }
    }

    fun onConsent(accepted: Boolean) {
        val pending = _consent.value ?: return
        _consent.value = null
        val bitmap = pendingBitmap ?: return
        val current = entry ?: return
        if (!accepted) return
        viewModelScope.launch {
            val action = withContext(Dispatchers.IO) { store.overwriteImage(current.uri, bitmap) }
            when (action) {
                is StoreAction.Done -> {
                    _messages.emit(action.message)
                    media.refresh()
                }
                is StoreAction.Failed -> _messages.emit(action.message)
                is StoreAction.NeedsConsent -> _consent.value = action
            }
        }
    }

    private suspend fun deliver(target: SaveTarget, current: GalleryEntry, bitmap: Bitmap): String? {
        return try {
            when (target) {
                SaveTarget.Copy -> {
                    withContext(Dispatchers.IO) { store.saveImage(bitmap, current.displayName) }
                    "Saved a new photo in Pictures/Media App"
                }
                SaveTarget.Overwrite -> {
                    if (current.vault) {
                        val file = writeTemp(bitmap)
                        val action = vault.replaceFile(current.id, file, bitmap.width, bitmap.height, 0)
                        file.delete()
                        action.message()
                    } else {
                        val action = withContext(Dispatchers.IO) { store.overwriteImage(current.uri, bitmap) }
                        if (action is StoreAction.NeedsConsent) {
                            _consent.value = action
                            null
                        } else action.message()
                    }
                }
                SaveTarget.Vault -> {
                    val file = writeTemp(bitmap)
                    val action = vault.importFile(file, "Edited ${current.displayName}", "image/jpeg", false, bitmap.width, bitmap.height, 0)
                    file.delete()
                    action.message()
                }
            }
        } catch (error: Exception) {
            error.message ?: "Couldn't save"
        }
    }

    private fun outputBitmap(): Bitmap? {
        if (_ui.value.collage) {
            if (collageSlots.isEmpty()) return null
            return renderCollage(collageSlots, _ui.value.template, stack.current.filter)
        }
        val src = source ?: return null
        return renderEdited(src, stack.current, bakeOverlays = true)
    }

    private fun writeTemp(bitmap: Bitmap): File {
        val file = File.createTempFile("edit_", ".jpg", context.cacheDir)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return file
    }

    private fun renderCollagePreview() {
        viewModelScope.launch {
            val slots = collageSlots
            val template = _ui.value.template
            val filter = stack.current.filter
            if (slots.isEmpty()) {
                _collagePreview.value = null
                return@launch
            }
            _collagePreview.value = withContext(Dispatchers.Default) { renderCollage(slots, template, filter, 900) }
        }
    }

    private fun change(block: (ImageDocument) -> ImageDocument, render: Boolean) {
        stack.push(block(stack.current))
        _ui.value = snapshot()
        if (render) schedule(force = false)
    }

    private fun schedule(force: Boolean) {
        val doc = stack.current
        val key = "${doc.filter}|${doc.crop}|${doc.quarterTurns}|${doc.flipHorizontal}|${doc.flipVertical}"
        if (!force && key == renderKey) return
        renderKey = key
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            delay(30)
            val src = source ?: return@launch
            val rendered = withContext(Dispatchers.Default) {
                renderEdited(src, doc.copy(texts = emptyList(), shapes = emptyList()), bakeOverlays = false)
            }
            _base.value = rendered
        }
    }

    private fun snapshot(): ImageEditUi {
        val current = _ui.value
        return current.copy(
            document = stack.current,
            canUndo = stack.canUndo,
            canRedo = stack.canRedo,
        )
    }

    private fun StoreAction.message(): String = when (this) {
        is StoreAction.Done -> message
        is StoreAction.Failed -> message
        is StoreAction.NeedsConsent -> "Android needs permission to replace that file"
    }
}
