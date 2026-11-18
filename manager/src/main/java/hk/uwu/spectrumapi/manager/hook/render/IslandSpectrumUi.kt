// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

// Modified for SpectrumAPI: bridge manager settings and per-UID SDK demand.
package hk.uwu.spectrumapi.manager.hook.render

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import hk.uwu.spectrumapi.manager.hook.HookSettings
import hk.uwu.spectrumapi.manager.hook.HostAccess
import hk.uwu.spectrumapi.manager.hook.IslandLog
import hk.uwu.spectrumapi.manager.hook.MediaRoute
import hk.uwu.spectrumapi.manager.hook.MediaTarget
import hk.uwu.spectrumapi.manager.hook.UidSubscriptionPool
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale

/** Main-looper owner of every replacement View and subscription. */
class IslandSpectrumUi : AutoCloseable {
    // Holder slots key a host IslandIconViewHolder; layout slots key a found island_icon container.
    private class Slot(val holder: Any?, val view: SpectrumView, val layout: Boolean) {
        var shown = true
    }

    private val main = Handler(Looper.getMainLooper())
    private val slots = IdentityHashMap<Any?, Slot>()
    private val active = Collections.newSetFromMap(IdentityHashMap<SpectrumView, Boolean>())
    private var lastPresentation: SpectrumView? = null
    private var settings: HookSettings? = null
    private var target: MediaTarget? = null
    @Volatile private var closed = false
    private var playing = false
    private var interactive = true
    private var silenced = false
    private var enabled = false
    private var key: String? = null
    private var route = ""
    private var context: Context? = null
    private var screenRegistered = false
    private var coverColors = CoverColors.gradient(0xffffffff.toInt(), 0xffffffff.toInt())
    private var originalColors = true
    // Layout fallback for island modules drawn without host holders (for example a lyric module
    // that rebuilds the big island with the host resource IDs). Holder slots always take priority.
    private var compatibility = false
    private var appLabel: String? = null
    private var islandRoot: View? = null
    private var iconId = 0
    private var layoutUnavailableReported = false
    private val layoutScan = ViewTreeObserver.OnGlobalLayoutListener(::scanLayout)
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            interactive = Intent.ACTION_SCREEN_ON == intent.action
            demand()
        }
    }

    private val pool = UidSubscriptionPool(main) { uid, levels, silent ->
        val current = target
        if (closed || current == null || current.uid != uid) return@UidSubscriptionPool
        updateSilence(silent)
        val time = SystemClock.uptimeMillis()
        for (view in ArrayList(active)) view.frame(levels, time, true)
    }
    private val palette = ArtworkPalette(main) { colors, original ->
        coverColors = colors
        originalColors = original
        for (slot in slots.values) slot.view.colors(colors, original)
    }

    private fun updateSilence(value: Boolean) {
        if (silenced == value) return
        silenced = value
        for (slot in slots.values) slot.view.silenced(value)
    }

    private fun main(action: Runnable) {
        if (Looper.myLooper() === Looper.getMainLooper()) {
            if (!closed) action.run()
        } else main.post { if (!closed) action.run() }
    }

    fun settings(next: HookSettings) {
        main {
            val previous = settings
            val reset = previous != null && (previous.analysisRate != next.analysisRate || previous.updateRate != next.updateRate)
            settings = next
            if (reset) pool.reset()
            palette.scheme(next.scheme)
            for (slot in slots.values) configure(slot.view)
            if (compatibility != next.compatibility) {
                compatibility = next.compatibility
                if (compatibility) watchLayout() else unwatchLayout()
            }
            updateEnabled()
            scanLayout()
            demand()
        }
    }

    private fun configure(view: SpectrumView) {
        val current = settings
        if (current != null) view.settings(current.scale, current.blur, current.amplitude)
    }

    private fun updateEnabled() {
        val current = settings
        val media = target
        val value = current != null && current.enabled && media != null && current.whitelist.contains(media.packageName)
        if (enabled == value) return
        enabled = value
        for (slot in ArrayList(slots.values)) {
            if (value) slot.view.allow(slot.shown)
            else slot.view.disable()
        }
        report("spectrum_enabled value=$value slots=${slots.size}", null)
    }

    fun originalColors(bitmap: Bitmap?, pair: Any?) {
        if (bitmap == null || pair == null) return
        var first = HostAccess.call(pair, "getFirst")
        var second = HostAccess.call(pair, "getSecond")
        if (first !is Number) first = HostAccess.field(pair, "a")
        if (second !is Number) second = HostAccess.field(pair, "b")
        if (first !is Number || second !is Number) return
        val fingerprint = try {
            OriginalArtwork.fingerprint(bitmap)
        } catch (_: RuntimeException) {
            return
        }
        val top = first.toInt()
        val bottom = second.toInt()
        main { palette.original(fingerprint, top, bottom) }
    }

    fun media(next: MediaTarget?) {
        if (next == null) return
        main {
            val ctx = next.context
            if (context == null) {
                context = ctx
                val power = ctx.getSystemService(PowerManager::class.java)
                interactive = power == null || power.isInteractive
                val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
                filter.addAction(Intent.ACTION_SCREEN_OFF)
                try {
                    ctx.registerReceiver(screen, filter, Context.RECEIVER_NOT_EXPORTED)
                    screenRegistered = true
                } catch (failure: RuntimeException) {
                    report("spectrum_screen_receiver_unavailable", failure)
                }
            }
            val current = target
            val changedUid = current == null || current.uid != next.uid
            target = next
            key = next.key
            route = next.route
            playing = next.playing == true && MediaRoute.isLocal(route)
            if (changedUid) updateSilence(false)
            palette.artwork(ctx, next.track, next.artwork)
            appLabel = next.appLabel
            updateEnabled()
            if (compatibility) watchLayout()
            scanLayout()
            demand()
        }
    }

    fun removed(removedKey: String?) {
        main {
            if (key != removedKey) return@main
            playing = false
            key = null
            target = null
            updateSilence(false)
            scanLayout() // Without a media island no layout slot remains.
            demand()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun holder(holder: Any?, event: String?, data: Any?) {
        main { apply(holder, event) }
    }

    private fun apply(holder: Any?, event: String?) {
        try {
            var slot = slots[holder]
            if (event!!.equals("onDetach")) {
                remove(holder, false)
                return
            }
            if (event == "onHidden") {
                if (slot != null) {
                    slot.shown = false
                    slot.view.allow(false)
                }
                return
            }
            val info = field(holder, "picInfo")
            val pic = if (info == null) "" else java.lang.String.valueOf(info.javaClass.getMethod("getPic").invoke(info))
            if (!MediaIcon.replace(pic, route)) {
                remove(holder, false)
                return
            }
            if (slot == null && event == "animation") return
            val wiredIcon = MediaIcon.wiredDevice(pic, route)
            val container = field(holder, "iconContainer") as FrameLayout?
            val original = field(holder, if (wiredIcon) "fixIcon" else "lottieView") as View?
            if (container == null || original == null || original.parent !== container) return
            if (slot == null) {
                val found = slots[container]
                if (found != null && found.layout) remove(container, true) // The host holder path wins.
                val view = createView(container, original, !wiredIcon, MediaIcon.playing(pic, route, playing))
                slot = Slot(holder, view, false)
                slots[holder] = slot
                report("spectrum_view_install holder=${System.identityHashCode(holder)}"
                        + " slots=${slots.size} source=${if (wiredIcon) "wired_device" else "waveform"}", null)
            } else if (slot.view.bindOriginal(original, !wiredIcon)) {
                // Pause switches the host back to Lottie. Preserve the same
                // spectrum view, presentation heights and artwork throughout.
                slot.view.layoutParams = FrameLayout.LayoutParams(original.layoutParams as FrameLayout.LayoutParams)
            }
            if (event == "onShow") slot.shown = true
            slot.view.playing(MediaIcon.playing(pic, route, playing))
            slot.view.allow(enabled && slot.shown)
            slot.view.suppressOriginal() // Also catches the end of asynchronous Lottie composition.
        } catch (failure: ReflectiveOperationException) {
            report("spectrum_view_error stage=bind", failure)
            remove(holder, true)
        } catch (failure: RuntimeException) {
            report("spectrum_view_error stage=bind", failure)
            remove(holder, true)
        }
    }

    private fun createView(container: FrameLayout, original: View, animated: Boolean, isPlaying: Boolean): SpectrumView {
        val view = SpectrumView(container.context, original, animated) { v, enabled ->
            if (enabled) {
                val previous = lastPresentation
                if (!active.contains(v) && previous != null && previous !== v) v.inheritPresentation(previous)
                active.add(v)
                lastPresentation = v
            } else {
                if (active.remove(v)) lastPresentation = v
            }
            demand()
        }
        configure(view)
        view.colors(coverColors, originalColors)
        view.playing(isPlaying)
        view.silenced(silenced)
        container.addView(view, FrameLayout.LayoutParams(original.layoutParams as FrameLayout.LayoutParams))
        return view
    }

    private fun watchLayout() {
        if (islandRoot?.isAttachedToWindow == true) return
        unwatchLayout()
        try {
            val global = Class.forName("android.view.WindowManagerGlobal")
            val instance = global.getMethod("getInstance").invoke(null)
            val rootsField = global.getDeclaredField("mRoots")
            val lockField = global.getDeclaredField("mLock")
            rootsField.isAccessible = true
            lockField.isAccessible = true
            val roots = synchronized(lockField.get(instance)!!) { ArrayList(rootsField.get(instance) as List<*>) }
            for (root in roots) {
                val view = root!!.javaClass.getMethod("getView").invoke(root) as View?
                val params = view?.layoutParams
                if (view != null && params is WindowManager.LayoutParams && ISLAND_WINDOW.contentEquals(params.title)) {
                    islandRoot = view
                    view.viewTreeObserver.addOnGlobalLayoutListener(layoutScan)
                    return
                }
            }
        } catch (failure: ReflectiveOperationException) {
            if (!layoutUnavailableReported) report("spectrum_layout_unavailable", failure)
            layoutUnavailableReported = true
        } catch (failure: RuntimeException) {
            if (!layoutUnavailableReported) report("spectrum_layout_unavailable", failure)
            layoutUnavailableReported = true
        }
    }

    private fun unwatchLayout() {
        val root = islandRoot ?: return
        try {
            root.viewTreeObserver.removeOnGlobalLayoutListener(layoutScan)
        } catch (_: IllegalStateException) { } // The detached observer is already dead.
        islandRoot = null
    }

    /** Keep layout slots in step with the island tree; runs on every island-window layout. */
    private fun scanLayout() {
        if (closed) return
        val seen = Collections.newSetFromMap(IdentityHashMap<Any?, Boolean>())
        val root = islandRoot
        if (compatibility && enabled && key != null && appLabel != null && MediaRoute.isLocal(route)
            && root != null && root.isAttachedToWindow) findIcons(root, false, seen)
        for (slot in ArrayList(slots.values)) {
            if (slot.layout && !seen.contains(slot.holder))
                remove(slot.holder, (slot.holder as View).isAttachedToWindow)
        }
    }

    private fun findIcons(view: View, media: Boolean, seen: MutableSet<Any?>) {
        // The big island (and its fake transition copy) carries the media app label.
        val description = view.contentDescription
        val isMedia = if (description != null) appLabel!!.contentEquals(description) else media
        if (view !is ViewGroup) return
        if (isMedia && view is FrameLayout && isIcon(view) && layoutIcon(view)) seen.add(view)
        var i = 0
        while (i < view.childCount) {
            findIcons(view.getChildAt(i), isMedia, seen)
            i++
        }
    }

    private fun isIcon(view: View): Boolean {
        val id = view.id
        if (id == View.NO_ID) return false
        if (iconId != 0) return id == iconId
        try {
            if ("island_icon" != view.resources.getResourceEntryName(id)
                || PLUGIN_PACKAGE != view.resources.getResourcePackageName(id)) return false
        } catch (_: Resources.NotFoundException) {
            return false
        }
        iconId = id
        return true
    }

    private fun layoutIcon(container: FrameLayout): Boolean {
        var slot = slots[container]
        var original: View? = null
        var i = 0
        while (i < container.childCount) {
            val child = container.getChildAt(i)
            if (child is SpectrumView) {
                if (slot == null || slot.view !== child) return false // Owned by a host holder.
            } else if (original == null && animation(child)) {
                original = child
            }
            i++
        }
        if (original == null || original.layoutParams !is FrameLayout.LayoutParams) return false
        try {
            if (slot == null) {
                slot = Slot(container, createView(container, original, false, playing), true)
                slots[container] = slot
                report("spectrum_view_install holder=${System.identityHashCode(container)}"
                        + " slots=${slots.size} source=layout original=${original.javaClass.name}"
                        + " drawable=${(original as ImageView).drawable.javaClass.name}", null)
            } else if (slot.view.bindOriginal(original, false)) {
                slot.view.layoutParams = FrameLayout.LayoutParams(original.layoutParams as FrameLayout.LayoutParams)
            }
            slot.view.playing(playing)
            slot.view.allow(enabled)
            slot.view.suppressOriginal() // The foreign module may show its own Lottie again.
            return true
        } catch (failure: ReflectiveOperationException) {
            report("spectrum_view_error stage=layout", failure)
            return false
        } catch (failure: RuntimeException) {
            report("spectrum_view_error stage=layout", failure)
            return false
        }
    }

    private fun demand() {
        val current = settings
        if (!closed && current != null) {
            var count = 0
            for (view in active) if (view.wantsAudio()) count++
            val media = target
            val uids = if (enabled && playing && interactive && count > 0 && media != null && media.local && media.uid >= 0)
                java.util.Set.of(media.uid) else java.util.Set.of()
            pool.demand(uids, current.analysisRate, current.updateRate)
        }
    }

    private fun remove(holder: Any?, restore: Boolean) {
        val slot = slots.remove(holder) ?: return
        slot.view.dispose(restore)
        if (lastPresentation === slot.view) lastPresentation = null
        val parent = slot.view.parent
        if (parent is FrameLayout) parent.removeView(slot.view)
        report("spectrum_view_remove holder=${System.identityHashCode(holder)} slots=${slots.size}", null)
    }

    override fun close() {
        main {
            closed = true
            unwatchLayout()
            palette.close()
            if (screenRegistered) {
                try {
                    context!!.unregisterReceiver(screen)
                } catch (failure: RuntimeException) {
                    report("spectrum_screen_unregister_error", failure)
                }
                screenRegistered = false
            }
            pool.close()
            for (holder in slots.keys.toTypedArray()) remove(holder, true)
            active.clear()
            lastPresentation = null
            target = null
            context = null
            main.removeCallbacksAndMessages(null)
        }
    }

    companion object {
        private const val PLUGIN_PACKAGE = "miui.systemui.plugin"
        private const val ISLAND_WINDOW = "DynamicIslandWindow"

        @JvmStatic
        fun report(message: String, failure: Throwable?) {
            if (failure == null) IslandLog.info(message)
            else IslandLog.warn(message, failure)
        }

        @JvmStatic
        @Throws(ReflectiveOperationException::class)
        fun field(`object`: Any?, name: String): Any? {
            var type: Class<*>? = `object`!!.javaClass
            while (type != null) {
                try {
                    val f = type.getDeclaredField(name)
                    f.isAccessible = true
                    return f.get(`object`)
                } catch (_: NoSuchFieldException) { }
                type = type.superclass
            }
            throw NoSuchFieldException(name)
        }

        /**
         * A Lottie view holding a composition. Measured with the lyric module: the album side also has
         * an empty Lottie view (null drawable) beside a plain ImageView with the artwork bitmap, and the
         * drawable class itself is obfuscated, so only the view class name is a usable marker.
         */
        private fun animation(child: View?): Boolean = child is ImageView && child.drawable != null
                && child.drawable !is BitmapDrawable && child.javaClass.name.lowercase(Locale.ROOT).contains("lottie")
    }
}
