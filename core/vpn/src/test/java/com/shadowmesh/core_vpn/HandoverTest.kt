package com.shadowmesh.core_vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.util.Log
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import uniffi.shadowmesh.VpnManager
import java.lang.reflect.Field

/**
 * Network-handover behavior: WireGuardService registers a ConnectivityManager
 * callback in `init` and refreshes the tunnel when the default network changes.
 * Roaming itself is handled inside the Rust core (integrated boringtun), so the
 * observable contract is the callback wiring and the refresh trigger.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HandoverTest {

    private lateinit var service: WireGuardService
    private lateinit var context: Context
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var vpnManager: VpnManager
    private val callbackSlot = slot<ConnectivityManager.NetworkCallback>()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0

        mockkConstructor(NetworkRequest.Builder::class)
        every { anyConstructed<NetworkRequest.Builder>().addCapability(any<Int>()) } returns mockk<NetworkRequest.Builder>(relaxed = true)
        every { anyConstructed<NetworkRequest.Builder>().build() } returns mockk<NetworkRequest>(relaxed = true)

        context = mockk(relaxed = true)
        vpnManager = mockk(relaxed = true)
        connectivityManager = mockk(relaxed = true)

        // Capture the callback registered in init
        every {
            connectivityManager.registerNetworkCallback(any<NetworkRequest>(), capture(callbackSlot))
        } just runs

        service = WireGuardService(context, vpnManager, connectivityManager)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun setField(name: String, value: Any?) {
        val field: Field = WireGuardService::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(service, value)
    }

    @Test
    fun `handover from wifi to lte should log handover and refresh tunnel`() = runTest {
        val wifiNetwork = mockk<Network>()
        val lteNetwork = mockk<Network>()

        // Simulate an active tunnel so refreshTunnel() takes the refresh path.
        setField("currentTunnelName", "ShadowMesh-US")

        // 1. WiFi becomes available
        callbackSlot.captured.onAvailable(wifiNetwork)

        // 2. LTE becomes available (Handover)
        callbackSlot.captured.onAvailable(lteNetwork)

        verify { Log.i("WireGuardService", match { it.contains("Handover detected") }) }
        verify { Log.i("WireGuardService", match { it.contains("Refreshing tunnel") }) }
    }

    @Test
    fun `handover without active tunnel must not attempt refresh`() = runTest {
        val wifiNetwork = mockk<Network>()
        val lteNetwork = mockk<Network>()

        setField("currentTunnelName", null)
        callbackSlot.captured.onAvailable(wifiNetwork)
        callbackSlot.captured.onAvailable(lteNetwork)

        verify { Log.i("WireGuardService", match { it.contains("Handover detected") }) }
        verify(exactly = 0) { Log.i("WireGuardService", match { it.contains("Refreshing tunnel") }) }
    }

    @Test
    fun `network loss should clear lastNetwork`() = runTest {
        val network = mockk<Network>()

        callbackSlot.captured.onAvailable(network)
        callbackSlot.captured.onLost(network)

        val lastNetworkField: Field = WireGuardService::class.java.getDeclaredField("lastNetwork")
        lastNetworkField.isAccessible = true
        assertNull(lastNetworkField.get(service))
    }

    @Test
    fun `same network re-availability must not trigger handover`() = runTest {
        val network = mockk<Network>()
        setField("currentTunnelName", "ShadowMesh-US")

        callbackSlot.captured.onAvailable(network)
        callbackSlot.captured.onAvailable(network)

        verify(exactly = 0) { Log.i("WireGuardService", match { it.contains("Handover detected") }) }
        verify(exactly = 0) { Log.i("WireGuardService", match { it.contains("Refreshing tunnel") }) }
    }
}
