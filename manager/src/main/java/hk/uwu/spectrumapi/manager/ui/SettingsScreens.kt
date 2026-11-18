package hk.uwu.spectrumapi.manager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hk.uwu.spectrumapi.manager.ManagerController
import hk.uwu.spectrumapi.manager.config.RemoteSettings
import hk.uwu.spectrumapi.manager.config.SettingsKeys
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.*
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun HomePage(controller: ManagerController, navigate: (String) -> Unit) {
    PageColumn {
        Section("媒体岛") {
            BasicComponent(title = "配置连接", summary = if (controller.ready) "框架远程配置已连接" else "未连接，只读浏览")
            BasicComponent(title = "原生服务", summary = controller.preview.nativeState)
            BasicComponent(title = "启用状态", summary = if (!controller.ready) "等待远程配置" else if (controller.settings.enabled) "已启用配置" else "已关闭")
            BasicComponent(title = "媒体应用", summary = if (controller.ready) "已选 ${controller.settings.whitelist.size} 个应用" else "等待远程配置")
        }
        if (!controller.ready) Section {
            ArrowPreference(title = "连接框架后才能保存", summary = "前往权限与作用域检查框架连接", onClick = { navigate("permissions") })
        } else if (controller.settings.whitelist.isEmpty()) Section {
            ArrowPreference(title = "选择媒体应用", summary = "空白名单不会接管任何媒体岛", onClick = { navigate("apps") })
        }
        Note("原生频谱服务对所有 SDK 接入方直接开放；此处媒体应用白名单仅控制超级岛频谱显示范围。打开 Manager 不自动采集。")
    }
}

@Composable
internal fun ConfigPage(controller: ManagerController, navigate: (String) -> Unit) {
    PageColumn {
        WriteStatus(controller)
        Section("媒体岛") {
            SwitchPreference(title = "启用媒体岛频谱", summary = "仅在明确选定的媒体应用上岛时显示",
                checked = controller.settings.enabled, enabled = controller.ready && !controller.saving,
                onCheckedChange = { controller.commit(RemoteSettings.Patch(enabled = it)) })
            ArrowPreference(title = "媒体应用", summary = "已选 ${controller.settings.whitelist.size} 个 · 空列表表示不接管",
                onClick = { navigate("apps") })
        }
        Section("显示与运行") {
            ArrowPreference(title = "外观", summary = "配色、模糊与柱尺寸", onClick = { navigate("appearance") })
            ArrowPreference(title = "布局兼容", summary = "超级岛布局适配", onClick = { navigate("compatibility") })
            ArrowPreference(title = "性能", summary = "分析率与更新率", onClick = { navigate("performance") })
        }
        Section {
            ArrowPreference(title = "权限与作用域", summary = "应用列表权限与 Xposed SystemUI 作用域", onClick = { navigate("permissions") })
        }
    }
}

@Composable
internal fun AppearancePage(controller: ManagerController) {
    val settings = controller.settings
    val writable = controller.ready && !controller.saving
    PageColumn {
        WriteStatus(controller)
        Section("六柱显示") {
            OverlayDropdownPreference(title = "配色", items = listOf("原始配色", "HyperOS", "iOS"),
                selectedIndex = listOf("original", "hyperos", "ios").indexOf(settings.colorScheme).coerceAtLeast(0),
                enabled = writable, onSelectedIndexChange = {
                    controller.commit(RemoteSettings.Patch(colorScheme = listOf("original", "hyperos", "ios")[it]))
                })
            SwitchPreference(title = "模糊", checked = settings.blur, enabled = writable,
                onCheckedChange = { controller.commit(RemoteSettings.Patch(blur = it)) })
            CommitSlider("柱尺寸倍率", settings.barScale, 0.5f..2f, writable, controller.saving,
                format = { String.format(Locale.ROOT, "%.2f×", it) }) {
                controller.commit(RemoteSettings.Patch(barScale = it))
            }
            CommitSlider("波动幅度", settings.amplitude, SettingsKeys.MIN_AMPLITUDE..SettingsKeys.MAX_AMPLITUDE,
                writable, controller.saving, format = { String.format(Locale.ROOT, "%.2f×", it) }) {
                controller.commit(RemoteSettings.Patch(amplitude = it))
            }
        }
        Note("波动幅度只调节柱高响应，与柱尺寸倍率独立，不影响音量或采集。滑块结束拖动后提交。")
    }
}

@Composable
internal fun CompatibilityPage(controller: ManagerController) {
    PageColumn {
        WriteStatus(controller)
        Section {
            SwitchPreference(title = "超级岛布局兼容", summary = "默认关闭；仅在当前系统布局确有需要时开启",
                checked = controller.settings.compatibility, enabled = controller.ready && !controller.saving,
                onCheckedChange = { controller.commit(RemoteSettings.Patch(compatibility = it)) })
        }
        Note("结构性布局改动可能需要按框架要求重新加载 SystemUI；不会自动重启系统进程。")
    }
}

@Composable
internal fun PerformancePage(controller: ManagerController) {
    val settings = controller.settings
    val maxRate = controller.preview.maxRate()
    val maxFps = controller.preview.maxFps()
    val writable = controller.ready && !controller.saving
    var edit by rememberSaveable { mutableStateOf<String?>(null) }
    var input by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    fun open(field: String, value: Int) { edit = field; input = value.toString(); error = "" }
    fun submit() {
        if (!writable) { error = "远程配置当前不可写，请稍后重试"; return }
        val min = if (edit == "analysis") 8000 else 1
        val max = if (edit == "analysis") maxRate else maxFps
        val value = input.toIntOrNull()
        if (value == null || !input.matches(Regex("[0-9]+")) || value !in min..max) {
            error = "请输入 $min–$max 范围内的十进制整数"
        } else {
            controller.commit(if (edit == "analysis") RemoteSettings.Patch(analysisRate = value) else RemoteSettings.Patch(updateRate = value))
            edit = null
        }
    }
    PageColumn {
        WriteStatus(controller)
        Section("分析") {
            val rates = listOf(8000, 12000, 16000, 24000, 32000, 48000, 96000, 192000).filter { it <= maxRate }
            val choices = (rates + settings.analysisRate).distinct().sorted()
            OverlayDropdownPreference(title = "分析采样率", summary = "更高分析率会增加开销；上限以能力报告为准",
                items = choices.map { "$it Hz" }, selectedIndex = choices.indexOf(settings.analysisRate).coerceAtLeast(0), enabled = writable,
                onSelectedIndexChange = { index ->
                    if (choices[index] <= maxRate) controller.commit(RemoteSettings.Patch(analysisRate = choices[index]))
                })
            ArrowPreference(title = "自定义分析率", summary = "${settings.analysisRate} Hz", enabled = writable,
                onClick = { open("analysis", settings.analysisRate) })
        }
        Section("更新") {
            if (maxFps > 1) CommitSlider("数据更新率", settings.updateRate.toFloat(), 1f..maxFps.toFloat(), writable, controller.saving,
                format = { "${it.roundToInt()} Hz" }, steps = (maxFps - 2).coerceAtLeast(0)) {
                controller.commit(RemoteSettings.Patch(updateRate = it.roundToInt()))
            } else BasicComponent(title = "数据更新率", summary = "本机能力上限为 1 Hz")
            ArrowPreference(title = "精确更新率", summary = "${settings.updateRate} Hz", enabled = writable,
                onClick = { open("update", settings.updateRate) })
        }
        Note("每条会话独立配置分析采样率与更新率，即时作用于超级岛订阅。")
    }
    OverlayDialog(show = edit != null, title = if (edit == "analysis") "分析率（Hz）" else "更新率（Hz）",
        onDismissRequest = { edit = null }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InputField(query = input, onQueryChange = { input = it; error = "" }, onSearch = { submit() },
                expanded = false, onExpandedChange = {}, label = "十进制整数")
            if (error.isNotEmpty()) Note(error)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { edit = null }, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = { submit() }, enabled = writable, modifier = Modifier.weight(1f)) { Text("保存") }
            }
        }
    }
}

@Composable
private fun CommitSlider(title: String, remote: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean,
    saving: Boolean, format: (Float) -> String, steps: Int = 0, commit: (Float) -> Unit) {
    val displayed = remote.takeIf { it.isFinite() }?.coerceIn(range) ?: range.start
    var draft by remember { mutableFloatStateOf(displayed) }
    LaunchedEffect(remote, saving) { if (!saving) draft = displayed }
    SliderPreference(title = title, value = draft, onValueChange = { draft = it }, valueText = format(draft),
        enabled = enabled, valueRange = range, steps = steps,
        onValueChangeFinished = { if (enabled && draft != remote) commit(draft) })
}

@Composable
internal fun PermissionsPage(controller: ManagerController) {
    PageColumn {
        Section("应用列表") {
            ArrowPreference(title = "应用列表权限", summary = controller.permissions.message,
                onClick = controller.permissions::requestPermission)
            ArrowPreference(title = "系统应用设置", summary = "拒绝后可在系统中修改；返回时自动刷新",
                onClick = controller.permissions::openSettings)
        }
        Section("Xposed 框架") {
            ArrowPreference(title = "刷新框架状态", summary = if (controller.frameworkConnected) "框架已连接；详细信息见故障排查" else "尚未连接框架",
                onClick = controller::refreshFramework)
            ArrowPreference(title = "请求 SystemUI 作用域", summary = "由框架确认，不自动重启 SystemUI",
                enabled = controller.frameworkConnected, onClick = controller::requestScope)
        }
        Note("应用列表权限仅用于选择超级岛显示白名单应用；原生频谱服务本身不需要任何 UID 鉴权或 root 授权。")
    }
}

@Composable
private fun WriteStatus(controller: ManagerController) {
    if (controller.saving) Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        InfiniteProgressIndicator(Modifier.size(20.dp))
        Text("正在提交远程配置…")
    } else if (!controller.ready) Note(controller.remoteMessage)
}
