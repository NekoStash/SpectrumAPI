// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0
// Modified for SpectrumAPI: relocated display-only helper; no capture backend.

package hk.uwu.spectrumapi.manager.hook.render

import kotlin.math.roundToInt

/** Deterministic spatial cover colors; every source pixel contributes to the full field. */
internal object CoverColors {
    const val SIZE = 12
    const val COUNT = SIZE * SIZE
    const val SAMPLE_SIZE = 4
    const val TOP_DEFAULT = 0xeedddddd.toInt()
    const val BOTTOM_DEFAULT = 0xcc999999.toInt()
    private const val GRAY_OVERLAY = 0.32
    private const val NEUTRAL_GRAY = 153.0

    @JvmStatic
    fun fingerprint(pixels: IntArray): Long {
        var hash = 0xcbf29ce484222325UL.toLong()
        for (pixel in pixels) {
            hash = hash xor (pixel.toLong() and 0xffffffffL)
            hash *= 0x100000001b3L
        }
        return hash
    }

    @JvmStatic fun fallback(): IntArray = gradient(TOP_DEFAULT, BOTTOM_DEFAULT)

    @JvmStatic
    fun gradient(top: Int, bottom: Int): IntArray {
        val colors = IntArray(COUNT)
        for (y in 0 until SIZE) {
            val color = blend(top, bottom, y / (SIZE - 1).toFloat())
            colors.fill(color, y * SIZE, (y + 1) * SIZE)
        }
        return colors
    }

    @JvmStatic
    fun field(pixels: IntArray, width: Int, height: Int): IntArray {
        if (width < SAMPLE_SIZE || height < SAMPLE_SIZE || width.toLong() * height != pixels.size.toLong()) {
            throw IllegalArgumentException("Invalid cover dimensions")
        }
        val samples = SAMPLE_SIZE * SAMPLE_SIZE
        val red = DoubleArray(samples)
        val green = DoubleArray(samples)
        val blue = DoubleArray(samples)
        val weight = DoubleArray(samples)
        // Area bins cover the entire artwork, including its edges. Alpha is premultiplied
        // before filtering so invisible RGB never contaminates neighboring visible colors.
        for (y in 0 until height) for (x in 0 until width) {
            val pixel = pixels[y * width + x]
            val alpha = (pixel ushr 24) / 255.0
            val cell = (y * SAMPLE_SIZE / height) * SAMPLE_SIZE + x * SAMPLE_SIZE / width
            weight[cell] += alpha
            red[cell] += ((pixel ushr 16) and 255) * alpha
            green[cell] += ((pixel ushr 8) and 255) * alpha
            blue[cell] += (pixel and 255) * alpha
        }
        // Filter broad 4x4 regions before interpolation. Fine text, texture, and small
        // color spots merge into their surrounding cover region instead of reaching bars.
        val softRed = DoubleArray(samples)
        val softGreen = DoubleArray(samples)
        val softBlue = DoubleArray(samples)
        val softWeight = DoubleArray(samples)
        for (y in 0 until SAMPLE_SIZE) for (x in 0 until SAMPLE_SIZE) {
            var r = 0.0
            var g = 0.0
            var b = 0.0
            var total = 0.0
            for (dy in -1..1) for (dx in -1..1) {
                val sx = maxOf(0, minOf(SAMPLE_SIZE - 1, x + dx))
                val sy = maxOf(0, minOf(SAMPLE_SIZE - 1, y + dy))
                val cell = sy * SAMPLE_SIZE + sx
                val kernel = (if (dx == 0) 2 else 1) * (if (dy == 0) 2 else 1)
                r += red[cell] * kernel
                g += green[cell] * kernel
                b += blue[cell] * kernel
                total += weight[cell] * kernel
            }
            val cell = y * SAMPLE_SIZE + x
            softRed[cell] = r
            softGreen[cell] = g
            softBlue[cell] = b
            softWeight[cell] = total
        }
        val colors = fallback()
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val sx = maxOf(0.0, minOf((SAMPLE_SIZE - 1).toDouble(), (x + 0.5) * SAMPLE_SIZE / SIZE - 0.5))
            val sy = maxOf(0.0, minOf((SAMPLE_SIZE - 1).toDouble(), (y + 0.5) * SAMPLE_SIZE / SIZE - 0.5))
            val total = sample(softWeight, sx, sy)
            if (total > 0) colors[y * SIZE + x] = visible(sample(softRed, sx, sy) / total,
                sample(softGreen, sx, sy) / total, sample(softBlue, sx, sy) / total)
        }
        return colors
    }

    private fun sample(values: DoubleArray, x: Double, y: Double): Double {
        val left = x.toInt()
        val top = y.toInt()
        val right = minOf(SAMPLE_SIZE - 1, left + 1)
        val bottom = minOf(SAMPLE_SIZE - 1, top + 1)
        val upper = values[top * SAMPLE_SIZE + left] * (1 - (x - left)) +
            values[top * SAMPLE_SIZE + right] * (x - left)
        val lower = values[bottom * SAMPLE_SIZE + left] * (1 - (x - left)) +
            values[bottom * SAMPLE_SIZE + right] * (x - left)
        return upper * (1 - (y - top)) + lower * (y - top)
    }

    private fun visible(r: Double, g: Double, b: Double): Int {
        val max = maxOf(r, maxOf(g, b))
        // Lift dark regions enough to remain legible on the island's black background.
        // Preserve the cover's hue without boosting saturation. A neutral gray veil
        // softens both chroma and contrast, while achromatic covers remain neutral.
        val value = maxOf(0.54, minOf(0.90, max / 255.0)) * 255
        val scale = if (max == 0.0) 0.0 else value / max
        val gray = NEUTRAL_GRAY * GRAY_OVERLAY
        val retained = 1 - GRAY_OVERLAY
        val rr = ((if (max == 0.0) value else r * scale) * retained + gray).roundToInt()
        val gg = ((if (max == 0.0) value else g * scale) * retained + gray).roundToInt()
        val bb = ((if (max == 0.0) value else b * scale) * retained + gray).roundToInt()
        return 0xee000000.toInt() or (rr shl 16) or (gg shl 8) or bb
    }

    @JvmStatic
    fun hyperOsField(pixels: IntArray): IntArray {
        val pair = extract(pixels)
        val colors = IntArray(COUNT)
        for (y in 0 until SIZE) {
            val color = blend(pair[0], pair[1], y / (SIZE - 1).toFloat())
            colors.fill(color, y * SIZE, (y + 1) * SIZE)
        }
        return colors
    }

    private fun extract(pixels: IntArray): IntArray {
        val weights = DoubleArray(4096)
        val red = DoubleArray(4096)
        val green = DoubleArray(4096)
        val blue = DoubleArray(4096)
        for (pixel in pixels) {
            if ((pixel ushr 24) < 128) continue
            val r = (pixel ushr 16) and 255
            val g = (pixel ushr 8) and 255
            val b = pixel and 255
            val max = maxOf(r, maxOf(g, b))
            val min = minOf(r, minOf(g, b))
            val saturation = if (max == 0) 0f else (max - min) / max.toFloat()
            if (max < 30 || saturation < 0.15f) continue
            val bucket = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            val weight = (0.4 + saturation) * ((pixel ushr 24) / 255.0)
            weights[bucket] += weight
            red[bucket] += r * weight
            green[bucket] += g * weight
            blue[bucket] += b * weight
        }
        var best = 0
        for (i in 1 until weights.size) if (weights[i] > weights[best]) best = i
        if (weights[best] == 0.0) return intArrayOf(TOP_DEFAULT, BOTTOM_DEFAULT)
        val r = (red[best] / weights[best] / 255).toFloat()
        val g = (green[best] / weights[best] / 255).toFloat()
        val b = (blue[best] / weights[best] / 255).toFloat()
        val max = maxOf(r, maxOf(g, b))
        val min = minOf(r, minOf(g, b))
        val delta = max - min
        var hue = if (max == r) (g - b) / delta else if (max == g) 2 + (b - r) / delta else 4 + (r - g) / delta
        if (hue < 0) hue += 6
        val saturation = minOf(0.8f, maxOf(0.45f, delta / max))
        return intArrayOf(hsv(0xee, hue, saturation * 0.85f, 0.94f), hsv(0xcc, hue, saturation, 0.68f))
    }

    private fun hsv(alpha: Int, hue: Float, saturation: Float, value: Float): Int {
        val sector = hue.toInt()
        val f = hue - sector
        val p = value * (1 - saturation)
        val q = value * (1 - saturation * f)
        val t = value * (1 - saturation * (1 - f))
        val values = when (sector) {
            0 -> floatArrayOf(value, t, p)
            1 -> floatArrayOf(q, value, p)
            2 -> floatArrayOf(p, value, t)
            3 -> floatArrayOf(p, q, value)
            4 -> floatArrayOf(t, p, value)
            else -> floatArrayOf(value, p, q)
        }
        return (alpha shl 24) or (Math.round(values[0] * 255) shl 16) or
            (Math.round(values[1] * 255) shl 8) or Math.round(values[2] * 255)
    }

    @JvmStatic fun distinct(colors: IntArray): Int = colors.toSet().size

    @JvmStatic
    fun blend(from: Int, to: Int, fraction: Float): Int {
        val clamped = maxOf(0f, minOf(1f, fraction))
        var color = 0
        for (shift in 0..24 step 8) {
            val a = (from ushr shift) and 255
            val b = (to ushr shift) and 255
            color = color or (Math.round(a + (b - a) * clamped) shl shift)
        }
        return color
    }
}
