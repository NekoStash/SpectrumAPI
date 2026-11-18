// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

// Modified for SpectrumAPI: keep the upstream native presentation and host lifecycle, with manager display settings.
package hk.uwu.spectrumapi.manager.hook.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import hk.uwu.spectrumapi.manager.config.SettingsKeys
import hk.uwu.spectrumapi.manager.hook.render.IslandSpectrumUi.Companion.report
import java.lang.reflect.Method
import kotlin.math.sqrt

/** The same six bars persist through pause/resume, with a cover-scoped palette. */
internal class SpectrumView @Throws(ReflectiveOperationException::class) constructor(
    context: Context, original: View, animated: Boolean, lifecycle: Lifecycle,
) : View(context) {
    fun interface Lifecycle { fun active(view: SpectrumView, active: Boolean) }

    private val lifecycle: Lifecycle?
    private var original: View? = null
    private var cancelAnimation: Method? = null
    private var playAnimation: Method? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val motion = AppleSpectrumMotion()
    private var scale = SettingsKeys.DEFAULT_BAR_SCALE
    private var amplitude = SettingsKeys.DEFAULT_AMPLITUDE
    private var blur = SettingsKeys.DEFAULT_BLUR
    private val artworkNode = RenderNode("HyperSpectrumArtwork")
    private val barMask = Path()
    // Six capsules as left, top, right, bottom, radius. The bars are composited in a
    // small layer instead of clipping the artwork node: HWUI caches the node's blur
    // snapshot only while its device clip is stable, and a per-frame clipPath both
    // rebuilt the blur and rasterized an AA clip atlas on every frame.
    private val bars = FloatArray(6 * 5)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val artworkPaint = Paint().apply { blendMode = BlendMode.SRC_IN }
    private var allowed = false
    private var aggregatedVisible = false
    private var subscribed = false
    private var ownsOriginal = false
    private var disposed = false
    private var drew = false
    private var fallback = true
    private var pauseSettled = false
    private var signalUnavailable = false
    private var silenced = false
    private var lastDraw = 0L
    private var lastData = 0L
    private var awaitingSince = 0L
    private val fieldSize = AppleArtwork.SIZE
    private val colors = AppleArtwork.fallback()
    private val fromColors = colors.copyOf()
    private val targetColors = colors.copyOf()
    private val colorBitmap = Bitmap.createBitmap(fieldSize, fieldSize, Bitmap.Config.ARGB_8888)
    private var colorShader: Shader? = null
    private var originalColors = false
    private val colorBounds = Matrix()
    private var paletteDirty = true
    private var colorStart = 0L
    private val stale = Runnable { stale() }
    private val choreographer = Choreographer.getInstance()
    private val frameTick = Choreographer.FrameCallback(::frameTick)
    private var frameRequested = false
    private var lastFrameNanos = 0L

    init {
        this.lifecycle = lifecycle
        fallback = false
        bindOriginal(original, animated)
        awaitingSince = SystemClock.uptimeMillis()
        tag = "miui-island-spectrum-v2"
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    companion object {
        // Measured on device: the stock musicWave Lottie presents ~30 fps and the ARR panel follows
        // the presented cadence. A view frame-rate vote alone did not change it.
        const val FRAME_RATE = 30f
        // Vsync-aligned pacing with 2 ms jitter tolerance: every 2nd vsync at 60 Hz, every 4th at 120 Hz.
        private val FRAME_NANOS = (1e9 / FRAME_RATE).toLong() - 2_000_000L
    }

    /** Animation and audio redraws; motion is time based, so skipped vsyncs only drop in-betweens. */
    private fun drawFrame() {
        if (frameRequested || disposed) return
        frameRequested = true
        choreographer.postFrameCallback(frameTick)
    }

    private fun frameTick(frameTimeNanos: Long) {
        if (disposed) {
            frameRequested = false
            return
        }
        if (frameTimeNanos - lastFrameNanos < FRAME_NANOS) {
            choreographer.postFrameCallback(frameTick)
            return
        }
        frameRequested = false
        lastFrameNanos = frameTimeNanos
        invalidate()
    }

    @Throws(ReflectiveOperationException::class)
    fun bindOriginal(next: View, animated: Boolean): Boolean {
        if (original === next) return false
        val cancel = if (animated) next.javaClass.getMethod("cancelAnimation") else null
        val play = if (animated) next.javaClass.getMethod("playAnimation") else null
        // The completed host bind owns visibility of its previous source.
        // Restoring it here would resurrect a now-hidden device/Lottie icon.
        ownsOriginal = false
        original = next
        cancelAnimation = cancel
        playAnimation = play
        return true
    }

    fun wantsAudio(): Boolean = subscribed && motion.isPlaying()

    fun inheritPresentation(source: SpectrumView) {
        if (motion.isPlaying() && source.motion.isPlaying()) {
            motion.inheritPresentation(source.motion)
            lastDraw = 0
        }
    }

    fun playing(value: Boolean) {
        if (lastDraw != 0L) {
            val now = SystemClock.uptimeMillis()
            motion.advance(maxOf(0L, now - lastDraw).toFloat())
            lastDraw = now
        }
        if (!motion.playing(value)) return
        removeCallbacks(stale)
        lastDraw = 0
        if (value) {
            awaitingSince = SystemClock.uptimeMillis()
            lastData = 0
            signalUnavailable = false
            scheduleStale()
        } else {
            fallback = false
            pauseSettled = false
        }
        suppressOriginal()
        invalidate()
        lifecycle!!.active(this, subscribed) // Re-evaluate audio demand without replacing this View.
        report("spectrum_motion mode=${if (value) "playing" else "pause_decay"} view=${System.identityHashCode(this)}", null)
    }

    fun colors(field: IntArray, original: Boolean) {
        // Manager's HyperOS/original fields have a different grid from Apple artwork.
        var incoming = field
        if (field.size != colors.size) {
            val size = sqrt(field.size.toDouble()).toInt()
            if (size == 0 || size * size != field.size) throw IllegalArgumentException("Square color field required")
            val expanded = IntArray(colors.size)
            for (y in 0 until fieldSize) for (x in 0 until fieldSize) {
                expanded[y * fieldSize + x] = field[y * size / fieldSize * size + x * size / fieldSize]
            }
            incoming = expanded
        }
        if (originalColors == original && incoming.contentEquals(targetColors)) return
        palette(SystemClock.uptimeMillis())
        originalColors = original
        colors.copyInto(fromColors, endIndex = colors.size)
        incoming.copyInto(targetColors, endIndex = colors.size)
        paletteDirty = true
        colorStart = SystemClock.uptimeMillis()
        invalidate()
    }

    fun allow(value: Boolean) {
        allowed = value
        reconcile()
    }

    fun disable() {
        // Resume the stock animation while this visible holder still owns its slot.
        restoreOriginal(true)
        allow(false)
        invalidate()
    }

    private fun reconcile() {
        val active = !disposed && allowed && isAttachedToWindow && aggregatedVisible && isShown && windowVisibility == VISIBLE
        if (active == subscribed) return
        subscribed = active
        lastDraw = 0
        if (!active) {
            removeCallbacks(stale)
            restoreOriginal(false)
            motion.hidden()
        } else {
            fallback = false
            signalUnavailable = false
            if (motion.isPlaying()) {
                awaitingSince = SystemClock.uptimeMillis()
                lastData = 0
                scheduleStale()
            }
            suppressOriginal()
            invalidate()
        }
        lifecycle!!.active(this, active)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        reconcile()
    }

    override fun onDetachedFromWindow() {
        aggregatedVisible = false
        reconcile()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(visible: Boolean) {
        super.onVisibilityAggregated(visible)
        aggregatedVisible = visible
        reconcile()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (lifecycle != null) reconcile()
    }

    /** Stop-at-volume-0: settle smoothly now and suspend the missing-data watchdog. */
    fun silenced(value: Boolean) {
        if (silenced == value || disposed) return
        silenced = value
        if (value) {
            removeCallbacks(stale)
            if (!subscribed || !motion.isPlaying()) return
            if (lastDraw != 0L) motion.advance(maxOf(0L, SystemClock.uptimeMillis() - lastDraw).toFloat())
            lastDraw = 0
            signalUnavailable = false
            motion.silence()
            suppressOriginal()
            drawFrame()
        } else {
            // Fresh audio must arrive within the usual window once sampling resumes.
            awaitingSince = SystemClock.uptimeMillis()
            lastData = 0
            scheduleStale()
        }
        report("spectrum_silenced value=$value view=${System.identityHashCode(this)}", null)
    }

    fun frame(levels: FloatArray, time: Long, available: Boolean) {
        if (disposed || !subscribed || !motion.isPlaying() || silenced) return
        // Pending stop deliveries during a holder switch are not an audio failure.
        // A sustained failure is handled by the same 750 ms watchdog as missing FFT.
        if (!available) return
        removeCallbacks(stale)
        val now = SystemClock.uptimeMillis()
        if (lastDraw != 0L) {
            motion.advance(maxOf(0L, now - lastDraw).toFloat())
            lastDraw = now
        }
        fallback = false
        signalUnavailable = false
        motion.frame(levels)
        lastData = time
        suppressOriginal()
        drawFrame()
        scheduleStale()
    }

    private fun scheduleStale() {
        if (subscribed && motion.isPlaying() && !silenced) postDelayed(stale, 800)
    }

    fun suppressOriginal() {
        if (disposed || fallback || !subscribed) return
        try {
            val cancel = cancelAnimation
            if (cancel != null && (!ownsOriginal || original!!.visibility == VISIBLE)) cancel.invoke(original)
            original!!.visibility = INVISIBLE
            ownsOriginal = true
        } catch (failure: ReflectiveOperationException) {
            fallback = true
            report("spectrum_view_error stage=suppress", failure)
            restoreOriginal(true)
        } catch (failure: RuntimeException) {
            fallback = true
            report("spectrum_view_error stage=suppress", failure)
            restoreOriginal(true)
        }
    }

    private fun restoreOriginal(animate: Boolean) {
        if (!ownsOriginal) return
        ownsOriginal = false
        original!!.visibility = VISIBLE
        if (animate && allowed && original!!.isShown) {
            try {
                playAnimation?.invoke(original)
            } catch (failure: ReflectiveOperationException) {
                report("spectrum_view_error stage=restore", failure)
            } catch (failure: RuntimeException) {
                report("spectrum_view_error stage=restore", failure)
            }
        }
    }

    private fun stale() {
        if (disposed || !subscribed || !motion.isPlaying() || silenced) return
        if (signalUnavailable) return
        if (SystemClock.uptimeMillis() - maxOf(lastData, awaitingSince) >= 750) {
            // Keep the resting capsules; never conceal missing measured data with a stock waveform.
            signalUnavailable = true
            motion.frame(FloatArray(6))
            fallback = false
            suppressOriginal()
            invalidate()
            report("spectrum_view_idle reason=stale_fft renderer=mac_waveform", null)
        }
    }

    fun dispose(restore: Boolean) {
        allowed = false
        disposed = true
        removeCallbacks(stale)
        choreographer.removeFrameCallback(frameTick)
        frameRequested = false
        paint.shader = null
        artworkNode.discardDisplayList()
        if (subscribed) {
            subscribed = false
            lifecycle!!.active(this, false)
        }
        fallback = true
        if (restore) {
            allowed = true
            restoreOriginal(true)
            allowed = false
        } else ownsOriginal = false
    }

    private fun palette(now: Long): Boolean {
        if (colorStart == 0L && !paletteDirty) return false
        val colorDuration = if (motion.isPlaying()) 150f else 900f
        val t = if (colorStart == 0L) 1f else minOf(1f, (now - colorStart) / colorDuration)
        var changed = paletteDirty
        for (i in colors.indices) {
            val color = CoverColors.blend(fromColors[i], targetColors[i], t)
            changed = changed or (color != colors[i])
            colors[i] = color
        }
        if (changed) {
            val shader = if (originalColors) {
                // Stock musicWave moves its two-stop vertical gradient with each bar's height.
                // Use the host's ARGB endpoints directly: no gray veil, saturation or alpha changes.
                LinearGradient(0f, 0f, 0f, fieldSize.toFloat(), colors[0], colors[colors.lastIndex], Shader.TileMode.CLAMP)
            } else {
                colorBitmap.setPixels(colors, 0, fieldSize, 0, 0, fieldSize, fieldSize)
                // BitmapShader can retain a native snapshot of a mutable bitmap. Refresh it only
                // during a cover transition; steady audio frames reuse the completed shader.
                BitmapShader(colorBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
                }
            }
            colorShader = shader
            shader.setLocalMatrix(colorBounds)
            paint.shader = shader
            recordArtwork()
        }
        paletteDirty = false
        if (t >= 1) colorStart = 0
        return t < 1
    }

    fun settings(scale: Float, blur: Boolean, amplitude: Float) {
        if (this.amplitude != amplitude) {
            this.amplitude = amplitude
            motion.amplitude(amplitude)
        }
        if (this.scale == scale && this.blur == blur) return
        this.scale = scale
        this.blur = blur
        geometry(width, height)
        recordArtwork()
        invalidate()
    }

    private fun extent(w: Int, h: Int): Float = AppleWaveform.compactExtent(w.toFloat(), h.toFloat()) * scale

    private fun geometry(w: Int, h: Int) {
        val width = extent(w, h)
        // The fixed artwork field is never stretched by individual bar heights.
        colorBounds.setScale(width / fieldSize, width / fieldSize)
        colorBounds.postTranslate((w - width) * 0.5f, (h - width) * 0.5f)
        colorShader?.setLocalMatrix(colorBounds)
        motion.geometry(width, width)
    }

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        geometry(w, h)
        recordArtwork()
        super.onSizeChanged(w, h, oldW, oldH)
    }

    private fun recordArtwork() {
        if (width <= 0 || height <= 0) return
        artworkNode.setPosition(0, 0, width, height)
        val radius = if (blur) AppleWaveform.artworkBlurRadius(extent(width, height)) else 0f
        artworkNode.setRenderEffect(if (radius > 0) RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP) else null)
        val recording = artworkNode.beginRecording()
        // Extend the compact image's edge colors through the host slot before
        // blurring; the shader clamps, so the surrounding margin adds no black.
        recording.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        artworkNode.endRecording()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (fallback || !subscribed || disposed) return
        val now = SystemClock.uptimeMillis()
        if (motion.isPlaying() && !signalUnavailable && !silenced && now - maxOf(lastData, awaitingSince) >= 750) {
            stale()
            return
        }
        val colorMoving = palette(now)
        val elapsed = if (lastDraw == 0L) 16f else (now - lastDraw).toFloat()
        lastDraw = now
        val moving = motion.advance(elapsed)
        val width = extent(this.width, height)
        val base = AppleWaveform.baseWidth(width)
        for (index in motion.heights.indices) {
            val bar = AppleWaveform.width(base, motion.heights[index])
            val left = (this.width - width) / 2 + AppleWaveform.left(width, index, bar)
            val height = motion.barHeight(index, width)
            val y = (this.height - height) / 2
            val radius = AppleWaveform.radius(bar)
            val at = index * 5
            bars[at] = left
            bars[at + 1] = y
            bars[at + 2] = left + bar
            bars[at + 3] = y + height
            bars[at + 4] = radius
        }
        if (canvas.isHardwareAccelerated) {
            val saved = canvas.saveLayer(0f, 0f, this.width.toFloat(), height.toFloat(), null)
            for (at in bars.indices step 5) {
                canvas.drawRoundRect(bars[at], bars[at + 1], bars[at + 2], bars[at + 3], bars[at + 4], bars[at + 4], barPaint)
            }
            canvas.saveLayer(0f, 0f, this.width.toFloat(), height.toFloat(), artworkPaint)
            // HWUI may discard a node's display list while this holder is
            // hidden. Artwork pixels and palette can still be unchanged:
            // the palette cache is not a validity check for GPU commands.
            if (!artworkNode.hasDisplayList()) {
                recordArtwork()
                report("spectrum_artwork_rebuilt reason=discarded_display_list view=${System.identityHashCode(this)}", null)
            }
            canvas.drawRenderNode(artworkNode)
            canvas.restoreToCount(saved)
        } else {
            barMask.reset()
            for (at in bars.indices step 5) {
                barMask.addRoundRect(bars[at], bars[at + 1], bars[at + 2], bars[at + 3], bars[at + 4], bars[at + 4], Path.Direction.CW)
            }
            val saved = canvas.save()
            canvas.clipPath(barMask)
            canvas.drawRect(0f, 0f, this.width.toFloat(), height.toFloat(), paint)
            canvas.restoreToCount(saved)
        }
        if (!drew) {
            drew = true
            val xy = IntArray(2)
            getLocationOnScreen(xy)
            report("spectrum_view_draw x=${xy[0]} y=${xy[1]}"
                    + " width=${this.width} height=$height"
                    + " contentWidth=$width contentHeight=$width layout=compact_fit"
                    + " maxBarHeight=$width renderer=mac_waveform"
                    + " colorMapping=full_spectrum", null)
        }
        if (!motion.isPlaying() && !moving && !pauseSettled) {
            pauseSettled = true
            report("spectrum_pause_settled view=${System.identityHashCode(this)}", null)
        }
        if (moving || colorMoving) drawFrame()
    }
}
