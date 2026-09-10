package com.shadowmesh.core_vpn.domain

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import com.shadowmesh.core_vpn.CoreUtils
import com.shadowmesh.core_vpn.WireGuardService
import com.shadowmesh.core_vpn.repository.VpnRepository
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import uniffi.shadowmesh.*

@OptIn(ExperimentalCoroutinesApi::class)
class MonitorSessionUseCaseTest {

    private val vpnRepository = mockk<VpnRepository>(relaxed = true)
    private val wireGuardService = mockk<WireGuardService>(relaxed = true)
    private val trafficAnalytics = mockk<TrafficAnalytics>(relaxed = true)
    private val application = mockk<Application>(relaxed = true)
    private val apiClient = mockk<ApiClient>(relaxed = true)
    private val vpnManager = mockk<VpnManager>(relaxed = true)
    
    private lateinit var useCase: MonitorSessionUseCase

    @Before
    fun setup() {
        mockkObject(CoreUtils)
        every { CoreUtils.getAndroidDeviceId(any()) } returns "test-device-id"
        every { CoreUtils.getDeepFingerprint(any()) } returns mapOf("mock" to "true")
        every { CoreUtils.isAppInForeground(any()) } returns false

        every { vpnRepository.apiClient } returns apiClient
        every { vpnRepository.vpnManager } returns vpnManager
        every { vpnManager.getProtocolStats() } returns mockk(relaxed = true)
        every { vpnRepository.getStatus() } returns ConnectionStatus.CONNECTED
        every { vpnRepository.isActivated() } returns true

        useCase = MonitorSessionUseCase(
            vpnRepository,
            wireGuardService,
            trafficAnalytics,
            application
        )
    }

    @After
    fun tearDown() {
        unmockkObject(CoreUtils)
    }

    @Test
    fun `test heartbeat respects server suggested interval`() = runTest {
        val heartbeatResponse = HeartbeatResponse(
            message = "OK",
            deviceId = "test",
            sessionActive = true,
            subscriptionNotice = "",
            nextHeartbeat = "120s"
        )
        
        every { apiClient.heartbeat(any()) } returns heartbeatResponse

        // Start use case and collect signals in background
        val signals = mutableListOf<SessionSignal>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.execute().toList(signals)
        }
        
        // Initial delay is 30s. Advance to 31s.
        advanceTimeBy(31000)
        
        verify(exactly = 1) { apiClient.heartbeat(any()) }
        
        // Next interval is 120s. Advance another 120s.
        advanceTimeBy(120000)
        
        verify(exactly = 2) { apiClient.heartbeat(any()) }
        
        job.cancel()
    }
}
