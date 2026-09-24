package com.shadowmesh.core_vpn.repository

import com.shadowmesh.core_vpn.WireGuardService
import com.shadowmesh.core_vpn.NetworkClient
import com.shadowmesh.core_vpn.SecureStorage
import com.shadowmesh.core_vpn.CoreUtils
import com.shadowmesh.core_vpn.Config
import io.mockk.*
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import uniffi.shadowmesh.*

class VpnRepositoryTest {

    private lateinit var vpnManager: VpnManager
    private lateinit var apiClient: ApiClient
    private lateinit var killSwitchManager: KillSwitchManagerInterface
    private lateinit var securityEventLogger: SecurityEventLogger
    private lateinit var nodeCache: NodeCache
    private lateinit var wireGuardService: WireGuardService
    private lateinit var networkClient: NetworkClient
    private lateinit var secureStorage: SecureStorage
    private lateinit var context: android.content.Context
    private lateinit var repository: VpnRepositoryImpl

    private val sharedPrefs = mockk<android.content.SharedPreferences>(relaxed = true)

    private val testDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher()

    @Before
    fun setup() {
        mockkObject(CoreUtils)
        vpnManager = mockk(relaxed = true)
        apiClient = mockk(relaxed = true)
        killSwitchManager = mockk(relaxed = true)
        securityEventLogger = mockk(relaxed = true)
        nodeCache = mockk(relaxed = true)
        wireGuardService = mockk<WireGuardService>(relaxed = true)
        networkClient = mockk<NetworkClient>(relaxed = true)
        secureStorage = mockk<SecureStorage>(relaxed = true)
        context = mockk(relaxed = true)

        every { secureStorage.prefs } returns sharedPrefs
        every { CoreUtils.getAndroidDeviceId(any()) } returns "device-id"

        repository = VpnRepositoryImpl(
            vpnManager,
            apiClient,
            killSwitchManager,
            securityEventLogger,
            nodeCache,
            wireGuardService,
            networkClient,
            secureStorage,
            context,
            testDispatcher
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `initialization restores state when code exists`() = runTest(testDispatcher) {
        clearMocks(vpnManager, apiClient)
        every { sharedPrefs.getString(Config.KEY_ACTIVATION_CODE, null) } returns "MY-CODE"
        every { sharedPrefs.getString(Config.KEY_AUTH_TOKEN, null) } returns "MY-TOKEN"
        every { sharedPrefs.getString(Config.KEY_PLAN_NAME, "Solo") } returns "Pro"
        every { sharedPrefs.getInt(Config.KEY_DEVICES_REMAINING, 0) } returns 5
        every { sharedPrefs.getLong(Config.KEY_REMAINING_DAYS, 0) } returns 100L

        VpnRepositoryImpl(
            vpnManager,
            apiClient,
            killSwitchManager,
            securityEventLogger,
            nodeCache,
            wireGuardService,
            networkClient,
            secureStorage,
            context,
            testDispatcher
        )

        advanceUntilIdle()

        verify { apiClient.setAuthToken("MY-TOKEN") }
        verify { vpnManager.activate("MY-CODE", "MY-TOKEN", "Pro", 5, 100L) }
    }

    @Test
    fun `initialization skips restore when code is null`() = runTest(testDispatcher) {
        clearMocks(vpnManager, apiClient)
        every { sharedPrefs.getString(Config.KEY_ACTIVATION_CODE, null) } returns null
        VpnRepositoryImpl(
            vpnManager,
            apiClient,
            killSwitchManager,
            securityEventLogger,
            nodeCache,
            wireGuardService,
            networkClient,
            secureStorage,
            context,
            testDispatcher
        )

        advanceUntilIdle()

        verify(exactly = 0) { vpnManager.activate(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `getStatus delegates to vpnManager`() {
        every { vpnManager.getStatus() } returns ConnectionStatus.CONNECTED
        assertEquals(ConnectionStatus.CONNECTED, repository.getStatus())
    }

    @Test
    fun `isActivated delegates to vpnManager`() {
        every { vpnManager.isActivated() } returns true
        assertTrue(repository.isActivated())
    }

    @Test
    fun `node management delegates to vpnManager`() {
        val node = mockk<VpnNode>()
        every { vpnManager.getSelectedNode() } returns node
        assertEquals(node, repository.getSelectedNode())

        repository.setSelectedNode(node)
        verify { vpnManager.setSelectedNode(node) }

        val nodes = listOf(node)
        every { vpnManager.getNodes() } returns nodes
        assertEquals(nodes, repository.getNodes())

        repository.setNodes(nodes)
        verify { vpnManager.setNodes(nodes) }
    }

    @Test
    fun `isZombieConnection returns false when not connected`() {
        every { vpnManager.getStatus() } returns ConnectionStatus.DISCONNECTED
        assertFalse(repository.isZombieConnection())
    }

    @Test
    fun `isZombieConnection returns true when no packets received after 30s`() {
        val stats = ConnectionStats(
            bytesReceived = 1000uL,
            bytesSent = 500uL,
            packetsReceived = 0uL,
            packetsSent = 10uL,
            lastHandshake = 0L,
            connectedSince = (System.currentTimeMillis() / 1000L) - 40
        )
        every { vpnManager.getStatus() } returns ConnectionStatus.CONNECTED
        every { wireGuardService.getStats() } returns stats
        assertTrue(repository.isZombieConnection())
    }

    @Test
    fun `isZombieConnection returns false when connection is very recent`() {
        val stats = ConnectionStats(
            bytesReceived = 0uL,
            bytesSent = 0uL,
            packetsReceived = 0uL,
            packetsSent = 0uL,
            lastHandshake = 0L,
            connectedSince = (System.currentTimeMillis() / 1000L) - 10
        )
        every { vpnManager.getStatus() } returns ConnectionStatus.CONNECTED
        every { wireGuardService.getStats() } returns stats
        assertFalse(repository.isZombieConnection())
    }

    @Test
    fun `verifyTunnelHealth returns false on empty response`() = runTest {
        coEvery { networkClient.get(any()) } returns ""
        assertFalse(repository.verifyTunnelHealth())
    }

    @Test
    fun `verifyTunnelHealth returns false on null response`() = runTest {
        coEvery { networkClient.get(any()) } returns null
        assertFalse(repository.verifyTunnelHealth())
    }

    @Test
    fun `activate updates vpnManager and apiClient`() {
        repository.activate("CODE", "TOKEN", "PLAN", 1, 10L)
        verify { vpnManager.activate("CODE", "TOKEN", "PLAN", 1, 10L) }
        verify { apiClient.setAuthToken("TOKEN") }
    }

    @Test
    fun `connect and disconnect delegate to wireGuardService`() = runTest {
        val node = mockk<VpnNode>()
        val config = mockk<VpnConfig>()
        repository.connect(node, config, "test-private-key", true)
        coVerify { wireGuardService.connect(node, config, "test-private-key", dnsLeakProtection = true) }

        repository.disconnect()
        coVerify { wireGuardService.disconnect() }
    }
}
