// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0
// Modified for SpectrumAPI: relocated display-only helper; no capture backend.
package hk.uwu.spectrumapi.manager.hook.render

/** Only media waveform icons and an independently verified wired device icon. */
internal object MediaIcon {
    @JvmStatic fun wiredDevice(pic: String?, route: String?): Boolean = pic == "miui_music_cast_others" && route == "wired"
    @JvmStatic fun replace(pic: String?, route: String?): Boolean = pic == "musicWave" || pic == "musicPause" || wiredDevice(pic, route)
    @JvmStatic fun playing(pic: String?, route: String?, mediaPlaying: Boolean): Boolean =
        pic == "musicWave" || (wiredDevice(pic, route) && mediaPlaying)
}
