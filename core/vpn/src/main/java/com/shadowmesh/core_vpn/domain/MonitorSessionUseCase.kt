package com.shadowmesh.core_vpn.domain

import android.app.Application
import com.shadowmesh.core_vpn.CoreUtils
import com.shadowmesh.core_vpn.WireGuardService
import com.shadowmesh.core_vpn.repository.VpnRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.shadowmesh.*
import javax.inject.Inject

data class SessionStats(
    val connectionStats: ConnectionStats,
    val protocolStats: ProtocolStats,
    val totalBytesThisMonth: ULong,
    val isEbpfActive: Boolean = false
)

sealed class SessionSignal {
    data class StatsUpdated(val stats: SessionStats) : SessionSignal()
    data object SessionFrozen : SessionSignal()
    data object Unauthorized : SessionSignal()
    data class ZombieDetected(val message: String) : SessionSignal()
}

/**
 * Orchestrates background monitoring of an active VPN session.
 * Handles stats polling, heartbeat, and zombie connection detection.
 * SOP 09 §1: Domain layer business logic.
 */
class MonitorSessionUseCase @Inject constructor(
    private val vpnRepository: VpnRepository,
    private val wireGuardService: WireGuardService,
    private val trafficAnalytics: TrafficAnalytics,
    private val application: Application
) {
    fun execute(): Flow<SessionSignal> = channelFlow {
        // Polling loop for stats
        launch {
            while (isActive) {
                if (vpnRepository.getStatus() == ConnectionStatus.CONNECTED) {
                    val stats = wireGuardService.getStats()
                    val pStats = vpnRepository.vpnManager.getProtocolStats()
                    
                    vpnRepository.getSelectedNode()?.id?.let { nodeId ->
                        withContext(Dispatchers.IO) {
                            trafficAnalytics.recordStats(nodeId, stats)
                        }
                    }

                    if (vpnRepository.isZombieConnection()) {
                        send(SessionSignal.ZombieDetected("Stalled connection. Refreshing..."))
                    }

                    send(SessionSignal.StatsUpdated(
                        SessionStats(
                            connectionStats = stats,
                            protocolStats = pStats,
                            totalBytesThisMonth = trafficAnalytics.getTotalBytes(),
                            isEbpfActive = vpnRepository.vpnManager.isEbpfActive()
                        )
                    ))
                }
                delay(2000)
            }
        }

        // Heartbeat loop (v5.5 Battery Optimized)
        launch {
            var nextInterval = 30000L // Default 30s
            while (isActive) {
                delay(nextInterval)
                if (vpnRepository.getStatus() == ConnectionStatus.CONNECTED && vpnRepository.isActivated()) {
                    try {
                        val deviceId = CoreUtils.getAndroidDeviceId(application)
                        val fingerprint = CoreUtils.getDeepFingerprint(application)
                        val pStats = vpnRepository.vpnManager.getProtocolStats()
                        
                        // v5.5 Battery Efficiency: Detect app visibility
                        val isBackground = !com.shadowmesh.core_vpn.CoreUtils.isAppInForeground(application)

                        val req = HeartbeatRequest(
                            deviceId = deviceId,
                            backgroundMode = isBackground,
                            deepFingerprint = fingerprint,
                            bytesSentQuantum = pStats.quantumSent,
                            bytesReceivedQuantum = pStats.quantumReceived,
                            bytesSentReality = pStats.realitySent,
                            bytesReceivedReality = pStats.realityReceived
                        )
                        val res = vpnRepository.apiClient.heartbeat(req)
                        
                        // Parse suggested interval (e.g. "120s" or "900s")
                        // Default to 120s if server doesn't provide, or respect server's peak performance window
                        nextInterval = res.nextHeartbeat.replace("s", "").toLongOrNull()?.times(1000) ?: 120000L
                        
                        if (!res.sessionActive) {
                            send(SessionSignal.Unauthorized)
                        }
                    } catch (e: Exception) {
                        when (e) {
                            is ShadowMeshException.SessionFrozen -> send(SessionSignal.SessionFrozen)
                            is ShadowMeshException.Unauthorized -> send(SessionSignal.Unauthorized)
                            is ShadowMeshException.TooManyRequests -> send(SessionSignal.Unauthorized)
                        }
                        // Fallback retry interval on error
                        nextInterval = 60000L 
                    }
                }
            }
        }
    }
}
