package com.masgzy.anything.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.masgzy.anything.log.AppLogger
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
 *   - 设计风格（alpha28）：Material Design 3 / Miuix（HyperOS 风格）——
 *     KSU 同款双设计切换；Miuix 风格经 miuix-ui 的 ThemeController 生成
 *     HyperOS 色板（主色种子/调色板风格/动态取色与 M3 模式共享同一套设置），
 *     未迁移的组件回退 MaterialTheme（用户自定义配色不丢失）；
 *   - 进入应用时自动更新索引（增量，有变动才重解析）；
 *   - 索引范围三模式（alpha26）：全盘+目录 / 仅目录 / 仅全盘 ——
 *     替代 alpha19 的 scopedSearchOnly 布尔开关；
 *   - 扫描 Android/data 目录：alpha26 起入口挪到「目录」页（与添加目录
 *     同列的一个条目，不再是设置页独立开关）；借助 Shizuku/Stellar
 *     特权通道收录应用数据目录的文件名（Android 11+ 隔离区）；
 *   - 筛选文字显示时长：点「所有」展开类别钮后，文字标签自动显示的时长
 *     （5 秒后消失，点击重新显示；可选不显示/3/5/10 秒/常驻）；
 *   - 结果排序：按名称 / 按时间（对应原版 FileSortSettings.NAME/TIME）；
 *   - 悬浮分页底栏：结果超一页时底部悬浮翻页条（默认开启）；
 *   - 液态玻璃底栏：底栏毛玻璃质感（Android 12+ 完整效果，默认关）；
 *   - 搜索目录（SAF 文件夹选择器授权，目录清单一并持久化）；
 *   - 首次启动引导页标记。
 *
 * 存储选用 SharedPreferences：读轻量同步（主题冷启动即需），写经 apply 异步。
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 设计风格（alpha28，KSU 同款双设计）：MD3（默认，历史行为） / Miuix。 */
    val designStyle: DesignStyle = DesignStyle.MATERIAL,
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
    /** 扫描 Android/data 目录（需 Shizuku/Stellar 特权，仅收录名称不含全文）。
     *  alpha26 起由「目录」页的 Android/data 条目开关驱动。 */
    val scanAndroidData: Boolean = false,
    /** 筛选钮文字标签显示时长（秒）：0=不自动显示，-1=常驻，>0=自动隐藏秒数。 */
    val filterLabelSeconds: Int = 5,
    val sortByName: Boolean = true,
    /** 悬浮分页底栏：结果超一页时显示悬浮翻页条（默认开启）。 */
    val showPaginator: Boolean = true,
    /** 液态玻璃底栏：底栏/翻页条毛玻璃质感（Android 12+ 完整效果）。 */
    val liquidGlass: Boolean = false,
    /** 索引/搜索范围（alpha26 三模式）：
     *  - [IndexScope.FULL_DISK_ONLY] 仅全盘（历史默认）；
     *  - [IndexScope.DIRS_ONLY] 仅目录：只索引 searchDirs（alpha19 起
     *    scopedSearchOnly=true 的语义）；
     *  - [IndexScope.FULL_DISK_AND_DIRS] 全盘+目录：全盘扫描之外，
     *    「同步索引」还经 SAF/DocumentsProvider 收录目录清单中
     *    不可物理映射的虚拟目录（部分应用的文件提供器只经此暴露内容）。 */
    val indexScope: IndexScope = IndexScope.FULL_DISK_ONLY,
    /** 已授权搜索目录（SAF tree URI 列表，JSON 存储持久化授权）。 */
    val searchDirs: List<String> = emptyList(),
    val welcomeSeen: Boolean = false,
    /** 调试日志记录等级（android.util.Log 优先级）：低于它的不进日志缓冲。 */
    val logLevel: Int = AppLogger.INFO,
    /** 启动时自动检查更新（GitHub Releases API）；关闭后仍可设置页手动检查。 */
    val autoCheckUpdates: Boolean = true,
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * 设计风格（alpha28，参照 KernelSU 的 UiMode 双设计）：
 *  - [MATERIAL]：Material Design 3（历史默认，material-kolor 调色引擎）；
 *  - [MIUIX]：Miuix（HyperOS 风格）—— miuix-ui ThemeController 生成色板，
 *    组件层经 LocalUiMode 派发（底栏/顶栏/设置分组等 chrome 换 HyperOS 观感）。
 * 液态玻璃底栏（NPatch 同款）在两种风格下均可用。
 */
enum class DesignStyle { MATERIAL, MIUIX }

/** 索引/搜索范围三模式（alpha26，替代 scopedSearchOnly 布尔）。 */
enum class IndexScope {
    /** 全盘 + 目录：全盘扫描，目录清单中的虚拟目录另行 SAF 注入。 */
    FULL_DISK_AND_DIRS,
    /** 仅目录：只索引已授权目录。 */
    DIRS_ONLY,
    /** 仅全盘（历史默认语义）。 */
    FULL_DISK_ONLY;

    companion object {
        fun from(name: String?): IndexScope? =
            entries.firstOrNull { it.name == name }
    }
}

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
        designStyle = runCatching {
            DesignStyle.valueOf(prefs.getString(KEY_DESIGN_STYLE, DesignStyle.MATERIAL.name)!!)
        }.getOrDefault(DesignStyle.MATERIAL),
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
        scanAndroidData = prefs.getBoolean(KEY_SCAN_ANDROID_DATA, false),
        filterLabelSeconds = prefs.getInt(KEY_FILTER_LABEL_SECONDS, 5),
        sortByName = prefs.getBoolean(KEY_SORT_NAME, true),
        showPaginator = prefs.getBoolean(KEY_SHOW_PAGINATOR, true),
        liquidGlass = prefs.getBoolean(KEY_LIQUID_GLASS, false),
        // 三模式迁移（alpha26）：优先读新 key；旧用户按 scopedSearchOnly 布尔
        // 折算（true=仅目录，false=仅全盘），不存在的场景回落默认仅全盘
        indexScope = IndexScope.from(prefs.getString(KEY_INDEX_SCOPE, null))
            ?: if (prefs.contains(KEY_SCOPED_SEARCH) && prefs.getBoolean(KEY_SCOPED_SEARCH, false)) {
                IndexScope.DIRS_ONLY
            } else {
                IndexScope.FULL_DISK_ONLY
            },
        searchDirs = runCatching {
            val arr = org.json.JSONArray(prefs.getString(KEY_SEARCH_DIRS, "[]") ?: "[]")
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList()),
        welcomeSeen = prefs.getBoolean(KEY_WELCOME_SEEN, false),
        logLevel = prefs.getInt(KEY_LOG_LEVEL, AppLogger.INFO),
        autoCheckUpdates = prefs.getBoolean(KEY_AUTO_CHECK_UPDATES, true),
    )

    fun setThemeMode(mode: ThemeMode) = put(KEY_THEME, mode.name)

    fun setDesignStyle(v: DesignStyle) = put(KEY_DESIGN_STYLE, v.name)

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

    fun setScanAndroidData(v: Boolean) = put(KEY_SCAN_ANDROID_DATA, v)

    fun setFilterLabelSeconds(v: Int) = put(KEY_FILTER_LABEL_SECONDS, v)

    fun setSortByName(v: Boolean) = put(KEY_SORT_NAME, v)

    fun setShowPaginator(v: Boolean) = put(KEY_SHOW_PAGINATOR, v)

    fun setLiquidGlass(v: Boolean) = put(KEY_LIQUID_GLASS, v)

    fun setIndexScope(v: IndexScope) = put(KEY_INDEX_SCOPE, v.name)

    fun setSearchDirs(dirs: List<String>) {
        val arr = org.json.JSONArray()
        dirs.forEach { arr.put(it) }
        prefs.edit { putString(KEY_SEARCH_DIRS, arr.toString()) }
    }

    fun setWelcomeSeen(v: Boolean) = put(KEY_WELCOME_SEEN, v)

    fun setLogLevel(v: Int) = put(KEY_LOG_LEVEL, v)

    fun setAutoCheckUpdates(v: Boolean) = put(KEY_AUTO_CHECK_UPDATES, v)

    private fun put(key: String, value: String) = prefs.edit { putString(key, value) }

    private fun put(key: String, value: Boolean) = prefs.edit { putBoolean(key, value) }

    private fun put(key: String, value: Int) = prefs.edit { putInt(key, value) }

    companion object {
        private const val KEY_THEME = "theme_mode"
        private const val KEY_DESIGN_STYLE = "design_style"
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
        private const val KEY_SCAN_ANDROID_DATA = "scan_android_data"
        private const val KEY_FILTER_LABEL_SECONDS = "filter_label_seconds"
        private const val KEY_SORT_NAME = "sort_by_name"
        private const val KEY_SHOW_PAGINATOR = "show_paginator"
        private const val KEY_LIQUID_GLASS = "liquid_glass"
        private const val KEY_INDEX_SCOPE = "index_scope"
        private const val KEY_SCOPED_SEARCH = "scoped_search_only" // 旧布尔开关，仅迁移读取
        private const val KEY_SEARCH_DIRS = "search_dirs"
        private const val KEY_WELCOME_SEEN = "welcome_seen"
        private const val KEY_LOG_LEVEL = "log_level"
        private const val KEY_AUTO_CHECK_UPDATES = "auto_check_updates"

        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context).also { instance = it }
            }
    }
}
