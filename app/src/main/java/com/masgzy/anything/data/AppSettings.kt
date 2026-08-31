package com.masgzy.anything.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 用户偏好与持久化。
 *
 * 外观设置项对齐 ImageToolbox（T8RIN/ImageToolbox）的外观面板：
 *   - 主题模式：跟随系统 / 浅色 / 深色（与原版 Anything 的
 *     DarkModelSettings.AUTO/LIGHT/DARK 三态一一对应）；
 *   - 动态取色（Monet）：Android 12+ 跟随壁纸取色开关；
 *   - 主题色：关闭动态取色（或低版本系统）时使用的预设种子色板；
 *   - AMOLED 纯黑：深色模式下将 surface 置为纯黑，省电护眼。
 * 功能设置：
 *   - 进入应用时自动更新索引（增量，有变动才重解析）；
 *   - 结果排序：按名称 / 按时间（对应原版 FileSortSettings.NAME/TIME）；
 *   - 首次启动引导页标记。
 *
 * 存储选用 SharedPreferences：读轻量同步（主题冷启动即需），写经 apply 异步。
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val seedColor: Int = SeedPalette.DEFAULT,
    val amoled: Boolean = false,
    val autoScanOnEnter: Boolean = true,
    val sortByName: Boolean = true,
    val welcomeSeen: Boolean = false,
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * 预设主题种子色板（关闭 Monet 或 Android <12 时生效）。
 *
 * 直接采用 ImageToolbox（T8RIN/ImageToolbox，Apache-2.0）外观设置中
 * ColorTupleDefaults.defaultColorTuples 的 16 个预设色，另保留
 * 原版 Anything 的靛蓝作为默认值，兼顾"复刻原版"与"高度自定义"。
 */
object SeedPalette {
    // 默认取原版 Anything 的靛蓝主色
    const val DEFAULT = 0xFF3F51B5.toInt()

    /** 自定义色（用户在调色板弹窗输入任意十六进制色）占位标记，仅用于文案。 */
    const val CUSTOM = "自定义"

    data class Swatch(val argb: Int, val name: String)

    val entries: List<Swatch> = listOf(
        Swatch(0xFF3F51B5.toInt(), "靛蓝 · 默认"),
        Swatch(0xFFf8130d.toInt(), "绯红"),
        Swatch(0xFF7a000b.toInt(), "酒红"),
        Swatch(0xFF8a3a00.toInt(), "赭石"),
        Swatch(0xFFff7900.toInt(), "亮橙"),
        Swatch(0xFFfcf721.toInt(), "柠檬黄"),
        Swatch(0xFF88dd20.toInt(), "草绿"),
        Swatch(0xFF16B16E.toInt(), "翡翠"),
        Swatch(0xFF01a0a3.toInt(), "青碧"),
        Swatch(0xFF005FFF.toInt(), "蔚蓝"),
        Swatch(0xFFfa64e1.toInt(), "粉红"),
        Swatch(0xFFd7036a.toInt(), "玫红"),
        Swatch(0xFFdb94fe.toInt(), "兰紫"),
        Swatch(0xFF7b2bec.toInt(), "紫罗兰"),
        Swatch(0xFF022b6d.toInt(), "藏青"),
        Swatch(0xFFFFFFFF.toInt(), "纯白"),
        Swatch(0xFF000000.toInt(), "纯黑"),
    )

    /** 按色值查名称；自定义色返回"自定义"。 */
    fun nameOf(argb: Int): String =
        entries.firstOrNull { it.argb == argb }?.name ?: "自定义"
}

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        themeMode = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name)!!)
        }.getOrDefault(ThemeMode.SYSTEM),
        dynamicColor = prefs.getBoolean(KEY_DYNAMIC, true),
        seedColor = prefs.getInt(KEY_SEED, SeedPalette.DEFAULT),
        amoled = prefs.getBoolean(KEY_AMOLED, false),
        autoScanOnEnter = prefs.getBoolean(KEY_AUTO_SCAN, true),
        sortByName = prefs.getBoolean(KEY_SORT_NAME, true),
        welcomeSeen = prefs.getBoolean(KEY_WELCOME_SEEN, false),
    )

    fun setThemeMode(mode: ThemeMode) = put(KEY_THEME, mode.name)

    fun setDynamicColor(v: Boolean) = put(KEY_DYNAMIC, v)

    fun setSeedColor(v: Int) = put(KEY_SEED, v)

    fun setAmoled(v: Boolean) = put(KEY_AMOLED, v)

    fun setAutoScanOnEnter(v: Boolean) = put(KEY_AUTO_SCAN, v)

    fun setSortByName(v: Boolean) = put(KEY_SORT_NAME, v)

    fun setWelcomeSeen(v: Boolean) = put(KEY_WELCOME_SEEN, v)

    private fun put(key: String, value: String) = prefs.edit { putString(key, value) }

    private fun put(key: String, value: Boolean) = prefs.edit { putBoolean(key, value) }

    private fun put(key: String, value: Int) = prefs.edit { putInt(key, value) }

    companion object {
        private const val KEY_THEME = "theme_mode"
        private const val KEY_DYNAMIC = "dynamic_color"
        private const val KEY_SEED = "seed_color"
        private const val KEY_AMOLED = "amoled"
        private const val KEY_AUTO_SCAN = "auto_scan_on_enter"
        private const val KEY_SORT_NAME = "sort_by_name"
        private const val KEY_WELCOME_SEEN = "welcome_seen"

        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context).also { instance = it }
            }
    }
}
