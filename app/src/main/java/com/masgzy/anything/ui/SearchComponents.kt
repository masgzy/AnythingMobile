package com.masgzy.anything.ui

import com.masgzy.anything.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.masgzy.anything.data.UiHit
import com.masgzy.anything.ui.animation.pressScale
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/** 按扩展名判定文件类别（筛选快捷钮使用）。 */
enum class FileCategory(val label: String, val iconRes: Int, val exts: Set<String>) {
    ALL("所有", R.drawable.ic_all_inclusive, emptySet()),
    VIDEO("视频", R.drawable.ic_video_library, setOf("mp4", "avi", "mkv", "mov", "3gp", "flv", "wmv", "webm", "m4v", "ts")),
    AUDIO("音乐", R.drawable.ic_music_note, setOf("mp3", "flac", "wav", "aac", "ogg", "m4a", "wma", "ape", "mid")),
    IMAGE("图片", R.drawable.ic_image, setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg")),
    DOC("文档", R.drawable.ic_edit, setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "wps", "csv", "html", "xml")),
    WORD("Word", R.drawable.ic_description, setOf("doc", "docx", "wps", "txt", "md", "html")),
    EXCEL("Excel", R.drawable.ic_table_chart, setOf("xls", "xlsx", "csv")),
    PPT("PPT", R.drawable.ic_slideshow, setOf("ppt", "pptx")),
    ARTICLE("文章", R.drawable.ic_article, setOf()),
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
/** 结果条目主图标（返回 drawable 资源 ID，调用处在组合内 painterResource）。 */
fun iconResFor(hit: UiHit): Int = when {
    hit.isFolder -> R.drawable.ic_folder
    hit.matched == "content" -> R.drawable.ic_article
    else -> when (val c = categoryOf(hit.path)) {
        FileCategory.VIDEO -> R.drawable.ic_video_library
        FileCategory.AUDIO -> R.drawable.ic_music_note
        FileCategory.IMAGE -> R.drawable.ic_image
        FileCategory.DOC, FileCategory.WORD -> R.drawable.ic_edit
        FileCategory.EXCEL -> R.drawable.ic_table_chart
        FileCategory.PPT -> R.drawable.ic_slideshow
        else -> R.drawable.ic_insert_drive_file
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
    // KSU 管理器同款按压弹性（alpha22）：同一 interactionSource 驱动点击与缩放
    val interaction = remember { MutableInteractionSource() }
    Surface(color = bg, modifier = Modifier.fillMaxWidth().pressScale(interaction)) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(iconResFor(hit)),
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
                    painterResource(iconResFor(hit)), null,
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
                SheetAction(painterResource(R.drawable.ic_open_in_new), if (hit.isFolder) "打开" else "打开路径", onOpen)
                if (!hit.isFolder) SheetAction(painterResource(R.drawable.ic_description), "发送", onShare)
                SheetAction(painterResource(R.drawable.ic_delete), "删除", onDelete)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SheetAction(icon: Painter, label: String, onClick: () -> Unit) {
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
 * 底部筛选圆钮（MD3 Rounded 图标 + 提示文字标签）。
 *
 * 标签显隐两种模式：
 *  - labelVisible = null（自控）：点击后浮现气泡，约 1.4 秒后自动淡出
 *    （用于漏斗排序钮的操作确认）；
 *  - labelVisible = Boolean（父控）：显隐完全由父级控制
 *    （用于类别钮：点"所有"展开后自动显示、超时消失、点击重显，
 *    时长在设置页自定义）。
 *
 * 实现细节：气泡区域用固定高度占位（labelSlot），出现/消失仅做
 * 淡入缩放动画，按钮位置不跳动；label 传 null 时无气泡（也不占位）。
 */
@Composable
fun FilterButton(
    icon: Painter,
    selected: Boolean,
    onClick: () -> Unit,
    label: String? = null,
    size: Dp = 48.dp,
    labelVisible: Boolean? = null,
) {
    // 自控模式：点击后短暂显示提示气泡，超时自动淡出
    var selfShow by remember { mutableStateOf(false) }
    if (labelVisible == null) {
        LaunchedEffect(selfShow) {
            if (selfShow) {
                delay(1400)
                selfShow = false
            }
        }
    }
    val showLabel = labelVisible ?: selfShow
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
            // 固定高度占位：气泡显示与否不影响按钮位置
            Box(
                modifier = Modifier.height(labelSlotHeight),
                contentAlignment = Alignment.BottomCenter,
            ) {
                // 全限定：此处处于 Column{Box{}} 嵌套中，简名会被
                // ColumnScope.AnimatedVisibility 扩展抢绑定（K2 编译错误）
                androidx.compose.animation.AnimatedVisibility(
                    visible = showLabel,
                    enter = fadeIn(tween(150)) + scaleIn(
                        initialScale = 0.6f,
                        animationSpec = tween(150),
                    ),
                    exit = fadeOut(tween(250)) + scaleOut(
                        targetScale = 0.6f,
                        animationSpec = tween(250),
                    ),
                ) {
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
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .clickable {
                    // 父控模式下显隐由父级决定，不触发自显气泡
                    if (labelVisible == null) selfShow = true
                    onClick()
                },
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

/** 气泡占位行高度（labelSmall + 上下内边距）。 */
private val labelSlotHeight = 28.dp

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
