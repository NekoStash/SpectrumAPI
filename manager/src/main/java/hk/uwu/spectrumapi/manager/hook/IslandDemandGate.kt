package hk.uwu.spectrumapi.manager.hook

/** Pure gate shared with the disposable host diagnostic; no media/package enumeration. */
object IslandDemandGate {
    @JvmStatic
    fun display(enabled: Boolean, whitelisted: Boolean, interactive: Boolean,
        knownCurrentIdentity: Boolean, local: Boolean, fullUid: Int): Boolean =
        enabled && whitelisted && interactive && knownCurrentIdentity && local && fullUid >= 0

    @JvmStatic
    fun audio(displayAllowed: Boolean, playing: Boolean, attachedAndVisible: Boolean): Boolean =
        displayAllowed && playing && attachedAndVisible
}
