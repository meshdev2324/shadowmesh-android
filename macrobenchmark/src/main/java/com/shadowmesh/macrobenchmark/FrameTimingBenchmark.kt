package com.shadowmesh.macrobenchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Macrobenchmark suite to measure frame timing and jank rate.
 *
 * SOP 02: Targets < 1% jank rate for premium fluidity.
 */
@RunWith(AndroidJUnit4::class)
class FrameTimingBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun scrollNodeListBenchmark() = benchmarkRule.measureRepeated(
        packageName = "com.shadowmesh.app",
        metrics = listOf(FrameTimingMetric()),
        iterations = 1,
        startupMode = StartupMode.WARM,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            
            // Navigate to Node Selection Sheet
            val entryNodeCard = device.findObject(By.text("Select Node")) ?: device.findObject(By.textContains("ms"))
            entryNodeCard?.click()
            device.wait(Until.hasObject(By.res("node_list")), 5000)
        }
    ) {
        val nodeList = device.findObject(By.res("node_list"))
        if (nodeList != null) {
            nodeList.setGestureMargin(device.displayWidth / 10)
            nodeList.fling(Direction.DOWN)
            device.waitForIdle()
            nodeList.fling(Direction.UP)
            device.waitForIdle()
        }
    }

    @Test
    fun liquidStatusOrbBenchmark() = benchmarkRule.measureRepeated(
        packageName = "com.shadowmesh.app",
        metrics = listOf(FrameTimingMetric()),
        iterations = 1,
        startupMode = StartupMode.WARM,
        setupBlock = {
            pressHome()
            startActivityAndWait()
        }
    ) {
        // Measure animation smoothness for 3 seconds
        Thread.sleep(3000)
    }
}
