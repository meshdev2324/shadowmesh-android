package com.shadowmesh.core_vpn.repository

import com.shadowmesh.core_vpn.WireGuardService
import uniffi.shadowmesh.*

interface VpnRepository {
    val vpnManager: VpnManager
    val apiClient: ApiClient
    val killSwitchManager: KillSwitchManagerInterface
    val securityEventLogger: SecurityEventLogger
    val nodeCache: NodeCache
    
    fun getStatus(): ConnectionStatus
    fun isActivated(): Boolean
    fun getSelectedNode(): VpnNode?
    fun setSelectedNode(node: VpnNode)
    fun getNodes(): List<VpnNode>
    fun setNodes(nodes: List<VpnNode>)
    
    suspend fun connect(node: VpnNode, config: VpnConfig, dnsLeakProtection: Boolean)
    suspend fun disconnect()
    
    fun getStats(): ConnectionStats
    fun getProtocolStats(): ProtocolStats
    
    fun activate(code: String, token: String?, plan: String?, devicesRemaining: Int, remainingDays: Long)
    
    suspend fun verifyTunnelHealth(): Boolean
    
    /**
     * SOP 02 §3: Zombie Connection Detection.
     * Checks if data is flowing despite being in CONNECTED state.
     */
    fun isZombieConnection(): Boolean
}
