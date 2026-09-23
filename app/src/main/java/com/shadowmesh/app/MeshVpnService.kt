package com.shadowmesh.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.shadowmesh.core_vpn.Config
import com.shadowmesh.core_vpn.SecureStorage
import com.wireguard.android.backend.GoBackend
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MeshVpnService : GoBackend.VpnService() {
    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "shadowmesh_vpn_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
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
            // Handle cases where startForeground might fail (e.g. background start restrictions)
            android.util.Log.e("MeshVpnService", "Failed to start foreground service", e)
        }

        return START_STICKY
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
