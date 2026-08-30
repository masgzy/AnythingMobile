package com.masgzy.anything.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 引导页 —— 三屏结构还原原版 Anything Welcome：
 *  1. 放大镜 / Search Anything / 堪比 Windows 端的 EveryThing……
 *  2. 文档图标 / 全文索引 / 不光是文件名，我们支持解析 Office 文档正文内容……
 *  3. 对勾 / 欢迎您使用 Anything / Let's Go !
 */
@Composable
fun WelcomeScreen(onFinish: () -> Unit) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 3 })

    Column(Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            WelcomePage(page, onFinish)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pagerState.currentPage < 2) {
                TextButton(onClick = onFinish) { Text("跳过") }
            } else {
                Box(Modifier.height(1.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { i ->
                    Surface(
                        shape = CircleShape,
                        color = if (i == pagerState.currentPage)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.size(8.dp),
                    ) {}
                }
            }
            if (pagerState.currentPage < 2) {
                TextButton(onClick = {
                    scope.launch {
                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                    }
                }) { Text("下一页") }
            } else {
                TextButton(onClick = onFinish) { Text("完成") }
            }
        }
    }
}

@Composable
private fun WelcomePage(page: Int, onFinish: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (page) {
            0 -> {
                Icon(
                    Icons.Filled.Search,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(140.dp),
                )
                PageTexts(
                    title = "Search Anything",
                    body = "堪比 Windows 端的 EveryThing，您可以快速搜索到您设备上的一切文件",
                )
            }
            1 -> {
                Icon(
                    Icons.Filled.Description,
                    null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(140.dp),
                )
                PageTexts(
                    title = "全文索引",
                    body = "不光是文件名，我们支持解析 Office 文档正文内容，并快速进行全文内容索引",
                )
            }
            else -> {
                Icon(
                    Icons.Filled.CheckCircle,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(140.dp),
                )
                PageTexts(
                    title = "欢迎您使用 Anything",
                    body = "Powered by Go & Kotlin · 开源",
                )
                Spacer(Modifier.height(32.dp))
                Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
                    Text("Let's Go !")
                }
            }
        }
    }
}

@Composable
private fun PageTexts(title: String, body: String) {
    Spacer(Modifier.height(72.dp))
    Text(
        title,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(16.dp))
    Text(
        body,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
