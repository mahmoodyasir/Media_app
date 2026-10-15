package com.mediaapp.editor

import com.mediaapp.editor.image.CollageTemplate
import com.mediaapp.editor.image.CropAspect
import com.mediaapp.editor.image.centeredCrop
import com.mediaapp.editor.image.collageFrames
import com.mediaapp.editor.image.ndcCenterCrop
import com.mediaapp.editor.image.orientedAspect
import com.mediaapp.editor.video.AudioWindow
import com.mediaapp.editor.video.ClipRange
import com.mediaapp.editor.video.TimedMark
import com.mediaapp.editor.video.OverlayKind
import com.mediaapp.editor.video.VideoPlan
import com.mediaapp.editor.video.clampedAudio
import com.mediaapp.editor.video.markSpan
import com.mediaapp.editor.video.timelineDuration
import com.mediaapp.editor.video.validatePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorLogicTest {
    @Test
    fun monoMatrixCollapsesChannels() {
        val matrix = FilterMath.matrix(FilterPreset.Mono)
        val pixel = FilterMath.apply(matrix, 255, 0, 0)
        assertEquals(pixel[0], pixel[1])
        assertEquals(pixel[1], pixel[2])
        val identity = FilterMath.apply(FilterMath.matrix(FilterPreset.None), 10, 20, 30)
        assertEquals(10, identity[0])
        assertEquals(20, identity[1])
        assertEquals(30, identity[2])
    }

    @Test
    fun warmBoostsRedAndCoolBoostsBlue() {
        val warm = FilterMath.videoLook(FilterPreset.Warm)
        val cool = FilterMath.videoLook(FilterPreset.Cool)
        assertTrue(warm.rgb!!.red > warm.rgb.blue)
        assertTrue(cool.rgb!!.blue > cool.rgb.red)
        assertTrue(FilterMath.videoLook(FilterPreset.Mono).grayscale)
        assertTrue(FilterMath.sharpens(FilterPreset.Sharp))
        assertFalse(FilterMath.sharpens(FilterPreset.Fade))
    }

    @Test
    fun squareCropInsetsAWideFrame() {
        val crop = centeredCrop(imageAspect = 2f, targetAspect = CropAspect.Square.ratio)
        assertEquals(0.25f, crop.left, 0.001f)
        assertEquals(0.75f, crop.right, 0.001f)
        assertEquals(0f, crop.top, 0.001f)
        assertEquals(1f, crop.bottom, 0.001f)
        val ndc = ndcCenterCrop(2f, 1f)
        assertEquals(-0.5f, ndc[0], 0.001f)
        assertEquals(0.5f, ndc[1], 0.001f)
        assertEquals(-1f, ndc[2], 0.001f)
        assertEquals(1f, ndc[3], 0.001f)
        assertEquals(0.5f, orientedAspect(2f, 1), 0.001f)
    }

    @Test
    fun collageTemplatesHaveTheRightSlotCount() {
        assertEquals(2, collageFrames(CollageTemplate.SideBySide).size)
        assertEquals(2, collageFrames(CollageTemplate.Stack).size)
        assertEquals(3, collageFrames(CollageTemplate.Three).size)
        assertEquals(4, collageFrames(CollageTemplate.Grid).size)
        val grid = collageFrames(CollageTemplate.Grid)
        assertEquals(0.5f, grid[3].left, 0.001f)
        assertEquals(0.5f, grid[3].top, 0.001f)
    }

    @Test
    fun undoRedoRestoresDocuments() {
        val stack = UndoStack(0)
        stack.push(1)
        stack.push(2)
        assertTrue(stack.undo())
        assertEquals(1, stack.current)
        assertTrue(stack.redo())
        assertEquals(2, stack.current)
        stack.beginGesture()
        stack.preview(9)
        stack.preview(8)
        stack.endGesture()
        assertEquals(8, stack.current)
        stack.undo()
        assertEquals(2, stack.current)
    }

    @Test
    fun timelineSumsClipsAndRejectsEmptyTrims() {
        val clips = listOf(ClipRange(0, 4_000), ClipRange(1_000, 3_000))
        assertEquals(6_000L, timelineDuration(clips))
        val valid = VideoPlan(clips = clips, audio = AudioWindow(500, 2_000))
        assertNull(validatePlan(valid))
        assertNotNull(validatePlan(valid.copy(clips = listOf(ClipRange(2_000, 2_000)))))
        val audio = clampedAudio(AudioWindow(100, 9_000), 5_000)
        assertEquals(100L, audio.startMs)
        assertEquals(5_000L, audio.endMs)
        val whole = TimedMark(
            id = "t",
            kind = OverlayKind.Text,
            text = "Hi",
            startMs = 10,
            endMs = 20,
            nx = 0.5f,
            ny = 0.5f,
            nw = 0.2f,
            nh = 0.1f,
            color = 0,
            wholeClip = true,
        )
        assertEquals(0L to 6_000L, markSpan(whole, 6_000))
    }
}
