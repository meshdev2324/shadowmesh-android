package com.shadowmesh.core_vpn.domain

import com.shadowmesh.core_vpn.repository.VpnRepository
import com.shadowmesh.core_vpn.Config
import com.shadowmesh.core_vpn.EngineStatus
import com.shadowmesh.core_vpn.SecureStorage
import com.shadowmesh.core_vpn.WireGuardService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import uniffi.shadowmesh.*
import javax.inject.Inject
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

object ConnectTiming {
    /**
     * Ceiling for one full connect ladder (3 attempts, each with a 25s traffic
     * probe and 4-6s cooldowns) plus the API round-trips. Generous enough not to
     * truncate a healthy slow-network connect, tight enough that a stall becomes
     * a reported error instead of an unbounded "SYNC".
     */
    const val CONNECT_DEADLINE_MS = 150_000L
}

sealed class ConnectionProgress {
    data object Idle : ConnectionProgress()
    data class Connecting(val status: ConnectionStatus, val message: String? = null, val node: VpnNode? = null) : ConnectionProgress()
    data class Connected(val node: VpnNode, val assignedIp: String, val isQuantum: Boolean, val mode: String = "normal") : ConnectionProgress()
    data class Error(val message: String, val isMitM: Boolean) : ConnectionProgress()
}

/**
 * Encapsulates the complex connection orchestration logic.
 * SOP 09 §1: Domain layer business logic.
 * SOP 02 §3: Handles slow handshake UI updates.
 */
class ConnectUseCase @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val vpnRepository: VpnRepository,
    private val wireGuardService: WireGuardService
) {
    fun execute(
        node: VpnNode,
        privateKey: String,
        publicKey: String,
        preference: TrafficModePreference,
        isCamouflage: Boolean,
        dnsLeakProtection: Boolean
    ): Flow<ConnectionProgress> = channelFlow {
        val client = vpnRepository.apiClient
        val vpnManager = vpnRepository.vpnManager
        val logger = vpnRepository.securityEventLogger

        // Hard ceiling for the whole attempt ladder.
        //
        // Without it the UI can sit on "SYNC" for minutes without ever reaching
        // a terminal state: the HTTP client allows 30s per request and retries
        // twice, the ladder runs three attempts, and each failed traffic check
        // adds a 25s probe plus a 4-6s cooldown. That is ~5 minutes of SYNC
        // with no progress and no error — indistinguishable, to a user, from a
        // hang. Reported on Android 15/16 as an indefinite SYNC.
        //
        // This bound converts any stall into a real, reported failure, and the
        // per-stage logging below identifies which stage stalled.
        val deadline = SystemClock.elapsedRealtime() + ConnectTiming.CONNECT_DEADLINE_MS
        fun remainingMs(): Long = deadline - SystemClock.elapsedRealtime()
        fun stage(name: String) {
            android.util.Log.i(
                "SHADOWMESH_STAGE",
                "stage=$name elapsed=${ConnectTiming.CONNECT_DEADLINE_MS - remainingMs()}ms remaining=${remainingMs()}ms"
            )
        }

        stage("flow-start")
        try {
            for (attempt in 0..2) {
                val budget = remainingMs()
                stage("attempt-$attempt")
                if (budget <= 0L) {
                    throw Exception("Connect deadline exceeded before attempt $attempt")
                }
                var selectedNode = node
                
                if (attempt > 0) {
                    client.setRetrySignal(true)
                    // v7.3 FireWall Critical Fix: Force a clean state before retrying.
                    // This prevents Attempt 1 from timing out if Attempt 0 left a zombie tunnel.
                    send(ConnectionProgress.Connecting(
                        ConnectionStatus.CONNECTING_DIRECT, 
                        "Purging stale tunnel routes...",
                        selectedNode
                    ))
                    wireGuardService.disconnect()
                    delay(4000) // Wait for Android OS to cleanup tun0

                    if (preference == TrafficModePreference.AUTO) {
                        selectedNode = shadowRouteBestNode(vpnRepository.getNodes()) ?: selectedNode
                    }
                    send(ConnectionProgress.Connecting(
                        ConnectionStatus.CONNECTING_DIRECT, 
                        "Optimizing resilient route...",
                        selectedNode
                    ))
                    delay(1000)
                } else {
                    client.setRetrySignal(false)
                    send(ConnectionProgress.Connecting(ConnectionStatus.CONNECTING_DIRECT, "Securing tunnel...", selectedNode))
                }

                // SOP 02 §3: Slow Handshake monitoring
                val isSuccessful = coroutineScope {
                    val slowHandshakeJob = launch {
                        delay(5000)
                        send(ConnectionProgress.Connecting(
                            ConnectionStatus.CONNECTING_DIRECT, 
                            "Finding most resilient route...",
                            selectedNode
                        ))
                    }

                    try {
                        val isHighRiskRegion = selectedNode.country.equals("MM", ignoreCase = true) || selectedNode.country.equals("CN", ignoreCase = true)
                        val mode = TrafficModeResolver.select(selectedNode, preference, attempt)

                        android.util.Log.i("SHADOWMESH_DEBUG", "🚀 Selected Mode: $mode (Attempt: $attempt, HighRisk: $isHighRiskRegion, RealityNode: ${TrafficModeResolver.isRealityNode(selectedNode)})")
                        vpnManager.setTrafficMode(mode)
                        client.setTrafficMode(mode)
                        vpnManager.initiateConnection(selectedNode, publicKey)

                        val modeStr = when(mode) {
                            TrafficMode.FRAGMENTED -> "fragmented"
                            TrafficMode.REALITY -> "reality"
                            TrafficMode.NORMAL -> "normal"
                            TrafficMode.WEB_SOCKET -> "websocket"
                            TrafficMode.SHADOWSOCKS -> "shadowsocks"
                            TrafficMode.HYSTERIA -> "hysteria"
                            TrafficMode.VMESS -> "vmess"
                        }

                        val deviceId = vpnRepository.getDeviceId()
                        
                        stage("config-fetch")
                        val config = withContext(Dispatchers.IO) {
                            try {
                                client.getConfig(selectedNode.id, deviceId, modeStr)
                            } catch (e: Exception) {
                                if (e is ShadowMeshException.Unauthorized && e.message?.contains("ExpiredSignature") == true) {
                                    android.util.Log.i("SHADOWMESH_DEBUG", "🔑 Token expired, attempting refresh...")
                                    try {
                                        val newToken = client.refreshToken()
                                        vpnRepository.updateAuthToken(newToken)
                                        // Retry config with new token
                                        client.getConfig(selectedNode.id, deviceId, modeStr)
                                    } catch (refreshError: Exception) {
                                        android.util.Log.e("SHADOWMESH_DEBUG", "❌ Token refresh failed", refreshError)
                                        throw e // Throw original unauthorized if refresh fails
                                    }
                                } else {
                                    if (attempt == 0) client.setRetrySignal(true)
                                    throw e
                                }
                            }
                        }
                        
                        val tunnelName = if (isCamouflage) "Secure Notes Sync" else "ShadowMesh"
                        val intent = Intent().apply {
                            component = android.content.ComponentName(appContext.packageName, "com.shadowmesh.app.MeshVpnService")
                            action = "com.shadowmesh.app.ACTION_CONNECT"
                            putExtra("private_key", privateKey)
                            putExtra("address", config.address)
                            putExtra("endpoint", config.endpoint)
                            putExtra("public_key", config.publicKey)
                            putExtra("dns", config.dns)
                            putExtra("mtu", config.mtu.toInt())
                            putExtra("traffic_mode", config.trafficMode)
                            
                            // v6.9.1: Correctly pass REALITY config to the Service
                            config.realityConfig?.let { rc ->
                                putExtra("reality_server_ip", rc.serverIp)
                                putExtra("reality_port", rc.port.toInt())
                                putExtra("reality_uuid", rc.uuid)
                                putExtra("reality_public_key", rc.publicKey)
                                putExtra("reality_short_id", rc.shortId)
                                putExtra("reality_sni_target", rc.sniTarget)
                                putExtra("reality_fingerprint", rc.fingerprint)
                            }
                        }
                        
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            appContext.startForegroundService(intent)
                        } else {
                            appContext.startService(intent)
                        }

                        // v5.8 Handshake Verification: In Myanmar, UDP handshakes often "succeed" 
                        // but traffic is immediately dropped. We wait for actual packets to flow.
                        // v6.0: Increased timeout to 25s for high-latency restricted networks in Myanmar/CN.
                        stage("vpn-service-started")

                        // Split tunnelling changes what "connected" means. In INCLUDE
                        // (per-app) mode the tunnel carries traffic ONLY for the
                        // selected apps. If those apps are idle — no page load, no
                        // background sync — no packet ever arrives, the probe
                        // expires, and the app reports a connection failure even
                        // though REALITY, VLESS and the WireGuard handshake all
                        // completed. That is why EXCLUDE worked and INCLUDE did
                        // not: under EXCLUDE every other app on the device is
                        // tunnelled, so traffic is always present.
                        //
                        // A completed WireGuard handshake is itself proof the
                        // tunnel is up end to end, so accept it as success in
                        // per-app mode, and keep the stricter "a live packet must
                        // arrive" check for full-tunnel connects where that
                        // signal is meaningful.
                        // Read the split-tunnel state from the SAME persisted store
                        // that MeshVpnService uses to build the rules. The core's
                        // in-memory copy (vpnManager.getSplitTunnelConfig) reverts
                        // to defaults on every process restart, so reading it here
                        // reported "disabled" while the service went on applying the
                        // real rules — the health gate and the tunnel disagreed.
                        val storage = SecureStorage.getInstance(appContext)
                        val stEnabled = storage.getBoolean(Config.KEY_ST_ENABLED, false)
                        val stMode = storage.get(Config.KEY_ST_MODE) ?: "exclude"
                        val stApps = storage.prefs
                            .getStringSet(Config.KEY_ST_APPS, emptySet())
                            .orEmpty()
                        val isPerAppRouting =
                            stEnabled && stMode.equals("include", ignoreCase = true) && stApps.isNotEmpty()
                        android.util.Log.i(
                            "SHADOWMESH_STAGE",
                            "probe-mode=${if (isPerAppRouting) "per-app(handshake-ok)" else "full-tunnel(packets-required)"} " +
                                "splitEnabled=$stEnabled mode=$stMode splitApps=${stApps.size}"
                        )

                        val probe = minOf(25000L, remainingMs().coerceAtLeast(1L))
                        val isTrafficFlowing = kotlinx.coroutines.withTimeoutOrNull(probe) {
                            while (isActive) {
                                val stats = wireGuardService.getStats()
                                if (stats.packetsReceived > 0uL) return@withTimeoutOrNull true
                                // Per-app mode: an idle tunnel legitimately carries
                                // no packets, so `lastHandshake` (written on the
                                // same receive path) can never help. Fall back to
                                // the engine's own "transport established" signal,
                                // which the service sets once the REALITY/VLESS
                                // session is up and the node's UDP relay is open.
                                if (isPerAppRouting && EngineStatus.transportEstablished) {
                                    return@withTimeoutOrNull true
                                }
                                delay(1500)
                            }
                            false
                        } ?: false

                        stage(
                            "traffic-probe-result flowing=$isTrafficFlowing " +
                                "engineEstablished=${EngineStatus.transportEstablished}"
                        )
                        if (!isTrafficFlowing) {
                            android.util.Log.e("SHADOWMESH_DEBUG", "Handshake succeeded but no traffic flowing on mode $modeStr. Escalating...")
                            // v7.6 High-Resiliency: Full reset and cool-down.
                            // We MUST ensure the tunnel is gone before the next loop iteration
                            // tries to fetch a config from the API.
                            wireGuardService.disconnect()
                            vpnManager.setTrafficMode(TrafficMode.NORMAL) 
                            delay(6000) // Myanmar cooldown requirement
                            throw Exception("Traffic flow timeout")
                        }

                        logger.logEvent(
                            eventType = SecurityEventType.LOGIN_ATTEMPT,
                            details = "Node: ${selectedNode.name} (${selectedNode.region}) | Mode: ${config.trafficMode}",
                            success = true,
                            apiClient = client
                        )

                        // Commit the session in the Rust state machine before
                        // announcing it. The `finally` cleanup below gates on
                        // getStatus() != CONNECTED, so without this the tunnel
                        // was torn down ~2s after every successful connect while
                        // the UI already showed "protected" — a zombie session
                        // with no interface behind it.
                        vpnManager.completeConnection()

                        send(ConnectionProgress.Connected(
                            node = selectedNode,
                            assignedIp = config.address,
                            isQuantum = config.trafficMode == "fragmented",
                            mode = config.trafficMode,
                        ))
                        slowHandshakeJob.cancel()
                        true
                    } catch (e: Exception) {
                        slowHandshakeJob.cancel()
                        if (attempt == 2) { // Last attempt
                            throw e
                        }
                        android.util.Log.w("SHADOWMESH_DEBUG", "Attempt $attempt failed, retrying...", e)
                        false
                    }
                }
                if (isSuccessful) return@channelFlow
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("SHADOWMESH_DEBUG", "CRITICAL: Connection failure", e)
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
        } finally {
            // v6.9 Safety: If we reach this point and we aren't connected, force a cleanup
            // of any "Ghost" VPN services or icons.
            if (this.isActive && vpnManager.getStatus() != ConnectionStatus.CONNECTED) {
                android.util.Log.i("SHADOWMESH_DEBUG", "Final cleanup: stopping service")
                wireGuardService.disconnect()
            }
        }
    }
}
