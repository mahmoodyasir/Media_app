package com.mediaapp.editor

enum class FilterPreset(val label: String) {
    None("None"),
    Vivid("Vivid"),
    Mono("Mono"),
    Warm("Warm"),
    Cool("Cool"),
    Vintage("Vintage"),
    Soft("Soft"),
    Sharp("Sharp"),
    Fade("Fade"),
}

data class RgbScale(val red: Float, val green: Float, val blue: Float)

data class HslShift(val hueDegrees: Float, val saturation: Float, val lightness: Float)

data class VideoLook(
    val grayscale: Boolean = false,
    val rgb: RgbScale? = null,
    val hsl: HslShift? = null,
    val contrast: Float? = null,
)

object FilterMath {
    fun matrix(preset: FilterPreset): FloatArray = when (preset) {
        FilterPreset.None -> identity()
        FilterPreset.Vivid -> multiply(saturation(1.45f), contrast(1.08f, 0f))
        FilterPreset.Mono -> saturation(0f)
        FilterPreset.Warm -> scale(1.18f, 1.04f, 0.86f)
        FilterPreset.Cool -> scale(0.88f, 1.02f, 1.18f)
        FilterPreset.Vintage -> multiply(sepia(), contrast(0.92f, 8f))
        FilterPreset.Soft -> multiply(saturation(0.85f), contrast(0.9f, 12f))
        FilterPreset.Sharp -> contrast(1.22f, -6f)
        FilterPreset.Fade -> multiply(saturation(0.72f), contrast(0.82f, 28f))
    }

    fun sharpens(preset: FilterPreset): Boolean = preset == FilterPreset.Sharp

    fun videoLook(preset: FilterPreset): VideoLook = when (preset) {
        FilterPreset.None -> VideoLook()
        FilterPreset.Vivid -> VideoLook(hsl = HslShift(0f, 0.45f, 0.04f), contrast = 0.12f)
        FilterPreset.Mono -> VideoLook(grayscale = true)
        FilterPreset.Warm -> VideoLook(rgb = RgbScale(1.18f, 1.04f, 0.86f))
        FilterPreset.Cool -> VideoLook(rgb = RgbScale(0.88f, 1.02f, 1.18f))
        FilterPreset.Vintage -> VideoLook(hsl = HslShift(-12f, -0.25f, 0.02f), contrast = -0.05f)
        FilterPreset.Soft -> VideoLook(hsl = HslShift(0f, -0.1f, 0.08f), contrast = -0.12f)
        FilterPreset.Sharp -> VideoLook(contrast = 0.4f, hsl = HslShift(0f, 0.08f, 0f))
        FilterPreset.Fade -> VideoLook(hsl = HslShift(0f, -0.28f, 0.12f), contrast = -0.18f)
    }

    fun apply(matrix: FloatArray, red: Int, green: Int, blue: Int): IntArray {
        val r = red.toFloat()
        val g = green.toFloat()
        val b = blue.toFloat()
        return intArrayOf(
            channel(matrix[0] * r + matrix[1] * g + matrix[2] * b + matrix[4]),
            channel(matrix[5] * r + matrix[6] * g + matrix[7] * b + matrix[9]),
            channel(matrix[10] * r + matrix[11] * g + matrix[12] * b + matrix[14]),
        )
    }

    fun identity(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    fun saturation(amount: Float): FloatArray {
        val inv = 1f - amount
        val r = 0.213f * inv
        val g = 0.715f * inv
        val b = 0.072f * inv
        return floatArrayOf(
            r + amount, g, b, 0f, 0f,
            r, g + amount, b, 0f, 0f,
            r, g, b + amount, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    fun contrast(scale: Float, shift: Float): FloatArray = floatArrayOf(
        scale, 0f, 0f, 0f, shift,
        0f, scale, 0f, 0f, shift,
        0f, 0f, scale, 0f, shift,
        0f, 0f, 0f, 1f, 0f,
    )

    fun scale(red: Float, green: Float, blue: Float): FloatArray = floatArrayOf(
        red, 0f, 0f, 0f, 0f,
        0f, green, 0f, 0f, 0f,
        0f, 0f, blue, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    fun sepia(): FloatArray = floatArrayOf(
        0.393f, 0.769f, 0.189f, 0f, 0f,
        0.349f, 0.686f, 0.168f, 0f, 0f,
        0.272f, 0.534f, 0.131f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    fun multiply(left: FloatArray, right: FloatArray): FloatArray {
        val out = FloatArray(20)
        for (row in 0 until 4) {
            for (col in 0 until 5) {
                out[row * 5 + col] = if (col == 4) {
                    left[row * 5 + 0] * right[4] +
                        left[row * 5 + 1] * right[9] +
                        left[row * 5 + 2] * right[14] +
                        left[row * 5 + 3] * right[19] +
                        left[row * 5 + 4]
                } else {
                    left[row * 5 + 0] * right[col] +
                        left[row * 5 + 1] * right[5 + col] +
                        left[row * 5 + 2] * right[10 + col] +
                        left[row * 5 + 3] * right[15 + col]
                }
            }
        }
        return out
    }

    private fun channel(value: Float): Int = value.toInt().coerceIn(0, 255)
}
