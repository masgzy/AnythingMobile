package com.masgzy.anything.ui.log

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.masgzy.anything.log.AppLogger
import com.masgzy.anything.log.AppLogger.Entry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试日志查看页（alpha22，参考 KSU/SukiSU 日志控制台 + 分享模式）：
 *  - 等级筛选 chips（全部/Error/Warn/Info/Debug/Verbose）；
 *  - 时间升序列表，等级着色（E 红 / W 橙 / I 主色 / D-V 灰）；
 *  - 顶栏：返回 / 清空 / 分享（导出文本文件走系统分享面板）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(AppLogger.snapshot()) }
    var filter by remember { mutableIntStateOf(-1) } // -1 = 全部
    var exportFile by remember { mutableStateOf<java.io.File?>(null) }
    val listState = rememberLazyListState()

    // 离开筛选时刷新快照（进入/返回本页时重取，不做实时订阅 —— 日志是诊断快照）
    androidx.compose.runtime.LaunchedEffect(filter) {
        val all = AppLogger.snapshot()
        entries = if (filter < 0) all else all.filter { it.level >= filter }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("调试日志（${entries.size} 条）") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        AppLogger.clear()
                        entries = emptyList()
                    }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "清空日志")
                    }
                    IconButton(onClick = {
                        val f = AppLogger.export(context)
                        AppLogger.pruneExports(context)
                        if (f != null) AppLogger.share(context, f)
                    }) {
                        Icon(Icons.Rounded.Share, contentDescription = "分享日志")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            // 等级筛选
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val chips = listOf(
                    -1 to "全部",
                    AppLogger.ERROR to "Error",
                    AppLogger.WARN to "Warn",
                    AppLogger.INFO to "Info",
                    AppLogger.DEBUG to "Debug",
                )
                chips.forEach { (lv, label) ->
                    FilterChip(
                        selected = filter == lv,
                        onClick = { filter = lv },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }

            if (entries.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "暂无日志\n索引/搜索操作后这里会记录运行轨迹",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(entries, key = { it.time.toString() + it.message.hashCode() }) { e ->
                        LogEntryRow(e)
                    }
                    item { Spacer(Modifier.height(96.dp)) }
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(e: Entry) {
    val levelColor = when (e.level) {
        AppLogger.ERROR -> MaterialTheme.colorScheme.error
        AppLogger.WARN -> androidx.compose.ui.graphics.Color(0xFFB57A1F)
        AppLogger.INFO -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(e.time)),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(horizontal = 3.dp))
                Text(
                    e.tag,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    when (e.level) {
                        AppLogger.VERBOSE -> "V"
                        AppLogger.DEBUG -> "D"
                        AppLogger.INFO -> "I"
                        AppLogger.WARN -> "W"
                        else -> "E"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = levelColor,
                )
            }
            Text(
                e.message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
            e.error?.let { t ->
                Text(
                    t.message ?: t.javaClass.simpleName,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
