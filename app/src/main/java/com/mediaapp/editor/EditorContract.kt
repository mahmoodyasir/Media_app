package com.mediaapp.editor

/**
 * Operation names shared by the viewer and the editors.
 * Photos open [com.mediaapp.ui.ImageEditorRoute]. Videos open [com.mediaapp.ui.VideoEditorRoute],
 * which exports through Media3 Transformer.
 */
enum class VideoEditOperation(val label: String, val detail: String) {
    Trim("Trim", "Cut the start and end"),
    Crop("Crop", "Reframe the picture"),
    Merge("Merge", "Join clips in order"),
    Filters("Filters", "Color and look"),
    RemoveAudio("Remove audio", "Keep the picture, drop the sound"),
    ReplaceAudio("Replace audio", "Swap the soundtrack"),
    TextOverlay("Text", "Titles on the whole clip or a time range"),
    ShapeOverlay("Shapes", "Shapes on the whole clip or a time range"),
}

enum class ImageEditOperation(val label: String, val detail: String) {
    Filters("Filters", "Color grades"),
    Crop("Crop", "Reframe and rotate"),
    Collage("Collage", "Combine several photos"),
    Text("Text", "Captions"),
    Shapes("Shapes", "Marks and stickers"),
}

data class VideoEditRequest(
    val uri: String,
    val mimeType: String,
    val operation: VideoEditOperation,
)

data class ImageEditRequest(
    val uri: String,
    val mimeType: String,
    val operation: ImageEditOperation,
)

sealed interface EditResult {
    val message: String

    data class Unavailable(override val message: String) : EditResult
    data class Success(val outputUri: String, override val message: String) : EditResult
}
