package com.mediaapp.editor.video

import com.mediaapp.editor.FilterPreset
import com.mediaapp.editor.image.ShapeKind

enum class FrameAspect(val label: String, val ratio: Float?) {
    Original("Original", null),
    Square("1:1", 1f),
    FourThree("4:3", 4f / 3f),
    SixteenNine("16:9", 16f / 9f),
}

data class ClipRange(
    val startMs: Long,
    val endMs: Long,
) {
    val lengthMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

data class AudioWindow(
    val startMs: Long,
    val endMs: Long,
)

enum class OverlayKind { Text, Rectangle, Circle, Line, Arrow }

data class TimedMark(
    val id: String,
    val kind: OverlayKind,
    val text: String = "",
    val shape: ShapeKind? = null,
    val startMs: Long,
    val endMs: Long,
    val nx: Float,
    val ny: Float,
    val nw: Float,
    val nh: Float,
    val color: Int,
    val wholeClip: Boolean,
)

data class VideoPlan(
    val clips: List<ClipRange>,
    val filter: FilterPreset = FilterPreset.None,
    val aspect: FrameAspect = FrameAspect.Original,
    val removeAudio: Boolean = false,
    val audio: AudioWindow? = null,
    val marks: List<TimedMark> = emptyList(),
)

fun timelineDuration(clips: List<ClipRange>): Long = clips.sumOf { it.lengthMs }

fun clampedAudio(window: AudioWindow, timelineMs: Long): AudioWindow {
    val endCap = timelineMs.coerceAtLeast(0L)
    val start = window.startMs.coerceIn(0L, endCap)
    val end = window.endMs.coerceIn(start, endCap)
    return AudioWindow(start, end)
}

fun markSpan(mark: TimedMark, timelineMs: Long): Pair<Long, Long> {
    if (mark.wholeClip) return 0L to timelineMs
    val start = mark.startMs.coerceIn(0L, timelineMs)
    val end = mark.endMs.coerceIn(start, timelineMs)
    return start to end
}

fun validatePlan(plan: VideoPlan): String? {
    if (plan.clips.isEmpty()) return "Add at least one clip"
    plan.clips.forEachIndexed { index, clip ->
        if (clip.endMs <= clip.startMs) return "Clip ${index + 1} ends before it starts"
        if (clip.startMs < 0L) return "Clip ${index + 1} starts before zero"
    }
    val duration = timelineDuration(plan.clips)
    if (duration <= 0L) return "The timeline is empty"
    plan.audio?.let { audio ->
        if (audio.endMs <= audio.startMs) return "Audio range is empty"
    }
    return null
}
