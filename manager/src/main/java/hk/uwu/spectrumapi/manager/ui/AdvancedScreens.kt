package hk.uwu.spectrumapi.manager.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import hk.uwu.spectrumapi.SpectrumOptions
import hk.uwu.spectrumapi.manager.ManagerController
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.*

@Composable
internal fun AboutPage(controller: ManagerController, navigate: (String) -> Unit) {
    PageColumn {
        Section {
            BasicComponent(title = "Spectrum API", summary = "版本 ${controller.version}")
            Note("通过原生 AudioFlinger Binder Extension 提供免鉴权的频谱与源域波形服务；Manager 为可选超级岛显示与配置端。")
        }
        Section {
            ArrowPreference(title = "故障排查", summary = "按需读取原生状态、框架详情和真实预览测试", onClick = { navigate("diagnostics") })
        }
        Note("超级岛显示仅订阅白名单媒体应用的 APP_UID，不使用模拟音频。")
    }
}

@Composable
internal fun DiagnosticsPage(controller: ManagerController, navigate: (String) -> Unit) {
    val context = LocalContext.current
    PageColumn {
        Section("当前阶段") {
            BasicComponent(title = if (controller.ready) "配置通路已连接" else "等待配置通路",
                summary = if (controller.ready) "可直连检查原生服务能力、活跃音源与真实预览。" else "先在权限与作用域中检查框架连接、作用域及应用列表权限。")
            ArrowPreference(title = "权限与作用域", onClick = { navigate("permissions") })
        }
        Section("按需检查") {
            ArrowPreference(title = "连接并读取原生能力", summary = controller.preview.nativeState,
                onClick = controller.preview::refreshCapabilities)
            ExpandableDetail("框架 API / 作用域 / 运行目标", controller.frameworkMessage)
            ExpandableDetail("远程配置状态", controller.remoteMessage)
            ExpandableDetail("SDK 与 Capabilities", "${controller.preview.sdkText}\n${controller.preview.capabilitiesText}")
        }
        Section("高级测试") {
            ArrowPreference(title = "真实频谱 / 波形预览", summary = "直连原生服务测试，离开页面或进入后台即释放",
                onClick = { navigate("preview") })
        }
        Action("复制诊断报告") {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("SpectrumAPI 诊断", controller.report()))
        }
        Note("报告可能包含已安装应用包名和音源信息，分享前请自行检查。")
    }
}

@Composable
private fun ExpandableDetail(title: String, detail: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ArrowPreference(title = title, summary = if (expanded) "点击收起" else "点击展开原始详情", onClick = { expanded = !expanded })
    if (expanded) SelectionContainer { Note(detail) }
}

@Composable
internal fun PreviewPage(controller: ManagerController) {
    val preview = controller.preview
    var rate by rememberSaveable { mutableStateOf("24000") }
    var fps by rememberSaveable { mutableStateOf("30") }
    var details by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(preview) {
        preview.enterPage()
        onDispose { preview.leavePage() }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        preview.Graph(Modifier.fillMaxWidth().height(180.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { preview.start(rate, fps) }, modifier = Modifier.weight(1f)) { Text("开始真实预览") }
            Button(onClick = { preview.release("已手动停止并释放；需手动开始。") }, modifier = Modifier.weight(1f)) { Text("停止") }
        }
        Note(preview.previewText.substringBefore('\n'))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Note("直接连接原生服务订阅 APP_UID 或 Track。无真实数据时保持空白，不生成模拟动画；后台返回不会自动重启。")
            Section("音源") {
                Field("目标应用包名（解析当前用户真实 UID）", preview.targetPackage, preview::setTarget)
                SwitchPreference(title = "使用原生 Track token", summary = "关闭时使用目标应用 APP_UID",
                    checked = preview.trackMode, onCheckedChange = preview::changeTrackMode)
                if (preview.trackMode) Field("Track token（不是媒体会话 token）", preview.trackToken, preview::setTrack)
                ArrowPreference(title = "刷新当前音源列表", onClick = preview::refreshSources)
                Note(preview.sourceText)
                preview.sources.forEach { source ->
                    ArrowPreference(title = source.label.orEmpty().ifEmpty { "原生音源" },
                        summary = "UID=${source.uid} · Track=${source.token}\n${source.sampleRate} Hz · channels=${source.channels} · active=${source.active} · format=0x${Integer.toHexString(source.format)}",
                        onClick = { preview.selectSource(source) })
                }
            }
            Section("输出") {
                OverlayDropdownPreference(title = "数据模式", items = listOf("频谱", "波形", "组合"),
                    selectedIndex = preview.outputMode, onSelectedIndexChange = preview::setOutput)
                if (preview.outputMode != SpectrumOptions.OUTPUT_WAVEFORM) Field("预览分析率 Hz（独立 AUTO）", rate) {
                    preview.stop("分析率已变化，请手动开始。"); rate = it
                }
                Field("预览更新率 Hz", fps) { preview.stop("更新率已变化，请手动开始。"); fps = it }
            }
            Section {
                ArrowPreference(title = "实时数据与帧详情", summary = if (details) "点击收起" else "点击展开", onClick = { details = !details })
                if (details) SelectionContainer { Note(preview.previewText) }
            }
            Note("波形是源域约 40 ms / 64 桶跨声道 min/max 包络，不是可回放 PCM 或最终混音。多个匹配 Track 时可能只停用波形。")
        }
    }
}
