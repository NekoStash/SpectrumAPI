package hk.uwu.spectrumapi.manager.hook

import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyModule
import hk.uwu.roxyhook.annotation.RoxyEntry
import hk.uwu.spectrumapi.manager.config.SettingsKeys

/** Optional modern-Xposed APK entry. No SDK dependency on this APK; no hot-reload opt-in. */
@RoxyEntry
class SpectrumHookEntry : RoxyModule() {
    private var hooker: SystemUiHooker? = null

    // Roxy dispatches this with libxposed's default loader, before PACKAGE_READY.
    override fun onPackageLoaded(scope: PackageScope) = scope.installSystemUi()

    override fun PackageScope.onLoad() = installSystemUi()

    private fun PackageScope.installSystemUi() {
        loadApp(SettingsKeys.SYSTEM_UI) {
            if (processName != SettingsKeys.SYSTEM_UI) return@loadApp
            val current = hooker
            if (current == null) {
                val first = SystemUiHooker()
                hooker = first
                loadHooker(first)
            } else {
                // The AppComponentFactory may supply a different loader at PACKAGE_READY.
                current.installFrom(this)
            }
        }
    }
}
