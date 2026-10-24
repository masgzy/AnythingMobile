package com.masgzy.anything.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.masgzy.anything.data.UpdateRepository

/**
 * 更新弹窗 —— ImageToolbox DefaultUpdateSheet 同构（alpha24）：
 * M3 ModalBottomSheet（拖拽把手）+ 标题行（图标 + 新版本号）+
 * 更新日志正文（GitHub release body 的轻量 markdown 渲染，可滚动）+
 * 确认按钮跳转浏览器到该 Release 页下载（不做应用内下载安装，
 * 与 ImageToolbox FOSS 渠道行为一致：下载与安装交给用户信任的浏览器/安装器）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheet(
    release: UpdateRepository.ReleaseInfo,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        // 标题行：ReleaseAlert 图标 + 「发现新版本 vX」（ImageToolbox TitleItem 同构）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.SystemUpdateAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.size(12.dp))
            Text(
                "发现新版本 ${release.tagName.removePrefix("v")}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        // 正文：release body（markdown）轻量渲染，限制高度可滚动
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            MarkdownBody(release.body.ifBlank { "（本次发布未填写更新说明）" })
        }

        // 按钮行：前往下载（跳 Release 页）+ 下次再说
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) { Text("下次再说") }
            FilledTonalButton(
                onClick = { uriHandler.openUri(release.htmlUrl) },
            ) {
                Text("前往下载", fontWeight = FontWeight.Bold)
            }
        }
        // 提示：下载页需按设备架构选 APK
        Text(
            "将在浏览器打开 GitHub Release 页面，按设备架构下载对应 APK 安装",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        )
    }
}

/**
 * GitHub release body（markdown 子集）的轻量渲染，零第三方依赖：
 *  - `### / ## / #` 标题 → 加粗分级文本；
 *  - `- / *` 列表 → 圆点行（支持二级缩进）；
 *  - `**粗体**` 与 `` `代码` `` → 行内 span；
 *  - 空行 → 段间距。不解析表格/图片/链接（release note 里不使用）。
 */
@Composable
fun MarkdownBody(md: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        md.lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.height(6.dp))

                line.startsWith("###") -> Text(
                    inlineStyle(line.removePrefix("###").trim()),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )

                line.startsWith("##") || line.startsWith("#") -> Text(
                    inlineStyle(line.removePrefix("#").removePrefix("#").trim()),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )

                // 二级列表：缩进圆点
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    val indent = if (line.startsWith("  ") || line.startsWith("\t")) 16.dp else 0.dp
                    Row(Modifier.padding(start = indent)) {
                        Text(
                            "•  ",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            inlineStyle(line.trimStart().removePrefix("- ").removePrefix("* ").trim()),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                else -> Text(
                    inlineStyle(line),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * 行内 markdown：`**粗体**` → bold，`` `代码` `` → 等宽。
 * 其余字符原样输出（GitHub note 中不出现裸 HTML）。
 */
private fun inlineStyle(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val bold = text.indexOf("**", i)
        val code = text.indexOf('`', i)
        val next = listOf(bold, code).filter { it >= 0 }.minOrNull()
        if (next == null) {
            append(text.substring(i))
            break
        }
        append(text.substring(i, next))
        when (next) {
            bold -> {
                val end = text.indexOf("**", bold + 2)
                if (end < 0) {
                    append(text.substring(bold)); break
                }
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(text.substring(bold + 2, end))
                pop()
                i = end + 2
            }
            else -> {
                val end = text.indexOf('`', code + 1)
                if (end < 0) {
                    append(text.substring(code)); break
                }
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = Color.Unspecified))
                append(text.substring(code + 1, end))
                pop()
                i = end + 1
            }
        }
    }
}
