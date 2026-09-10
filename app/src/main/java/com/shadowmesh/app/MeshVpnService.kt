package com.shadowmesh.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import android.net.VpnService
import com.shadowmesh.core_vpn.Config
import com.shadowmesh.core_vpn.SecureStorage
import dagger.hilt.android.AndroidEntryPoint
import uniffi.shadowmesh.stopVpnEngine
import uniffi.shadowmesh.VpnConfig
import uniffi.shadowmesh.RealityConfig
import uniffi.shadowmesh.registerSocketProtector
import uniffi.shadowmesh.SocketProtector
import android.os.ParcelFileDescriptor

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
            "com.shadowmesh.app.ACTION_DISCONNECT" -> handleDisconnect()
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

        return START_STICKY
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
            } else null
        )

        // v8.0: Unified Rust engine — all traffic modes (normal / fragmented /
        // reality / websocket) are handled natively by shadowmesh-core.
        val builder = this.Builder()
            .addAddress(config.address.split("/")[0], config.address.split("/")[1].toInt())
            .addDnsServer(config.dns)
            .addRoute("0.0.0.0", 0)
            .setMtu(config.mtu.toInt())
            .setBlocking(false) // v6.9 Non-blocking I/O hint
            .setSession("ShadowMesh")

        val pfd = builder.establish()
        if (pfd != null) {
            val fd = pfd.detachFd()
            Thread {
                try {
                    android.util.Log.i("SHADOWMESH_DEBUG", "🚀 Starting Native Rust engine (mode=${config.trafficMode})")
                    uniffi.shadowmesh.startVpnEngine(fd, config)
                } catch (e: Throwable) {
                    android.util.Log.e("SHADOWMESH_DEBUG", "FATAL: VPN engine crashed", e)
                    handleDisconnect()
                }
            }.start()
        }
    }

    private fun handleDisconnect() {
        android.util.Log.i("SHADOWMESH_DEBUG", "🛑 handleDisconnect: Tearing down VPN...")
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
