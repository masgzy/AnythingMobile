package com.masgzy.anything.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.masgzy.anything.ui.theme.ColorTupleDefaults
import com.masgzy.anything.ui.theme.PaletteStyle

/**
 * 用户偏好与持久化。
 *
 * 外观设置项完整对齐 ImageToolbox（T8RIN/ImageToolbox）的外观面板：
 *   - 主题模式：跟随系统 / 浅色 / 深色（与原版 Anything 的
 *     DarkModelSettings.AUTO/LIGHT/DARK 三态一一对应）；
 *   - 动态取色（Monet）：Android 12+ 跟随壁纸取色开关；
 *   - 配色方案：ImageToolbox 式角色化调色板 —— 主色/辅助色/第三色/
 *     表面/中性变体/错误六个角色可逐一定制，另附"简单变体"预设
 *     （16 个预设完整照抄 ImageToolbox ColorTupleDefaults）；
 *   - 调色板风格：TonalSpot/Neutral/Vibrant/Expressive/Rainbow/
 *     FruitSalad/Monochrome/Fidelity/Content 九种（ImageToolbox 同款）；
 *   - 对比度：-1..1 连续可调（DynamicScheme contrastLevel）；
 *   - 反转颜色：对静态方案所有角色取反（ImageToolbox 同款）；
 *   - AMOLED 纯黑：深色模式下将 surface 置为纯黑，省电护眼。
 * 功能设置：
 *   - 进入应用时自动更新索引（增量，有变动才重解析）；
 *   - 筛选文字显示时长：点「所有」展开类别钮后，文字标签自动显示的时长
 *     （5 秒后消失，点击重新显示；可选不显示/3/5/10 秒/常驻）；
 *   - 结果排序：按名称 / 按时间（对应原版 FileSortSettings.NAME/TIME）；
 *   - 首次启动引导页标记。
 *
 * 存储选用 SharedPreferences：读轻量同步（主题冷启动即需），写经 apply 异步。
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val roleColors: RoleColors = ColorTupleDefaults.defaultRoles,
    /** 调色板风格（ImageToolbox 配色方案弹窗的"调色板风格"项）。 */
    val paletteStyle: PaletteStyle = PaletteStyle.TonalSpot,
    /** 对比度 -1..1（ImageToolbox DynamicScheme contrastLevel）。 */
    val themeContrast: Float = 0f,
    /** 反转颜色（仅静态方案生效，ImageToolbox 同款）。 */
    val invertColors: Boolean = false,
    val amoled: Boolean = false,
    val autoScanOnEnter: Boolean = true,
    /** 筛选钮文字标签显示时长（秒）：0=不自动显示，-1=常驻，>0=自动隐藏秒数。 */
    val filterLabelSeconds: Int = 5,
    val sortByName: Boolean = true,
    val welcomeSeen: Boolean = false,
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * 角色化配色：对应 M3 配色方案中可由用户直接指定的六个关键角色，
 * 与 ImageToolbox 配色方案弹窗的字段一一对应：
 * 主色 primary / 辅助色 secondary / 第三色 tertiary /
 * 表面 surface / 中性变体 surfaceVariant / 错误 error。
 * 其余角色由调色引擎（ui/theme/DynamicTheme.kt，照抄 ImageToolbox
 * lib/dynamic-theme）以 HCT 色板派生。
 */
data class RoleColors(
    val primary: Int,
    val secondary: Int,
    val tertiary: Int,
    val surface: Int,
    val surfaceVariant: Int,
    val error: Int,
)

/** 简单变体：一键应用整套角色配色（对应 ImageToolbox 的预设色板）。 */
data class PalettePreset(val name: String, val roles: RoleColors)

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        themeMode = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name)!!)
        }.getOrDefault(ThemeMode.SYSTEM),
        dynamicColor = prefs.getBoolean(KEY_DYNAMIC, true),
        roleColors = RoleColors(
            primary = prefs.getInt(KEY_ROLE_PRIMARY, ColorTupleDefaults.defaultRoles.primary),
            secondary = prefs.getInt(KEY_ROLE_SECONDARY, ColorTupleDefaults.defaultRoles.secondary),
            tertiary = prefs.getInt(KEY_ROLE_TERTIARY, ColorTupleDefaults.defaultRoles.tertiary),
            surface = prefs.getInt(KEY_ROLE_SURFACE, ColorTupleDefaults.defaultRoles.surface),
            surfaceVariant = prefs.getInt(
                KEY_ROLE_SURFACE_VARIANT,
                ColorTupleDefaults.defaultRoles.surfaceVariant,
            ),
            error = prefs.getInt(KEY_ROLE_ERROR, ColorTupleDefaults.defaultRoles.error),
        ),
        paletteStyle = runCatching {
            PaletteStyle.valueOf(
                prefs.getString(KEY_STYLE, PaletteStyle.TonalSpot.name)!!,
            )
        }.getOrDefault(PaletteStyle.TonalSpot),
        themeContrast = prefs.getFloat(KEY_CONTRAST, 0f),
        invertColors = prefs.getBoolean(KEY_INVERT, false),
        amoled = prefs.getBoolean(KEY_AMOLED, false),
        autoScanOnEnter = prefs.getBoolean(KEY_AUTO_SCAN, true),
        filterLabelSeconds = prefs.getInt(KEY_FILTER_LABEL_SECONDS, 5),
        sortByName = prefs.getBoolean(KEY_SORT_NAME, true),
        welcomeSeen = prefs.getBoolean(KEY_WELCOME_SEEN, false),
    )

    fun setThemeMode(mode: ThemeMode) = put(KEY_THEME, mode.name)

    fun setDynamicColor(v: Boolean) = put(KEY_DYNAMIC, v)

    fun setRoleColors(c: RoleColors) {
        prefs.edit {
            putInt(KEY_ROLE_PRIMARY, c.primary)
                .putInt(KEY_ROLE_SECONDARY, c.secondary)
                .putInt(KEY_ROLE_TERTIARY, c.tertiary)
                .putInt(KEY_ROLE_SURFACE, c.surface)
                .putInt(KEY_ROLE_SURFACE_VARIANT, c.surfaceVariant)
                .putInt(KEY_ROLE_ERROR, c.error)
        }
    }

    fun setPaletteStyle(v: PaletteStyle) = put(KEY_STYLE, v.name)

    fun setThemeContrast(v: Float) = prefs.edit { putFloat(KEY_CONTRAST, v) }

    fun setInvertColors(v: Boolean) = put(KEY_INVERT, v)

    fun setAmoled(v: Boolean) = put(KEY_AMOLED, v)

    fun setAutoScanOnEnter(v: Boolean) = put(KEY_AUTO_SCAN, v)

    fun setFilterLabelSeconds(v: Int) = put(KEY_FILTER_LABEL_SECONDS, v)

    fun setSortByName(v: Boolean) = put(KEY_SORT_NAME, v)

    fun setWelcomeSeen(v: Boolean) = put(KEY_WELCOME_SEEN, v)

    private fun put(key: String, value: String) = prefs.edit { putString(key, value) }

    private fun put(key: String, value: Boolean) = prefs.edit { putBoolean(key, value) }

    private fun put(key: String, value: Int) = prefs.edit { putInt(key, value) }

    companion object {
        private const val KEY_THEME = "theme_mode"
        private const val KEY_DYNAMIC = "dynamic_color"
        private const val KEY_ROLE_PRIMARY = "role_primary"
        private const val KEY_ROLE_SECONDARY = "role_secondary"
        private const val KEY_ROLE_TERTIARY = "role_tertiary"
        private const val KEY_ROLE_SURFACE = "role_surface"
        private const val KEY_ROLE_SURFACE_VARIANT = "role_surface_variant"
        private const val KEY_ROLE_ERROR = "role_error"
        private const val KEY_STYLE = "palette_style"
        private const val KEY_CONTRAST = "theme_contrast"
        private const val KEY_INVERT = "invert_colors"
        private const val KEY_AMOLED = "amoled"
        private const val KEY_AUTO_SCAN = "auto_scan_on_enter"
        private const val KEY_FILTER_LABEL_SECONDS = "filter_label_seconds"
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
