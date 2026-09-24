package com.shadowmesh.app.performance

import android.content.Context
import android.os.PowerManager
import com.shadowmesh.app.util.DeviceInfoProvider
import com.shadowmesh.ui_kit.performance.PerformanceProfile
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PerformanceMonitorTest {

    private val context = mockk<Context>(relaxed = true)
    private val deviceInfoProvider = mockk<DeviceInfoProvider>(relaxed = true)
    private val powerManager = mockk<PowerManager>(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        every { context.getSystemService(Context.POWER_SERVICE) } returns powerManager
        every { deviceInfoProvider.sdkInt } returns 33
        every { deviceInfoProvider.totalRam } returns 8 * 1024 * 1024 * 1024L
        every { deviceInfoProvider.isLowRamDevice } returns false
        every { powerManager.isPowerSaveMode } returns false
        every { powerManager.currentThermalStatus } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `initial profile is HIGH for modern device with plenty RAM`() {
        val monitor = PerformanceMonitor(context, deviceInfoProvider, testDispatcher)
        assertEquals(PerformanceProfile.HIGH, monitor.profile.value)
    }

    @Test
    fun `profile is LOW when SDK is below 31`() {
        every { deviceInfoProvider.sdkInt } returns 30
        val monitor = PerformanceMonitor(context, deviceInfoProvider, testDispatcher)
        assertEquals(PerformanceProfile.LOW, monitor.profile.value)
    }

    @Test
    fun `profile is LOW when battery saver is on`() {
        every { powerManager.isPowerSaveMode } returns true
        val monitor = PerformanceMonitor(context, deviceInfoProvider, testDispatcher)
        assertEquals(PerformanceProfile.LOW, monitor.profile.value)
    }

    @Test
    fun `profile is MEDIUM for light thermal stress`() {
        every { powerManager.currentThermalStatus } returns 1
        val monitor = PerformanceMonitor(context, deviceInfoProvider, testDispatcher)
        assertEquals(PerformanceProfile.MEDIUM, monitor.profile.value)
    }

    @Test
    fun `profile is LOW for heavy thermal stress`() {
        every { powerManager.currentThermalStatus } returns 2
        val monitor = PerformanceMonitor(context, deviceInfoProvider, testDispatcher)
        assertEquals(PerformanceProfile.LOW, monitor.profile.value)
    }

    @Test
    fun `profile is LOW for low RAM device`() {
        every { deviceInfoProvider.totalRam } returns 2 * 1024 * 1024 * 1024L
        val monitor = PerformanceMonitor(context, deviceInfoProvider, testDispatcher)
        assertEquals(PerformanceProfile.LOW, monitor.profile.value)
    }
}
