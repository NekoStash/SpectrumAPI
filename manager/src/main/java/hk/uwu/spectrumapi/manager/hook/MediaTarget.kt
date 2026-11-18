// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0
// MediaData observation ported from HyperSpectrum SpectrumModule. Capture uses SpectrumAPI UID.
package hk.uwu.spectrumapi.manager.hook

import android.content.Context
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import java.util.function.Consumer

class MediaTarget private constructor(
    @JvmField val context: Context,
    @JvmField val key: String?,
    @JvmField val playing: Boolean,
    @JvmField val route: String,
    @JvmField val packageName: String,
    @JvmField val uid: Int,
    @JvmField val local: Boolean,
    @JvmField val track: String,
    @JvmField val artwork: Icon?,
    @JvmField val appLabel: String?,
) {
    companion object {
        @JvmStatic
        fun observe(controller: Any?, media: Any?, unavailable: Consumer<String>): MediaTarget? {
            val context = HostAccess.field(controller, "context") as Context?
            if (context == null) {
                unavailable.accept("no_controller_context")
                return null
            }
            val key = HostAccess.string(HostAccess.field(media, "notificationKey"))
            val playing = true.equals(HostAccess.field(media, "isPlaying"))
            val device = HostAccess.field(controller, "castDeviceType")
            var route = if (device == null) "" else device.toString()
            val token = HostAccess.field(media, "token")
            if (route == "others" && token is MediaSession.Token)
                route = MediaRoute.resolve(context, route, token) as String
            val pkg = HostAccess.string(HostAccess.field(media, "packageName"))
            if (pkg == null) {
                unavailable.accept("no_media_package")
                return null
            }
            val user = HostAccess.integer(HostAccess.field(media, "userId"), -1)
            var uid = HostAccess.integer(HostAccess.field(media, "appUid"), -1)
            if (uid < 0 || user < 0 || uid / 100000 != user) uid = -1
            val playbackLocation = HostAccess.integer(HostAccess.field(media, "playbackLocation"), -1)
            val local = playbackLocation == 0 && MediaRoute.isLocal(route)
            val artwork = HostAccess.field(media, "artwork")
            val track = "$pkg|$user|$key|${HostAccess.field(media, "mediaId")}" +
                "|${HostAccess.field(media, "song")}|${HostAccess.field(media, "artist")}"
            val app = HostAccess.field(media, "app")
            return MediaTarget(context, key, playing, route, pkg, uid, local, track, artwork as? Icon, app?.toString())
        }
    }
}
