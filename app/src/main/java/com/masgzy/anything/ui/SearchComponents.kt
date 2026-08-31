package com.masgzy.anything.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.masgzy.anything.data.UiHit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 按扩展名判定文件类别（筛选快捷钮使用）。 */
enum class FileCategory(val label: String, val icon: ImageVector, val exts: Set<String>) {
    ALL("所有", Icons.Filled.AllInclusive, emptySet()),
    VIDEO("视频", Icons.Filled.VideoLibrary, setOf("mp4", "avi", "mkv", "mov", "3gp", "flv", "wmv", "webm", "m4v", "ts")),
    AUDIO("音乐", Icons.Filled.MusicNote, setOf("mp3", "flac", "wav", "aac", "ogg", "m4a", "wma", "ape", "mid")),
    IMAGE("图片", Icons.Filled.Image, setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg")),
    DOC("文档", Icons.Filled.Edit, setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "wps", "csv", "html", "xml")),
    WORD("Word", Icons.Filled.Description, setOf("doc", "docx", "wps", "txt", "md", "html")),
    EXCEL("Excel", Icons.Filled.TableChart, setOf("xls", "xlsx", "csv")),
    PPT("PPT", Icons.Filled.Slideshow, setOf("ppt", "pptx")),
    ARTICLE("文章", Icons.Filled.Article, setOf()),
}

val FileCategory.isAll: Boolean get() = this == FileCategory.ALL

/**
 * 类别判定顺序：先查更具体的 office 子类，再查大类。
 * 顺序错误会让 DOC 的扩展名集合遮蔽 Word/Excel/PPT，
 * 导致 office 页签筛选永远为空（已修复的 bug）。
 */
private val categoryLookup = listOf(
    FileCategory.WORD, FileCategory.EXCEL, FileCategory.PPT,
    FileCategory.VIDEO, FileCategory.AUDIO, FileCategory.IMAGE, FileCategory.DOC,
)

fun categoryOf(path: String): FileCategory {
    val ext = path.substringAfterLast('.', "").lowercase()
    return categoryLookup.firstOrNull { ext in it.exts } ?: FileCategory.ALL
}

/** 结果条目主图标。 */
fun iconFor(hit: UiHit): ImageVector = when {
    hit.isFolder -> Icons.Filled.Folder
    hit.matched == "content" -> Icons.Filled.Article
    else -> when (val c = categoryOf(hit.path)) {
        FileCategory.VIDEO -> Icons.Filled.VideoLibrary
        FileCategory.AUDIO -> Icons.Filled.MusicNote
        FileCategory.IMAGE -> Icons.Filled.Image
        FileCategory.DOC, FileCategory.WORD -> Icons.Filled.Edit
        FileCategory.EXCEL -> Icons.Filled.TableChart
        FileCategory.PPT -> Icons.Filled.Slideshow
        else -> if (c.exts.isNotEmpty()) Icons.Filled.InsertDriveFile else Icons.Filled.InsertDriveFile
    }
}

/** 空态提示（居中灰字，还原原版"请在上方搜索栏中输入搜索内容"）。 */
@Composable
fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 120.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 结果区块标题（如：文件名中包含 "xx" 的文件有）。 */
@Composable
fun ResultHeader(text: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Text(
                "$count 项",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * 单条结果。还原原版交互：点击看详情/打开，长按进入多选。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HitItem(
    hit: UiHit,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer
    else MaterialTheme.colorScheme.surface
    Surface(color = bg, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = iconFor(hit),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = hit.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hit.matched == "content" && hit.snippet.isNotBlank()) {
                    Text(
                        text = hit.snippet,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = hit.path.substringBeforeLast('/', ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!hit.isFolder) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatSize(hit.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 详情底部面板：位置/大小/时间 + 打开/发送/删除。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailSheet(
    hit: UiHit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    iconFor(hit), null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    hit.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            ListItem(headlineContent = { Text("位置：${hit.path.substringBeforeLast('/', "")}") })
            if (!hit.isFolder) {
                ListItem(headlineContent = { Text("大小：${formatSize(hit.size)}") })
            }
            ListItem(headlineContent = { Text("时间：${formatTime(hit.mtime)}") })
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                SheetAction(Icons.Filled.OpenInNew, if (hit.isFolder) "打开" else "打开路径", onOpen)
                if (!hit.isFolder) SheetAction(Icons.Filled.Description, "发送", onShare)
                SheetAction(Icons.Filled.Delete, "删除", onDelete)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(onClick = onClick, colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )) {
            Icon(icon, null)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * 底部筛选圆钮（含上方悬浮标签气泡，还原原版样式）。
 *
 * 注意：label 传 null 时不显示气泡（漏斗钮无标签，同原版）；
 * 选中/未选中颜色经 animateColorAsState 过渡，不再生硬跳变。
 */
@Composable
fun FilterButton(
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    label: String? = null,
    size: Dp = 48.dp,
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = tween(220),
        label = "filterContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.primary,
        animationSpec = tween(220),
        label = "filterContent",
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (label != null) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 2.dp,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = CircleShape,
                color = containerColor,
                tonalElevation = if (selected) 0.dp else 3.dp,
                shadowElevation = 3.dp,
                modifier = Modifier.size(size),
            ) {}
            Icon(
                icon, label ?: "",
                tint = contentColor,
                modifier = Modifier.size(size * 22 / 48),
            )
        }
    }
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1 shl 30 -> "%.1f GB".format(bytes / 1073741824f)
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576f)
    bytes >= 1 shl 10 -> "%.1f KB".format(bytes / 1024f)
    bytes > 0 -> "$bytes B"
    else -> ""
}

fun formatTime(ms: Long): String =
    if (ms <= 0) "" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        .format(Date(ms))
