package com.mediaapp.editor

import com.mediaapp.editor.image.ShapeStamp
import com.mediaapp.editor.image.TextStamp

/** Normalized stroke width shown on the shapes slider. */
val StrokeSliderRange = 0.004f..0.05f

/** Normalized caption size shown on the text slider. */
val TextSliderRange = 0.03f..0.2f

/** Crop window width controlled by the tightness slider. */
val CropSliderRange = 0.2f..1f

const val DefaultStroke = 0.012f
const val DefaultTextSize = 0.07f

data class StrokeAdjustment(
    val width: Float,
    val shapes: List<ShapeStamp>,
)

data class TextSizeAdjustment(
    val size: Float,
    val texts: List<TextStamp>,
)

/**
 * The stroke slider is controlled by the value passed back on the next frame.
 * An empty shape list still has to publish [StrokeAdjustment.width], or the
 * thumb stays on the fallback and the drag is discarded.
 */
fun adjustStroke(
    shapes: List<ShapeStamp>,
    requested: Float,
    color: Int,
    range: ClosedFloatingPointRange<Float> = StrokeSliderRange,
): StrokeAdjustment {
    val width = requested.coerceIn(range.start, range.endInclusive)
    if (shapes.isEmpty()) return StrokeAdjustment(width, shapes)
    val id = shapes.last().id
    return StrokeAdjustment(
        width,
        shapes.map { shape ->
            if (shape.id == id) shape.copy(stroke = width, color = color) else shape
        },
    )
}

fun strokeSliderValue(
    shapes: List<ShapeStamp>,
    draft: Float,
    range: ClosedFloatingPointRange<Float> = StrokeSliderRange,
): Float {
    val raw = shapes.lastOrNull()?.stroke ?: draft
    return raw.coerceIn(range.start, range.endInclusive)
}

/**
 * Same contract as [adjustStroke]: with nothing selected, the size draft still
 * changes so the slider can move before the first caption exists.
 */
fun adjustTextSize(
    texts: List<TextStamp>,
    selectedId: String?,
    requested: Float,
    range: ClosedFloatingPointRange<Float> = TextSliderRange,
): TextSizeAdjustment {
    val size = requested.coerceIn(range.start, range.endInclusive)
    if (selectedId == null || texts.none { it.id == selectedId }) {
        return TextSizeAdjustment(size, texts)
    }
    return TextSizeAdjustment(
        size,
        texts.map { stamp -> if (stamp.id == selectedId) stamp.copy(size = size) else stamp },
    )
}

fun textSizeSliderValue(
    texts: List<TextStamp>,
    selectedId: String?,
    draft: Float,
    range: ClosedFloatingPointRange<Float> = TextSliderRange,
): Float {
    val raw = texts.firstOrNull { it.id == selectedId }?.size ?: draft
    return raw.coerceIn(range.start, range.endInclusive)
}

fun cropSliderValue(width: Float, range: ClosedFloatingPointRange<Float> = CropSliderRange): Float =
    width.coerceIn(range.start, range.endInclusive)

fun timelineSpanMs(durationMs: Long): Long = durationMs.coerceAtLeast(1L)

fun timelineSliderRange(durationMs: Long): ClosedFloatingPointRange<Float> {
    val span = timelineSpanMs(durationMs).toFloat()
    return 0f..span
}

fun timelineSliderValue(positionMs: Long, durationMs: Long): Float {
    val span = timelineSpanMs(durationMs).toFloat()
    return positionMs.toFloat().coerceIn(0f, span)
}

fun playheadPosition(requestedMs: Long, durationMs: Long): Long =
    requestedMs.coerceIn(0L, timelineSpanMs(durationMs))
