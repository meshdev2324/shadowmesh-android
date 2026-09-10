package com.shadowmesh.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.shadowmesh.core_vpn.Config
import com.shadowmesh.core_vpn.SecureStorage
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShadowMeshNotificationManager
    @Inject
    constructor(
        private val context: Context,
    ) {
        companion object {
            private const val CHANNEL_ID = "shadowmesh_vpn_channel"
        }

        init {
            createNotificationChannel()
        }

        private fun createNotificationChannel() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val name = "ShadowMesh VPN Status"
                val descriptionText = "Shows active VPN connection status"
                val importance = NotificationManager.IMPORTANCE_LOW
                val channel =
                    NotificationChannel(CHANNEL_ID, name, importance).apply {
                        description = descriptionText
                    }
                val notificationManager: NotificationManager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(channel)
            }
        }

        fun getVpnNotification(
            isConnected: Boolean,
            serverName: String,
            latency: Int? = null,
        ): Notification {
            val isCamouflage = SecureStorage.getInstance(context).getBoolean(Config.KEY_CAMOUFLAGE_ENABLED, false)

            val title = if (isCamouflage) "Notes" else "ShadowMesh"
            val contentText =
                if (isCamouflage) {
                    if (isConnected) "Notes sync active" else "Notes sync idle"
                } else {
                    if (isConnected) {
                        if (latency != null) "Connected to $serverName • ${latency}ms" else "Connected to $serverName"
                    } else {
                        "Disconnected"
                    }
                }

            // Use a generic icon for camouflage, or the app icon for normal mode
            val iconRes = if (isCamouflage) android.R.drawable.ic_menu_edit else R.drawable.ic_launcher_foreground

            return NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(contentText)
                .setSmallIcon(iconRes)
                .setOngoing(isConnected)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }
    }
