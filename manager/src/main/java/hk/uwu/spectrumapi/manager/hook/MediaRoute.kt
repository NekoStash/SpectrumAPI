// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0
// Modified for SpectrumAPI: relocated local-route helper; playbackLocation is additionally gated.
package hk.uwu.spectrumapi.manager.hook

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSession

/** Distinguish a local wired output from HyperOS's generic casting category. */
object MediaRoute {
    @JvmStatic
    fun resolve(context: Context?, route: String?, token: MediaSession.Token?): String? {
        if (route != "others" || token == null) return route
        return try {
            val playback: MediaController.PlaybackInfo? = MediaController(context!!, token).playbackInfo
            if (playback == null || playback.playbackType != MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL)
                return route
            val audio = context.getSystemService(AudioManager::class.java) ?: return route
            val attributes = playback.audioAttributes ?: return route
            val outputs = audio.getAudioDevicesForAttributes(attributes)
            if (outputs.size != 1 || !outputs[0].isSink) return route
            classify(route, true, intArrayOf(outputs[0].type))
        } catch (_: RuntimeException) {
            // A connected USB device alone never authorizes replacing a cast icon.
            route
        }
    }

    @JvmStatic
    @JvmName("classify")
    internal fun classify(route: String?, localPlayback: Boolean, outputTypes: IntArray?): String? {
        if (route != "others" || !localPlayback || outputTypes!!.size != 1) return route
        return when (outputTypes[0]) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_HEADSET -> "wired"
            else -> route
        }
    }

    @JvmStatic
    fun isLocal(route: String?): Boolean = route == "" || route == "local" || route == "headset" || route == "wired"
}
