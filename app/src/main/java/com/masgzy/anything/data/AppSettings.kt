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
 *   - 配色方案：ImageToolbox 式角色化调色板 —— 主色/辅助色/第三色/
 *     表面/中性变体/错误六个角色可逐一定制，另附"简单变体"预设；
 *   - 主题色：单一种子色派生方案（关闭动态取色且未开角色配色时使用）；
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
    /** 是否使用角色化自定义配色（ImageToolbox 的配色方案功能）。 */
    val advancedPalette: Boolean = false,
    val roleColors: RoleColors = RolePalette.DEFAULT_ROLES,
    val amoled: Boolean = false,
    val autoScanOnEnter: Boolean = true,
    val sortByName: Boolean = true,
    val welcomeSeen: Boolean = false,
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * 角色化配色：对应 M3 配色方案中可由用户直接指定的六个关键角色，
 * 与 ImageToolbox 配色方案弹窗的字段一一对应：
 * 主色 primary / 辅助色 secondary / 第三色 tertiary /
 * 表面 surface / 中性变体 surfaceVariant / 错误 error。
 * 其余角色（容器色、轮廓、文字色等）由 Theme 以各角色色相 HSL 派生。
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

/**
 * 角色化配色预设与默认值（对齐 ImageToolbox 配色方案）。
 *
 * 预设主色取自 ImageToolbox ColorTupleDefaults.defaultColorTuples；
 * 辅助/第三/表面等配套色按同色系手工调校，保证浅色方案下的可读性。
 */
object RolePalette {

    /** 默认角色配色：主色取原版 Anything 靛蓝。 */
    val DEFAULT_ROLES = RoleColors(
        primary = 0xFF3F51B5.toInt(),
        secondary = 0xFF5C6BC0.toInt(),
        tertiary = 0xFF7E57C2.toInt(),
        surface = 0xFFFAFAFD.toInt(),
        surfaceVariant = 0xFFE7EAF6.toInt(),
        error = 0xFFB3261E.toInt(),
    )

    /** 简单变体（浅色方案下的表面色均为带轻微色相的近白/近灰）。 */
    val presets: List<PalettePreset> = listOf(
        PalettePreset(
            "原版靛蓝", DEFAULT_ROLES,
        ),
        PalettePreset(
            "绯红", RoleColors(
                0xFFF8130D.toInt(), 0xFFFF6B52.toInt(), 0xFFFF7900.toInt(),
                0xFFFDF8F7.toInt(), 0xFFF6E3E1.toInt(), 0xFFB3261E.toInt(),
            ),
        ),
        PalettePreset(
            "酒红", RoleColors(
                0xFF7A000B.toInt(), 0xFFB5494C.toInt(), 0xFF8A3A00.toInt(),
                0xFFFCF7F7.toInt(), 0xFFF3E0E0.toInt(), 0xFF93000A.toInt(),
            ),
        ),
        PalettePreset(
            "赭石", RoleColors(
                0xFF8A3A00.toInt(), 0xFFB56B3A.toInt(), 0xFF6D4C41.toInt(),
                0xFFFCF8F5.toInt(), 0xFFF3E5DC.toInt(), 0xFFB3261E.toInt(),
            ),
        ),
        PalettePreset(
            "亮橙", RoleColors(
                0xFFFF7900.toInt(), 0xFFFFA96B.toInt(), 0xFFC67100.toInt(),
                0xFFFDF9F5.toInt(), 0xFFF6EADD.toInt(), 0xFFB3261E.toInt(),
            ),
        ),
        PalettePreset(
            "草绿", RoleColors(
                0xFF88DD20.toInt(), 0xFF4CAD32.toInt(), 0xFF16B16E.toInt(),
                0xFFF8FCF4.toInt(), 0xFFE5F1DB.toInt(), 0xFFB3261E.toInt(),
            ),
        ),
        PalettePreset(
            "翡翠", RoleColors(
                0xFF16B16E.toInt(), 0xFF3F9E7E.toInt(), 0xFF01A0A3.toInt(),
                0xFFF5FBF8.toInt(), 0xFFDEEFE8.toInt(), 0xFFB3261E.toInt(),
            ),
        ),
        PalettePreset(
            "青碧", RoleColors(
                0xFF01A0A3.toInt(), 0xFF3F8C8E.toInt(), 0xFF005FFF.toInt(),
                0xFFF5FAFA.toInt(), 0xFFDCEEEE.toInt(), 0xFFB3261E.toInt(),
            ),
        ),
        PalettePreset(
            "蔚蓝", RoleColors(
                0xFF005FFF.toInt(), 0xFF3F6FFF.toInt(), 0xFF7B2BEC.toInt(),
                0xFFF6F8FE.toInt(), 0xFFE0E6F9.toInt(), 0xFFB3261E.toInt(),
            ),
        ),
        PalettePreset(
            "粉红", RoleColors(
                0xFFFA64E1.toInt(), 0xFFD94FB8.toInt(), 0xFFD7036A.toInt(),
                0xFFFDF6FB.toInt(), 0xFFF6E2F1.toInt(), 0xFFB3261E.toInt(),
            ),
        ),
        PalettePreset(
            "紫罗兰", RoleColors(
                0xFF7B2BEC.toInt(), 0xFF9C5FF0.toInt(), 0xFFDB94FE.toInt(),
                0xFFF9F6FD.toInt(), 0xFFEAE2F7.toInt(), 0xFFB3261E.toInt(),
            ),
        ),
        PalettePreset(
            "藏青", RoleColors(
                0xFF022B6D.toInt(), 0xFF3A569E.toInt(), 0xFF01A0A3.toInt(),
                0xFFF6F7FA.toInt(), 0xFFE1E4EC.toInt(), 0xFFB3261E.toInt(),
            ),
        ),
    )

    /** 当前角色配色是否命中某个预设（用于弹窗中的选中态）。 */
    fun matchPreset(roles: RoleColors): PalettePreset? =
        presets.firstOrNull { it.roles == roles }
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
        advancedPalette = prefs.getBoolean(KEY_ADVANCED_PALETTE, false),
        roleColors = RoleColors(
            primary = prefs.getInt(KEY_ROLE_PRIMARY, RolePalette.DEFAULT_ROLES.primary),
            secondary = prefs.getInt(KEY_ROLE_SECONDARY, RolePalette.DEFAULT_ROLES.secondary),
            tertiary = prefs.getInt(KEY_ROLE_TERTIARY, RolePalette.DEFAULT_ROLES.tertiary),
            surface = prefs.getInt(KEY_ROLE_SURFACE, RolePalette.DEFAULT_ROLES.surface),
            surfaceVariant = prefs.getInt(
                KEY_ROLE_SURFACE_VARIANT,
                RolePalette.DEFAULT_ROLES.surfaceVariant,
            ),
            error = prefs.getInt(KEY_ROLE_ERROR, RolePalette.DEFAULT_ROLES.error),
        ),
        amoled = prefs.getBoolean(KEY_AMOLED, false),
        autoScanOnEnter = prefs.getBoolean(KEY_AUTO_SCAN, true),
        sortByName = prefs.getBoolean(KEY_SORT_NAME, true),
        welcomeSeen = prefs.getBoolean(KEY_WELCOME_SEEN, false),
    )

    fun setThemeMode(mode: ThemeMode) = put(KEY_THEME, mode.name)

    fun setDynamicColor(v: Boolean) = put(KEY_DYNAMIC, v)

    fun setSeedColor(v: Int) = put(KEY_SEED, v)

    fun setAdvancedPalette(v: Boolean) = put(KEY_ADVANCED_PALETTE, v)

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
        private const val KEY_ADVANCED_PALETTE = "advanced_palette"
        private const val KEY_ROLE_PRIMARY = "role_primary"
        private const val KEY_ROLE_SECONDARY = "role_secondary"
        private const val KEY_ROLE_TERTIARY = "role_tertiary"
        private const val KEY_ROLE_SURFACE = "role_surface"
        private const val KEY_ROLE_SURFACE_VARIANT = "role_surface_variant"
        private const val KEY_ROLE_ERROR = "role_error"
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
