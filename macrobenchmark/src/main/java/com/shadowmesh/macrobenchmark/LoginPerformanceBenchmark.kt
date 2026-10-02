package com.shadowmesh.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Macrobenchmark for the high-end Login experience.
 * SOP 02: Validating frame stability for physics-based animations.
 */
@RunWith(AndroidJUnit4::class)
class LoginPerformanceBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun startup() = benchmarkRule.measureRepeated(
        packageName = "com.shadowmesh.app",
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.COLD
    ) {
        pressHome()
        startActivityAndWait()
    }

    @Test
    fun testLoginInteractionPerformance() = benchmarkRule.measureRepeated(
        packageName = "com.shadowmesh.app",
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Full(),
        iterations = 5,
        setupBlock = {
            pressHome()
            startActivityAndWait()
        }
    ) {
        // Find token input
        val tokenInput = device.wait(Until.findObject(By.res("token_input")), 5000)
        tokenInput?.click()
        device.waitForIdle()
        
        // Simulate typing a segment
        repeat(5) {
            device.pressKeyCode(android.view.KeyEvent.KEYCODE_X)
            Thread.sleep(100) // Simulate human typing speed to observe frame stability during input
        }
        
        device.waitForIdle()
    }
}
