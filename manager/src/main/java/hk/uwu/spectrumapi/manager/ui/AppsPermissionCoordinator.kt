package hk.uwu.spectrumapi.manager.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import hk.uwu.spectrumapi.manager.config.SettingsKeys

/** Only the MIUI application-list permission is requested automatically. */
class AppsPermissionCoordinator(private val activity: ComponentActivity) {
    var granted by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
        private set
    var revision by mutableIntStateOf(0)
        private set
    private var provider: String? = null
    private var pending = false
    // This is only a permission-prompt marker, not a fallback for framework-owned settings.
    private val promptHistory = activity.getSharedPreferences("manager_permission_prompt", 0)
    private val request = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending = false
        refresh()
        revision++
    }

    init { refresh() }

    fun onResume() {
        refresh()
        // Also refresh a visible selector after returning from system settings/package changes.
        revision++
        activity.window.decorView.post { launchPermission(userInitiated = false) }
    }

    fun refresh() {
        provider = try {
            activity.packageManager.getPermissionInfo(SettingsKeys.INSTALLED_APPS_PERMISSION, 0).packageName
        } catch (_: PackageManager.NameNotFoundException) { null }
        catch (_: RuntimeException) { "unverified" }
        granted = ApplicationListPermissionPolicy.directoryGranted(provider,
            runtimeGranted = provider == ApplicationListPermissionPolicy.MIUI_PROVIDER &&
                activity.checkSelfPermission(SettingsKeys.INSTALLED_APPS_PERMISSION) == PackageManager.PERMISSION_GRANTED)
        message = when {
            provider == null -> "此系统无 MIUI 应用列表运行权限；使用 Manifest 应用可见性声明。"
            provider != ApplicationListPermissionPolicy.MIUI_PROVIDER -> "应用列表权限提供者无法核验，已停止申请和目录覆盖。"
            granted -> "应用列表权限已授予。"
            else -> "应用列表权限未授予。可重试或前往系统设置，原白名单保持不变。"
        }
    }

    fun requestPermission() = launchPermission(userInitiated = true)

    private fun launchPermission(userInitiated: Boolean) {
        refresh()
        if (!ApplicationListPermissionPolicy.shouldRequest(provider = provider, granted = granted,
                resumed = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                pending = pending, attempted = promptHistory.getBoolean("apps_attempted", false),
                userInitiated = userInitiated)) return
        // Mark before launching: a denial, launch failure or recreated Activity must not auto-loop.
        promptHistory.edit().putBoolean("apps_attempted", true).apply()
        pending = true
        try { request.launch(SettingsKeys.INSTALLED_APPS_PERMISSION) }
        catch (_: RuntimeException) { pending = false; message = "暂时无法打开权限请求，请前往系统设置。" }
    }

    fun openSettings() {
        runCatching {
            activity.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", activity.packageName, null)))
        }.onFailure { message = "无法打开应用设置，请在系统权限管理中操作。" }
    }

}
