package hk.uwu.spectrumapi.manager.config

/** Shared remote-preference contract; this belongs to the optional Xposed APK, not the SDK. */
object SettingsKeys {
    const val GROUP = "spectrum_island"
    const val ENABLED = "enabled"
    const val WHITELIST = "media_whitelist"
    const val COMPATIBILITY = "super_island_compatibility"
    const val ANALYSIS_RATE = "analysis_rate_hz"
    const val UPDATE_RATE = "update_rate_hz"
    const val COLOR_SCHEME = "color_scheme"
    const val BAR_SCALE = "bar_scale"
    const val AMPLITUDE = "amplitude"
    const val BLUR = "blur"
    const val DEFAULT_ANALYSIS_RATE = 24000
    const val DEFAULT_UPDATE_RATE = 30
    const val DEFAULT_ENABLED = true
    const val DEFAULT_COMPATIBILITY = false
    const val DEFAULT_BLUR = true
    const val DEFAULT_BAR_SCALE = 1.0f
    const val DEFAULT_AMPLITUDE = 1.0f
    const val MIN_AMPLITUDE = 0.5f
    const val MAX_AMPLITUDE = 2.0f
    const val DEFAULT_COLOR_SCHEME = "original"
    const val SYSTEM_UI = "com.android.systemui"
    const val INSTALLED_APPS_PERMISSION = "com.android.permission.GET_INSTALLED_APPS"

    @JvmStatic
    fun normalizeAmplitude(value: Float): Float =
        if (value.isFinite()) value.coerceIn(MIN_AMPLITUDE, MAX_AMPLITUDE) else DEFAULT_AMPLITUDE
}
