package com.masgzy.anything

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 存储权限工具。
 *
 * 依据 Android 官方文档（training/data-storage/manage-all-files）：
 *  - API 30+：MANAGE_EXTERNAL_STORAGE 属"特殊应用访问"，
 *    通过 ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION 引导用户到系统设置页开启；
 *    检测用 Environment.isExternalStorageManager()。
 *  - API 24~29：传统 READ/WRITE_EXTERNAL_STORAGE 运行时权限。
 */
object StoragePermissions {

    /** 引擎可用的扫描根目录（按系统版本决定）。 */
    fun scanRoots(context: Context): Array<String> {
        val external = Environment.getExternalStorageDirectory()?.absolutePath
        return if (external != null) arrayOf(external) else arrayOf(context.filesDir.absolutePath)
    }

    fun hasStorageAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    /** 引导用户跳转系统设置授予"所有文件访问"（API 30+）。 */
    fun requestAllFiles(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val appUri = Uri.parse("package:${activity.packageName}")
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, appUri),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        )
        for (intent in intents) {
            try {
                activity.startActivity(intent)
                return
            } catch (_: Exception) {
                // 尝试下一个 action
            }
        }
    }
}
