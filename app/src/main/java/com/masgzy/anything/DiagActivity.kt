package com.masgzy.anything

import androidx.activity.ComponentActivity

/**
 * 仪器化诊断专用空 Activity（androidTest IconDiagTest 的宿主）。
 *
 * compose ui-test 的 createAndroidComposeRule 要求被测 Activity 注册在
 * 目标 APK（targetContext 进程）内 —— 注册在 androidTest manifest 的
 * ComponentActivity 会跑进 .test 进程，startActivitySync 直接抛
 * "resolved to different process"。exported=false，对用户不可见，
 * 生产包体积代价一个空类。
 */
class DiagActivity : ComponentActivity()
