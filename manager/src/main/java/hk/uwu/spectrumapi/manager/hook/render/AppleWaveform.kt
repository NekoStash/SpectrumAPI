// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0
// Modified for SpectrumAPI: relocated display-only helper; no capture backend.
package hk.uwu.spectrumapi.manager.hook.render

/** Independently implemented formulas recovered from the local MediaCoreUI reference. */
internal object AppleWaveform {
    const val SAMPLE_MS = 80f
    const val PAUSE_MS = 900f

    @JvmStatic
    fun compactExtent(slotWidth: Float, slotHeight: Float): Float {
        // Fit the reference square to the host icon slot. Its logical16-point
        // size defines proportions, not an additional Android content inset.
        return maxOf(0f, minOf(slotWidth, slotHeight))
    }

    // SwiftUI's measured physical blur sigma, scaled with the compact square.
    @JvmStatic fun artworkBlur(extent: Float): Float = extent * (6f / 16)

    @JvmStatic
    fun artworkBlurRadius(extent: Float): Float {
        // RenderEffect accepts HWUI radius, not Gaussian sigma. HWUI converts
        // radius to sigma with radius * 0.57735 + 0.5 (utils/Blur.cpp).
        // Passing SwiftUI's value directly makes colored regions too distinct.
        val sigma = artworkBlur(extent)
        return if (sigma > 0.5f) (sigma - 0.5f) / 0.57735f else 0f
    }

    @JvmStatic fun baseWidth(width: Float): Float = width / 8 * 5 / 6
    @JvmStatic fun width(base: Float, level: Float): Float = base * (1 + 0.12f * level)
    @JvmStatic fun height(base: Float, height: Float, level: Float): Float = maxOf(base, minOf(height, height * level))

    @JvmStatic
    fun left(width: Float, index: Int, currentWidth: Float): Float {
        val maxWidth = baseWidth(width) * 1.12f
        // Six-bar frequency table has seven boundaries. MediaCoreUI divides
        // remaining space by boundaryCount-2 and places each bar in a max-width slot.
        val gap = (width - 6 * maxWidth) / 5
        return index * (maxWidth + gap) + (maxWidth - currentWidth) / 2
    }

    @JvmStatic fun radius(width: Float): Float = Math.round(width / 2).toFloat()

    @JvmStatic
    fun ease(fraction: Float, pause: Boolean): Float {
        val x = maxOf(0f, minOf(1f, fraction))
        // Solve the timing curve's x(t), then evaluate y(t). Using the time
        // fraction directly as t changes both Apple's easeOut and pause timing.
        var lo = 0.0
        var hi = 1.0
        val x1 = if (pause) 0.33 else 0.0
        val x2 = if (pause) 0.67 else 0.58
        repeat(24) {
            val t = (lo + hi) / 2
            val u = 1 - t
            val bx = 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t
            if (bx < x) lo = t else hi = t
        }
        val t = (lo + hi) / 2
        val u = 1 - t
        // easeOut=(0,0,0.58,1); pause=(0.33,1,0.67,1).
        return if (x == 0f) 0f else if (x == 1f) 1f
        else ((if (pause) 3 * u * u * t else 0.0) + 3 * u * t * t + t * t * t).toFloat()
    }
}
