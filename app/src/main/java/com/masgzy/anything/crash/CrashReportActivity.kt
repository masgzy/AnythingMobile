/*
 * 崩溃报告页 —— 参考 ImageToolbox core/crash CrashActivity：
 * 崩溃瞬间由 CrashReporter 拉起（NEW_TASK|CLEAR_TASK|CLEAR_TOP），
 * 独立 :crash 进程运行（主进程 OOM 崩溃也不受影响）。
 * 刻意使用独立于应用主题引擎的 MaterialTheme 深色方案：
 * 主题代码自身也可能是崩溃源，报告页绝不能再依赖它。
 */

package com.masgzy.anything.crash

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class CrashReportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = runCatching {
            CrashReporter.reportFile(this).takeIf { it.exists() }?.readText()
        }.getOrNull() ?: "（未找到崩溃日志文件）"

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CrashReportContent(
                    report = report,
                    onShare = { CrashReporter.shareReport(this) },
                    onCopy = { copyToClipboard(report) },
                    onRestart = ::restartApp,
                    onExit = ::exitApp,
                )
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(
            android.content.ClipData.newPlainText("crash_log", text),
        )
    }

    private fun restartApp() {
        val pkg = packageName
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (intent != null) startActivity(intent)
        finishAffinity()
    }

    private fun exitApp() {
        finishAffinity()
    }
}

@Composable
private fun CrashReportContent(
    report: String,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onRestart: () -> Unit,
    onExit: () -> Unit,
) {
    var shared by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "应用遇到问题，已停止运行",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "抱歉！已自动收集崩溃日志。您可以分享日志帮助修复问题（日志仅包含设备信息与堆栈，不含个人文件）。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 日志预览（等宽字体，可选中复制）
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                SelectionContainer {
                    Text(
                        text = report.take(24_000),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                    )
                }
            }

            Row(Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        onShare()
                        shared = true
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("分享日志")
                }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(
                    onClick = onCopy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("复制日志")
                }
            }
            Row(Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        onRestart()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("重启应用")
                }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = onExit,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("退出")
                }
            }
            if (shared) {
                Text(
                    "已打开分享面板；返回本页后可选择\"退出\"。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
