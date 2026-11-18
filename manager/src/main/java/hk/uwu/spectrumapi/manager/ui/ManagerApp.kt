package hk.uwu.spectrumapi.manager.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import hk.uwu.spectrumapi.manager.ManagerController
import top.yukonga.miuix.kmp.basic.*

/** Three root tabs plus a tiny saveable configuration tree, without a navigation dependency. */
@Composable
fun ManagerApp(controller: ManagerController) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var stack by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val roots = listOf("home", "config", "about")
    val route = stack.lastOrNull() ?: roots[tab]
    val holder = rememberSaveableStateHolder()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val snackbars = remember { SnackbarHostState() }
    LaunchedEffect(controller.notice) {
        val message = controller.notice
        if (message.isNotEmpty()) {
            snackbars.showSnackbar(message, withDismissAction = true)
            if (controller.notice == message) controller.dismissNotice()
        }
    }
    fun navigate(page: String) {
        if (route == "preview" || route == "diagnostics") controller.preview.leavePage()
        if (page == "apps") holder.removeState("apps")
        stack = stack + page
    }
    fun back() {
        if (route == "preview" || route == "diagnostics") controller.preview.leavePage()
        if (route == "apps") holder.removeState("apps")
        if (stack.isNotEmpty()) stack = stack.dropLast(1) else tab = 0
    }
    BackHandler(stack.isNotEmpty() || tab != 0) {
        if (imeVisible) { keyboard?.hide(); focus.clearFocus() } else back()
    }
    LaunchedEffect(route) {
        if (route == "preview") controller.preview.enterPage()
        else if (route != "diagnostics") controller.preview.leavePage()
    }
    LaunchedEffect(controller.whitelistSaveRevision) {
        if (controller.whitelistSaveRevision > 0 && route == "apps") back()
    }
    val title = when (route) {
        "home" -> "Spectrum API"
        "config" -> "配置"
        "about" -> "关于"
        "appearance" -> "外观"
        "compatibility" -> "布局兼容"
        "performance" -> "性能"
        "permissions" -> "权限与作用域"
        "diagnostics" -> "故障排查"
        "preview" -> "真实预览"
        else -> "Spectrum API"
    }
    Scaffold(snackbarHost = { SnackbarHost(state = snackbars) }, topBar = {
        if (route != "apps") TopAppBar(title = title, navigationIcon = { if (stack.isNotEmpty()) BackButton(::back) })
    }, bottomBar = {
        if (stack.isEmpty()) NavigationBar {
            NavigationBarItem(tab == 0, { controller.preview.leavePage(); tab = 0 }, AppIcons.Home, "主页")
            NavigationBarItem(tab == 1, { controller.preview.leavePage(); tab = 1 }, AppIcons.Config, "配置")
            NavigationBarItem(tab == 2, { controller.preview.leavePage(); tab = 2 }, AppIcons.About, "关于")
        }
    }) { padding ->
        if (route == "apps") {
            holder.SaveableStateProvider("apps") {
                AppSelector(context.packageManager, controller.settings.whitelist,
                    controller.ready && !controller.saving, controller.permissions.granted,
                    controller.permissions.message, controller.permissions.revision,
                    controller.permissions::requestPermission, controller.permissions::openSettings,
                    controller::saveWhitelist, ::back)
            }
        } else Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
            AnimatedContent(targetState = route, label = "manager-route") { page ->
                holder.SaveableStateProvider(page) {
                    when (page) {
                        "home" -> HomePage(controller, ::navigate)
                        "config" -> ConfigPage(controller, ::navigate)
                        "about" -> AboutPage(controller, ::navigate)
                        "appearance" -> AppearancePage(controller)
                        "compatibility" -> CompatibilityPage(controller)
                        "performance" -> PerformancePage(controller)
                        "permissions" -> PermissionsPage(controller)
                        "diagnostics" -> DiagnosticsPage(controller, ::navigate)
                        "preview" -> PreviewPage(controller)
                    }
                }
            }
        }
    }
}
