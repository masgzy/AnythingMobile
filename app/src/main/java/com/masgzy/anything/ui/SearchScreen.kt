package com.masgzy.anything.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.masgzy.anything.AppViewModel
import com.masgzy.anything.StoragePermissions
import com.masgzy.anything.data.UiHit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 搜索主界面：权限横幅 / 搜索框 / 扫描控制 / 结果列表。
 */
@Composable
fun SearchScreen(
    viewModel: AppViewModel,
    onRequestAllFiles: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.repo.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var hasAccess by remember { mutableStateOf(StoragePermissions.hasStorageAccess(context)) }

    // 从系统设置页授权返回后刷新权限状态
    LaunchedEffect(state.scanning) {
        hasAccess = StoragePermissions.hasStorageAccess(context)
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = "Anything",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(vertical = 12.dp),
            )

            if (!hasAccess) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("需要\"所有文件访问\"权限才能建立全盘索引")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onRequestAllFiles) {
                            Text("去授权")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    viewModel.search(it)
                },
                label = { Text("搜索文件名 / 文档全文") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.scanning) {
                    OutlinedButton(onClick = { viewModel.cancelScan() }) { Text("取消扫描") }
                    LinearProgressIndicator(
                        progress = {
                            if (state.progress > 0) (state.progress % 1000) / 1000f else 0.2f
                        },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Button(
                        onClick = { viewModel.startScan() },
                        enabled = hasAccess,
                    ) { Text("开始扫描") }
                }
            }

            Text(
                text = state.statusText + if (state.hits.isEmpty()) "" else " · 命中 ${state.hits.size}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.hits, key = { it.path }) { hit ->
                    HitCard(hit) { viewModel.openFile(hit.path) }
                }
            }
        }
    }
}

@Composable
private fun HitCard(hit: UiHit, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.padding(12.dp)) {
            Text(hit.name, style = MaterialTheme.typography.titleMedium)
            Text(
                hit.path,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
            )
            if (hit.snippet.isNotBlank()) {
                Text(
                    hit.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 3,
                )
            }
            Text(
                text = formatMeta(hit),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun formatMeta(hit: UiHit): String {
    val sizeText = when {
        hit.size >= 1 shl 20 -> "%.1f MB".format(hit.size / 1048576f)
        hit.size >= 1 shl 10 -> "%.1f KB".format(hit.size / 1024f)
        hit.size > 0 -> "${hit.size} B"
        else -> ""
    }
    val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        .format(Date(hit.mtime))
    val tag = if (hit.matched == "content") "全文" else "文件名"
    return listOf(tag, sizeText, date).filter { it.isNotBlank() }.joinToString("  ·  ")
}
