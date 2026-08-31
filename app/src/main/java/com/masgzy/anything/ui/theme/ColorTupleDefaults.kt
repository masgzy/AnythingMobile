/*
 * 简单变体预设 —— 完整照抄 ImageToolbox
 * core/ui/widget/color_picker/ColorTupleDefaults.kt：
 * 16 个预设主色一字不差，配套的辅助/第三/表面/中性变体/错误角色
 * 全部由调色引擎的 calculate*Color 公式派生（与 ImageToolbox 相同）。
 */

package com.masgzy.anything.ui.theme

import androidx.compose.ui.graphics.Color
import com.masgzy.anything.data.RoleColors

object ColorTupleDefaults {

    /**
     * ImageToolbox ColorTupleDefaults.defaultColorTuples 的 16 个预设色，
     * 顺序与色值原样保留。
     */
    private val defaultPrimaryColors = listOf(
        0xFFf8130d,
        0xFF7a000b,
        0xFF8a3a00,
        0xFFff7900,
        0xFFfcf721,
        0xFF88dd20,
        0xFF16B16E,
        0xFF01a0a3,
        0xFF005FFF,
        0xFFfa64e1,
        0xFFd7036a,
        0xFFdb94fe,
        0xFF7b2bec,
        0xFF022b6d,
        0xFFFFFFFF,
        0xFF000000,
    )

    /** 与预设一一对应的中文名（沿用 SeedPalette 命名）。 */
    private val names = listOf(
        "绯红", "酒红", "赭石", "亮橙", "柠檬黄", "草绿", "翡翠", "青碧",
        "蔚蓝", "粉红", "玫红", "兰紫", "紫罗兰", "藏青", "纯白", "纯黑",
    )

    /** 主色 -> 由同一套公式派生六角色（与 ImageToolbox defaultColorTuples 一致）。 */
    private fun tupleOf(argb: Long): RoleColors = run {
        val primary = Color(argb.toInt())
        RoleColors(
            primary = primary.toArgb(),
            secondary = primary.calculateSecondaryColor(),
            tertiary = primary.calculateTertiaryColor(),
            surface = primary.calculateSurfaceColor(),
            surfaceVariant = primary.calculateNeutralVariantColor(),
            error = primary.calculateErrorColor(),
        )
    }

    /**
     * 简单变体预设：一键应用整套角色配色。
     * （默认排最前的"原版靛蓝"对应 Anything 原版主色，其余 16 个照抄。）
     */
    val presets: List<Pair<String, RoleColors>> by lazy {
        listOf("原版靛蓝" to defaultRoles) +
            defaultPrimaryColors.zip(names).map { (argb, name) ->
                name to tupleOf(argb)
            }
    }

    /**
     * 默认角色配色：主色取原版 Anything 靛蓝，其余角色按同一公式派生
     * （错误色保留 M3 标准红，保证红色语义稳定）。
     */
    val defaultRoles: RoleColors by lazy {
        val primary = Color(0xFF3F51B5.toInt())
        RoleColors(
            primary = primary.toArgb(),
            secondary = primary.calculateSecondaryColor(),
            tertiary = primary.calculateTertiaryColor(),
            surface = primary.calculateSurfaceColor(),
            surfaceVariant = primary.calculateNeutralVariantColor(),
            error = 0xFFB3261E.toInt(),
        )
    }
}
