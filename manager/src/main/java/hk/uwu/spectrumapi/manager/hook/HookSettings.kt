package hk.uwu.spectrumapi.manager.hook

import hk.uwu.roxyhook.prefs.*
import hk.uwu.spectrumapi.manager.config.RemoteSettings
import hk.uwu.spectrumapi.manager.config.SettingsKeys as K

/** The UI writes this exact remote group; empty whitelist means no display or capture. */
data class HookSettings(
    val enabled: Boolean,
    val whitelist: Set<String>,
    val compatibility: Boolean,
    val analysisRate: Int,
    val updateRate: Int,
    val scheme: String,
    val scale: Float,
    val blur: Boolean,
    val amplitude: Float = K.DEFAULT_AMPLITUDE,
) {
    companion object {
        fun read(p: Preferences) = HookSettings(
            p[booleanPreference(K.ENABLED, K.DEFAULT_ENABLED)],
            p[stringSetPreference(K.WHITELIST)].toSet(),
            p[booleanPreference(K.COMPATIBILITY, K.DEFAULT_COMPATIBILITY)],
            p[intPreference(K.ANALYSIS_RATE, K.DEFAULT_ANALYSIS_RATE)].takeIf { it in 8000..192000 } ?: K.DEFAULT_ANALYSIS_RATE,
            p[intPreference(K.UPDATE_RATE, K.DEFAULT_UPDATE_RATE)].coerceIn(1, 60),
            p[stringPreference(K.COLOR_SCHEME, K.DEFAULT_COLOR_SCHEME)].takeIf { it in setOf("original", "hyperos", "ios") } ?: "original",
            p[floatPreference(K.BAR_SCALE, K.DEFAULT_BAR_SCALE)].takeIf { it.isFinite() }?.coerceIn(0.5f, 2f) ?: 1f,
            p[booleanPreference(K.BLUR, K.DEFAULT_BLUR)],
            RemoteSettings.readAmplitude(p),
        )
    }
}
