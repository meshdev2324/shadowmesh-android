package com.shadowmesh.core_vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.util.Log
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.Tunnel
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import uniffi.shadowmesh.VpnManager

@OptIn(ExperimentalCoroutinesApi::class)
class HandoverTest {

    private lateinit var service: WireGuardService
    private lateinit var context: Context
    private lateinit var backend: Backend
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
        backend = mockk(relaxed = true)
        vpnManager = mockk(relaxed = true)
        connectivityManager = mockk(relaxed = true)

        // Capture the callback registered in init
        every { 
            connectivityManager.registerNetworkCallback(any<NetworkRequest>(), capture(callbackSlot)) 
        } just runs

        service = WireGuardService(context, vpnManager, backend, connectivityManager)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `handover from wifi to lte should refresh tunnel`() = runTest {
        val wifiNetwork = mockk<Network>()
        val lteNetwork = mockk<Network>()
        
        // Mock a tunnel being active
        val tunnelField = WireGuardService::class.java.getDeclaredField("tunnel")
        tunnelField.isAccessible = true
        tunnelField.set(service, mockk<WireGuardService.WgTunnel>(relaxed = true))
        
        val nameField = WireGuardService::class.java.getDeclaredField("currentTunnelName")
        nameField.isAccessible = true
        nameField.set(service, "ShadowMesh-US")

        // 1. WiFi becomes available
        callbackSlot.captured.onAvailable(wifiNetwork)
        
        // 2. LTE becomes available (Handover)
        callbackSlot.captured.onAvailable(lteNetwork)

        // Verify backend.getState was called to check tunnel health
        verify { backend.getState(any()) }
        verify { Log.i("WireGuardService", match { it.contains("Handover detected") }) }
    }

    @Test
    fun `network loss should clear lastNetwork`() = runTest {
        val network = mockk<Network>()
        
        callbackSlot.captured.onAvailable(network)
        callbackSlot.captured.onLost(network)
        
        val lastNetworkField = WireGuardService::class.java.getDeclaredField("lastNetwork")
        lastNetworkField.isAccessible = true
        assert(lastNetworkField.get(service) == null)
    }
}
