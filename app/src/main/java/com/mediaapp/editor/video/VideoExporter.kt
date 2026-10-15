package com.mediaapp.editor.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Contrast
import androidx.media3.effect.Crop
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.mediaapp.editor.FilterMath
import com.mediaapp.editor.FilterPreset
import com.mediaapp.editor.image.ShapeKind
import com.mediaapp.editor.image.ndcCenterCrop
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class ExportClip(
    val uri: String,
    val startMs: Long,
    val endMs: Long,
)

data class ExportRequest(
    val clips: List<ExportClip>,
    val filter: FilterPreset,
    val aspect: FrameAspect,
    val sourceAspect: Float,
    val removeAudio: Boolean,
    val audioUri: String?,
    val audioStartMs: Long,
    val audioEndMs: Long,
    val marks: List<TimedMark>,
)

@OptIn(UnstableApi::class)
@Singleton
class VideoExporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var transformer: Transformer? = null

    fun start(request: ExportRequest, output: File, onDone: () -> Unit, onError: (String) -> Unit) {
        cancel()
        val composition = composition(request)
        val engine = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: androidx.media3.transformer.ExportResult) {
                    onDone()
                }

                override fun onError(
                    composition: Composition,
                    exportResult: androidx.media3.transformer.ExportResult,
                    exportException: ExportException,
                ) {
                    onError(exportException.message ?: "Export failed")
                }
            })
            .build()
        transformer = engine
        output.parentFile?.mkdirs()
        engine.start(composition, output.absolutePath)
    }

    fun progress(): Int {
        val holder = ProgressHolder()
        val state = transformer?.getProgress(holder) ?: return 0
        return if (state == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else 0
    }

    fun cancel() {
        transformer?.cancel()
        transformer = null
    }

    private fun composition(request: ExportRequest): Composition {
        var offset = 0L
        val items = request.clips.map { clip ->
            val length = (clip.endMs - clip.startMs).coerceAtLeast(1L)
            val overlays = overlaysForClip(request.marks, offset, length, timelineDuration(request.clips.map { ClipRange(it.startMs, it.endMs) }))
            offset += length
            val media = MediaItem.Builder()
                .setUri(clip.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.startMs)
                        .setEndPositionMs(clip.endMs)
                        .build(),
                )
                .build()
            EditedMediaItem.Builder(media)
                .setRemoveAudio(request.removeAudio || request.audioUri != null)
                .setEffects(Effects(emptyList(), effects(request, overlays)))
                .build()
        }
        val video = EditedMediaItemSequence.Builder().apply {
            items.forEach { addItem(it) }
        }.build()
        val audioUri = request.audioUri
        if (audioUri == null) return Composition.Builder(video).build()
        val span = (request.audioEndMs - request.audioStartMs).coerceAtLeast(1L)
        val audioMedia = MediaItem.Builder()
            .setUri(audioUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setEndPositionMs(span)
                    .build(),
            )
            .build()
        val audioItem = EditedMediaItem.Builder(audioMedia).setRemoveVideo(true).build()
        val audio = EditedMediaItemSequence.Builder().apply {
            if (request.audioStartMs > 0L) addGap(request.audioStartMs * 1_000L)
            addItem(audioItem)
        }.build()
        return Composition.Builder(listOf(video, audio)).build()
    }

    private fun effects(request: ExportRequest, overlays: List<BitmapOverlay>): List<Effect> {
        val effects = previewLookEffects(request.filter, request.aspect, request.sourceAspect).toMutableList()
        if (overlays.isNotEmpty()) effects += OverlayEffect(overlays)
        return effects
    }

    private fun overlaysForClip(
        marks: List<TimedMark>,
        offsetMs: Long,
        lengthMs: Long,
        timelineMs: Long,
    ): List<BitmapOverlay> {
        val clipEnd = offsetMs + lengthMs
        return marks.mapNotNull { mark ->
            val (start, end) = markSpan(mark, timelineMs)
            val overlapStart = maxOf(start, offsetMs)
            val overlapEnd = minOf(end, clipEnd)
            if (overlapEnd <= overlapStart) return@mapNotNull null
            timedOverlay(
                mark,
                startUs = (overlapStart - offsetMs) * 1_000L,
                endUs = (overlapEnd - offsetMs) * 1_000L,
            )
        }
    }

    private fun timedOverlay(mark: TimedMark, startUs: Long, endUs: Long): BitmapOverlay {
        val bitmap = raster(mark)
        val blank = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val cx = ((mark.nx + mark.nw / 2f).coerceIn(0f, 1f) * 2f) - 1f
        val cy = 1f - ((mark.ny + mark.nh / 2f).coerceIn(0f, 1f) * 2f)
        val settings = StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(cx, cy)
            .setOverlayFrameAnchor(0f, 0f)
            .setScale(mark.nw.coerceIn(0.05f, 1f), mark.nh.coerceIn(0.05f, 1f))
            .build()
        return object : BitmapOverlay() {
            override fun getBitmap(presentationTimeUs: Long): Bitmap {
                return if (presentationTimeUs in startUs until endUs) bitmap else blank
            }

            override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings
        }
    }

    private fun raster(mark: TimedMark): Bitmap {
        val bitmap = Bitmap.createBitmap(720, 405, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = mark.color
            style = Paint.Style.STROKE
            strokeWidth = 14f
        }
        val bounds = RectF(36f, 36f, bitmap.width - 36f, bitmap.height - 36f)
        when (mark.kind) {
            OverlayKind.Text -> {
                paint.style = Paint.Style.FILL
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = 72f
                canvas.drawText(mark.text.ifBlank { "Text" }, bitmap.width / 2f, bitmap.height / 2f, paint)
            }
            OverlayKind.Rectangle, OverlayKind.Line -> if (mark.shape == ShapeKind.Line || mark.kind == OverlayKind.Line) {
                canvas.drawLine(bounds.left, bounds.centerY(), bounds.right, bounds.centerY(), paint)
            } else {
                canvas.drawRect(bounds, paint)
            }
            OverlayKind.Circle -> canvas.drawOval(bounds, paint)
            OverlayKind.Arrow -> {
                canvas.drawLine(bounds.left, bounds.centerY(), bounds.right, bounds.centerY(), paint)
                canvas.drawLine(bounds.right, bounds.centerY(), bounds.right - 48f, bounds.centerY() - 36f, paint)
                canvas.drawLine(bounds.right, bounds.centerY(), bounds.right - 48f, bounds.centerY() + 36f, paint)
            }
        }
        return bitmap
    }
}

@OptIn(UnstableApi::class)
fun previewLookEffects(filter: FilterPreset, aspect: FrameAspect, sourceAspect: Float): List<Effect> {
    val look = FilterMath.videoLook(filter)
    val effects = mutableListOf<Effect>()
    if (look.grayscale) effects += RgbFilter.createGrayscaleFilter()
    look.rgb?.let {
        effects += RgbAdjustment.Builder()
            .setRedScale(it.red)
            .setGreenScale(it.green)
            .setBlueScale(it.blue)
            .build()
    }
    look.hsl?.let {
        effects += HslAdjustment.Builder()
            .adjustHue(it.hueDegrees)
            .adjustSaturation(it.saturation)
            .adjustLightness(it.lightness)
            .build()
    }
    look.contrast?.let { effects += Contrast(it) }
    aspect.ratio?.let { target ->
        if (sourceAspect > 0f) {
            val ndc = ndcCenterCrop(sourceAspect, target)
            effects += Crop(ndc[0], ndc[1], ndc[2], ndc[3])
        }
    }
    return effects
}
