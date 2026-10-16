package com.shadowmesh.macrobenchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Macrobenchmark suite to measure navigation responsiveness.
 * SOP 02: Targets immediate interaction response (< 100ms).
 */
@RunWith(AndroidJUnit4::class)
class NavigationBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun navigateToSettingsBenchmark() = benchmarkRule.measureRepeated(
        packageName = "com.shadowmesh.app",
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        setupBlock = {
            pressHome()
            startActivityAndWait()
        }
    ) {
        // Navigate to "CONFIG" (Settings)
        val configTab = device.findObject(By.text("CONFIG"))
        configTab?.click()
        
        // Wait for Settings screen to be visible
        device.wait(Until.hasObject(By.text("SETTINGS")), 5000)
        
        // Return to Status
        val networkTab = device.findObject(By.text("NETWORK"))
        networkTab?.click()
        device.wait(Until.hasObject(By.text("PROTECT")), 5000)
    }
}
