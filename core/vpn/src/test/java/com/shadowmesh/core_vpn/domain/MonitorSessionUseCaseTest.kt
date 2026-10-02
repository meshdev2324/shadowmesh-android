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
import org.junit.Assert.assertTrue
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
            application,
            Dispatchers.Unconfined
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

    /**
     * Regression: a rate-limited heartbeat (HTTP 429) must never log the user out.
     *
     * SessionSignal.Unauthorized is consumed by VPNManagerViewModel, which calls
     * logout() -> IdentityManager.logout() -> securePrefs.remove(KEY_ACTIVATION_CODE).
     * That deleted the user's sovereign activation code (the only credential they
     * hold, and the reason the app kept asking to be re-activated). 429 is a
     * server-load signal, so it now backs off and keeps the session.
     */
    @Test
    fun `rate limited heartbeat backs off without signalling unauthorized`() = runTest {
        every { apiClient.heartbeat(any()) } throws
            ShadowMeshException.TooManyRequests("rate limited")

        val signals = mutableListOf<SessionSignal>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.execute().toList(signals)
        }

        // First heartbeat lands at 30s, then the client backs off for 300s.
        advanceTimeBy(31000)

        verify(exactly = 1) { apiClient.heartbeat(any()) }
        assertTrue(
            "429 must not destroy the activation session",
            signals.none { it is SessionSignal.Unauthorized }
        )

        // No further heartbeat inside the backoff window.
        advanceTimeBy(60000)
        verify(exactly = 1) { apiClient.heartbeat(any()) }

        job.cancel()
    }

    /**
     * Regression: the control plane reporting the session inactive must first
     * attempt a token refresh. Only a failed refresh may log the user out.
     */
    @Test
    fun `inactive session is recovered by token refresh without logout`() = runTest {
        every { apiClient.heartbeat(any()) } returns HeartbeatResponse(
            message = "OK",
            deviceId = "test",
            sessionActive = false,
            subscriptionNotice = "",
            nextHeartbeat = "30s"
        )
        every { apiClient.refreshToken() } returns "refreshed-token"
        every { vpnRepository.updateAuthToken(any()) } just runs

        val signals = mutableListOf<SessionSignal>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.execute().toList(signals)
        }

        advanceTimeBy(31000)

        verify(exactly = 1) { apiClient.refreshToken() }
        verify(exactly = 1) { vpnRepository.updateAuthToken("refreshed-token") }
        assertTrue(
            "recoverable inactive session must not log the user out",
            signals.none { it is SessionSignal.Unauthorized }
        )

        job.cancel()
    }

    /**
     * Regression: a genuinely unrecoverable unauthorized response is still
     * surfaced, so the fix does not silently disable re-authentication.
     */
    @Test
    fun `unauthorized heartbeat that cannot be refreshed still signals logout`() = runTest {
        every { apiClient.heartbeat(any()) } throws
            ShadowMeshException.Unauthorized("bad token")

        val signals = mutableListOf<SessionSignal>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.execute().toList(signals)
        }

        advanceTimeBy(31000)

        assertTrue(
            "a real 401 must still be surfaced",
            signals.any { it is SessionSignal.Unauthorized }
        )

        job.cancel()
    }
}
