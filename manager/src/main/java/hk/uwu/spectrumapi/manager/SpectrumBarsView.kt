package hk.uwu.spectrumapi.manager

import kotlin.math.abs

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import hk.uwu.spectrumapi.ReusableSpectrumFrame
import hk.uwu.spectrumapi.SpectrumOptions
import hk.uwu.spectrumapi.SpectrumStatus
import hk.uwu.spectrumapi.WaveformKind
import hk.uwu.spectrumapi.WaveformStatus

/** Draws received dBFS bands and signed source-domain extrema, without an animation loop. */
internal class SpectrumBarsView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bands = FloatArray(32)
    private val waveform = FloatArray(ReusableSpectrumFrame.WAVEFORM_FLOATS)
    private var outputMode = SpectrumOptions.OUTPUT_SPECTRUM
    private var count = 0
    private var waveformCount = 0
    private var reportedWaveformBins = 0
    private var waveformStatus = WaveformStatus.NONE
    private var waveformKind = WaveformKind.NONE
    var waveformScale = 1f
        private set
    private var generation = 0L
    private var trackToken = 0L
    private var sourceEpoch = 0L
    private var sourceRate = 0
    private var hasFrame = false
    var waveformMessage = "尚未收到波形数据"
        private set

    init {
        paint.textSize = 12 * resources.displayMetrics.scaledDensity
        contentDescription = "真实音频频谱与波形；尚未收到数据"
    }

    fun setOutputMode(mode: Int) {
        outputMode = mode
        clear()
    }

    /** Returns whether source/state metadata changed; drawing still happens for every frame. */
    fun showFrame(frame: ReusableSpectrumFrame): Boolean {
        val sourceChanged = !hasFrame || generation != frame.generation
            || trackToken != frame.waveformTrackToken || sourceEpoch != frame.waveformSourceEpoch
            || sourceRate != frame.waveformSourceRateHz || outputMode != frame.outputMode
        val metadataChanged = sourceChanged || waveformStatus != frame.waveformStatus
            || waveformKind != frame.waveformKind || reportedWaveformBins != frame.waveformBinCount
        if (sourceChanged) clearWaveform()
        hasFrame = true
        generation = frame.generation
        trackToken = frame.waveformTrackToken
        sourceEpoch = frame.waveformSourceEpoch
        sourceRate = frame.waveformSourceRateHz
        outputMode = frame.outputMode
        waveformStatus = frame.waveformStatus
        waveformKind = frame.waveformKind
        reportedWaveformBins = frame.waveformBinCount

        count = if (outputMode == SpectrumOptions.OUTPUT_WAVEFORM) 0 else frame.bandCount
        for (i in 0 until count) {
            val db = frame.getBandDb(i)
            bands[i] = if (db.isFinite()) maxOf(0f, minOf(1f, (db + 72) / 72)) else 0f
        }

        if (outputMode != SpectrumOptions.OUTPUT_SPECTRUM
            && waveformStatus == WaveformStatus.READY
            && waveformKind == WaveformKind.MIN_MAX_64
            && frame.waveformBinCount > 0
        ) {
            frame.copyWaveformInto(waveform)
            waveformCount = frame.waveformBinCount
            waveformScale = 1f
            for (i in 0 until waveformCount * 2) {
                waveformScale = maxOf(waveformScale, abs(waveform[i]))
            }
            waveformMessage = "就绪：跨声道 min/max 包络，非混音 PCM"
        } else {
            clearWaveform()
            waveformMessage = if (waveformStatus == WaveformStatus.READY) {
                "波形格式或有效桶数不可用"
            } else {
                waveformStatusText(waveformStatus)
            }
        }
        invalidate()
        return metadataChanged
    }

    fun onStateChanged(status: Int, newGeneration: Long) {
        if (generation != newGeneration || (status != SpectrumStatus.OK
                && status != SpectrumStatus.PARTIAL && status != SpectrumStatus.DROPPED)
        ) {
            clear()
            waveformMessage = when (status) {
                SpectrumStatus.IDLE -> "音源空闲，旧波形已清除"
                SpectrumStatus.SILENT -> "静音，旧波形已清除"
                SpectrumStatus.DISCONNECTED -> "连接已断开，旧波形已清除"
                SpectrumStatus.UNSUPPORTED_PATH, SpectrumStatus.UNSUPPORTED_BUILD -> "此采集路径不支持波形"
                else -> "来源代次已变化，等待新波形"
            }
        }
        generation = newGeneration
    }

    fun clear() {
        count = 0
        bands.fill(0f)
        clearWaveform()
        waveformStatus = WaveformStatus.NONE
        waveformKind = WaveformKind.NONE
        reportedWaveformBins = 0
        waveformMessage = "尚未收到波形数据"
        hasFrame = false
        invalidate()
    }

    private fun clearWaveform() {
        waveformCount = 0
        waveformScale = 1f
        waveform.fill(0f)
    }

    private fun waveformStatusText(status: Int): String = when (status) {
        WaveformStatus.NONE -> "未请求波形或暂无波形数据"
        WaveformStatus.WARMING_UP -> "波形窗口尚在预热"
        WaveformStatus.DISCONTINUITY -> "源样本不连续，等待新波形窗口"
        WaveformStatus.IDLE -> "音源空闲，没有有效波形"
        WaveformStatus.UNSUPPORTED_MULTISOURCE -> "匹配多个活动 Track，无法提供单源波形"
        WaveformStatus.UNSUPPORTED_PATH -> "此音频路径不支持波形"
        else -> "波形不可用"
    }

    protected override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFFEDF2F7.toInt())
        when (outputMode) {
            SpectrumOptions.OUTPUT_COMBINED -> {
                val middle = height / 2f
                drawSpectrum(canvas, 0f, middle - 4)
                drawWaveform(canvas, middle + 4, height.toFloat())
            }
            SpectrumOptions.OUTPUT_WAVEFORM -> drawWaveform(canvas, 0f, height.toFloat())
            else -> drawSpectrum(canvas, 0f, height.toFloat())
        }
    }

    private fun drawSpectrum(canvas: Canvas, top: Float, bottom: Float) {
        paint.color = 0xFF526779.toInt()
        canvas.drawText("频谱 · dBFS", 8f, top + 16, paint)
        val baseline = bottom - 8
        paint.color = 0xFFBECBD9.toInt()
        canvas.drawLine(8f, baseline, (width - 8).toFloat(), baseline, paint)
        if (count == 0) return
        val width = (width - 16f) / count
        val gap = minOf(6f, width * 0.25f)
        paint.color = 0xFF146B9D.toInt()
        for (i in 0 until count) {
            val height = bands[i] * (bottom - top - 32)
            if (height <= 0) continue
            val left = 8 + i * width + gap / 2
            canvas.drawRoundRect(left, baseline - height, left + width - gap, baseline, 3f, 3f, paint)
        }
    }

    private fun drawWaveform(canvas: Canvas, top: Float, bottom: Float) {
        paint.color = 0xFF526779.toInt()
        canvas.drawText("波形 · 跨声道 min/max", 8f, top + 16, paint)
        val center = (top + 24 + bottom - 8) / 2
        val halfHeight = (bottom - top - 32) / 2
        paint.color = 0xFFBECBD9.toInt()
        canvas.drawLine(8f, center, (width - 8).toFloat(), center, paint)
        if (waveformCount == 0) {
            canvas.drawText(waveformMessage, 8f, center + 18, paint)
            return
        }
        val width = (width - 16f) / waveformCount
        paint.color = 0xFF187C69.toInt()
        paint.strokeWidth = maxOf(1f, minOf(3f, width * 0.4f))
        for (i in 0 until waveformCount) {
            val x = 8 + (i + 0.5f) * width
            val yMin = center - waveform[i * 2] / waveformScale * halfHeight
            val yMax = center - waveform[i * 2 + 1] / waveformScale * halfHeight
            canvas.drawLine(x, yMax, x, yMin, paint)
        }
        paint.strokeWidth = 1f
    }
}
