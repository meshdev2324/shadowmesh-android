package com.shadowmesh.core_vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import dagger.hilt.android.qualifiers.ApplicationContext
import uniffi.shadowmesh.*
import java.io.ByteArrayInputStream
import com.wireguard.crypto.KeyPair
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WireGuardService @Inject constructor(
    @ApplicationContext private val appContext: Context,
    val vpnManager: VpnManager,
    private val backend: Backend,
    private val connectivityManager: ConnectivityManager
) {
    private var tunnel: WgTunnel? = null
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
        val backend = this.backend
        val tunnel = this.tunnel ?: return
        
        try {
            vpnManager.pause(minutes.toUInt())
            backend.setState(tunnel, Tunnel.State.DOWN, null)
            this.tunnel = null
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
        val keyPair = KeyPair()
        return Pair(keyPair.privateKey.toBase64(), keyPair.publicKey.toBase64())
    }

    class WgTunnel(private val tunnelName: String) : Tunnel {
        override fun getName() = tunnelName
        override fun onStateChange(newState: Tunnel.State) {}
    }

    suspend fun connect(
        node: VpnNode, 
        clientConfig: VpnConfig, 
        tunnelName: String = "ShadowMesh",
        dnsLeakProtection: Boolean = true
    ) {
        val backend = this.backend
        isKillSwitchActive = false 
        
        try {
            vpnManager.initiateConnection(node, clientConfig.publicKey)
            
            tunnel = WgTunnel(tunnelName)
            currentTunnelName = "$tunnelName-${node.region}"

            val splitTunnelConfig = vpnManager.getSplitTunnelConfig()
            var appRouting = ""
            if (splitTunnelConfig.enabled && splitTunnelConfig.appList.isNotEmpty()) {
                val appListStr = splitTunnelConfig.appList.joinToString(",")
                appRouting = if (splitTunnelConfig.mode == SplitTunnelMode.INCLUDE) {
                    "IncludedApplications = $appListStr"
                } else {
                    "ExcludedApplications = $appListStr"
                }
            }

            val allowedIps = if (dnsLeakProtection) "0.0.0.0/0, ::/0" else "0.0.0.0/0"

            val configString = """
            [Interface]
            PrivateKey = ${clientConfig.privateKey}
            Address = ${clientConfig.address}
            DNS = ${clientConfig.dns}
            MTU = ${clientConfig.mtu}
            $appRouting

            [Peer]
            PublicKey = ${node.publicKey}
            AllowedIPs = $allowedIps
            Endpoint = ${node.endpoint}
            PersistentKeepalive = 25
            """.trimIndent()

            val config = Config.parse(ByteArrayInputStream(configString.toByteArray(Charsets.UTF_8)))
            tunnel?.let { backend.setState(it, Tunnel.State.UP, config) }
            
            // Note: MeshVpnService must still be in :app or we need to move it to a common module.
            // For now we'll use a broadcast or an interface to start it.
            // SOP 09 says "Module Separation: Encapsulate all Rust calls in a dedicated core-vpn module."
            // It doesn't say move the Android Service.
            // But WireGuardService needs to start the MeshVpnService.
            
            val intent = Intent().apply {
                component = android.content.ComponentName(appContext.packageName, "com.shadowmesh.app.MeshVpnService")
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
        val backend = this.backend
        val tunnel = this.tunnel
        
        if (tunnel != null) {
            try {
                if (isKillSwitchActive) {
                    activateKillSwitch()
                } else {
                    backend.setState(tunnel, Tunnel.State.DOWN, null)
                    this.tunnel = null
                    val intent = Intent().apply {
                        component = android.content.ComponentName(appContext.packageName, "com.shadowmesh.app.MeshVpnService")
                    }
                    appContext.stopService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        vpnManager.disconnect()
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
        val backend = this.backend
        
        if (tunnel == null) {
            tunnel = WgTunnel("ShadowMesh-KillSwitch")
        }

        try {
            val blockConfig = """
                [Interface]
                PrivateKey = ${KeyPair().privateKey.toBase64()}
                Address = 192.168.0.2/32, fd00::2/128
                DNS = 127.0.0.1, ::1
                
                [Peer]
                PublicKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
                AllowedIPs = 0.0.0.0/0, ::/0
            """.trimIndent()
            
            val config = Config.parse(ByteArrayInputStream(blockConfig.toByteArray(Charsets.UTF_8)))
            tunnel?.let { backend.setState(it, Tunnel.State.UP, config) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    suspend fun deactivateKillSwitch() {
        isKillSwitchActive = false
        val currentTunnel = tunnel
        if (currentTunnelName == null && currentTunnel != null) {
            backend.setState(currentTunnel, Tunnel.State.DOWN, null)
            tunnel = null
        }
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
                        // We could trigger a connectivity check here to verify the tunnel
                    }
                }
            }
        })
    }

    private fun refreshTunnel() {
        val tunnel = this.tunnel ?: return
        val tunnelName = currentTunnelName ?: return
        
        Log.i("WireGuardService", "Refreshing tunnel $tunnelName after network change...")
        try {
            // Proactively check state. GoBackend handles roaming, but we want to ensure 
            // the service layer knows we're still alive.
            val state = backend.getState(tunnel)
            if (state == Tunnel.State.UP) {
                Log.d("WireGuardService", "Tunnel is still UP after handover. Roaming handled by backend.")
            } else {
                Log.w("WireGuardService", "Tunnel state is $state. Attempting recovery...")
                // If it's not UP, we might need to restart it, but usually State.UP persists
            }
        } catch (e: Exception) {
            Log.e("WireGuardService", "Failed to refresh tunnel after handover", e)
        }
    }
}
