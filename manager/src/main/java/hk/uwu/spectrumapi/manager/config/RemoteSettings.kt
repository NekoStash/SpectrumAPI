package hk.uwu.spectrumapi.manager.config

import hk.uwu.roxyhook.prefs.*

/** Typed contract for the framework-owned preferences. Never falls back to local storage. */
data class IslandSettings(
    val enabled: Boolean = SettingsKeys.DEFAULT_ENABLED,
    val whitelist: Set<String> = emptySet(),
    val compatibility: Boolean = SettingsKeys.DEFAULT_COMPATIBILITY,
    val analysisRate: Int = SettingsKeys.DEFAULT_ANALYSIS_RATE,
    val updateRate: Int = SettingsKeys.DEFAULT_UPDATE_RATE,
    val colorScheme: String = SettingsKeys.DEFAULT_COLOR_SCHEME,
    val barScale: Float = SettingsKeys.DEFAULT_BAR_SCALE,
    val blur: Boolean = SettingsKeys.DEFAULT_BLUR,
    val amplitude: Float = SettingsKeys.DEFAULT_AMPLITUDE,
)

object RemoteSettings {
    private val enabled = booleanPreference(SettingsKeys.ENABLED, SettingsKeys.DEFAULT_ENABLED)
    private val whitelist = stringSetPreference(SettingsKeys.WHITELIST)
    private val compatibility = booleanPreference(SettingsKeys.COMPATIBILITY, SettingsKeys.DEFAULT_COMPATIBILITY)
    private val analysis = intPreference(SettingsKeys.ANALYSIS_RATE, SettingsKeys.DEFAULT_ANALYSIS_RATE)
    private val update = intPreference(SettingsKeys.UPDATE_RATE, SettingsKeys.DEFAULT_UPDATE_RATE)
    private val color = stringPreference(SettingsKeys.COLOR_SCHEME, SettingsKeys.DEFAULT_COLOR_SCHEME)
    private val scale = floatPreference(SettingsKeys.BAR_SCALE, SettingsKeys.DEFAULT_BAR_SCALE)
    private val blur = booleanPreference(SettingsKeys.BLUR, SettingsKeys.DEFAULT_BLUR)
    private val amplitude = floatPreference(SettingsKeys.AMPLITUDE, SettingsKeys.DEFAULT_AMPLITUDE)

    internal fun readAmplitude(prefs: Preferences): Float = try {
        SettingsKeys.normalizeAmplitude(prefs[amplitude])
    } catch (_: ClassCastException) {
        SettingsKeys.DEFAULT_AMPLITUDE
    }

    fun load(prefs: Preferences) = IslandSettings(
        prefs[enabled], prefs[whitelist].toSet(), prefs[compatibility], prefs[analysis],
        prefs[update], prefs[color], prefs[scale], prefs[blur], readAmplitude(prefs),
    )

    /** A change contains only fields the user actually edited, never a stale whole-page snapshot. */
    data class Patch(
        val enabled: Boolean? = null,
        val compatibility: Boolean? = null,
        val analysisRate: Int? = null,
        val updateRate: Int? = null,
        val colorScheme: String? = null,
        val barScale: Float? = null,
        val blur: Boolean? = null,
        val amplitude: Float? = null,
    )

    @Synchronized
    fun commit(prefs: MutablePreferences, patch: Patch): Boolean {
        patch.analysisRate?.let { require(it in 8000..192000) }
        patch.updateRate?.let { require(it in 1..60) }
        patch.barScale?.let { require(it.isFinite() && it in 0.5f..2f) }
        patch.colorScheme?.let { require(it in setOf("original", "hyperos", "ios")) }
        patch.amplitude?.let { require(it in SettingsKeys.MIN_AMPLITUDE..SettingsKeys.MAX_AMPLITUDE) }
        return prefs.editAndCommit {
            patch.enabled?.let { this[enabled] = it }
            patch.compatibility?.let { this[compatibility] = it }
            patch.analysisRate?.let { this[analysis] = it }
            patch.updateRate?.let { this[update] = it }
            patch.colorScheme?.let { this[color] = it }
            patch.barScale?.let { this[scale] = it }
            patch.blur?.let { this[blur] = it }
            patch.amplitude?.let { this[amplitude] = it }
        }
    }

    /** Optimistic conflict check repeated immediately before commit; the provider has no CAS API. */
    @Synchronized
    fun saveWhitelist(prefs: MutablePreferences, packages: Set<String>, expected: Set<String>): Boolean {
        check(prefs[whitelist].toSet() == expected) { "远程白名单已变化，请取消并重新打开" }
        return prefs.editAndCommit { this[whitelist] = packages.toSet() }
    }
}
