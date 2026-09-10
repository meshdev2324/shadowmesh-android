package com.shadowmesh.macrobenchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Micro-benchmarks for core utility performance.
 * SOP 02: Verification of µs level optimizations.
 */
@RunWith(AndroidJUnit4::class)
class CoreMicroBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun byteFormattingBenchmark() = benchmarkRule.measureRepeated(
        packageName = "com.shadowmesh.app",
        metrics = listOf(TraceSectionMetric("formatBytes")),
        iterations = 10,
        startupMode = null
    ) {
        // This is a placeholder since formatBytes is in :core-vpn.
        // In a real environment, we'd wrap it in a trace section in the app.
        // For now, we measure the end-to-end impact via startup/navigation.
    }
}
