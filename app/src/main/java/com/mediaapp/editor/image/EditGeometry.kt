package com.mediaapp.editor.image

import com.mediaapp.editor.FilterPreset

enum class CropAspect(val label: String, val ratio: Float?) {
    Free("Free", null),
    Square("1:1", 1f),
    FourThree("4:3", 4f / 3f),
    SixteenNine("16:9", 16f / 9f),
    Original("Original", null),
}

enum class CollageTemplate(val label: String, val slots: Int) {
    SideBySide("2 side", 2),
    Stack("2 stack", 2),
    Three("3", 3),
    Grid("4", 4),
}

enum class ShapeKind { Rectangle, Circle, Line, Arrow }

data class NormRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)

    fun clamp(): NormRect {
        val l = left.coerceIn(0f, 1f)
        val t = top.coerceIn(0f, 1f)
        val r = right.coerceIn(l, 1f)
        val b = bottom.coerceIn(t, 1f)
        return copy(left = l, top = t, right = r, bottom = b)
    }
}

fun fullFrame(): NormRect = NormRect(0f, 0f, 1f, 1f)

fun centeredCrop(imageAspect: Float, targetAspect: Float?): NormRect {
    if (targetAspect == null || imageAspect <= 0f || targetAspect <= 0f) return fullFrame()
    return if (imageAspect > targetAspect) {
        val width = (targetAspect / imageAspect).coerceIn(0.05f, 1f)
        val left = (1f - width) / 2f
        NormRect(left, 0f, left + width, 1f)
    } else {
        val height = (imageAspect / targetAspect).coerceIn(0.05f, 1f)
        val top = (1f - height) / 2f
        NormRect(0f, top, 1f, top + height)
    }
}

/** Media3 Crop edges: left, right, bottom, top in NDC (-1..1, y up). */
fun ndcCenterCrop(sourceAspect: Float, targetAspect: Float): FloatArray {
    val norm = centeredCrop(sourceAspect, targetAspect)
    val left = norm.left * 2f - 1f
    val right = norm.right * 2f - 1f
    val top = 1f - norm.top * 2f
    val bottom = 1f - norm.bottom * 2f
    return floatArrayOf(left, right, bottom, top)
}

fun collageFrames(template: CollageTemplate): List<NormRect> = when (template) {
    CollageTemplate.SideBySide -> listOf(
        NormRect(0f, 0f, 0.5f, 1f),
        NormRect(0.5f, 0f, 1f, 1f),
    )
    CollageTemplate.Stack -> listOf(
        NormRect(0f, 0f, 1f, 0.5f),
        NormRect(0f, 0.5f, 1f, 1f),
    )
    CollageTemplate.Three -> listOf(
        NormRect(0f, 0f, 0.5f, 1f),
        NormRect(0.5f, 0f, 1f, 0.5f),
        NormRect(0.5f, 0.5f, 1f, 1f),
    )
    CollageTemplate.Grid -> listOf(
        NormRect(0f, 0f, 0.5f, 0.5f),
        NormRect(0.5f, 0f, 1f, 0.5f),
        NormRect(0f, 0.5f, 0.5f, 1f),
        NormRect(0.5f, 0.5f, 1f, 1f),
    )
}

data class TextStamp(
    val id: String,
    val text: String,
    val nx: Float,
    val ny: Float,
    val size: Float,
    val color: Int,
)

data class ShapeStamp(
    val id: String,
    val kind: ShapeKind,
    val nx: Float,
    val ny: Float,
    val nw: Float,
    val nh: Float,
    val color: Int,
    val stroke: Float,
)

data class ImageDocument(
    val filter: FilterPreset = FilterPreset.None,
    val aspect: CropAspect = CropAspect.Free,
    val crop: NormRect = fullFrame(),
    val quarterTurns: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val texts: List<TextStamp> = emptyList(),
    val shapes: List<ShapeStamp> = emptyList(),
)

fun orientedAspect(sourceAspect: Float, quarterTurns: Int): Float {
    if (sourceAspect <= 0f) return 1f
    return if (quarterTurns % 2 == 0) sourceAspect else 1f / sourceAspect
}
