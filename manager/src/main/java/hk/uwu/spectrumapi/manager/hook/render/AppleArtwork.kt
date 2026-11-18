// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0
// Modified for SpectrumAPI: relocated display-only helper; no capture backend.
package hk.uwu.spectrumapi.manager.hook.render

import kotlin.math.sqrt

/** Private artwork image, before the view-space blur and bar mask. */
internal object AppleArtwork {
    const val SIZE = 48
    const val COUNT = SIZE * SIZE

    @JvmRecord
    data class Treatment(val luminance: Double, val saturation: Double, val black: Double, val white: Double)

    @JvmStatic
    fun treatment(pixels: IntArray): Treatment {
        if (pixels.isEmpty()) throw IllegalArgumentException("Empty artwork")
        val analysis = Analysis()
        analysis.add(pixels, pixels.size)
        return analysis.treatment()
    }

    /** Full source pixels, before the independent display-sized color field. */
    class Analysis {
        private var total = 0.0
        private var count = 0L
        private var hash = 0xcbf29ce484222325UL.toLong()

        fun add(pixels: IntArray, length: Int) {
            if (length < 0 || length > pixels.size) throw IllegalArgumentException("Invalid pixel count")
            for (i in 0 until length) {
                val p = pixels[i]
                val a = (p ushr 24) / 255.0
                val r = ((p ushr 16) and 255) * a / 255
                val g = ((p ushr 8) and 255) * a / 255
                val b = (p and 255) * a / 255
                total += sqrt(0.299 * r * r + 0.587 * g * g + 0.114 * b * b)
                hash = hash xor (p.toLong() and 0xffffffffL)
                hash *= 0x100000001b3L
            }
            count += length
        }

        fun treatment(): Treatment {
            if (count == 0L) throw IllegalArgumentException("Empty artwork")
            val l = total / count
            val white = maxOf(0.0, 0.6 - l)
            return Treatment(l, 1.25 + 4 * white, maxOf(0.0, l - 0.7), white)
        }

        fun fingerprint(): Long = hash
    }

    @JvmStatic fun fallback(): IntArray = IntArray(COUNT).apply { fill(0xff999999.toInt()) }

    @JvmStatic
    fun field(pixels: IntArray): IntArray {
        if (pixels.size != COUNT) throw IllegalArgumentException("Expected private 48x48 artwork")
        return field(pixels, treatment(pixels))
    }

    @JvmStatic
    fun field(pixels: IntArray, t: Treatment): IntArray {
        if (pixels.size != COUNT) throw IllegalArgumentException("Expected private 48x48 artwork")
        val result = IntArray(COUNT)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p ushr 24) / 255.0
            val r = (p ushr 16) and 255
            val g = (p ushr 8) and 255
            val b = p and 255
            // SwiftUI Image reference uses the RGB saturation matrix in its
            // 8-bit image pass; Color views have a different optimized path.
            val y = 0.213 * r + 0.715 * g + 0.072 * b
            val rr = channel(y + (r - y) * t.saturation, a, t)
            val gg = channel(y + (g - y) * t.saturation, a, t)
            val bb = channel(y + (b - y) * t.saturation, a, t)
            val alpha = 1 - (1 - a) * (1 - t.black) * (1 - t.white)
            result[i] = (Math.round(alpha * 255).toInt() shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        return result
    }

    private fun channel(value: Double, alpha: Double, t: Treatment): Int {
        val a = 1 - (1 - alpha) * (1 - t.black) * (1 - t.white)
        // Match the measured Image pass quantization before scrim overlays.
        val saturated = Math.round(maxOf(0.0, minOf(255.0, value))).toDouble()
        val premultiplied = saturated * alpha * (1 - t.black) * (1 - t.white) + 255 * t.white
        return if (a == 0.0) 0 else Math.round(maxOf(0.0, minOf(255.0, premultiplied / a))).toInt()
    }
}
