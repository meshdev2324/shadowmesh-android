package com.shadowmesh.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates a Baseline Profile for ShadowMesh to optimize startup and critical paths.
 * SOP 02: Performance optimization via AOT compilation.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineRule = BaselineProfileRule()

    @Test
    fun generate() = baselineRule.collect(
        packageName = "com.shadowmesh.app"
    ) {
        pressHome()
        startActivityAndWait()

        // Path 1: Node Selection
        val selectNodeButton = device.findObject(By.textContains("ms")) ?: device.findObject(By.text("SELECT NODE"))
        selectNodeButton?.click()
        device.wait(Until.hasObject(By.res("node_list")), 3000)
        device.pressBack()

        // Path 2: Navigation to Config
        val configTab = device.findObject(By.text("CONFIG"))
        configTab?.click()
        device.wait(Until.hasObject(By.text("SETTINGS")), 3000)

        // Path 3: Toggle Kill Switch (if visible)
        val killSwitch = device.findObject(By.text("Kill Switch"))
        killSwitch?.click()
    }
}
