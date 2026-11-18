package hk.uwu.spectrumapi.manager.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import hk.uwu.spectrumapi.manager.config.SettingsKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference

private data class AppRow(override val name: String, override val pkg: String, override val system: Boolean,
    val info: ApplicationInfo) : ApplicationListItem
private val PackageSetSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

/** A package-name draft, never a UID list. Only a successful complete catalog may be saved. */
@Composable
fun AppSelector(
    packageManager: PackageManager,
    saved: Set<String>,
    canSave: Boolean,
    permissionGranted: Boolean,
    permissionMessage: String,
    permissionRevision: Int,
    onPermission: () -> Unit,
    onSettings: () -> Unit,
    onSave: (Set<String>, Set<String>) -> Unit,
    onCancel: () -> Unit,
) {
    val baseline = rememberSaveable(saver = PackageSetSaver) { saved.toSet() }
    var selection by rememberSaveable(stateSaver = PackageSetSaver) { mutableStateOf(saved.toSet()) }
    var query by rememberSaveable { mutableStateOf("") }
    var systems by rememberSaveable { mutableStateOf(false) }
    var selectedOnly by rememberSaveable { mutableStateOf(false) }
    var packageSort by rememberSaveable { mutableStateOf(false) }
    var selectedFirst by rememberSaveable { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    var catalog by remember { mutableStateOf<List<AppRow>>(emptyList()) }
    var catalogStatus by remember { mutableStateOf(ApplicationCatalogStatus.UNLOADED) }
    val loading = catalogStatus == ApplicationCatalogStatus.LOADING
    var message by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var clearConfirmation by remember { mutableStateOf(false) }
    var searchExpanded by remember { mutableStateOf(false) }
    var iconRevision by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val conflict = AppSelectionPolicy.hasConflict(baseline, saved)
    fun back() {
        when {
            showMenu -> showMenu = false
            imeVisible -> { keyboard?.hide(); focus.clearFocus() }
            query.isNotEmpty() -> { query = ""; searchExpanded = false; focus.clearFocus() }
            searchExpanded -> { searchExpanded = false; focus.clearFocus() }
            else -> onCancel()
        }
    }
    BackHandler { back() }
    LaunchedEffect(refresh, permissionRevision, permissionGranted) {
        if (!permissionGranted) {
            catalogStatus = AppSelectionPolicy.catalogStatus(false, false, emptyList(), SettingsKeys.SYSTEM_UI)
            catalog = emptyList()
            message = "目录权限不足，不能覆盖已有白名单。"
            return@LaunchedEffect
        }
        catalogStatus = ApplicationCatalogStatus.LOADING
        val result = withContext(Dispatchers.IO) {
            runCatching {
                packageManager.getInstalledApplications(0).map { info ->
                    AppRow(runCatching { info.loadLabel(packageManager).toString() }.getOrDefault(info.packageName),
                        info.packageName, info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0, info)
                }
            }
        }
        result.onSuccess {
            catalog = it
            iconRevision++
            // A restricted/empty result must not silently turn the saved whitelist into an empty set.
            catalogStatus = AppSelectionPolicy.catalogStatus(permissionGranted, true, it.map { row -> row.pkg }, SettingsKeys.SYSTEM_UI)
            message = when (catalogStatus) {
                ApplicationCatalogStatus.EMPTY -> "系统返回空目录，原白名单保留。"
                ApplicationCatalogStatus.READY -> "${it.size} 个应用；空白名单表示不接管任何媒体。"
                else -> "目录可见性不足，无法核验完整目录；已禁止保存。"
            }
        }.onFailure {
            catalogStatus = AppSelectionPolicy.catalogStatus(permissionGranted, false, emptyList(), SettingsKeys.SYSTEM_UI)
            catalog = emptyList()
            message = "目录读取失败：${it.message}；原白名单保留。"
        }
    }
    val visible by produceState(initialValue = emptyList<AppRow>(), catalog, query, systems, selectedOnly, packageSort, selectedFirst, selection) {
        delay(100)
        val snapshot = catalog
        val selected = selection
        val filter = ApplicationListFilter(query, systems, selectedOnly, packageSort, selectedFirst)
        value = withContext(Dispatchers.Default) { AppSelectionPolicy.visible(snapshot, selected, filter) }
    }
    val missing = remember(selection, catalog) { AppSelectionPolicy.retained(selection, catalog.map { it.pkg }) }

    Scaffold(topBar = {
        Column {
            TopAppBar(title = "媒体应用 · 已选 ${selection.size}", navigationIcon = { BackButton(::back) }, actions = {
                Box {
                    IconButton(onClick = { showMenu = true }, holdDownState = showMenu) {
                        Icon(imageVector = AppIcons.More, contentDescription = "排序、筛选与更多操作")
                    }
                    OverlayListPopup(show = showMenu, onDismissRequest = { showMenu = false },
                        alignment = PopupPositionProvider.Align.End, minWidth = 240.dp) {
                        ListPopupColumn {
                            CheckboxPreference(title = "显示系统应用", checked = systems, onCheckedChange = { systems = it; showMenu = false })
                            CheckboxPreference(title = "仅看已选", checked = selectedOnly, onCheckedChange = { selectedOnly = it; showMenu = false })
                            CheckboxPreference(title = "已选置顶", checked = selectedFirst, onCheckedChange = { selectedFirst = it; showMenu = false })
                            CheckboxPreference(title = "按包名排序", checked = packageSort, onCheckedChange = { packageSort = it; showMenu = false })
                            BasicComponent(title = "刷新目录", enabled = !loading, onClick = { showMenu = false; refresh++ })
                            BasicComponent(title = "应用列表权限", onClick = { showMenu = false; onPermission() })
                            BasicComponent(title = "系统应用设置", onClick = { showMenu = false; onSettings() })
                            BasicComponent(title = "清空本次草稿", onClick = { showMenu = false; clearConfirmation = true })
                        }
                    }
                }
            })
            SearchBar(inputField = {
                InputField(query = query, onQueryChange = { query = it }, onSearch = { keyboard?.hide(); focus.clearFocus() },
                    expanded = searchExpanded, onExpandedChange = { searchExpanded = it }, label = "搜索名称或包名")
            }, onExpandedChange = { searchExpanded = it }, expanded = false, content = {})
        }
    }, bottomBar = {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (conflict) Note("远程白名单已变化；请返回取消后重新打开，防止覆盖其他更新。")
            if (!canSave) Note("远程配置当前不可写；允许浏览，不能保存。")
            Button(onClick = {
                keyboard?.hide()
                AppSelectionPolicy.prepareSubmission(selection, baseline, saved, canSave, permissionGranted, catalogStatus)
                    ?.let { onSave(it.packages, it.expected) }
            }, modifier = Modifier.fillMaxWidth(),
                enabled = AppSelectionPolicy.canSave(canSave, permissionGranted, catalogStatus, baseline, saved)) { Text("保存 ${selection.size} 项") }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
            if (!permissionGranted) {
                Note(permissionMessage)
                Note("请使用右上角菜单申请权限或前往系统设置，返回时会自动刷新。")
            }
            if (loading) Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InfiniteProgressIndicator(Modifier.size(20.dp)); Text("正在后台加载应用目录…")
            } else Note(message)
            LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(bottom = 12.dp)) {
                if (missing.isNotEmpty()) item(key = "retained-header") { Note("保留当前不可见 / 已卸载的 ${missing.size} 个已选包") }
                items(missing, key = { "missing:$it" }) { pkg ->
                    CheckboxPreference(title = pkg, summary = "不会因筛选或权限变化自动移除；取消勾选仅影响本次草稿",
                        checked = true, checkboxLocation = CheckboxLocation.End,
                        onCheckedChange = { selection = AppSelectionPolicy.toggle(selection, pkg, it) })
                }
                items(visible, key = { it.pkg }) { app ->
                    CheckboxPreference(title = app.name, summary = app.pkg, checked = app.pkg in selection,
                        checkboxLocation = CheckboxLocation.End, startAction = { ApplicationIcon(packageManager, app.info, iconRevision) },
                        onCheckedChange = { checked -> selection = AppSelectionPolicy.toggle(selection, app.pkg, checked) })
                }
                if (!loading && visible.isEmpty()) item(key = "empty") { Note("没有符合当前搜索或筛选的应用。筛选不会改变草稿。") }
            }
        }
        OverlayDialog(show = clearConfirmation, title = "清空媒体应用草稿？", summary = "保存后将不接管任何媒体；不会自动撤销旧音频授权。",
            onDismissRequest = { clearConfirmation = false }) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { clearConfirmation = false }, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = { selection = emptySet(); clearConfirmation = false }, modifier = Modifier.weight(1f)) { Text("清空草稿") }
            }
        }
    }
}

private object ApplicationIcons {
    val cache = LruCache<String, ImageBitmap>(96)
    val loaders = Semaphore(3)
}

/** Only composed (visible/prefetched) rows decode icons, off-main, with bounded work and memory. */
@Composable
private fun ApplicationIcon(pm: PackageManager, info: ApplicationInfo, revision: Int) {
    val key = "${info.packageName}:${info.sourceDir}:$revision"
    val icon by produceState<ImageBitmap?>(initialValue = ApplicationIcons.cache.get(key), key1 = pm, key2 = key) {
        if (value != null) return@produceState
        val loaded = withContext(Dispatchers.IO) {
            ApplicationIcons.loaders.withPermit {
                ApplicationIcons.cache.get(key) ?: runCatching {
                    val drawable = info.loadIcon(pm)
                    Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888).also {
                        drawable.setBounds(0, 0, 80, 80)
                        drawable.draw(Canvas(it))
                    }.asImageBitmap().also { ApplicationIcons.cache.put(key, it) }
                }.getOrNull()
            }
        }
        value = loaded
    }
    if (icon == null) Spacer(Modifier.size(40.dp))
    else Image(bitmap = icon!!, contentDescription = null, modifier = Modifier.size(40.dp))
}
