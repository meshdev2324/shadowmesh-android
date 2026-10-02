package com.shadowmesh.app.vpn

import com.shadowmesh.core_vpn.WireGuardService
import com.shadowmesh.core_vpn.SecureStorage
import com.shadowmesh.core_vpn.Config
import com.shadowmesh.core_vpn.domain.ConnectUseCase
import com.shadowmesh.core_vpn.domain.ConnectionProgress
import com.shadowmesh.core_vpn.domain.MonitorSessionUseCase
import com.shadowmesh.core_vpn.domain.SessionSignal
import com.shadowmesh.core_vpn.repository.VpnRepository
import com.shadowmesh.app.identity.DeviceIdentity
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uniffi.shadowmesh.*

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionManagerTest {

    private val vpnRepository = mockk<VpnRepository>(relaxed = true)
    private val wireguardService = mockk<WireGuardService>(relaxed = true)
    private val connectUseCase = mockk<ConnectUseCase>(relaxed = true)
    private val monitorSessionUseCase = mockk<MonitorSessionUseCase>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val networkDetector = mockk<NetworkDetector>(relaxed = true)
    private val deviceIdentity = mockk<DeviceIdentity>(relaxed = true)
    private val testDispatcher = UnconfinedTestDispatcher()

    private val sharedPrefs = mockk<android.content.SharedPreferences>(relaxed = true)
    private val editor = mockk<android.content.SharedPreferences.Editor>(relaxed = true)

    private lateinit var connectionManager: ConnectionManager

    @Before
    fun setup() {
        every { monitorSessionUseCase.execute() } returns flowOf()
        // ConnectionManager observes network handovers from construction; an
        // explicit empty flow keeps that collector inert instead of letting a
        // relaxed mock leak an uncaught exception into the test scope.
        every { wireguardService.networkHandovers } returns MutableSharedFlow()
        every { secureStorage.prefs } returns sharedPrefs
        every { sharedPrefs.edit() } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.putStringSet(any(), any()) } returns editor
        every { editor.apply() } just Runs

        connectionManager = ConnectionManager(
            // A relaxed Context is enough: the diagnostics classification only
            // calls getSystemService, and the tests never run that path.
            mockk<android.content.Context>(relaxed = true),
            vpnRepository,
            wireguardService,
            connectUseCase,
            monitorSessionUseCase,
            secureStorage,
            networkDetector,
            deviceIdentity,
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `toggleConnection disconnects when already connected`() = runTest {
        every { vpnRepository.getStatus() } returns ConnectionStatus.CONNECTED
        connectionManager.toggleConnection(mockk(relaxed = true), TrafficModePreference.AUTO, false, true)
        coVerify { wireguardService.disconnect() }
    }

    @Test
    fun `toggleConnection connects when disconnected`() = runTest {
        every { vpnRepository.getStatus() } returns ConnectionStatus.DISCONNECTED
        // Production gate: a connection requires an activation seed.
        every { secureStorage.get(Config.KEY_ACTIVATION_CODE) } returns "seed-code"
        val node = mockk<VpnNode>(relaxed = true)
        val publicKey = "public-key"
        every { deviceIdentity.ensureKeyPair() } returns
            DeviceIdentity.DeviceKeyPair("private", publicKey)

        val progressFlow = flowOf(ConnectionProgress.Connecting(ConnectionStatus.CONNECTING_DIRECT, "Wait", null))
        every { connectUseCase.execute(any(), any(), any(), any(), any(), any()) } returns progressFlow

        connectionManager.toggleConnection(node, TrafficModePreference.AUTO, false, true)
        verify { connectUseCase.execute(any(), any(), any(), any(), any(), any()) }
    }

    /**
     * RFC-026: the tunnel key must be the stored per-device identity. Reusing
     * the activation code as the KDF seed made every device on a code share one
     * WireGuard identity.
     */
    @Test
    fun `toggleConnection uses the per-device key and never derives it from the activation code`() = runTest {
        every { vpnRepository.getStatus() } returns ConnectionStatus.DISCONNECTED
        every { secureStorage.get(Config.KEY_ACTIVATION_CODE) } returns "seed-code"
        every { deviceIdentity.ensureKeyPair() } returns
            DeviceIdentity.DeviceKeyPair("device-private", "device-public")
        val node = mockk<VpnNode>(relaxed = true)
        every { connectUseCase.execute(any(), any(), any(), any(), any(), any()) } returns
            flowOf(ConnectionProgress.Connecting(ConnectionStatus.CONNECTING_DIRECT, "Wait", null))

        connectionManager.toggleConnection(node, TrafficModePreference.AUTO, false, true)

        // The stored private key is what reaches the tunnel.
        verify { connectUseCase.execute(node, "device-private", "device-public", any(), any(), any()) }
        verify(exactly = 0) { wireguardService.generateDeviceKeyPair(any()) }
    }

    /**
     * Security posture: a first install has no stored preference, and the
     * fail-closed default is that the kill switch is ON. It must be armed as
     * part of establishing the tunnel, not only when the user flips the toggle
     * in Settings — otherwise a fresh install connects with the kill switch
     * disarmed, and a drop silently leaks traffic.
     */
    @Test
    fun `kill switch is armed on connect when no preference is stored`() = runTest {
        every { vpnRepository.getStatus() } returns ConnectionStatus.DISCONNECTED
        every { secureStorage.get(Config.KEY_ACTIVATION_CODE) } returns "code"
        every { secureStorage.getBoolean(Config.KEY_KILL_SWITCH_ENABLED, true) } returns true
        every { deviceIdentity.ensureKeyPair() } returns
            DeviceIdentity.DeviceKeyPair("priv", "pub")
        val node = mockk<VpnNode>(relaxed = true)
        every { connectUseCase.execute(any(), any(), any(), any(), any(), any()) } returns
            flowOf(ConnectionProgress.Connecting(ConnectionStatus.CONNECTING_DIRECT, "Wait", null))

        connectionManager.toggleConnection(node, TrafficModePreference.AUTO, false, true)

        verify { vpnRepository.vpnManager.setKillSwitchEnabled(true) }
    }

    @Test
    fun `kill switch is disarmed on connect when the user explicitly disabled it`() = runTest {
        every { vpnRepository.getStatus() } returns ConnectionStatus.DISCONNECTED
        every { secureStorage.get(Config.KEY_ACTIVATION_CODE) } returns "code"
        every { secureStorage.getBoolean(Config.KEY_KILL_SWITCH_ENABLED, true) } returns false
        every { deviceIdentity.ensureKeyPair() } returns
            DeviceIdentity.DeviceKeyPair("priv", "pub")
        val node = mockk<VpnNode>(relaxed = true)
        every { connectUseCase.execute(any(), any(), any(), any(), any(), any()) } returns
            flowOf(ConnectionProgress.Connecting(ConnectionStatus.CONNECTING_DIRECT, "Wait", null))

        connectionManager.toggleConnection(node, TrafficModePreference.AUTO, false, true)

        verify { vpnRepository.vpnManager.setKillSwitchEnabled(false) }
    }

    @Test
    fun `disconnect cancels job and calls service`() = runTest {
        connectionManager.disconnect()
        coVerify { wireguardService.disconnect() }
        assertEquals(ConnectionProgress.Idle, connectionManager.connectionProgress.value)
    }

    @Test
    fun `pauseTunnel calls wireguardService pause`() = runTest {
        connectionManager.pauseTunnel(15)
        coVerify { wireguardService.pause(15) }
    }

    @Test
    fun `resumeTunnel calls wireguardService resume`() = runTest {
        connectionManager.resumeTunnel()
        coVerify { wireguardService.resume() }
    }

    @Test
    fun `setKillSwitchEnabled enabled true`() = runTest {
        connectionManager.setKillSwitchEnabled(true)
        verify { vpnRepository.vpnManager.setKillSwitchEnabled(true) }
        verify { editor.putBoolean(Config.KEY_KILL_SWITCH_ENABLED, true) }
    }

    @Test
    fun `setKillSwitchEnabled enabled false deactivates manager`() = runTest {
        connectionManager.setKillSwitchEnabled(false)
        verify { vpnRepository.vpnManager.setKillSwitchEnabled(false) }
        verify { vpnRepository.killSwitchManager.deactivateKillSwitch() }
    }

    @Test
    fun `setTrafficModePreference updates vpnManager`() {
        connectionManager.setTrafficModePreference(TrafficModePreference.SPEED)
        verify { vpnRepository.vpnManager.setTrafficModePreference(TrafficModePreference.SPEED) }
    }

    @Test
    fun `setSplitTunnelConfig updates vpnManager and storage`() {
        val config = SplitTunnelConfig(true, SplitTunnelMode.EXCLUDE, listOf("pkg1"))
        connectionManager.setSplitTunnelConfig(config)
        verify { vpnRepository.vpnManager.setSplitTunnelConfig(config) }
        verify { editor.putBoolean(Config.KEY_ST_ENABLED, true) }
    }

    @Test
    fun `runNetworkDetection updates vpnManager`() = runTest {
        // NetworkReport is a final data class, so it is constructed rather than
        // mocked. A mockk of it threw a ClassCastException on the field access.
        val report = NetworkReport(
            isConnected = true,
            networkType = uniffi.shadowmesh.NetworkType.UNKNOWN,
            latencyMs = null,
            jitterMs = null,
            packetLoss = null,
            speedTest = null,
            serverReport = null,
            captivePortal = (if (false) 1 else 0).toUByte(),

            dpiDetectedByServer = false,
            dpiDetected = (if (true) 1 else 0).toUByte(),
            isProtected = false,
        )
        coEvery { networkDetector.detect(any()) } returns report

        val result = connectionManager.runNetworkDetection(false)
        assertEquals(report, result)
        verify { vpnRepository.vpnManager.setDpiDetected(true) }
    }

    @Test
    fun `session monitoring frozen signal triggers disconnect`() = runTest {
        val frozenSignal = SessionSignal.SessionFrozen
        every { monitorSessionUseCase.execute() } returns flowOf(frozenSignal)

        // Re-init to use the new flow
        ConnectionManager(
            mockk<android.content.Context>(relaxed = true),
            vpnRepository, wireguardService, connectUseCase,
            monitorSessionUseCase, secureStorage, networkDetector,
            deviceIdentity, testDispatcher, testDispatcher
        )
        coVerify { wireguardService.disconnect() }
    }

    @Test
    fun `cancelConnection resets state and disconnects`() = runTest {
        connectionManager.cancelConnection()
        assertEquals(ConnectionProgress.Idle, connectionManager.connectionProgress.value)
        coVerify { wireguardService.disconnect() }
    }
}
