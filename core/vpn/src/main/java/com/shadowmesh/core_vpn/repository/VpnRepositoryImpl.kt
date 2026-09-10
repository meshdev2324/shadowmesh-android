package com.shadowmesh.core_vpn.repository

import com.shadowmesh.core_vpn.WireGuardService
import com.shadowmesh.core_vpn.NetworkClient
import com.shadowmesh.core_vpn.CoreUtils
import com.shadowmesh.core_vpn.Config
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
            apiClient.setAuthToken(token)
            vpnManager.activate(code, token, plan, devices, days)
        }
    }

    private suspend fun ensureInitialized() {
        initJob.await()
    }

    override fun getStatus(): ConnectionStatus = vpnManager.getStatus()

    override fun isActivated(): Boolean = vpnManager.isActivated()

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
        val stats = getStats()
        val now = System.currentTimeMillis() / 1000L
        return stats.packetsReceived == 0uL && (now - stats.connectedSince > 30)
    }
}
