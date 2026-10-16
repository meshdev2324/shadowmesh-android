package com.shadowmesh.core_vpn.repository

import android.util.Log
import com.shadowmesh.core_vpn.Config
import com.shadowmesh.core_vpn.EngineStatus
import com.shadowmesh.core_vpn.WireGuardService
import com.shadowmesh.core_vpn.NetworkClient
import com.shadowmesh.core_vpn.CoreUtils
import kotlinx.coroutines.*
import uniffi.shadowmesh.*
import javax.inject.Inject
import javax.inject.Singleton

class VpnRepositoryImpl @Inject constructor(
    override val vpnManager: VpnManager,
    override val apiClient: ApiClient,
    override val killSwitchManager: KillSwitchManagerInterface,
    override val securityEventLogger: SecurityEventLogger,
    override val nodeCache: NodeCache,
    private val wireGuardService: WireGuardService,
    private val networkClient: NetworkClient,
    private val secureStorage: com.shadowmesh.core_vpn.SecureStorage,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    @com.shadowmesh.core_vpn.di.IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : VpnRepository {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val initJob: Deferred<Unit>

    init {
        // SOP 11: Decouple initialization from UI thread
        initJob = scope.async {
            initializeInternal()
        }
    }

    private fun initializeInternal() {
        // v5.5 Zero-Trust: Initialize ApiClient with the persistent device ID
        val deviceId = CoreUtils.getAndroidDeviceId(context)
        apiClient.setDeviceId(deviceId)

        // v5.5 Sync: Restore activation state into core components on startup
        val prefs = secureStorage.prefs
        val token = prefs.getString(Config.KEY_AUTH_TOKEN, null)
        val code = prefs.getString(Config.KEY_ACTIVATION_CODE, null)
        val plan = prefs.getString(Config.KEY_PLAN_NAME, "Solo")
        val devices = prefs.getInt(Config.KEY_DEVICES_REMAINING, 0)
        val days = prefs.getLong(Config.KEY_REMAINING_DAYS, 0)

        if (code != null) {
            // Redacted presence diagnostic. Distinguishes the two failure modes
            // that look identical from the UI ("please re-enter your code"):
            //   storage fault  -> usingFallbackMirror == true
            //   validation fault-> a token exists but the server rejects it
            // No value is ever logged (ZPII).
            Log.i(
                "VpnRepository",
                "Restoring activation: code=${if (code.isNotEmpty()) "present" else "empty"}, " +
                    "token=${if (token.isNullOrEmpty()) "absent" else "present"}, " +
                    "plan=$plan, secureStore=${if (secureStorage.usingFallbackMirror) "FALLBACK_MIRROR" else "encrypted"}"
            )
            apiClient.setAuthToken(token)
            vpnManager.activate(code, token, plan, devices, days)
        } else {
            Log.i("VpnRepository", "No stored activation code found; showing activation screen")
        }
    }

    private suspend fun ensureInitialized() {
        initJob.await()
    }

    override fun getStatus(): ConnectionStatus = vpnManager.getStatus()

    /**
     * Activation is a *persisted* fact, so it must be answered from persisted
     * state — not from the core's in-memory view of it.
     *
     * `vpnManager.activate(...)` only runs inside the asynchronous
     * [initializeInternal], so reading the core alone raced the restore: the UI
     * could observe `false`, gate to the activation screen, and then never
     * re-check — leaving a fully activated device asking for its code again.
     * Whether you saw the bug depended purely on which finished first, which is
     * why it looked OS-correlated (slower OEM init paths lost the race more
     * often) while actually being a defect in our own code.
     *
     * The stored activation code is the source of truth; the core state is a
     * cache of it that catches up when initialization completes.
     */
    override fun isActivated(): Boolean =
        vpnManager.isActivated() || secureStorage.prefs.contains(Config.KEY_ACTIVATION_CODE)

    override fun getDeviceId(): String = CoreUtils.getAndroidDeviceId(context)

    override fun getSelectedNode(): VpnNode? = vpnManager.getSelectedNode()

    override fun setSelectedNode(node: VpnNode) {
        vpnManager.setSelectedNode(node)
    }

    override fun getNodes(): List<VpnNode> = vpnManager.getNodes()

    override fun setNodes(nodes: List<VpnNode>) {
        vpnManager.setNodes(nodes)
    }

    override suspend fun connect(node: VpnNode, config: VpnConfig, privateKey: String, dnsLeakProtection: Boolean) {
        ensureInitialized()
        wireGuardService.connect(node, config, privateKey, dnsLeakProtection = dnsLeakProtection)
    }

    override suspend fun disconnect() {
        wireGuardService.disconnect()
    }

    override fun getStats(): ConnectionStats = wireGuardService.getStats()

    override fun getProtocolStats(): ProtocolStats = vpnManager.getProtocolStats()

    override fun activate(code: String, token: String?, plan: String?, devicesRemaining: Int, remainingDays: Long) {
        vpnManager.activate(code, token, plan, devicesRemaining, remainingDays)
        // v5.5 Peak Security: Ensure ApiClient is synchronized with the new session token
        updateAuthToken(token)
    }

    override fun updateAuthToken(token: String?) {
        apiClient.setAuthToken(token)
        secureStorage.set(Config.KEY_AUTH_TOKEN, token)
    }

    override suspend fun verifyTunnelHealth(): Boolean {
        // v11.1 Verification Flow: Check external IP via tunnel
        val response = networkClient.get("https://api64.ipify.org?format=json")
        return response?.contains("\"ip\":") == true
    }

    override fun isZombieConnection(): Boolean {
        if (getStatus() != ConnectionStatus.CONNECTED) return false

        // A session with no inbound packets is a zombie — but only when the
        // tunnel is supposed to be carrying traffic. Under split tunnelling in
        // INCLUDE (per-app) mode the tunnel serves only the selected apps, so an
        // idle device legitimately receives nothing, and this check fired a
        // false "Stalled connection. Refreshing..." seconds after every
        // successful per-app connect. Same invalid assumption as the connect
        // health gate; both now consult the same persisted configuration.
        val storage = secureStorage
        val perAppRouting = storage.getBoolean(Config.KEY_ST_ENABLED, false) &&
            (storage.get(Config.KEY_ST_MODE) ?: "exclude").equals("include", ignoreCase = true) &&
            storage.prefs.getStringSet(Config.KEY_ST_APPS, emptySet()).orEmpty().isNotEmpty()
        if (perAppRouting) {
            return !EngineStatus.transportEstablished
        }

        val stats = getStats()
        val now = System.currentTimeMillis() / 1000L
        return stats.packetsReceived == 0uL && (now - stats.connectedSince > 30)
    }
}
