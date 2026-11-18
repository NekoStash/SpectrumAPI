package hk.uwu.spectrumapi.manager

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import hk.uwu.roxyhook.platform.libxposed.service.LibXposedService
import hk.uwu.roxyhook.platform.libxposed.service.RoxyServices
import hk.uwu.roxyhook.prefs.MutablePreferences
import hk.uwu.roxyhook.prefs.Subscription
import hk.uwu.spectrumapi.manager.config.IslandSettings
import hk.uwu.spectrumapi.manager.config.RemoteSettings
import hk.uwu.spectrumapi.manager.config.SettingsKeys
import hk.uwu.spectrumapi.manager.ui.AppsPermissionCoordinator
import kotlinx.coroutines.*
import java.util.concurrent.CompletionException

/** Framework configuration and direct SDK diagnostics. Never invokes su at runtime. */
class ManagerController(private val activity: ComponentActivity) {
    private val tasks = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val permissions = AppsPermissionCoordinator(activity)
    val preview = PreviewController(activity)
    private var frameworkObserver: Subscription? = null
    private var preferenceObserver: Subscription? = null
    private var service: LibXposedService? = null
    private var preferences: MutablePreferences? = null
    private var remoteGeneration = 0
    private var readGeneration = 0
    private var visible = false
    var settings by mutableStateOf(IslandSettings())
        private set
    var ready by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var frameworkConnected by mutableStateOf(false)
        private set
    var remoteMessage by mutableStateOf("框架未连接；可以浏览，不能保存。不使用本地配置替代。")
        private set
    var frameworkMessage by mutableStateOf("尚未收到框架服务；不代表原生模块未安装。")
        private set
    var notice by mutableStateOf("")
        private set
    var whitelistSaveRevision by mutableIntStateOf(0)
        private set
    val version: String = runCatching {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "未知版本"
    }.getOrDefault("未知版本")

    fun observe() {
        try {
            frameworkObserver = RoxyServices.observe { services ->
                activity.runOnUiThread {
                    attachService(services.singleOrNull())
                    if (services.size > 1) frameworkMessage = "连接了多个框架；为避免写错，已禁止保存。请只保留一个活动框架。"
                }
            }
        } catch (failure: Exception) { frameworkMessage = "框架不可用：${errorText(failure)}" }
    }

    fun onStart() {
        visible = true
        preview.onStart()
    }

    fun onStop() {
        visible = false
        preview.onStop()
    }

    fun close() {
        onStop()
        frameworkObserver?.close()
        preferenceObserver?.close()
        preview.close()
        tasks.cancel()
    }

    fun dismissNotice() { notice = "" }

    fun refreshFramework() = attachService(service)

    private fun attachService(value: LibXposedService?) {
        val generation = ++remoteGeneration
        ++readGeneration
        preferenceObserver?.close()
        preferenceObserver = null
        service = value
        frameworkConnected = value != null
        preferences = null
        ready = false
        if (value == null) {
            remoteMessage = "框架未连接；可以浏览，不能保存。不使用本地配置替代。"
            frameworkMessage = "框架未连接；API、作用域和运行目标未知。"
            return
        }
        tasks.launch {
            try {
                val status = withContext(Dispatchers.IO) {
                    val scope = runCatching { value.scope().joinToString().ifEmpty { "空" } }.getOrElse { "读取失败：${errorText(it)}" }
                    val targets = if (!value.supportsApi102) "API 不支持" else runCatching {
                        value.runningTargets().joinToString("\n") {
                            "${it.processName} · UID ${it.uid} · PID ${it.pid} · ${it.state} · 代码版本 ${it.loadedVersionCode}"
                        }.ifEmpty { "无" }
                    }.getOrElse { "读取失败：${errorText(it)}" }
                    "${value.info.name} ${value.info.version} · API ${value.info.apiVersion}\n作用域：$scope\n运行目标：$targets"
                }
                if (generation != remoteGeneration) return@launch
                frameworkMessage = "$status\n运行目标不等于 Hook 已适配；需结合框架日志和实际媒体岛验证。"
                val loaded = withContext(Dispatchers.IO) {
                    val prefs = value.preferences(SettingsKeys.GROUP)
                    prefs to RemoteSettings.load(prefs)
                }
                if (generation != remoteGeneration) return@launch
                preferences = loaded.first
                settings = loaded.second
                ready = true
                remoteMessage = "使用框架远程偏好；普通选项即时提交。"
                preferenceObserver = loaded.first.observe {
                    activity.runOnUiThread { if (generation == remoteGeneration) reloadSettings() }
                }
            } catch (failure: Exception) {
                if (generation == remoteGeneration) remoteMessage = "远程配置不可用：${errorText(failure)}；禁止保存。"
            }
        }
    }

    private fun reloadSettings() {
        val prefs = preferences ?: return
        val generation = remoteGeneration
        val read = ++readGeneration
        tasks.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { RemoteSettings.load(prefs) }
                if (generation == remoteGeneration && read == readGeneration) {
                    settings = loaded
                    ready = true
                }
            } catch (failure: Exception) {
                if (generation == remoteGeneration && read == readGeneration) {
                    ready = false
                    remoteMessage = "远程读取失败：${errorText(failure)}"
                }
            }
        }
    }

    fun commit(patch: RemoteSettings.Patch) = saveRemote("已保存到框架远程配置。") {
        RemoteSettings.commit(it, patch)
    }

    fun saveWhitelist(packages: Set<String>, expected: Set<String>) {
        permissions.refresh()
        if (!permissions.granted) { notice = "目录权限不足，不能覆盖原白名单。"; return }
        saveRemote("媒体应用白名单已保存。", whitelist = true) {
            RemoteSettings.saveWhitelist(it, packages, expected)
        }
    }

    private fun saveRemote(message: String, whitelist: Boolean = false, write: (MutablePreferences) -> Boolean) {
        val prefs = preferences
        if (!ready || prefs == null || saving) { notice = "远程配置不可写或正在提交，未保存。"; return }
        val generation = remoteGeneration
        saving = true
        ++readGeneration
        tasks.launch {
            var committed = false
            try {
                val loaded = withContext(Dispatchers.IO) {
                    check(write(prefs)) { "框架拒绝提交" }
                    committed = true
                    RemoteSettings.load(prefs)
                }
                if (generation == remoteGeneration) {
                    ++readGeneration
                    settings = loaded
                    notice = message
                    if (whitelist) whitelistSaveRevision++
                }
            } catch (failure: Exception) {
                if (generation == remoteGeneration) {
                    if (committed) {
                        ready = false
                        remoteMessage = "提交已被框架接受，但回读失败；请刷新后确认当前配置。"
                        notice = "$remoteMessage ${errorText(failure)}"
                    } else notice = "未保存，显示远程原值：${errorText(failure)}"
                    reloadSettings()
                }
            } finally { saving = false }
        }
    }

    fun requestScope() {
        if (!visible) return
        runCatching {
            service?.requestScope(listOf(SettingsKeys.SYSTEM_UI)) { result ->
                activity.runOnUiThread {
                    notice = result.fold({ "作用域请求已批准；请按框架要求加载 SystemUI。" }, { "作用域请求失败：${errorText(it)}" })
                    attachService(service)
                }
            } ?: run { notice = "请先连接 Xposed 框架。" }
        }.onFailure { notice = "作用域请求失败：${errorText(it)}" }
    }

    fun report(): String = "SpectrumAPI $version\n$frameworkMessage\n$remoteMessage\n${preview.diagnosticsReport()}"
}

internal fun errorText(error: Throwable): String {
    var cause = error
    while (cause is CompletionException && cause.cause != null) cause = cause.cause!!
    return "${cause.javaClass.simpleName}：${cause.message.orEmpty()}"
}

internal fun decimal(value: String, label: String, min: Int, max: Int): Int {
    require(value.matches(Regex("[0-9]{1,10}"))) { "$label 必须为十进制整数" }
    val parsed = value.toLong()
    require(parsed in min.toLong()..max.toLong()) { "$label 必须在 $min–$max 范围内" }
    return parsed.toInt()
}
