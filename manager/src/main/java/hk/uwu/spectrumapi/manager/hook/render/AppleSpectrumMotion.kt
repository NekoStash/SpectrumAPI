// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0
package hk.uwu.spectrumapi.manager.hook.render

import hk.uwu.spectrumapi.manager.config.SettingsKeys

/** Retarget from the current presentation values; no second attack/release filter. */
// Modified for SpectrumAPI: standalone native presentation model; no legacy filter/base class.
internal class AppleSpectrumMotion {
    companion object {
        private const val DEFAULT_HEIGHT_KNEE = 0.40f
    }

    @JvmField val heights = FloatArray(6)
    private val from = FloatArray(6)
    private val to = FloatArray(6)
    private val heightFrom = FloatArray(6)
    private val heightTo = FloatArray(6)
    private val heightPresentation = FloatArray(6)
    private var floor = 0f
    private var heightKnee = DEFAULT_HEIGHT_KNEE
    private var playing = true
    // Stop-at-volume-0 settles with the pause curve but keeps the playing state.
    private var settling = false
    private var elapsed = AppleWaveform.SAMPLE_MS

    fun isPlaying(): Boolean = playing
    fun recovering(): Boolean = false

    fun amplitude(value: Float) {
        // Apply on the next measured frame without disturbing the current animation.
        heightKnee = DEFAULT_HEIGHT_KNEE / SettingsKeys.normalizeAmplitude(value)
    }

    fun geometry(width: Float, height: Float) {
        floor = if (height <= 0) 0f else minOf(1f, AppleWaveform.baseWidth(width) / height)
        for (i in 0 until 6) {
            heightFrom[i] = maxOf(floor, heightFrom[i])
            heightTo[i] = maxOf(floor, heightTo[i])
            heightPresentation[i] = maxOf(floor, heightPresentation[i])
        }
    }

    fun barHeight(index: Int, available: Float): Float = heightPresentation[index] * available

    fun inheritPresentation(source: AppleSpectrumMotion) {
        source.heights.copyInto(heights, endIndex = 6)
        for (i in 0 until 6) heightPresentation[i] = maxOf(floor, source.heightPresentation[i])
        // A holder handoff inherits the visible shape, never an old target or
        // elapsed animation time. Only fresh measured frames retarget it.
        hidden()
    }

    fun playing(value: Boolean): Boolean {
        if (playing == value) return false
        playing = value
        settling = false
        heights.copyInto(from, endIndex = 6)
        heightPresentation.copyInto(heightFrom, endIndex = 6)
        if (value) {
            // Resume has no measured target yet. Hold the presentation until
            // frame() supplies fresh audio instead of inserting a zero target.
            heights.copyInto(to, endIndex = 6)
            heightPresentation.copyInto(heightTo, endIndex = 6)
            elapsed = AppleWaveform.SAMPLE_MS
        } else {
            heightTo.fill(floor)
            to.fill(0f)
            elapsed = 0f
        }
        return true
    }

    fun frame(levels: FloatArray) {
        if (!playing) return
        settling = false
        heights.copyInto(from, endIndex = 6)
        heightPresentation.copyInto(heightFrom, endIndex = 6)
        for (i in 0 until 6) {
            to[i] = if (levels[i].isFinite()) maxOf(0f, minOf(1f, levels[i])) else 0f
            // Vary the low-level lift while keeping the curve anchored at zero and full height.
            // Only this height target changes; the measured levels still drive bar width.
            val level = to[i]
            heightTo[i] = maxOf(floor, level / (heightKnee + (1f - heightKnee) * level))
        }
        elapsed = 0f
    }

    fun silence() {
        if (!playing) return
        heights.copyInto(from, endIndex = 6)
        heightPresentation.copyInto(heightFrom, endIndex = 6)
        to.fill(0f)
        heightTo.fill(floor)
        elapsed = 0f
        settling = true
    }

    private fun duration(): Float = if (playing && !settling) AppleWaveform.SAMPLE_MS else AppleWaveform.PAUSE_MS

    fun advance(ms: Float): Boolean {
        val duration = duration()
        elapsed = minOf(duration, elapsed + maxOf(0f, ms))
        val progress = AppleWaveform.ease(elapsed / duration, duration == AppleWaveform.PAUSE_MS)
        for (i in 0 until 6) {
            heights[i] = from[i] + (to[i] - from[i]) * progress
            heightPresentation[i] = heightFrom[i] + (heightTo[i] - heightFrom[i]) * progress
        }
        return elapsed < duration
    }

    fun hidden() {
        elapsed = duration()
        if (playing) {
            // Keep the last presentation across an app/Home transition, but
            // discard the old target so it cannot advance off screen or replay.
            heights.copyInto(from, endIndex = 6)
            heights.copyInto(to, endIndex = 6)
            heightPresentation.copyInto(heightFrom, endIndex = 6)
            heightPresentation.copyInto(heightTo, endIndex = 6)
            return
        }
        heights.fill(0f)
        from.fill(0f)
        to.fill(0f)
        heightPresentation.fill(floor)
        heightFrom.fill(floor)
        heightTo.fill(floor)
    }
}
