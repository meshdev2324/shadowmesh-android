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
    // Needed to read the platform transport. The core detector cannot: Rust has
    // no ConnectivityManager, which is why network_type was always Unknown.
    @dagger.hilt.android.qualifiers.ApplicationContext
    private val applicationContext: android.content.Context,
    private val vpnRepository: VpnRepository,
    private val wireguardService: WireGuardService,
    private val connectUseCase: ConnectUseCase,
    private val monitorSessionUseCase: MonitorSessionUseCase,
    private val secureStorage: SecureStorage,
    private val networkDetector: NetworkDetector,
    private val deviceIdentity: com.shadowmesh.app.identity.DeviceIdentity,
    @com.shadowmesh.app.di.MainDispatcher private val mainDispatcher: CoroutineDispatcher,
    @com.shadowmesh.app.di.IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)

    init {
        observeNetworkHandovers()
    }
    
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
            // RFC-026: connect with the device's own persisted WireGuard
            // identity. The activation code is only proof of entitlement here —
            // it is never a key-derivation input.
            lastConnectedNode = node
            lastTrafficMode = preference
            lastCamouflage = isCamouflage
            lastDnsLeakProtection = dnsLeakProtection
            // Arm the kill switch as part of establishing the tunnel, not only
            // when the Settings toggle is flipped. It used to be applied solely
            // by the toggle, so a fresh install connected with it disarmed and a
            // drop silently leaked traffic off-tunnel. Fail closed: absent a
            // stored preference the switch is ON.
            scope.launch {
                runCatching {
                    vpnRepository.vpnManager.setKillSwitchEnabled(
                        secureStorage.getBoolean(Config.KEY_KILL_SWITCH_ENABLED, true)
                    )
                }.onFailure { ZLog.w(TAG, "Could not arm kill switch at connect: ${it.message}") }
            }
            val keys = deviceIdentity.ensureKeyPair()
            connect(node, keys.privateKey, keys.publicKey, preference, isCamouflage, dnsLeakProtection)
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
                    // A tunnel that reaches CONNECTED and then stays up clears the
                    // recovery budget. Without this the ceiling would be spent
                    // over a long session and later handovers would be ignored.
                    if (progress is ConnectionProgress.Idle && vpnRepository.getStatus() == ConnectionStatus.CONNECTED) {
                        val connectedFor = System.currentTimeMillis() - connectedSinceMs
                        if (HandoverRecoveryPolicy.shouldResetBudget(connectedFor)) {
                            recoveryAttempts = 0
                            lastRecoveryAttemptAtMs = null
                        }
                    }
                    if (vpnRepository.getStatus() == ConnectionStatus.CONNECTED && progress !is ConnectionProgress.Idle) {
                        connectedSinceMs = System.currentTimeMillis()
                    }
                    _connectionProgress.value = progress
                }
        }
    }

    /**
     * Gracefully disconnects the current VPN session and updates internal state.
     */
    /**
     * Recovers a tunnel that died with the network underneath it.
     *
     * A WiFi -> mobile handover can leave the WireGuard socket bound to an
     * interface that no longer exists. Boringtun cannot repair that, so without
     * this the user is left staring at a tunnel that reports connected but
     * carries nothing — the intermittent "it just drops" symptom.
     *
     * The reconnect is deliberately conservative (see [HandoverRecoveryPolicy]):
     * a grace period for the new network to settle, exponential backoff, and a
     * hard attempt ceiling so a network that cannot come up does not loop.
     */
    private fun observeNetworkHandovers() {
        scope.launch {
            wireguardService.networkHandovers.collect {
                val wasConnected = vpnRepository.getStatus() == ConnectionStatus.CONNECTED
                if (!wasConnected) return@collect

                ZLog.w(TAG, "Handover while connected; scheduling guarded tunnel recovery")
                val job = scope.launch {
                    delay(HandoverRecoveryPolicy.GRACE_PERIOD_MS)

                    val attempts = recoveryAttempts
                    val since = lastRecoveryAttemptAtMs?.let { System.currentTimeMillis() - it } ?: Long.MAX_VALUE
                    if (!HandoverRecoveryPolicy.shouldAttempt(true, attempts, since)) {
                        ZLog.i(TAG, "Handover recovery skipped by policy (attempts=$attempts)")
                        return@launch
                    }
                    if (vpnRepository.getStatus() != ConnectionStatus.CONNECTED) return@launch

                    lastRecoveryAttemptAtMs = System.currentTimeMillis()
                    recoveryAttempts++
                    ZLog.w(TAG, "Attempting tunnel re-establishment after handover (attempt $recoveryAttempts)")

                    val keys = deviceIdentity.ensureKeyPair()
                    val node = lastConnectedNode ?: return@launch
                    // The old session is torn down first: leaving it up races the
                    // new VpnService establishment and leaves a dead tun0 behind.
                    wireguardService.disconnect()
                    connect(
                        node,
                        keys.privateKey,
                        keys.publicKey,
                        lastTrafficMode,
                        lastCamouflage,
                        lastDnsLeakProtection,
                    )
                }
                job.invokeOnCompletion { }
            }
        }
    }

    private var recoveryAttempts: Int = 0
    private var lastRecoveryAttemptAtMs: Long? = null
    private var connectedSinceMs: Long = 0L
    private var lastConnectedNode: uniffi.shadowmesh.VpnNode? = null
    private var lastTrafficMode: TrafficModePreference = TrafficModePreference.AUTO
    private var lastCamouflage: Boolean = false
    private var lastDnsLeakProtection: Boolean = true

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

        // The core detector has no way to learn the transport: `network_type` is
        // initialised to Unknown and never assigned, because Rust has no access
        // to ConnectivityManager. The platform owns that fact, so Android fills
        // it in here rather than leaving a permanently uninformative row in the
        // diagnostics panel.
        // Written onto the report because it is a var field and this is the
        // only place the platform transport is knowable. The value is only
        // overwritten when the platform gave a definite answer, so an
        // unclassifiable network keeps the detector's own default rather than
        // being asserted as something untrue.
        currentNetworkType()?.let { report.networkType = it }

        vpnRepository.vpnManager.setDpiDetected(report.dpiDetected?.toInt() == 1)
        report
    }

    /**
     * Classify the active transport from the platform.
     *
     * Returns `null` when the network cannot be classified, so the caller can
     * distinguish "no idea" from a definite answer and leave the field at its
     * unknown default rather than asserting something untrue.
     */
    fun currentNetworkType(): uniffi.shadowmesh.NetworkType? {
        val cm = try {
            applicationContext.getSystemService(android.net.ConnectivityManager::class.java)
        } catch (e: ClassCastException) {
            // A substituted or instrumented system service. Not our concern to
            // fix, but it must not take the diagnostics panel down with it.
            return null
        } ?: return null
        val network = runCatching { cm.activeNetwork }.getOrNull() ?: return null
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: return null
        return when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ->
                uniffi.shadowmesh.NetworkType.WI_FI
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ->
                uniffi.shadowmesh.NetworkType.CELLULAR
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) ->
                uniffi.shadowmesh.NetworkType.ETHERNET
            else -> null
        }
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
