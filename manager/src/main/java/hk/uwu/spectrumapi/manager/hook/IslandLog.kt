package hk.uwu.spectrumapi.manager.hook

import hk.uwu.roxyhook.RLog

/** Shared diagnostic switch for the SystemUI hook, renderer and subscription. */
object IslandLog {
    const val is_debug = true
    private const val PREFIX = "[SpectrumIsland] "

    @JvmStatic fun info(message: String?) {
        if (is_debug) RLog.info(PREFIX + message, null)
    }

    @JvmStatic fun warn(message: String?, error: Throwable?) {
        if (is_debug) RLog.warn(PREFIX + message, error)
    }
}
