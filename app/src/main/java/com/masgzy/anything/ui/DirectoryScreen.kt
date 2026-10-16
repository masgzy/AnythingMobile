package com.masgzy.anything.ui

import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.masgzy.anything.AppViewModel
import com.masgzy.anything.data.SafDirs
import com.masgzy.anything.data.ScanPhase

/**
 * 「目录」tab —— SAF 文件夹选择器授权的搜索目录管理。
 *
 * 数据流参照 NPatch 的授权闭环（GPL-3.0，README 致谢）：
 * 裸 ACTION_OPEN_DOCUMENT_TREE 选择 → 回调里 takePersistableUriPermission
 * （READ|WRITE）→ uri 字符串持久化进 AppSettings.searchDirs；列表展示时
 * 校验授权有效性（重取权限 + exists() 探测），失效标记并可删除。
 *
 * 「同步索引」按搜索范围语义分流（见 AppViewModel.syncDirIndex）：
 * 可映射物理路径时走引擎常规扫描管线，否则 SAF 遍历 + Ingest 注入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectoryScreen(
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val repoState by viewModel.repo.state.collectAsStateWithLifecycle()

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_CANCELED) return@rememberLauncherForActivityResult
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            viewModel.addSearchDir(uri.toString())
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("目录") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    // 裸 Intent（NPatch 同款）：选择器返回带持久化授权的 tree URI
                    pickerLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
                },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("添加目录") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + FLOATING_BAR_CONTENT_PADDING,
            ),
        ) {
            item(key = "scope") {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    headlineContent = { Text("仅搜索选定目录") },
                    supportingContent = {
                        Text(
                            "开启后索引只覆盖下方目录（全盘扫描暂停）；" +
                                "目录可映射为物理路径时仍走高速扫描管线，" +
                                "否则经文件夹选择器授权逐个读取。" +
                                "关闭则按全盘扫描，本页目录仅作授权记录。",
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = settings.scopedSearchOnly,
                            onCheckedChange = { viewModel.setScopedSearchOnly(it) },
                        )
                    },
                )
            }
            item(key = "sync") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { viewModel.syncDirIndex() },
                        enabled = repoState.phase == ScanPhase.IDLE,
                    ) {
                        Icon(Icons.Rounded.Sync, null, Modifier.size(18.dp))
                        Spacer(Modifier.height(0.dp))
                        Text(
                            if (settings.scopedSearchOnly) "同步选定目录" else "重建全盘索引",
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                    OutlinedButton(
                        onClick = { viewModel.startScan(incremental = false) },
                        enabled = repoState.phase == ScanPhase.IDLE,
                    ) {
                        Text("增量扫描")
                    }
                }
            }
            if (settings.searchDirs.isEmpty()) {
                item(key = "empty") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Rounded.Folder,
                            null,
                            Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("还没有授权任何目录", fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "点右下角「添加目录」，通过系统文件夹\n选择器（文件提供器 API）授权后即可纳入搜索。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                items(settings.searchDirs, key = { it }) { uriStr ->
                    var valid by remember(uriStr) { mutableStateOf(true) }
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                        leadingContent = {
                            Icon(
                                if (valid) Icons.Rounded.Folder else Icons.Rounded.FolderOff,
                                null,
                                tint = if (valid) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                            )
                        },
                        headlineContent = { Text(SafDirs.displayName(uriStr)) },
                        supportingContent = {
                            Text(
                                if (valid) {
                                    SafDirs.treeToPath(context, uriStr) ?: SafDirs.subPath(uriStr)
                                } else {
                                    "授权已失效（目录被移动或权限被回收），请删除后重新添加"
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = { viewModel.removeSearchDir(uriStr) }) {
                                Icon(Icons.Rounded.Close, "移除", modifier = Modifier.size(18.dp))
                            }
                        },
                    )
                    // 首次进入列表时探测授权有效性（NPatch 用时校验模式）
                    LaunchedEffect(uriStr) {
                        valid = SafDirs.isValid(context, uriStr)
                    }
                }
            }
            item(key = "hint") {
                Text(
                    "说明：授权信息保存在本机（系统级持久授权 + 目录列表）；" +
                        "移除应用或手动回收后失效。SAF 遍历通道每次同步为整体重建，" +
                        "目录内容量大时建议优先保持「所有文件访问」授权以走高速管线。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}
