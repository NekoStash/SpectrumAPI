package hk.uwu.spectrumapi.manager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}

@Composable
internal fun Section(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) Text(title, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 12.dp))
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp), content = content)
    }
}

@Composable
internal fun Note(text: String) {
    Text(text, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
}

@Composable
internal fun Action(title: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(title) }
}

@Composable
internal fun Field(label: String, value: String, onChange: (String) -> Unit) {
    TextField(value = value, onValueChange = onChange, label = label, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
internal fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(imageVector = AppIcons.Back, contentDescription = "返回") }
}

/** Small local vectors avoid adding a material-icons dependency for three navigation icons. */
internal object AppIcons {
    private fun vector(name: String, draw: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, pathBuilder = draw)
        }.build()
    val Home = vector("主页") { moveTo(3f, 11f); lineTo(12f, 3f); lineTo(21f, 11f); moveTo(5f, 10f); lineTo(5f, 21f); lineTo(10f, 21f); lineTo(10f, 15f); lineTo(14f, 15f); lineTo(14f, 21f); lineTo(19f, 21f); lineTo(19f, 10f) }
    val Config = vector("配置") { moveTo(5f, 4f); lineTo(5f, 20f); moveTo(12f, 4f); lineTo(12f, 20f); moveTo(19f, 4f); lineTo(19f, 20f); moveTo(2f, 8f); lineTo(8f, 8f); moveTo(9f, 16f); lineTo(15f, 16f); moveTo(16f, 10f); lineTo(22f, 10f) }
    val About = vector("关于") { moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 20f); lineTo(4f, 20f); close(); moveTo(12f, 10f); lineTo(12f, 17f); moveTo(12f, 7f); lineTo(12f, 8f) }
    val Back = vector("返回") { moveTo(14f, 5f); lineTo(7f, 12f); lineTo(14f, 19f) }
    val More = vector("更多操作") { moveTo(5f, 11f); lineTo(5f, 13f); moveTo(12f, 11f); lineTo(12f, 13f); moveTo(19f, 11f); lineTo(19f, 13f) }
}
