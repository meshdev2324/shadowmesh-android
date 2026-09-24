package com.shadowmesh.app.vpn

import com.shadowmesh.app.util.ZLog
import com.shadowmesh.core_vpn.WireGuardService
import com.shadowmesh.core_vpn.Config
import com.shadowmesh.core_vpn.SecureStorage
import com.shadowmesh.core_vpn.domain.ConnectUseCase
import com.shadowmesh.core_vpn.domain.ConnectionProgress
import com.shadowmesh.core_vpn.domain.MonitorSessionUseCase
import com.shadowmesh.core_vpn.domain.SessionSignal
import com.shadowmesh.core_vpn.repository.VpnRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import uniffi.shadowmesh.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

private const val TAG = "ConnectionManager"

/**
 * Orchestrates VPN connection lifecycle and health monitoring.
 * SOP 09 §3: Encapsulates connection orchestration.
 *
 * This manager is the central authority for managing the WireGuard tunnel lifecycle,
 * including connection initiation, disconnection, and background health monitoring.
 * It coordinates between the [VpnRepository], [WireGuardService], and domain-level Use Cases.
 */
@Singleton
class ConnectionManager @Inject constructor(
    private val vpnRepository: VpnRepository,
    private val wireguardService: WireGuardService,
    private val connectUseCase: ConnectUseCase,
    private val monitorSessionUseCase: MonitorSessionUseCase,
    private val secureStorage: SecureStorage,
    private val networkDetector: NetworkDetector,
    @com.shadowmesh.app.di.MainDispatcher private val mainDispatcher: CoroutineDispatcher,
    @com.shadowmesh.app.di.IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    
    /**
     * Flow of connection progress events, used by the UI to show status updates.
     */
    private val _connectionProgress = MutableStateFlow<ConnectionProgress>(ConnectionProgress.Idle)
    val connectionProgress = _connectionProgress.asStateFlow()

    /**
     * Flow of session-level signals (e.g., unauthorized, session frozen).
     */
    private val _sessionSignal = MutableSharedFlow<SessionSignal>()
    val sessionSignal = _sessionSignal.asSharedFlow()

    private var connectionJob: Job? = null

    init {
        startSessionMonitoring()
        startTunnelHealthMonitor()
    }

    /**
     * Toggles the VPN connection for a specific node.
     * If already connected, it will disconnect. Otherwise, it generates a new keypair and initiates connection.
     *
     * @param node The [VpnNode] to connect to.
     * @param preference User's traffic mode preference (Auto, Speed, Stealth).
     * @param isCamouflage Whether camouflage mode is enabled (affects routing/DPI bypass).
     * @param dnsLeakProtection Whether to force all DNS/IPv6 traffic into the tunnel.
     */
    fun toggleConnection(
        node: VpnNode,
        preference: TrafficModePreference,
        isCamouflage: Boolean,
        dnsLeakProtection: Boolean
    ) {
        val status = vpnRepository.getStatus()
        if (status == ConnectionStatus.CONNECTED) {
            disconnect()
        } else if (status == ConnectionStatus.DISCONNECTED || status == ConnectionStatus.ERROR) {
            val seed = secureStorage.get(Config.KEY_ACTIVATION_CODE)
            if (seed.isNullOrEmpty()) {
                _connectionProgress.value = ConnectionProgress.Error("Activation required", false)
                return
            }
            val keys = wireguardService.generateDeviceKeyPair(seed)
            connect(node, keys.first, keys.second, preference, isCamouflage, dnsLeakProtection)
        } else {
            ZLog.w(TAG, "Connection already in progress (Status: $status). Ignoring toggle.")
        }
    }

    private fun connect(
        node: VpnNode,
        privateKey: String,
        publicKey: String,
        preference: TrafficModePreference,
        isCamouflage: Boolean,
        dnsLeakProtection: Boolean
    ) {
        connectionJob?.cancel()
        connectionJob = scope.launch {
            connectUseCase.execute(node, privateKey, publicKey, preference, isCamouflage, dnsLeakProtection)
                .collect { progress ->
                    _connectionProgress.value = progress
                }
        }
    }

    /**
     * Gracefully disconnects the current VPN session and updates internal state.
     */
    fun disconnect() {
        connectionJob?.cancel()
        scope.launch {
            wireguardService.disconnect()
            _connectionProgress.value = ConnectionProgress.Idle
        }
    }

    /**
     * Temporarily pauses the VPN connection for a specified duration.
     * @param minutes Duration in minutes to stay disconnected.
     */
    fun pauseTunnel(minutes: Int) {
        scope.launch {
            wireguardService.pause(minutes)
        }
    }

    /**
     * Resumes a previously paused VPN connection.
     */
    fun resumeTunnel() {
        scope.launch {
            // Using FQN or specific call to avoid naming conflicts with coroutine resume
            wireguardService.resume()
        }
    }

    /**
     * Configures the global VPN Kill Switch.
     * @param enabled If true, all non-VPN traffic is blocked if the tunnel drops.
     */
    suspend fun setKillSwitchEnabled(enabled: Boolean) {
        vpnRepository.vpnManager.setKillSwitchEnabled(enabled)
        if (!enabled) {
            vpnRepository.killSwitchManager.deactivateKillSwitch()
        }
        secureStorage.prefs.edit().putBoolean(Config.KEY_KILL_SWITCH_ENABLED, enabled).apply()
    }

    /**
     * Updates the user's preferred traffic routing mode.
     */
    fun setTrafficModePreference(pref: TrafficModePreference) {
        vpnRepository.vpnManager.setTrafficModePreference(pref)
    }

    /**
     * Configures Split Tunneling rules for specific applications.
     */
    fun setSplitTunnelConfig(config: SplitTunnelConfig) {
        vpnRepository.vpnManager.setSplitTunnelConfig(config)
        secureStorage.prefs.edit().apply {
            putBoolean(Config.KEY_ST_ENABLED, config.enabled)
            putString(Config.KEY_ST_MODE, config.mode.name.lowercase())
            putStringSet(Config.KEY_ST_APPS, config.appList.toHashSet())
        }.apply()
    }

    /**
     * Runs network diagnostics to detect DPI or connection issues.
     * @param force If true, ignores cached reports and performs a full scan.
     */
    suspend fun runNetworkDetection(force: Boolean): NetworkReport? = withContext(ioDispatcher) {
        val report = networkDetector.detect(force)
        vpnRepository.vpnManager.setDpiDetected(report.dpiDetected)
        report
    }

    private fun startSessionMonitoring() {
        scope.launch {
            monitorSessionUseCase.execute().collect { signal ->
                _sessionSignal.emit(signal)
                if (signal is SessionSignal.SessionFrozen) {
                    disconnect()
                }
            }
        }
    }

    private fun startTunnelHealthMonitor() {
        scope.launch {
            while (isActive) {
                if (vpnRepository.getStatus() == ConnectionStatus.CONNECTED) {
                    val isHealthy = vpnRepository.verifyTunnelHealth()
                    if (!isHealthy) {
                        ZLog.w(TAG, "Tunnel health verification failed!")
                        // SOP 08: Calm messaging for stalled connections
                        _sessionSignal.emit(SessionSignal.ZombieDetected("Improving mesh stability. Connection refresh recommended."))
                    }
                }
                // SOP 09: Balance security with battery life (v5.5 Optimized)
                delay(180.seconds)
            }
        }
    }

    fun cancelConnection() {
        connectionJob?.cancel()
        _connectionProgress.value = ConnectionProgress.Idle
        disconnect()
    }
}
