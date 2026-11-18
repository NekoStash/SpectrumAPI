package hk.uwu.spectrumapi.manager.hook.render

import hk.uwu.spectrumapi.ReusableSpectrumFrame
import kotlin.math.pow
import kotlin.math.sqrt

/** Display-only adaptation of total band power; this does not reconstruct the source FFT. */
class NativeSixBandProjector {
    companion object {
        private val TARGET = doubleArrayOf(20.0, 81.0, 170.0, 527.0, 1500.0, 4500.0, 10000.0)
    }

    private var edges = FloatArray(0)
    private var weights = DoubleArray(0)
    private val power = DoubleArray(32)
    private var count = 0

    /** Call on initial stream metadata or generation/edge change, not on every frame. */
    fun configure(sourceEdges: FloatArray) {
        if (edges.contentEquals(sourceEdges)) return
        if (sourceEdges.size < 2 || sourceEdges.size > 33) throw IllegalArgumentException("Invalid band edges")
        for (i in sourceEdges.indices) {
            if (!sourceEdges[i].isFinite() || sourceEdges[i] < 0 || (i > 0 && sourceEdges[i] <= sourceEdges[i - 1]))
                throw IllegalArgumentException("Non-increasing band edges")
        }
        edges = sourceEdges.copyOf()
        count = edges.size - 1
        weights = DoubleArray(6 * count)
        for (j in 0 until 6) for (i in 0 until count) {
            val overlap = maxOf(0.0, minOf(TARGET[j + 1], edges[i + 1].toDouble()) - maxOf(TARGET[j], edges[i].toDouble()))
            weights[j * count + i] = overlap / (edges[i + 1] - edges[i])
        }
    }

    fun project(frame: ReusableSpectrumFrame, destination: FloatArray) {
        if (frame.bandCount != count) throw IllegalArgumentException("Band metadata changed")
        for (i in 0 until count) {
            val db = frame.getBandDb(i)
            power[i] = if (db.isFinite()) 10.0.pow(db / 10.0) else 0.0
        }
        projectPowers(power, destination)
    }

    // Also used by the disposable host diagnostic; all buffers are reused per stream.
    fun projectPowers(sourcePower: DoubleArray, destination: FloatArray) {
        if (destination.size < 6) throw IllegalArgumentException("Six bars required")
        for (j in 0 until 6) {
            var q = 0.0
            for (i in 0 until count) q += weights[j * count + i] * sourcePower[i]
            val amplitude = sqrt(2 * maxOf(0.0, q))
            val x = (0.6 * amplitude).pow(0.4000000059604645) * (1 - 0.05 * j)
            val y = x * x * (3.840823 + x * (-8.182433 + x * (7.772333 - 2.430722 * x)))
            destination[j] = if (y.isNaN()) 0f else maxOf(0.0, minOf(1.0, y)).toFloat()
        }
    }
}
