package hk.uwu.spectrumapi.manager

import android.content.Context
import android.graphics.Rect
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.viewinterop.AndroidView
import hk.uwu.spectrumapi.*
import hk.uwu.spectrumapi.ipc.Capabilities
import hk.uwu.spectrumapi.ipc.SourceInfo
import java.util.Locale
import java.util.concurrent.CompletableFuture

/** Owns real SDK resources; route/background/visibility transitions invalidate all late results. */
class PreviewController(private val activity: ComponentActivity) {
    private var visible = false
    private var lifeGeneration = 0
    private var previewGeneration = 0
    private var client: SpectrumClient? = null
    private var connecting: CompletableFuture<SpectrumClient>? = null
    private var subscribing: CompletableFuture<SpectrumSubscription>? = null
    private var subscription: SpectrumSubscription? = null
    private var previewConnecting: CompletableFuture<SpectrumClient>? = null
    private var requested = false
    private var routeVisible = false
    private var composeVisible = false
    private var host: PreviewHost? = null
    private var capabilities: Capabilities? = null
    private var lastSummary = 0L
    var nativeState by mutableStateOf("尚未检查")
        private set
    var sdkText by mutableStateOf("尚未手动连接原生服务；不自动采集。")
        private set
    var capabilitiesText by mutableStateOf("Capabilities 尚未读取")
        private set
    var sourceText by mutableStateOf("音源尚未读取。")
        private set
    var sources by mutableStateOf<List<SourceInfo>>(emptyList())
        private set
    var previewText by mutableStateOf("没有真实数据时不生成示例波形或频谱。")
        private set
    var targetPackage by mutableStateOf("")
        private set
    var previewUid by mutableStateOf("")
        private set
    var trackToken by mutableStateOf("")
        private set
    var trackMode by mutableStateOf(false)
        private set
    var outputMode by mutableIntStateOf(SpectrumOptions.OUTPUT_SPECTRUM)
        private set

    fun onStart() { visible = true; lifeGeneration++ }
    fun onStop() { visible = false; lifeGeneration++; release("页面进入后台；已释放，返回后需手动开始。") }
    fun close() { onStop(); host?.releaseObservers() }
    fun enterPage() { routeVisible = true }
    fun leavePage(message: String = "已离开预览；订阅与连接均已释放，返回后需手动开始。") {
        routeVisible = false
        release(message)
    }

    fun release(message: String) {
        val hadConnection = client != null || connecting != null
        stop(message)
        connecting?.cancel(true)
        connecting = null
        client?.close()
        client = null
        // Invalidate pending diagnostics too; no off-page callback may recreate a connection.
        lifeGeneration++
        if (hadConnection) nativeState = "连接已释放；上次结果见故障排查"
    }

    fun setTarget(value: String) { stop("目标已变化，请手动开始。"); targetPackage = value; previewUid = ""; trackToken = "" }
    fun setTrack(value: String) { stop("Track 已变化，请手动开始。"); trackToken = value }
    fun changeTrackMode(value: Boolean) { stop("选择模式已变化，请手动开始。"); trackMode = value }
    fun setOutput(value: Int) { stop("输出模式已变化，请手动开始。"); outputMode = value; host?.graph?.setOutputMode(value) }
    fun selectSource(source: SourceInfo) {
        stop("已选择真实音源，请手动开始。")
        previewUid = source.uid.toString()
        trackToken = source.token.toString()
        targetPackage = activity.packageManager.getPackagesForUid(source.uid)?.singleOrNull().orEmpty()
    }

    private fun connect(): CompletableFuture<SpectrumClient> {
        check(visible) { "页面不可见" }
        client?.let { if (!it.isClosed) return CompletableFuture.completedFuture(it) }
        connecting?.let { if (!it.isDone) return it }
        val life = lifeGeneration
        sdkText = "通过 AudioFlinger Binder Extension 查找原生服务…"
        nativeState = "正在检查"
        val attempt = SpectrumClient.connect()
        connecting = attempt
        attempt.whenComplete { connected, error -> activity.runOnUiThread {
            if (!current(life)) { connected?.close(); return@runOnUiThread }
            if (connecting === attempt) connecting = null
            if (error != null) {
                sdkText = "发现或连接失败：${errorText(error)}"
                nativeState = "连接失败，需排查"
            } else {
                client?.takeIf { it !== connected }?.close()
                client = connected
                sdkText = "已连接原生服务扩展；可读取能力、活跃音源并启动真实预览。"
                nativeState = "服务可访问，采集未验证"
            }
        } }
        return attempt
    }

    fun refreshCapabilities() {
        if (!visible) return
        val life = lifeGeneration
        connect().thenCompose { it.getCapabilities() }.whenComplete { value, error -> activity.runOnUiThread {
            if (!current(life)) return@runOnUiThread
            if (error != null) capabilitiesText = "读取失败：${errorText(error)}"
            else {
                capabilities = value
                nativeState = if (value.captureAvailable) "服务可访问，采集能力已报告" else "服务可访问，采集能力不可用"
                capabilitiesText = "protocolVersion=${value.protocolVersion}\ncaptureAvailable=${value.captureAvailable}\ndiagnostic=${value.diagnostic}\nmaxAnalysisRateHz=${value.maxAnalysisRateHz}\nmaxUpdateRateHz=${value.maxUpdateRateHz}\nmaxBandCount=${value.maxBandCount}\nmaxActiveSources=${value.maxActiveSources}\nsupportedModes=0x${Integer.toHexString(value.supportedModes)}"
            }
        } }
    }

    fun refreshSources() {
        if (!visible) return
        val life = lifeGeneration
        connect().thenCompose { it.listSources() }.whenComplete { value, error -> activity.runOnUiThread {
            if (!current(life)) return@runOnUiThread
            if (error != null) { sources = emptyList(); sourceText = "读取失败：${errorText(error)}" }
            else { sources = value.filterNotNull().take(128); sourceText = "返回 ${value.size} 个音源，最多显示 128。" }
        } }
    }

    fun start(rateInput: String, fpsInput: String) {
        if (!actuallyVisible()) { previewText = "请在预览图可见时手动开始；隐藏时不采集。"; return }
        val options = try {
            val source = if (trackMode) SourceSelector.track(trackToken.toLongOrNull()?.takeIf { it > 0 } ?: error("Track token 必须为正整数"))
            else {
                val pkg = targetPackage.trim()
                require(pkg.isNotEmpty()) { "请填写已安装应用包名，不从标签猜测 UID" }
                val uid = activity.packageManager.getApplicationInfo(pkg, 0).uid
                require(uid / 100000 == Process.myUid() / 100000) { "不跨用户猜测 UID" }
                previewUid = uid.toString()
                SourceSelector.app(uid)
            }
            SpectrumOptions.builder().source(source).outputMode(outputMode)
                .updateRateHz(decimal(fpsInput, "预览更新率", 1, maxFps())).also { builder ->
                    if (outputMode != SpectrumOptions.OUTPUT_WAVEFORM) {
                        val rate = decimal(rateInput, "预览分析率", 8000, maxRate())
                        builder.analysisRate(AnalysisRate.auto(rate)).bandCount(16).frequencyRange(20f, minOf(10000f, rate / 2f))
                    }
                }.build()
        } catch (failure: Exception) { previewText = errorText(failure); return }
        stop("正在打开真实定向订阅…")
        requested = true
        host?.graph?.setOutputMode(outputMode)
        val life = lifeGeneration
        val preview = previewGeneration
        val connection = connect()
        previewConnecting = connection
        connection.whenComplete { connected, error -> activity.runOnUiThread {
            if (!current(life) || preview != previewGeneration) return@runOnUiThread
            previewConnecting = null
            if (!actuallyVisible()) { stop("预览已隐藏；连接已取消，请手动开始。"); return@runOnUiThread }
            if (error != null) { stop("连接失败：${errorText(error)}"); return@runOnUiThread }
            val attempt = connected.subscribe(options)
            subscribing = attempt
            attempt.whenComplete { created, failure -> activity.runOnUiThread accept@ {
                if (!current(life) || preview != previewGeneration) { created?.close(); return@accept }
                subscribing = null
                if (failure != null) { stop("订阅不可用：${errorText(failure)}"); return@accept }
                if (!actuallyVisible()) { created.close(); stop("预览已隐藏，迟到订阅已释放。"); return@accept }
                subscription = created
                lastSummary = 0L
                created.setListener(activity.mainExecutor, object : SpectrumSubscription.Listener {
                    override fun onFrame(frame: ReusableSpectrumFrame) {
                        if (!current(life) || preview != previewGeneration) return
                        val changed = host?.graph?.showFrame(frame) ?: false
                        val now = SystemClock.uptimeMillis()
                        if (changed || now - lastSummary >= 500) { lastSummary = now; previewText = frameSummary(frame) }
                    }
                    override fun onStateChanged(status: Int, generation: Long) {
                        if (!current(life) || preview != previewGeneration) return
                        host?.graph?.onStateChanged(status, generation)
                        previewText = "${statusName(status)} · generation $generation\n${host?.graph?.waveformMessage.orEmpty()}"
                    }
                })
                created.setActive(true).whenComplete { _, activationError -> activity.runOnUiThread {
                    if (current(life) && preview == previewGeneration && activationError != null) stop("激活失败：${errorText(activationError)}")
                } }
            } }
        } }
    }

    fun stop(message: String = "已停止真实订阅。") {
        previewGeneration++
        requested = false
        val pending = previewConnecting
        previewConnecting = null
        if (pending != null && !pending.isDone) {
            if (connecting === pending) connecting = null
            pending.cancel(true)
        }
        subscribing?.cancel(true)
        subscribing = null
        subscription?.close()
        subscription = null
        host?.graph?.clear()
        previewText = message
    }

    private fun actuallyVisible(): Boolean {
        val view = host ?: return false
        val rect = Rect()
        return visible && routeVisible && composeVisible && view.isAttachedToWindow && view.isShown &&
            view.windowVisibility == View.VISIBLE && view.getGlobalVisibleRect(rect) && !rect.isEmpty
    }

    private fun enforceVisibility() {
        if (requested && !actuallyVisible()) release("预览图已隐藏；请求、订阅与连接已释放，恢复后需手动开始。")
    }

    private inner class PreviewHost(context: Context) : FrameLayout(context) {
        val graph = SpectrumBarsView(context)
        private var observedTree: ViewTreeObserver? = null
        private val scroll = ViewTreeObserver.OnScrollChangedListener { enforceVisibility() }
        private val layout = ViewTreeObserver.OnGlobalLayoutListener { enforceVisibility() }
        init { addView(graph, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            releaseObservers()
            observedTree = viewTreeObserver.also { it.addOnScrollChangedListener(scroll); it.addOnGlobalLayoutListener(layout) }
            enforceVisibility()
        }
        override fun onDetachedFromWindow() {
            releaseObservers()
            if (requested) release("预览 View 已移除，全部资源已释放。")
            super.onDetachedFromWindow()
        }
        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            if (visibility != View.VISIBLE && requested) release("预览窗口不可见，全部资源已释放。")
        }
        override fun onVisibilityAggregated(isVisible: Boolean) {
            super.onVisibilityAggregated(isVisible)
            if (!isVisible && requested) release("预览 View 不可见，全部资源已释放。")
        }
        fun releaseObservers() {
            observedTree?.takeIf { it.isAlive }?.let { it.removeOnScrollChangedListener(scroll); it.removeOnGlobalLayoutListener(layout) }
            observedTree = null
        }
    }

    @Composable
    fun Graph(modifier: Modifier = Modifier) {
        AndroidView(factory = { context -> PreviewHost(context).also {
            host = it
            it.graph.setOutputMode(outputMode)
        } }, modifier = modifier.onGloballyPositioned {
            val bounds = it.boundsInWindow()
            composeVisible = it.isAttached && bounds.width > 0f && bounds.height > 0f
            enforceVisibility()
        }, onReset = null, onRelease = {
            it.releaseObservers()
            release("预览视图已移除；返回后需手动开始。")
            if (host === it) { host = null; composeVisible = false }
        })
    }

    private fun frameSummary(frame: ReusableSpectrumFrame): String = buildString {
        append("${statusName(frame.status)} · 帧 ${frame.sequence} · generation ${frame.generation}")
        if (frame.outputMode != SpectrumOptions.OUTPUT_WAVEFORM) append(String.format(Locale.ROOT,
            "\n实际分析率 %d Hz · %d 带 · RMS %.1f / Peak %.1f dBFS\n覆盖 %d‰ · 丢帧 %d",
            frame.actualAnalysisRateHz, frame.bandCount, frame.rmsDb, frame.peakDb, frame.coverage, frame.droppedUnsigned))
        if (frame.outputMode != SpectrumOptions.OUTPUT_SPECTRUM) {
            append("\n${host?.graph?.waveformMessage.orEmpty()}\n源采样率 ${frame.waveformSourceRateHz} Hz · ${frame.waveformBinCount} 桶")
            append("\n真实窗口 ${frame.waveformWindowFrames} 源帧 / ${frame.waveformWindowDurationNs / 1_000_000.0} ms")
            append("\nTrack ${frame.waveformTrackToken} · source epoch ${frame.waveformSourceEpoch}")
            append("\n结束源序号 ${frame.waveformEndSourceSequence} · 采集时间 ${frame.waveformCaptureEndTimeNs} ns（非 presentation）")
        }
    }

    fun diagnosticsReport(): String = buildString {
        append("$sdkText\n$capabilitiesText\n$sourceText")
        sources.forEach { append("\nUID=${it.uid} Track=${it.token} ${it.label.orEmpty()} ${it.sampleRate}Hz channels=${it.channels} active=${it.active} format=0x${Integer.toHexString(it.format)}") }
        append("\n$previewText")
    }

    fun maxRate() = minOf(192000, capabilities?.maxAnalysisRateHz?.takeIf { it >= 8000 } ?: 192000)
    fun maxFps() = minOf(60, capabilities?.maxUpdateRateHz?.takeIf { it > 0 } ?: 60)
    private fun current(life: Int) = visible && lifeGeneration == life
}

private fun statusName(status: Int) = when (status) {
    SpectrumStatus.OK -> "正常"
    SpectrumStatus.IDLE -> "空闲，等待媒体音源"
    SpectrumStatus.SILENT -> "静音"
    SpectrumStatus.PARTIAL -> "部分音源覆盖"
    SpectrumStatus.UNSUPPORTED_PATH -> "不支持此音频路径"
    SpectrumStatus.UNSUPPORTED_BUILD -> "不支持当前系统构建"
    SpectrumStatus.DISCONNECTED -> "连接已断开"
    SpectrumStatus.DROPPED -> "有采样丢帧"
    else -> "状态 $status"
}
