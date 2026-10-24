package com.masgzy.anything.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.masgzy.anything.BuildConfig
import com.masgzy.anything.data.UpdateRepository

/**
 * 关于内容块 —— 居中圆形 logo + 应用名 + 许可与致敬信息。
 * alpha17 起作为设置页底部的「关于」分区嵌入（原独立路由已移除）。
 * 文案面向用户：只保留 GPL-3.0 许可，不展示实现技术栈。
 */
@Composable
fun AboutContent(modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(96.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Rounded.Search,
                    null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(44.dp),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Anything",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "search everything on Android",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        // GitHub 仓库入口（用户反馈 alpha24）：浏览器打开仓库主页
        val uriHandler = LocalUriHandler.current
        FilledTonalButton(
            onClick = { uriHandler.openUri(UpdateRepository.REPO_URL) },
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text("GitHub 仓库")
        }
        Spacer(Modifier.height(28.dp))
        Text(
            "v${BuildConfig.VERSION_NAME} · GPL-3.0 开源许可",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "致敬 SincereXIA 的原版 Anything\n本应用为开源重写版本\n文件夹选择器与液态玻璃底栏参考 NPatch（GPL-3.0）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
