// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0
package hk.uwu.spectrumapi.manager.hook.render

import kotlin.math.roundToInt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Handler
import hk.uwu.spectrumapi.manager.hook.IslandLog
import java.util.LinkedHashMap
import java.util.concurrent.Executors

/** Adapted from HyperSpectrum: private artwork snapshots, bounded cover cache, late-result rejection.
 * Modified for SpectrumAPI: scheme affects only color, never the SDK source or presentation model. */
internal class ArtworkPalette(private val main: Handler, private val listener: Listener) : AutoCloseable {
    fun interface Listener { fun colors(colors: IntArray, original: Boolean) }

    private val worker = Executors.newSingleThreadExecutor { action ->
        Thread(action, "SpectrumIslandArtwork").apply { isDaemon = true }
    }
    private val cache = object : LinkedHashMap<String, IntArray>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IntArray>?): Boolean = size > 8
    }
    private val originals = object : LinkedHashMap<Long?, IntArray>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long?, IntArray>?): Boolean = size > 8
    }
    private var context: Context? = null
    private var icon: Icon? = null
    private var track: String? = null
    private var scheme = "original"
    private var cover: Long? = null
    private var ticket = 0L
    private var closed = false

    fun scheme(next: String) {
        if (scheme == next) return
        scheme = next
        val currentContext = context
        if (currentContext != null) artwork(currentContext, track, icon, true)
    }

    fun artwork(context: Context, track: String?, icon: Icon?) {
        artwork(context, track, icon, false)
    }

    private fun artwork(ctx: Context, nextTrack: String?, nextIcon: Icon?, force: Boolean) {
        if (closed || (!force && track == nextTrack && icon === nextIcon)) return
        context = ctx
        track = nextTrack
        icon = nextIcon
        cover = null
        val request = ++ticket
        val requestedScheme = scheme
        // No previous application's palette is shown while a different cover is decoded.
        listener.colors(fallback(requestedScheme), requestedScheme == "original")
        worker.execute {
            var colors = fallback(requestedScheme)
            var originalCover: Long? = null
            try {
                val drawable = nextIcon?.loadDrawable(ctx)
                if (drawable != null) {
                    originalCover = OriginalArtwork.fingerprint(drawable)
                    val sampled = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
                    val bounds = Rect(drawable.bounds)
                    try {
                        drawable.setBounds(0, 0, 48, 48)
                        drawable.draw(Canvas(sampled))
                        val pixels = IntArray(48 * 48)
                        sampled.getPixels(pixels, 0, 48, 0, 0, 48, 48)
                        val analysis = if (requestedScheme == "ios") analyze(drawable) else null
                        val key = "$requestedScheme:${CoverColors.fingerprint(pixels)}:${analysis?.fingerprint() ?: 0L}"
                        var existing = cache[key]
                        if (existing == null) {
                            existing = if (requestedScheme == "ios") AppleArtwork.field(pixels, analysis!!.treatment())
                                else CoverColors.hyperOsField(pixels)
                            cache[key] = existing
                        }
                        colors = existing
                    } finally {
                        drawable.bounds = bounds
                        sampled.recycle()
                    }
                }
            } catch (unavailable: RuntimeException) {
                IslandLog.warn("Artwork palette unavailable", unavailable)
            }
            val result = colors
            val hostHash = originalCover
            main.post {
                if (closed || request != ticket) return@post
                cover = hostHash
                if (requestedScheme == "original") {
                    val host = originals[cover]
                    listener.colors(if (host == null) fallback("original") else host.copyOf(), true)
                } else listener.colors(result.copyOf(), false)
            }
        }
    }

    fun original(fingerprint: Long, top: Int, bottom: Int) {
        if (closed) return
        val field = CoverColors.gradient(top, bottom)
        originals[fingerprint] = field
        if (scheme == "original" && cover == fingerprint) listener.colors(field.copyOf(), true)
    }

    override fun close() {
        closed = true
        ticket++
        context = null
        icon = null
        originals.clear()
        worker.shutdown()
    }

    companion object {
        private fun fallback(scheme: String): IntArray =
            if (scheme == "ios") AppleArtwork.fallback() else CoverColors.gradient(0xffffffff.toInt(), 0xffffffff.toInt())

        private fun analyze(drawable: Drawable): AppleArtwork.Analysis {
            val snapshot: Bitmap
            if (drawable is BitmapDrawable && drawable.bitmap.width.toLong() * drawable.bitmap.height <= 1024 * 1024) {
                snapshot = drawable.bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    ?: throw IllegalStateException("Artwork copy failed")
            } else {
                var w = maxOf(1, drawable.intrinsicWidth)
                var h = maxOf(1, drawable.intrinsicHeight)
                val scale = minOf(1.0, 1024.0 / maxOf(w, h))
                w = maxOf(1, (w * scale).roundToInt())
                h = maxOf(1, (h * scale).roundToInt())
                snapshot = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val bounds = Rect(drawable.bounds)
                try {
                    drawable.setBounds(0, 0, w, h)
                    drawable.draw(Canvas(snapshot))
                } catch (failure: RuntimeException) {
                    snapshot.recycle()
                    throw failure
                } finally {
                    drawable.bounds = bounds
                }
            }
            try {
                val analysis = AppleArtwork.Analysis()
                val width = snapshot.width
                val height = snapshot.height
                val rows = minOf(32, height)
                val pixels = IntArray(width * rows)
                var y = 0
                while (y < height) {
                    val count = minOf(rows, height - y)
                    snapshot.getPixels(pixels, 0, width, 0, y, width, count)
                    analysis.add(pixels, width * count)
                    y += rows
                }
                return analysis
            } finally {
                snapshot.recycle()
            }
        }
    }
}
