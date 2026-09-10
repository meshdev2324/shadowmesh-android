package com.shadowmesh.core_vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import uniffi.shadowmesh.*
import java.io.ByteArrayInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WireGuardService @Inject constructor(
    @ApplicationContext private val appContext: Context,
    val vpnManager: VpnManager,
    private val connectivityManager: ConnectivityManager
) {
    private var currentTunnelName: String? = null
    private var connectedSince: Long = 0L
    private var totalBytesReceived: ULong = 0uL
    private var totalBytesSent: ULong = 0uL
    private var lastHandshake: Long = 0L
    private var isKillSwitchActive: Boolean = false
    private var lastNetwork: Network? = null

    init {
        setupNetworkCallback()
    }

    suspend fun pause(minutes: Int) {
        try {
            vpnManager.pause(minutes.toUInt())
            disconnect()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun resume() {
        vpnManager.resume()
    }

    suspend fun detectNetwork(apiClient: ApiClient, runSpeedTest: Boolean): NetworkReport? {
        return try {
            val detector = createNetworkDetector(apiClient, vpnManager)
            detector.detect(runSpeedTest)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun generateKeyPair(): Pair<String, String> {
        val keys = generateWireguardKeys()
        return Pair(keys[0], keys[1])
    }

    /**
     * Generates deterministic WireGuard keys from a seed.
     * SOP 04 §3: Maintains peer consistency for wg0 provisioning.
     */
    fun generateDeviceKeyPair(seed: String): Pair<String, String> {
        val keys = generateDeviceWireguardKeys(seed)
        return Pair(keys[0], keys[1])
    }

    suspend fun connect(
        node: VpnNode,
        clientConfig: VpnConfig,
        privateKey: String,
        tunnelName: String = "ShadowMesh",
        dnsLeakProtection: Boolean = true
    ) {
        try {
            vpnManager.initiateConnection(node, clientConfig.publicKey)
            currentTunnelName = "$tunnelName-${node.region}"

            val intent = Intent().apply {
                component = android.content.ComponentName(appContext.packageName, "com.shadowmesh.app.MeshVpnService")
                action = "com.shadowmesh.app.ACTION_CONNECT"
                putExtra("private_key", privateKey)
                putExtra("address", clientConfig.address)
                putExtra("endpoint", clientConfig.endpoint)
                putExtra("public_key", clientConfig.publicKey)
                putExtra("dns", clientConfig.dns)
                putExtra("mtu", clientConfig.mtu.toInt())
                putExtra("traffic_mode", clientConfig.trafficMode)
                
                clientConfig.realityConfig?.let { reality ->
                    putExtra("reality_server_ip", reality.serverIp)
                    putExtra("reality_port", reality.port.toInt())
                    putExtra("reality_uuid", reality.uuid)
                    putExtra("reality_public_key", reality.publicKey)
                    putExtra("reality_short_id", reality.shortId)
                    putExtra("reality_sni_target", reality.sniTarget)
                    putExtra("reality_fingerprint", reality.fingerprint)
                }
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }

            connectedSince = System.currentTimeMillis() / 1000L
            vpnManager.completeConnection()
        } catch (e: Exception) {
            e.printStackTrace()
            vpnManager.disconnect()
            throw e
        }
    }

    suspend fun disconnect() {
        Log.i("WireGuardService", "Determining tunnel teardown...")
        try {
            val intent = Intent().apply {
                component = android.content.ComponentName(appContext.packageName, "com.shadowmesh.app.MeshVpnService")
                action = "com.shadowmesh.app.ACTION_DISCONNECT"
            }
            appContext.startService(intent)
            
            // v7.5 Determinism: Explicitly reset core state before continuing
            vpnManager.disconnect()
            
            // Wait for service cleanup
            delay(1000)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        currentTunnelName = null
        connectedSince = 0L
    }

    fun getStats(): ConnectionStats {
        val stats = vpnManager.getStats()
        totalBytesReceived = stats.bytesReceived
        totalBytesSent = stats.bytesSent
        lastHandshake = stats.lastHandshake

        return ConnectionStats(
            totalBytesReceived, totalBytesSent,
            totalBytesReceived / 1500uL, totalBytesSent / 1500uL,
            lastHandshake, connectedSince
        )
    }

    suspend fun activateKillSwitch() {
        isKillSwitchActive = true
        // v6.2: Kill Switch logic moved to Rust core / eBPF
        vpnManager.setKillSwitchEnabled(true)
    }

    suspend fun deactivateKillSwitch() {
        isKillSwitchActive = false
        vpnManager.setKillSwitchEnabled(false)
    }

    private fun setupNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i("WireGuardService", "Network Available: ${network}")
                if (lastNetwork != null && lastNetwork != network) {
                    Log.i("WireGuardService", "Network Handover detected: From ${lastNetwork} to ${network}")
                    refreshTunnel()
                }
                lastNetwork = network
            }

            override fun onLost(network: Network) {
                Log.w("WireGuardService", "Network Lost: ${network}")
                if (network == lastNetwork) {
                    lastNetwork = null
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (network == lastNetwork) {
                    val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val isValidated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    } else true

                    if (hasInternet && isValidated) {
                        Log.d("WireGuardService", "Network ${network} is now validated and has internet.")
                    }
                }
            }
        })
    }

    private fun refreshTunnel() {
        val tunnelName = currentTunnelName ?: return
        Log.i("WireGuardService", "Refreshing tunnel $tunnelName after network change...")
        // Roaming is handled internally by integrated boringtun
    }
}
