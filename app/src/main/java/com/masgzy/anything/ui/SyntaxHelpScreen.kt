package com.masgzy.anything.ui

import com.masgzy.anything.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 搜索语法教程（alpha18）：全屏教程页，取代原抽屉内的「搜索语法」速查区。
 *
 * 入口：搜索页顶栏 ? 图标、设置页「其他」分区的「搜索语法教程」。
 * 内容与 core/query.go 的解析语义严格同源：
 *   - 空格 = AND；"引号短语" 支持含空格的词；
 *   - type: 类别过滤（中英文别名，多 type 取 OR，剔除目录命中）；
 *   - dir: 父目录路径包含匹配（大小写不敏感，多 dir 取 AND，含空格用引号）；
 *   - 纯过滤器无关键词为非法组合（浏览用 ∞ 类别快筛）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyntaxHelpScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("搜索语法教程") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "三种检索范围",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "搜索页顶部三个页签决定关键词匹配的字段：文件名、office 文档正文、目录名。" +
                            "下方所有语法在任一页签下都可以使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            HelpSection("基础规则")
            HelpRow("合同 模板", "空格分隔多个词，结果需同时包含（AND）")
            HelpRow("\u201c季度 报告\u201d", "用英文引号包成短语：含空格也作为一个词整体匹配")

            HelpSection("type: 限定文件类别")
            HelpRow("type:视频 会议记录", "只搜视频类文件，且包含「会议记录」")
            HelpAliasTable()
            HelpNote("中英文别名均可，如 type:video 与 type:视频 等价；多个 type: 取 OR（任一类别即命中）")
            HelpNote("目录没有类型：带 type: 的查询不会返回目录条目")

            HelpSection("dir: 限定所在目录")
            HelpRow("dir:Download 报告", "限定路径位于名称含 Download 的目录下")
            HelpRow("dir:\"My Documents\" 报告", "目录名含空格时用引号包住")
            HelpNote("多个 dir: 取 AND；匹配的是父目录路径的包含关系，大小写不敏感；目录条目按其父目录参与匹配")

            HelpSection("组合示例")
            HelpRow("type:doc 预算 dir:工作", "工作目录下的文档类文件，包含「预算」")
            HelpRow("type:音乐 type:图片", "音乐或图片（多 type: 取 OR）")
            HelpRow("\u201c进度 汇报\u201d dir:Download", "下载目录下正文/文件名含「进度 汇报」整词的文档")

            HelpSection("注意事项")
            HelpNote("只有 type:/dir: 而没有关键词的查询无效——想按类别浏览文件，请用搜索页底部的 ∞ 类别快筛")
            HelpNote("dir:/type: 是「缩小范围」的过滤器；关键词匹配哪个字段仍由当前页签决定")
        }
    }
}

@Composable
private fun HelpSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/** 语法示例行：语法 chip 独占一行（避免原抽屉窄栏挤压换行），说明在其下。 */
@Composable
private fun HelpRow(syntax: String, desc: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    syntax,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** type: 完整别名表（与 core/query.go typeAliases 同源）。 */
@Composable
private fun HelpAliasTable() {
    val groups = listOf(
        "视频" to listOf("视频", "影片", "video", "movie"),
        "音乐" to listOf("音乐", "音频", "music", "audio"),
        "图片" to listOf("图片", "照片", "image", "photo", "pic"),
        "文档" to listOf("文档", "doc", "document", "docs"),
        "文件" to listOf("文件", "file", "files"),
        "安装包" to listOf("安装包", "apk"),
    )
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "type: 后可接（type:视频 / type:music 等写法等价）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            groups.forEach { (label, aliases) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(52.dp),
                    )
                    Text(
                        aliases.joinToString(" / "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun HelpNote(text: String) {
    Row(Modifier.padding(horizontal = 4.dp)) {
        Text(
            "· ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
