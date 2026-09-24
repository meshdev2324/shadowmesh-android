package com.shadowmesh.core_vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkRequest
import android.util.Log
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import uniffi.shadowmesh.*

/**
 * Tests for WireGuardService against the current uniffi-backed core API.
 *
 * Historical note: these tests previously drove a wireguard-android `Backend`
 * directly; tunnel encryption now lives inside the Rust core (boringtun) and
 * the service only orchestrates VpnManager calls and the MeshVpnService
 * hand-off intent. Coverage tracks the current orchestration contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WireGuardServiceTest {

    private lateinit var service: WireGuardService
    private lateinit var context: Context
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var vpnManager: VpnManager

    private val validKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0

        // Mock NetworkRequest.Builder to avoid NPE in init
        mockkConstructor(NetworkRequest.Builder::class)
        every { anyConstructed<NetworkRequest.Builder>().addCapability(any()) } returns mockk(relaxed = true)
        every { anyConstructed<NetworkRequest.Builder>().build() } returns mockk(relaxed = true)

        context = mockk<Context>(relaxed = true)
        connectivityManager = mockk<ConnectivityManager>(relaxed = true)
        vpnManager = mockk<VpnManager>(relaxed = true)

        service = WireGuardService(context, vpnManager, connectivityManager)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun testNode() = VpnNode("id", "Node", "US", "USA", "1.1.1.1:51820", validKey, 0u, 0u, true, true, null)
    private fun testConfig() = VpnConfig(validKey, validKey, "10.0.0.1/32", "1.1.1.1:51820", "8.8.8.8", 1420u, "normal", null)

    @Test
    fun `connect should initiate and complete vpnManager connection`() = runTest {
        service.connect(testNode(), testConfig(), validKey)

        verify { vpnManager.initiateConnection(testNode(), testConfig().publicKey) }
        verify { vpnManager.completeConnection() }
    }

    @Test
    fun `connect hands off to MeshVpnService via a service intent`() = runTest {
        service.connect(testNode(), testConfig(), validKey)

        // Under local JVM unit tests Build.VERSION.SDK_INT is 0 (< O), so the
        // service dispatches via startService; on device (API >= 26) the same
        // call site uses startForegroundService.
        verify { context.startService(any()) }
    }

    @Test
    fun `connect failure should roll back vpnManager state and rethrow`() = runTest {
        every { vpnManager.initiateConnection(any(), any()) } throws RuntimeException("core offline")

        try {
            service.connect(testNode(), testConfig(), validKey)
            fail("connect must propagate the core failure")
        } catch (expected: RuntimeException) {
            // rethrown after rollback
        }
        verify { vpnManager.disconnect() }
        verify(exactly = 0) { vpnManager.completeConnection() }
    }

    @Test
    fun `disconnect should notify vpnManager`() = runTest {
        service.disconnect()
        verify { vpnManager.disconnect() }
    }

    @Test
    fun `pause should pause core and disconnect tunnel`() = runTest {
        service.pause(15)
        verify { vpnManager.pause(15u) }
        verify { vpnManager.disconnect() }
    }

    @Test
    fun `resume should resume core session`() = runTest {
        service.resume()
        verify { vpnManager.resume() }
    }

    @Test
    fun `kill switch activation should be enforced by core`() = runTest {
        service.activateKillSwitch()
        verify { vpnManager.setKillSwitchEnabled(true) }

        service.deactivateKillSwitch()
        verify { vpnManager.setKillSwitchEnabled(false) }
    }

    @Test
    fun `getStats should surface core counters`() = runTest {
        every { vpnManager.getStats() } returns ConnectionStats(
            bytesReceived = 1500uL,
            bytesSent = 3000uL,
            packetsReceived = 1uL,
            packetsSent = 2uL,
            lastHandshake = 42L,
            connectedSince = 7L,
        )

        val stats = service.getStats()

        assertEquals(1500uL, stats.bytesReceived)
        assertEquals(3000uL, stats.bytesSent)
        assertEquals(42L, stats.lastHandshake)
    }
}
