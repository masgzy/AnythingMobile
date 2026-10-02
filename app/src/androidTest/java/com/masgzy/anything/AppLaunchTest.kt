package com.masgzy.anything

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 模拟器启动冒烟（emulator.yml 里 connectedDebugAndroidTest 执行）。
 *
 * 只验证最硬的一条线：真实 Android 系统上 MainActivity 走完
 * onCreate -> onStart -> onResume 且不崩 —— 覆盖崩溃监听安装、
 * ViewModel 构造、Go 引擎 JNI 加载（libgojni.so）这三件
 * JVM 单测永远碰不到的事。
 *
 * 刻意不做 UI 断言：首次启动路由在 WelcomeScreen / SearchScreen
 * 之间切换，断言具体控件会让测试与文案和改版强耦合，得不偿失；
 * 启动崩溃由「进程存活 + RESUMED 状态」兜底，视觉问题靠 CI 截图人审。
 */
@RunWith(AndroidJUnit4::class)
class AppLaunchTest {

    @Test
    fun mainActivity_resumed_withoutCrash() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun targetContext_isCorrectPackage() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.masgzy.anything", target.packageName)
    }
}
