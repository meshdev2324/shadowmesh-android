package com.shadowmesh.core_vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkRequest
import android.util.Log
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import uniffi.shadowmesh.*

@OptIn(ExperimentalCoroutinesApi::class)
class WireGuardServiceTest {

    private lateinit var service: WireGuardService
    private lateinit var context: Context
    private lateinit var backend: Backend
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
        backend = mockk<Backend>(relaxed = true)
        connectivityManager = mockk<ConnectivityManager>(relaxed = true)
        vpnManager = mockk<VpnManager>(relaxed = true)
        
        service = WireGuardService(context, vpnManager, backend, connectivityManager)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `connect with DNS leak protection enabled should include IPv6`() = runTest {
        val node = VpnNode("id", "Node", "US", "USA", "1.1.1.1:51820", validKey, 0u, 0u, true)
        val vpnConfig = VpnConfig(validKey, validKey, "10.0.0.1/32", "1.1.1.1:51820", "8.8.8.8", 1420u, "normal", null)
        
        val configSlot = slot<Config>()
        every { backend.setState(any(), any(), capture(configSlot)) } returns Tunnel.State.UP
        
        service.connect(node, vpnConfig, dnsLeakProtection = true)
        
        val capturedConfig = configSlot.captured
        val allAllowedIps = capturedConfig.peers.flatMap { it.allowedIps }
        val hasIpv6DefaultRoute = allAllowedIps.any { 
            val str = it.toString()
            str == "::/0" || str == "0:0:0:0:0:0:0:0/0" || str.contains("::/0")
        }
        assertTrue("IPv6 default route should be present in allowed IPs when DNS leak protection is enabled", hasIpv6DefaultRoute)
    }

    @Test
    fun `connect should update vpnManager`() = runTest {
        val node = VpnNode("id", "Node", "US", "USA", "1.1.1.1:51820", validKey, 0u, 0u, true)
        val vpnConfig = VpnConfig(validKey, validKey, "10.0.0.1/32", "1.1.1.1:51820", "8.8.8.8", 1420u, "normal", null)
        
        service.connect(node, vpnConfig)
        
        verify { vpnManager.initiateConnection(node, vpnConfig.publicKey) }
        verify { vpnManager.completeConnection() }
    }

    @Test
    fun `disconnect should notify vpnManager`() = runTest {
        // Need a tunnel instance for disconnect to actually call backend.setState
        val tunnelField = WireGuardService::class.java.getDeclaredField("tunnel")
        tunnelField.isAccessible = true
        tunnelField.set(service, mockk<WireGuardService.WgTunnel>(relaxed = true))

        service.disconnect()
        verify { vpnManager.disconnect() }
    }
}
