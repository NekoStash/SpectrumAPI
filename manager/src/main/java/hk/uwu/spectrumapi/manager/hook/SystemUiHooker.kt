// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0
// Port of HyperSpectrum SpectrumModule host callbacks to RoxyHook and SpectrumAPI.
package hk.uwu.spectrumapi.manager.hook

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import hk.uwu.roxyhook.*
import hk.uwu.spectrumapi.manager.config.SettingsKeys
import hk.uwu.spectrumapi.manager.hook.render.IslandSpectrumUi
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap
import org.json.JSONObject

class SystemUiHooker : RoxyHooker() {
    private lateinit var base: PackageScope
    private lateinit var ui: IslandSpectrumUi
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val loaders = Collections.newSetFromMap(IdentityHashMap<ClassLoader, Boolean>())
    private val hooked = HashSet<Method>()
    private val deoptimized = HashSet<Method>()
    private var observingSettings = false
    private var lastMediaState: String? = null

    override fun PackageScope.onHook() {
        base = this
        ui = runtime.manage(IslandSpectrumUi())
        installFrom(this)
    }

    fun installFrom(scope: PackageScope) {
        if (scope.runtime.isClosed) return
        observeSettings()
        install(scope.appClassLoader)
    }

    private fun observeSettings() {
        if (observingSettings) return
        try {
            val preferences = base.prefs(SettingsKeys.GROUP)
            fun read() = onMain {
                try { ui.settings(HookSettings.read(preferences)) }
                catch (error: RuntimeException) {
                    ui.settings(DISABLED)
                    IslandLog.warn("spectrum_settings_unavailable", error)
                }
            }
            base.runtime.manage(preferences.observe { read() })
            observingSettings = true
            read()
        } catch (error: RuntimeException) {
            IslandLog.warn("spectrum_remote_preferences_unavailable", error)
        }
    }

    @Synchronized private fun install(loader: ClassLoader) {
        if (base.runtime.isClosed) return
        val fresh = loaders.add(loader)
        base.withClassLoader(loader) {
            val controller = hostClass(loader, CONTROLLER)
            val holder = hostClass(loader, HOLDER)
            val plugin = hostClass(loader, PLUGIN)
            if (fresh) IslandLog.info("loader_route controller=${controller != null} holder=${holder != null} plugin=${plugin != null}")
            if (controller != null) {
                installMethod(controller, "addDynamicIslandView", Role.MEDIA, MEDIA_HOLDER, MEDIA_HOLDER, MEDIA)
                installMethod(controller, "generateRightImage", Role.RIGHT, "org.json.JSONObject", MEDIA, "android.os.Bundle")
                installMethod(controller, "removeDynamicIslandView\$1", Role.REMOVE, "java.lang.String", "boolean")
            }
            if (holder != null) {
                for (name in listOf("bind", "updatePartial")) installMethod(holder, name, Role.HOLDER, TEMPLATE, DATA)
                for (name in listOf("onShow", "onHidden", "onDetach")) installMethod(holder, name, Role.HOLDER)
                for (name in listOf("playAnimation", "registerLottieCallback")) installMethod(holder, name, Role.ANIMATION)
                installMethod(holder, "getLottieColor", Role.PALETTE, "android.graphics.Bitmap")
                try {
                    for (method in holder.declaredMethods) {
                        if (method.name in LOTTIE_CALLERS && deoptimized.add(method)) method.deoptimize()
                    }
                } catch (error: RuntimeException) { IslandLog.warn("spectrum_deopt_unavailable", error) }
                catch (error: LinkageError) { IslandLog.warn("spectrum_deopt_unavailable", error) }
            }
            if (plugin != null) installMethod(plugin, "onPluginLoaded", Role.PLUGIN,
                "com.android.systemui.plugins.Plugin", "android.content.Context", "com.android.systemui.plugins.PluginLifecycleManager")
        }
    }

    private enum class Role { MEDIA, RIGHT, REMOVE, HOLDER, ANIMATION, PALETTE, PLUGIN }

    private fun PackageScope.installMethod(owner: Class<*>, name: String, role: Role, vararg parameters: String) {
        val label = "${owner.name}.$name"
        try {
            val method = owner.declaredMethods.filter {
                it.name == name && (if (role == Role.PALETTE) !it.returnType.isPrimitive else it.returnType == Void.TYPE)
                    && it.parameterTypes.map(Class<*>::getName) == parameters.asList()
            }.singleOrNull() ?: throw NoSuchMethodException(label)
            if (method in hooked) return
            method.intercept {
                val result = proceed(arguments.toTypedArray())
                if (!base.runtime.isClosed) {
                    try { after(role, name, receiver, arguments, result) }
                    catch (error: Exception) { IslandLog.warn("observer_error role=$role", error) }
                    catch (error: LinkageError) { IslandLog.warn("observer_error role=$role", error) }
                }
                result
            }
            hooked.add(method)
            IslandLog.info("hook_ready role=$role owner=$label")
        } catch (error: Exception) { IslandLog.warn("hook_unavailable $label", error) }
        catch (error: LinkageError) { IslandLog.warn("hook_unavailable $label", error) }
    }

    private fun after(role: Role, name: String, receiver: Any?, args: List<Any?>, result: Any?) {
        when (role) {
            Role.MEDIA -> observe(receiver, args[2])
            Role.RIGHT -> {
                observe(receiver, args[1])
                if (IslandLog.is_debug) {
                    val pic = (args[0] as? JSONObject)?.optJSONObject("imageTextInfoRight")?.optJSONObject("picInfo")
                    IslandLog.info("right_image pic=${pic?.optString("pic") ?: "missing"}")
                }
            }
            Role.REMOVE -> (args[0] as? String)?.let { key -> onMain { ui.removed(key) } }
            Role.HOLDER, Role.ANIMATION -> if (receiver != null) onMain {
                ui.holder(receiver, if (role == Role.ANIMATION) "animation" else name, null)
            }
            Role.PALETTE -> if (result != null && args[0] is Bitmap) ui.originalColors(args[0] as Bitmap, result)
            Role.PLUGIN -> args[0]?.javaClass?.classLoader?.let(::install)
        }
    }

    private fun observe(controller: Any?, media: Any?) {
        if (controller == null || media == null) return
        onMain {
            var state = "missing"
            val target = MediaTarget.observe(controller, media) { state = "unavailable reason=$it" }
            if (target != null) state = "playing=${target.playing} route=${target.route} uid=${target.uid} label=${target.appLabel != null}"
            if (lastMediaState != state) {
                lastMediaState = state
                IslandLog.info("media_state $state")
            }
            if (target != null) ui.media(target)
        }
    }

    private fun onMain(action: () -> Unit) {
        val run = Runnable {
            if (!base.runtime.isClosed) {
                try { action() }
                catch (error: Exception) { IslandLog.warn("observer_error main", error) }
                catch (error: LinkageError) { IslandLog.warn("observer_error main", error) }
            }
        }
        if (Looper.myLooper() == main.looper) run.run() else main.post(run)
    }

    private fun hostClass(loader: ClassLoader, name: String): Class<*>? = try {
        Class.forName(name, false, loader)
    } catch (_: ClassNotFoundException) { null }
    catch (error: LinkageError) { IslandLog.warn("spectrum_class_unavailable $name", error); null }

    companion object {
        private val DISABLED = HookSettings(false, emptySet(), false, 24000, 30, "original", 1f, true)
        private val LOTTIE_CALLERS = setOf("playAnimation\$lambda\$4", "access\$getLottieColor")
        private const val CONTROLLER = "com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaControllerImpl"
        private const val MEDIA_HOLDER = "com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaViewHolder"
        private const val MEDIA = "com.android.systemui.media.controls.shared.model.MediaData"
        private const val PLUGIN = "com.android.systemui.statusbar.notification.DynamicIslandPluginController"
        private const val HOLDER = "miui.systemui.dynamicisland.module.IslandIconViewHolder"
        private const val TEMPLATE = "miui.systemui.dynamicisland.model.IslandTemplate"
        private const val DATA = "com.android.systemui.plugins.miui.dynamicisland.DynamicIslandData"
    }
}
