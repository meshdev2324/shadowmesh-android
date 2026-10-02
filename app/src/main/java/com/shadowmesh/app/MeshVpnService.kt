package com.shadowmesh.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import android.content.pm.PackageManager
import android.net.VpnService
import android.system.OsConstants
import com.shadowmesh.core_vpn.Config
import com.shadowmesh.core_vpn.EngineStatus
import com.shadowmesh.core_vpn.SecureStorage
import dagger.hilt.android.AndroidEntryPoint
import uniffi.shadowmesh.stopVpnEngine
import uniffi.shadowmesh.VpnConfig
import uniffi.shadowmesh.RealityConfig
import uniffi.shadowmesh.registerSocketProtector
import uniffi.shadowmesh.SocketProtector
import android.os.ParcelFileDescriptor

internal data class TunnelAddress(
    val address: String,
    val prefixLength: Int
)

internal fun parseTunnelAddress(raw: String): TunnelAddress? {
    val parts = raw.trim().split('/', limit = 2)
    val address = parts.getOrNull(0)?.trim().orEmpty()
    val prefixLength = parts.getOrNull(1)?.toIntOrNull() ?: 32
    if (address.isBlank() || prefixLength !in 1..32) return null
    return TunnelAddress(address, prefixLength)
}

/** What happens to IPv6 while the tunnel is up (leak-verification posture). */
internal enum class Ipv6Posture {
    /** The engine carries IPv6: route ::/0 into the tunnel. */
    ROUTE_INTO_TUNNEL,

    /**
     * The engine cannot carry IPv6, so an unrouted IPv6 stack would bypass the
     * tunnel wholesale — including DNS to the link's IPv6 resolvers. Blocking
     * the family is the only leak-proof posture.
     */
    BLOCK,

    /** Leak protection is off: the user has opted out of IPv6 coverage. */
    PASS_THROUGH_UNDERLYING,
}

/** Whether the Rust engine currently forwards IPv6 packets from the tun. */
internal const val ENGINE_SUPPORTS_IPV6 = false

/**
 * Decides the IPv6 posture from what the engine can carry and what the user
 * chose. Pure so the invariant that matters — a dual-stack network can never
 * send IPv6 around the tunnel while protection is on (the default) — is unit-
 * testable without the framework.
 */
internal fun ipv6Posture(
    engineSupportsIpv6: Boolean,
    leakProtectionEnabled: Boolean,
): Ipv6Posture = when {
    engineSupportsIpv6 -> Ipv6Posture.ROUTE_INTO_TUNNEL
    leakProtectionEnabled -> Ipv6Posture.BLOCK
    else -> Ipv6Posture.PASS_THROUGH_UNDERLYING
}

@AndroidEntryPoint
class MeshVpnService : VpnService() {
    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "shadowmesh_vpn_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // v6.9.4: Register socket protector to prevent VPN infinite loops
        registerSocketProtector(object : SocketProtector {
            override fun protect(fd: Int): Boolean {
                val success = this@MeshVpnService.protect(fd)
                if (success) {
                    android.util.Log.d("SHADOWMESH_DEBUG", "🛡️ Socket $fd protected from VPN")
                } else {
                    android.util.Log.e("SHADOWMESH_DEBUG", "⚠️ Failed to protect socket $fd")
                }
                return success
            }
        })
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            "com.shadowmesh.app.ACTION_CONNECT" -> handleConnect(intent)
            "com.shadowmesh.app.ACTION_DISCONNECT" -> {
                // Must not re-arm the foreground state after teardown: doing so leaves a
                // zombie VpnService holding a dead tun0 that blackholes all device traffic.
                handleDisconnect()
                return START_NOT_STICKY
            }
        }

        val secureStorage = SecureStorage.getInstance(this)
        val isCamouflage = secureStorage.getBoolean(Config.KEY_CAMOUFLAGE_ENABLED, false)

        val notification = createNotification(isCamouflage)
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            android.util.Log.e("MeshVpnService", "Failed to start foreground service", e)
        }

        return START_NOT_STICKY
    }

    override fun onRevoke() {
        // Android calls this both when a competing VPN app claims the device's
        // single VPN slot and when the user removes ShadowMesh from the allowed
        // VPN list. Those are very different events, and silently treating both
        // as a routine disconnect is what made users report "the VPN stops by
        // itself". VpnService.prepare() returns non-null only when consent has
        // been withdrawn, which distinguishes the two.
        val stillHasConsent = android.net.VpnService.prepare(this) == null
        val reason = com.shadowmesh.app.vpn.RevokeReasonPolicy.forRevoke(stillHasConsent)
        val message = com.shadowmesh.app.vpn.RevokeReasonPolicy.message(reason)
        Log.w("ShadowMeshVpnService", "onRevoke: $reason${if (message.isNotBlank()) " — $message" else ""}")

        // Persisted, not just logged: the UI may not be running when the slot is
        // taken, and a reason that only reaches logcat is a reason the user
        // never sees. The ViewModel consumes and clears it on next display.
        runCatching {
            com.shadowmesh.core_vpn.SecureStorage.getInstance(applicationContext)
                .set(Config.KEY_LAST_DISCONNECT_REASON, reason.name)
        }.onFailure { Log.e("ShadowMeshVpnService", "Could not persist disconnect reason", it) }

        handleDisconnect()
        super.onRevoke()
    }

    override fun onDestroy() {
        Log.i("SHADOWMESH_DEBUG", "MeshVpnService destroyed, releasing tunnel resources")
        handleDisconnect()
        super.onDestroy()
    }

    private fun handleConnect(intent: Intent) {
        val config = VpnConfig(
            privateKey = intent.getStringExtra("private_key"),
            publicKey = intent.getStringExtra("public_key") ?: "",
            address = intent.getStringExtra("address") ?: "",
            endpoint = intent.getStringExtra("endpoint") ?: "",
            dns = intent.getStringExtra("dns") ?: "",
            mtu = intent.getIntExtra("mtu", 1420).toUInt(),
            trafficMode = intent.getStringExtra("traffic_mode") ?: "normal",
            realityConfig = if (intent.hasExtra("reality_server_ip")) {
                RealityConfig(
                    serverIp = intent.getStringExtra("reality_server_ip") ?: "",
                    port = intent.getIntExtra("reality_port", 443).toUInt(),
                    uuid = intent.getStringExtra("reality_uuid") ?: "",
                    publicKey = intent.getStringExtra("reality_public_key") ?: "",
                    shortId = intent.getStringExtra("reality_short_id") ?: "",
                    sniTarget = intent.getStringExtra("reality_sni_target") ?: "",
                    fingerprint = intent.getStringExtra("reality_fingerprint")
                )
            } else null,
            shadowsocksConfig = null,
            hysteriaConfig = null,
            vmessConfig = null
        )

        // v8.0: Unified Rust engine — all traffic modes (normal / fragmented /
        // reality / websocket) are handled natively by shadowmesh-core.
        val tunnelAddress = parseTunnelAddress(config.address)
        if (tunnelAddress == null) {
            Log.e("MeshVpnService", "Invalid tunnel address")
            handleDisconnect()
            return
        }

        val builder = this.Builder()
            .addAddress(tunnelAddress.address, tunnelAddress.prefixLength)
            .addDnsServer(config.dns)
            .addRoute("0.0.0.0", 0)
            .setMtu(config.mtu.toInt())
            .setBlocking(false) // v6.9 Non-blocking I/O hint
            .setSession("ShadowMesh")

        // IPv6 leak posture (command-center leak-verification mandate). The
        // builder routed only 0.0.0.0/0, so on any dual-stack network every
        // IPv6 packet — DNS included — rode the underlying network around the
        // tunnel. The `dnsLeakProtection` setting existed but was consumed by
        // nothing. Today the engine forwards IPv4 only, so "protection on"
        // (the default) must BLOCK the family outright; when the core gains
        // IPv6, ROUTE_INTO_TUNNEL takes over via `addRoute("::", 0)`.
        val leakProtectionOn = SecureStorage.getInstance(this)
            .getBoolean(Config.KEY_DNS_LEAK_PROTECTION, true)
        when (ipv6Posture(ENGINE_SUPPORTS_IPV6, leakProtectionOn)) {
            // allowFamily(AF_INET) denies every other family: IPv6 traffic
            // cannot route around the tunnel while the engine is IPv4-only.
            Ipv6Posture.BLOCK -> builder.allowFamily(OsConstants.AF_INET)
            Ipv6Posture.ROUTE_INTO_TUNNEL -> builder.addRoute("::", 0)
            Ipv6Posture.PASS_THROUGH_UNDERLYING -> { /* user opted out */ }
        }

        // Split tunnelling. Until now the picker in Settings persisted a
        // SplitTunnelConfig that nothing ever read, so every selection silently
        // did nothing: per-app routing is a property of the VpnService.Builder,
        // and the builder was only ever given a full-tunnel 0.0.0.0/0 route.
        // Applied here, before establish(), which is the only point at which
        // Android accepts per-app rules.
        applySplitTunnel(builder, config)

        val pfd = builder.establish()
        if (pfd != null) {
            val fd = pfd.detachFd()
            Thread {
                try {
                    android.util.Log.i("SHADOWMESH_DEBUG", "🚀 Starting Native Rust engine (mode=${config.trafficMode})")
                    uniffi.shadowmesh.startVpnEngine(fd, config)
                    // startVpnEngine returns only after the REALITY/VLESS session
                    // is established and the node's UDP relay is open. Record that
                    // so per-app (split-tunnelling INCLUDE) connects can be judged
                    // on a truthful signal instead of waiting for tunnelled
                    // packets that an idle per-app tunnel will never produce.
                    EngineStatus.markEstablished("engine-started mode=${config.trafficMode}")
                } catch (e: Throwable) {
                    android.util.Log.e("SHADOWMESH_DEBUG", "FATAL: VPN engine crashed", e)
                    handleDisconnect()
                }
            }.start()
        }
    }

    /**
     * Applies the persisted per-app routing rules to [builder].
     *
     * EXCLUDE — the listed apps bypass the tunnel and keep using the underlying
     * network. INCLUDE — only the listed apps (plus this VPN app, which Android
     * always includes) are routed through it.
     *
     * Failure handling is deliberately per-package: Android throws
     * [PackageManager.NameNotFoundException] for a package that was uninstalled
     * since the list was saved, and `addDisallowedApplication` throws
     * [IllegalArgumentException] for this app's own package. Either must not
     * take down the tunnel — a stale entry costs that one app its rule, nothing
     * more.
     */
    private fun applySplitTunnel(builder: Builder, config: VpnConfig) {
        val storage = SecureStorage.getInstance(this)
        if (!storage.getBoolean(Config.KEY_ST_ENABLED, false)) return

        val mode = storage.get(Config.KEY_ST_MODE) ?: "exclude"
        val packages = storage.prefs
            .getStringSet(Config.KEY_ST_APPS, emptySet())
            .orEmpty()
            .filter { it.isNotBlank() }
        // An empty list means "no per-app restriction" in both modes: applying
        // INCLUDE with no apps would route nothing but the VPN app itself and
        // silently break all connectivity on the device.
        if (packages.isEmpty()) return

        var applied = 0
        var skipped = 0
        for (pkg in packages) {
            if (pkg == packageName) {
                // Routing the VPN provider through itself is invalid.
                skipped++
                continue
            }
            try {
                if (mode.equals("include", ignoreCase = true)) {
                    builder.addAllowedApplication(pkg)
                } else {
                    builder.addDisallowedApplication(pkg)
                }
                applied++
            } catch (e: PackageManager.NameNotFoundException) {
                skipped++
            } catch (e: IllegalArgumentException) {
                skipped++
            }
        }
        Log.i("SHADOWMESH_DEBUG", "Split tunnel: mode=$mode applied=$applied skipped=$skipped")
    }

    private fun handleDisconnect() {
        android.util.Log.i("SHADOWMESH_DEBUG", "🛑 handleDisconnect: Tearing down VPN...")
        EngineStatus.markStopped()
        try {
            stopVpnEngine()
        } catch (e: Throwable) {
            android.util.Log.e("SHADOWMESH_DEBUG", "Error stopping Rust engine", e)
        }

        // v7.3: Immediate notification cleanup to signal OS
        stopForeground(STOP_FOREGROUND_REMOVE)

        // Force-close the service to ensure tun0 is destroyed
        stopSelf()
    }

    private fun createNotification(isCamouflage: Boolean): Notification {
        val title = if (isCamouflage) "Notes Cloud Sync" else "ShadowMesh Active"
        val content = if (isCamouflage) "Personal diary is synchronizing..." else "Secure mesh tunnel is active."
        val icon = if (isCamouflage) R.drawable.ic_notes_monochrome else R.drawable.ic_launcher_foreground

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(icon)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "ShadowMesh VPN Service"
            val descriptionText = "Ensures secure mesh tunnel remains active in the background."
            val importance = NotificationManager.IMPORTANCE_MIN
            val channel =
                NotificationChannel(CHANNEL_ID, name, importance).apply {
                    description = descriptionText
                }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }
}
