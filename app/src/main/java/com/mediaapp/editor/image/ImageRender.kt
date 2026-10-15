package com.mediaapp.editor.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.mediaapp.editor.FilterMath
import com.mediaapp.editor.FilterPreset
import kotlin.math.min

fun renderEdited(source: Bitmap, document: ImageDocument, bakeOverlays: Boolean): Bitmap {
    val oriented = orient(source, document)
    val cropped = cropBitmap(oriented, document.crop)
    val colored = applyMatrix(cropped, FilterMath.matrix(document.filter))
    val sharpened = if (FilterMath.sharpens(document.filter)) sharpen(colored) else colored
    val result = if (bakeOverlays) drawMarks(sharpened, document.texts, document.shapes) else sharpened
    recycleUnless(oriented, source, result)
    recycleUnless(cropped, source, result, oriented)
    recycleUnless(colored, source, result, oriented, cropped)
    recycleUnless(sharpened, source, result, oriented, cropped, colored)
    return result
}

fun renderCollage(
    slots: List<Bitmap?>,
    template: CollageTemplate,
    filter: FilterPreset,
    size: Int = 1440,
): Bitmap {
    val canvasBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(canvasBitmap)
    canvas.drawColor(0xFF12141A.toInt())
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    collageFrames(template).forEachIndexed { index, frame ->
        val bitmap = slots.getOrNull(index) ?: return@forEachIndexed
        val dest = Rect(
            (frame.left * size).toInt(),
            (frame.top * size).toInt(),
            (frame.right * size).toInt(),
            (frame.bottom * size).toInt(),
        )
        val src = centerSource(bitmap.width, bitmap.height, dest.width(), dest.height())
        canvas.drawBitmap(bitmap, src, dest, paint)
    }
    val colored = applyMatrix(canvasBitmap, FilterMath.matrix(filter))
    val result = if (FilterMath.sharpens(filter)) sharpen(colored) else colored
    recycleUnless(canvasBitmap, result)
    recycleUnless(colored, result, canvasBitmap)
    return result
}

private fun orient(source: Bitmap, document: ImageDocument): Bitmap {
    if (document.quarterTurns % 4 == 0 && !document.flipHorizontal && !document.flipVertical) return source
    val matrix = Matrix()
    if (document.flipHorizontal || document.flipVertical) {
        matrix.postScale(if (document.flipHorizontal) -1f else 1f, if (document.flipVertical) -1f else 1f)
    }
    matrix.postRotate((document.quarterTurns % 4) * 90f)
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}

private fun cropBitmap(source: Bitmap, crop: NormRect): Bitmap {
    val rect = crop.clamp()
    if (rect.left <= 0.001f && rect.top <= 0.001f && rect.right >= 0.999f && rect.bottom >= 0.999f) return source
    val x = (rect.left * source.width).toInt().coerceIn(0, source.width - 1)
    val y = (rect.top * source.height).toInt().coerceIn(0, source.height - 1)
    val width = (rect.width * source.width).toInt().coerceAtLeast(1).coerceAtMost(source.width - x)
    val height = (rect.height * source.height).toInt().coerceAtLeast(1).coerceAtMost(source.height - y)
    return Bitmap.createBitmap(source, x, y, width, height)
}

private fun applyMatrix(source: Bitmap, matrix: FloatArray): Bitmap {
    if (matrix.contentEquals(FilterMath.identity())) return source
    val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) }
    canvas.drawBitmap(source, 0f, 0f, paint)
    return out
}

private fun sharpen(source: Bitmap): Bitmap {
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    val out = IntArray(pixels.size)
    for (y in 0 until height) {
        for (x in 0 until width) {
            var red = 0
            var green = 0
            var blue = 0
            for (ky in -1..1) {
                for (kx in -1..1) {
                    val px = (x + kx).coerceIn(0, width - 1)
                    val py = (y + ky).coerceIn(0, height - 1)
                    val color = pixels[py * width + px]
                    val weight = if (kx == 0 && ky == 0) 5 else if (kx == 0 || ky == 0) -1 else 0
                    red += weight * ((color shr 16) and 0xFF)
                    green += weight * ((color shr 8) and 0xFF)
                    blue += weight * (color and 0xFF)
                }
            }
            val alpha = pixels[y * width + x] ushr 24
            out[y * width + x] = (alpha shl 24) or
                (red.coerceIn(0, 255) shl 16) or
                (green.coerceIn(0, 255) shl 8) or
                blue.coerceIn(0, 255)
        }
    }
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
        it.setPixels(out, 0, width, 0, 0, width, height)
    }
}

fun drawMarks(source: Bitmap, texts: List<TextStamp>, shapes: List<ShapeStamp>): Bitmap {
    if (texts.isEmpty() && shapes.isEmpty()) return source
    val out = source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(out)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    shapes.forEach { shape ->
        paint.color = shape.color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (shape.stroke.coerceIn(0.004f, 0.08f) * min(out.width, out.height)).coerceAtLeast(2f)
        val left = shape.nx * out.width
        val top = shape.ny * out.height
        val right = (shape.nx + shape.nw) * out.width
        val bottom = (shape.ny + shape.nh) * out.height
        when (shape.kind) {
            ShapeKind.Rectangle -> canvas.drawRect(left, top, right, bottom, paint)
            ShapeKind.Circle -> canvas.drawOval(RectF(left, top, right, bottom), paint)
            ShapeKind.Line -> canvas.drawLine(left, top, right, bottom, paint)
            ShapeKind.Arrow -> {
                canvas.drawLine(left, top, right, bottom, paint)
                val angle = kotlin.math.atan2((bottom - top).toDouble(), (right - left).toDouble())
                val head = paint.strokeWidth * 4f
                val a1 = angle + Math.PI * 0.75
                val a2 = angle - Math.PI * 0.75
                canvas.drawLine(right, bottom, right + (head * kotlin.math.cos(a1)).toFloat(), bottom + (head * kotlin.math.sin(a1)).toFloat(), paint)
                canvas.drawLine(right, bottom, right + (head * kotlin.math.cos(a2)).toFloat(), bottom + (head * kotlin.math.sin(a2)).toFloat(), paint)
            }
        }
    }
    texts.forEach { stamp ->
        paint.style = Paint.Style.FILL
        paint.color = stamp.color
        paint.textSize = (stamp.size.coerceIn(0.03f, 0.22f) * out.height)
        canvas.drawText(stamp.text, stamp.nx * out.width, stamp.ny * out.height, paint)
    }
    return out
}

private fun centerSource(srcW: Int, srcH: Int, destW: Int, destH: Int): Rect {
    if (srcW <= 0 || srcH <= 0 || destW <= 0 || destH <= 0) return Rect(0, 0, srcW, srcH)
    val srcAspect = srcW.toFloat() / srcH
    val destAspect = destW.toFloat() / destH
    return if (srcAspect > destAspect) {
        val width = (srcH * destAspect).toInt().coerceAtLeast(1)
        val left = (srcW - width) / 2
        Rect(left, 0, left + width, srcH)
    } else {
        val height = (srcW / destAspect).toInt().coerceAtLeast(1)
        val top = (srcH - height) / 2
        Rect(0, top, srcW, top + height)
    }
}

private fun recycleUnless(bitmap: Bitmap, vararg keep: Bitmap) {
    if (keep.any { it === bitmap }) return
    if (!bitmap.isRecycled) bitmap.recycle()
}
