package com.shadowmesh.core_vpn.repository

import com.shadowmesh.core_vpn.WireGuardService
import com.shadowmesh.core_vpn.domain.MeshNodeEntry
import uniffi.shadowmesh.*

interface VpnRepository {
    val vpnManager: VpnManager
    val apiClient: ApiClient
    val killSwitchManager: KillSwitchManagerInterface
    val securityEventLogger: SecurityEventLogger
    val nodeCache: NodeCache

    fun getDeviceId(): String
    fun getStatus(): ConnectionStatus
    fun isActivated(): Boolean

    /**
     * Stage 1 of discovery: the anonymous directory the client actually holds.
     *
     * Deliberately [MeshNodeEntry], not the core's `VpnNode`. The core type
     * describes a node that can be *dialled* — it requires an endpoint and a
     * peer key — and the directory deliberately publishes neither. Reading one
     * through the other is what made every listing fail to deserialize.
     */
    fun getDirectory(): List<MeshNodeEntry>
    fun setDirectory(entries: List<MeshNodeEntry>)
    fun getSelectedEntry(): MeshNodeEntry?
    fun setSelectedEntry(entry: MeshNodeEntry)

    /**
     * Resolves once this repository has pushed the device id and any stored
     * session token into the core's [ApiClient].
     *
     * Both happen on a background scope during construction, so any request
     * issued straight from a caller's `init` races them and leaves without a
     * session. Discovery must await this; see `VPNManagerViewModel`.
     */
    suspend fun awaitInitialized()

    suspend fun connect(node: MeshNodeEntry, config: VpnConfig, privateKey: String, dnsLeakProtection: Boolean)
    suspend fun disconnect()
    
    fun getStats(): ConnectionStats
    fun getProtocolStats(): ProtocolStats
    
    fun activate(code: String, token: String?, plan: String?, devicesRemaining: Int, remainingDays: Long)
    fun updateAuthToken(token: String?)
    
    suspend fun verifyTunnelHealth(): Boolean
    
    /**
     * SOP 02 §3: Zombie Connection Detection.
     * Checks if data is flowing despite being in CONNECTED state.
     */
    fun isZombieConnection(): Boolean
}
