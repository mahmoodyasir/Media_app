package com.mediaapp.editor

import com.mediaapp.editor.image.ShapeKind
import com.mediaapp.editor.image.ShapeStamp
import com.mediaapp.editor.image.TextStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorSliderTest {
    @Test
    fun strokeDragMovesWhenNoShapeHasBeenAdded() {
        val shapes = emptyList<ShapeStamp>()
        var draft = DefaultStroke
        assertEquals(DefaultStroke, strokeSliderValue(shapes, draft), 0.0001f)

        val adjusted = adjustStroke(shapes, 0.04f, color = 0xFFD6F25C.toInt())
        draft = adjusted.width
        assertEquals(0.04f, draft, 0.0001f)
        assertTrue(adjusted.shapes.isEmpty())
        assertEquals(0.04f, strokeSliderValue(adjusted.shapes, draft), 0.0001f)
        assertTrue(strokeSliderValue(adjusted.shapes, draft) in StrokeSliderRange)
    }

    @Test
    fun strokeDragUpdatesTheLastShapeAndClampsIntoRange() {
        val shapes = listOf(
            shape("a", ShapeKind.Rectangle, 0.012f),
            shape("b", ShapeKind.Arrow, 0.012f),
        )
        val adjusted = adjustStroke(shapes, 0.9f, color = 7)
        assertEquals(StrokeSliderRange.endInclusive, adjusted.width, 0.0001f)
        assertEquals(0.012f, adjusted.shapes[0].stroke, 0.0001f)
        assertEquals(1, adjusted.shapes[0].color)
        assertEquals(StrokeSliderRange.endInclusive, adjusted.shapes[1].stroke, 0.0001f)
        assertEquals(7, adjusted.shapes[1].color)
        assertEquals(adjusted.width, strokeSliderValue(adjusted.shapes, DefaultStroke), 0.0001f)
    }

    @Test
    fun strokeBelowTheRangeSnapsToTheStartInsteadOfStickingOutsideIt() {
        val adjusted = adjustStroke(emptyList(), -1f, color = 1)
        assertEquals(StrokeSliderRange.start, adjusted.width, 0.0001f)
        assertEquals(StrokeSliderRange.start, strokeSliderValue(adjusted.shapes, adjusted.width), 0.0001f)
    }

    @Test
    fun textSizeDragMovesWhenNothingIsSelected() {
        var draft = DefaultTextSize
        val adjusted = adjustTextSize(emptyList(), selectedId = null, requested = 0.16f)
        draft = adjusted.size
        assertEquals(0.16f, draft, 0.0001f)
        assertTrue(adjusted.texts.isEmpty())
        assertEquals(0.16f, textSizeSliderValue(adjusted.texts, null, draft), 0.0001f)
    }

    @Test
    fun textSizeDragUpdatesOnlyTheSelectedCaption() {
        val texts = listOf(
            TextStamp("a", "Hi", 0.1f, 0.2f, 0.07f, 1),
            TextStamp("b", "There", 0.2f, 0.3f, 0.07f, 1),
        )
        val adjusted = adjustTextSize(texts, selectedId = "b", requested = 0.5f)
        assertEquals(TextSliderRange.endInclusive, adjusted.size, 0.0001f)
        assertEquals(0.07f, adjusted.texts[0].size, 0.0001f)
        assertEquals(TextSliderRange.endInclusive, adjusted.texts[1].size, 0.0001f)
        assertEquals(adjusted.size, textSizeSliderValue(adjusted.texts, "b", DefaultTextSize), 0.0001f)
    }

    @Test
    fun missingTextSelectionDoesNotFreezeTheSizeSlider() {
        val texts = listOf(TextStamp("a", "Hi", 0.1f, 0.2f, 0.07f, 1))
        val adjusted = adjustTextSize(texts, selectedId = "gone", requested = 0.12f)
        assertEquals(0.12f, adjusted.size, 0.0001f)
        assertEquals(0.07f, adjusted.texts[0].size, 0.0001f)
        assertEquals(0.12f, textSizeSliderValue(adjusted.texts, "gone", adjusted.size), 0.0001f)
    }

    @Test
    fun cropAndTimelineValuesStayInsideTheirRanges() {
        assertEquals(CropSliderRange.start, cropSliderValue(0.01f), 0.0001f)
        assertEquals(1f, cropSliderValue(2f), 0.0001f)
        assertEquals(0.5f, cropSliderValue(0.5f), 0.0001f)

        assertEquals(0f, timelineSliderValue(-40L, 8_000L), 0.0001f)
        assertEquals(8_000f, timelineSliderValue(99_000L, 8_000L), 0.0001f)
        assertEquals(0f..1f, timelineSliderRange(0L))
        assertEquals(0L, playheadPosition(-5L, 1_500L))
        assertEquals(1_500L, playheadPosition(9_000L, 1_500L))
        assertEquals(40L, playheadPosition(40L, 1_500L))
    }

    private fun shape(id: String, kind: ShapeKind, stroke: Float) = ShapeStamp(
        id = id,
        kind = kind,
        nx = 0.2f,
        ny = 0.3f,
        nw = 0.4f,
        nh = 0.2f,
        color = 1,
        stroke = stroke,
    )
}
