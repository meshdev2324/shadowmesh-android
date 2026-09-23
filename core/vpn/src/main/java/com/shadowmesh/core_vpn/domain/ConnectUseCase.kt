package com.shadowmesh.core_vpn.domain

import com.shadowmesh.core_vpn.repository.VpnRepository
import com.shadowmesh.core_vpn.WireGuardService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import uniffi.shadowmesh.*
import javax.inject.Inject

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

sealed class ConnectionProgress {
    data object Idle : ConnectionProgress()
    data class Connecting(val status: ConnectionStatus, val message: String? = null, val node: VpnNode? = null) : ConnectionProgress()
    data class Connected(val node: VpnNode, val assignedIp: String, val isQuantum: Boolean) : ConnectionProgress()
    data class Error(val message: String, val isMitM: Boolean) : ConnectionProgress()
}

/**
 * Encapsulates the complex connection orchestration logic.
 * SOP 09 §1: Domain layer business logic.
 * SOP 02 §3: Handles slow handshake UI updates.
 */
class ConnectUseCase @Inject constructor(
    private val vpnRepository: VpnRepository,
    private val wireGuardService: WireGuardService
) {
    fun execute(
        node: VpnNode,
        publicKey: String,
        preference: TrafficModePreference,
        isCamouflage: Boolean,
        dnsLeakProtection: Boolean
    ): Flow<ConnectionProgress> = channelFlow {
        val client = vpnRepository.apiClient
        val vpnManager = vpnRepository.vpnManager
        val logger = vpnRepository.securityEventLogger

        try {
            repeat(3) { attempt ->
                var selectedNode = node
                
                if (attempt > 0) {
                    client.setRetrySignal(true)
                    if (preference == TrafficModePreference.AUTO) {
                        selectedNode = shadowRouteBestNode(vpnRepository.getNodes()) ?: selectedNode
                    }
                    send(ConnectionProgress.Connecting(
                        ConnectionStatus.CONNECTING_DIRECT, 
                        "Optimizing mesh entry...",
                        selectedNode
                    ))
                    delay(2000)
                } else {
                    client.setRetrySignal(false)
                    send(ConnectionProgress.Connecting(ConnectionStatus.CONNECTING_DIRECT, "Securing tunnel...", selectedNode))
                }

                // SOP 02 §3: Slow Handshake monitoring
                coroutineScope {
                    val slowHandshakeJob = launch {
                        delay(5000)
                        send(ConnectionProgress.Connecting(
                            ConnectionStatus.CONNECTING_DIRECT, 
                            "Finding most resilient route...",
                            selectedNode
                        ))
                    }

                    try {
                        vpnManager.initiateConnection(selectedNode, publicKey)
                        val mode = vpnManager.getCurrentConnectionMode() ?: TrafficMode.NORMAL
                        client.setTrafficMode(mode)

                        val modeStr = when(mode) {
                            TrafficMode.FRAGMENTED -> "fragmented"
                            TrafficMode.REALITY -> "reality"
                            TrafficMode.NORMAL -> "normal"
                        }

                        val config = withContext(Dispatchers.IO) {
                            client.getConfig(selectedNode.id, publicKey, modeStr)
                        }
                        
                        val tunnelName = if (isCamouflage) "Secure Notes Sync" else "ShadowMesh"
                        wireGuardService.connect(
                            node = selectedNode,
                            clientConfig = config,
                            tunnelName = tunnelName,
                            dnsLeakProtection = dnsLeakProtection
                        )

                        logger.logEvent(
                            eventType = SecurityEventType.LOGIN_ATTEMPT,
                            details = "Node: ${selectedNode.name} (${selectedNode.region}) | Mode: ${config.trafficMode}",
                            success = true,
                            apiClient = client
                        )
                        
                        send(ConnectionProgress.Connected(
                            node = selectedNode,
                            assignedIp = config.address,
                            isQuantum = config.trafficMode == "fragmented"
                        ))
                        slowHandshakeJob.cancel()
                        return@coroutineScope
                    } catch (e: Exception) {
                        slowHandshakeJob.cancel()
                        throw e
                    }
                }
                return@repeat
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val isMitM = e.message?.contains("handshake") == true || e.message?.contains("certificate") == true
            val errorMsg = if (isMitM) {
                "Security Alert: This network may be compromised. Connection blocked for your safety."
            } else {
                "Mesh synchronization failed. Re-trying..."
            }
            
            logger.logEvent(
                eventType = if (isMitM) SecurityEventType.TAMPERING_ALERT else SecurityEventType.LOGIN_ATTEMPT,
                details = "Connection failure: ${e.message}",
                success = false,
                apiClient = client
            )
            
            send(ConnectionProgress.Error(errorMsg, isMitM))
        }
    }
}
