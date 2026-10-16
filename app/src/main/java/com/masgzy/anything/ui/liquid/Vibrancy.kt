// Vendored from NPatch (https://github.com/7723mod/NPatch), GPL-3.0 — see README 致谢。
// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 NPatch contributors
// 原文件：manager/src/main/java/top/nkbe/npatch/ui/component/liquid/Vibrancy.kt
//
package com.masgzy.anything.ui.liquid

import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.colorControls

fun BackdropEffectScope.vibrancy() {
    colorControls(
        brightness = 0f,
        contrast = 1f,
        saturation = 1.5f,
    )
}
