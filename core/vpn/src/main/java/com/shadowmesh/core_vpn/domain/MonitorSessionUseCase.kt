package com.shadowmesh.core_vpn.domain

import android.app.Application
import android.util.Log
import com.shadowmesh.core_vpn.CoreUtils
import com.shadowmesh.core_vpn.WireGuardService
import com.shadowmesh.core_vpn.repository.VpnRepository
import kotlinx.coroutines.CoroutineDispatcher
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
    private val application: Application,
    @com.shadowmesh.core_vpn.di.IoDispatcher
    private val ioDispatcher: CoroutineDispatcher
) {

    private companion object {
        const val TAG = "MonitorSession"
    }
    fun execute(): Flow<SessionSignal> = channelFlow {
        // Polling loop for stats
        launch {
            while (isActive) {
                if (vpnRepository.getStatus() == ConnectionStatus.CONNECTED) {
                    val stats = wireGuardService.getStats()
                    val pStats = vpnRepository.vpnManager.getProtocolStats()
                    
                    vpnRepository.getSelectedNode()?.id?.let { nodeId ->
                        withContext(ioDispatcher) {
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
                        // The UniFFI heartbeat is a SYNCHRONOUS FFI call that
                        // performs an HTTPS round-trip (30s client timeout) and
                        // blocks the calling thread. This loop runs in the
                        // ViewModel scope, i.e. Dispatchers.Main, so every
                        // heartbeat froze the UI — captured on-device as:
                        //   "main" RUNNABLE at uniffi.shadowmesh.ApiClient.heartbeat
                        //   at MonitorSessionUseCase$execute$1$2 → Looper.loop
                        // which is why interacting with the app (setting a
                        // passcode, opening Settings) could appear to hang. Move
                        // the network call off the main thread; the loop's own
                        // cadence and ordering are unchanged.
                        val res = withContext(ioDispatcher) {
                            vpnRepository.apiClient.heartbeat(req)
                        }
                        
                        // Parse suggested interval (e.g. "120s" or "900s")
                        // Default to 120s if server doesn't provide, or respect server's peak performance window
                        nextInterval = res.nextHeartbeat.replace("s", "").toLongOrNull()?.times(1000) ?: 120000L
                        
                        if (!res.sessionActive) {
                            // The control plane reports the session inactive. Try to
                            // re-authenticate before destroying anything: a stale or
                            // lagging server-side flag must not cost the user their
                            // sovereign activation code, which is the only credential
                            // they have and cannot be reissued automatically.
                            val recovered = runCatching {
                                val newToken = withContext(ioDispatcher) {
                                    vpnRepository.apiClient.refreshToken()
                                }
                                vpnRepository.updateAuthToken(newToken)
                                true
                            }.getOrDefault(false)
                            if (recovered) {
                                Log.i(TAG, "Heartbeat reported inactive session; token refreshed, session retained")
                                nextInterval = 30000L
                            } else {
                                Log.w(TAG, "Heartbeat reported inactive session and refresh failed")
                                send(SessionSignal.Unauthorized)
                            }
                        }
                    } catch (e: Exception) {
                        when (e) {
                            is ShadowMeshException.SessionFrozen -> send(SessionSignal.SessionFrozen)
                            is ShadowMeshException.Unauthorized -> {
                                // A 401 means *this request* was refused. It is not
                                // proof the subscription ended, and treating it as
                                // such deleted a still-valid activation code from
                                // the user's device.
                                //
                                // The token may simply have expired. Try to refresh
                                // it and retry the heartbeat once before
                                // concluding anything, and only then report a
                                // failure. A genuine revocation survives the retry;
                                // an expired token does not.
                                Log.w(TAG, "Heartbeat unauthorized; attempting token refresh and retry")
                                // A refresh that returns no token has not
                                // refreshed anything. Treating an empty result as
                                // success would suppress the Unauthorized signal
                                // and leave the client retrying forever against a
                                // credential the server will keep refusing.
                                val refreshed = try {
                                    val token = vpnRepository.apiClient.refreshToken()
                                    if (token.isNullOrBlank()) {
                                        Log.w(TAG, "Token refresh after 401 returned no token")
                                        false
                                    } else {
                                        true
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Token refresh after 401 failed: ${e.message}")
                                    false
                                }
                                if (!refreshed) {
                                    send(SessionSignal.Unauthorized)
                                } else {
                                    nextInterval = 120000L
                                    Log.i(TAG, "Token refreshed after 401; session retained for retry")
                                }
                            }
                            is ShadowMeshException.TooManyRequests -> {
                                // 429 is a server-load signal, NOT an authorization
                                // failure. It used to be mapped to Unauthorized,
                                // which logged the user out and deleted their
                                // activation code whenever the control plane
                                // asked the client to slow down. Back off instead.
                                Log.w(TAG, "Heartbeat rate-limited; backing off, session retained")
                                nextInterval = 300000L
                            }
                            else -> Log.w(TAG, "Heartbeat failed: ${e.message}")
                        }
                        if (nextInterval < 60000L) nextInterval = 60000L
                    }
                }
            }
        }
    }
}
