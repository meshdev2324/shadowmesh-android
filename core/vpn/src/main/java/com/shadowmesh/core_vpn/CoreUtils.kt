package com.shadowmesh.core_vpn

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.tracing.trace
import java.security.MessageDigest

object CoreUtils {
    fun getAndroidDeviceId(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "anonymous"
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(androidId.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun getHardwareFingerprint(context: Context): String {
        val buildInfo = "${Build.BOARD}${Build.BRAND}${Build.DEVICE}${Build.DISPLAY}${Build.FINGERPRINT}${Build.HOST}${Build.ID}${Build.MANUFACTURER}${Build.MODEL}${Build.PRODUCT}${Build.TAGS}${Build.TYPE}${Build.USER}"
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(buildInfo.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * Generates a deep fingerprint for heartbeat verification (SOP 11).
     */
    fun getDeepFingerprint(context: Context): Map<String, String> {
        val sensors = (context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager)
            .getSensorList(android.hardware.Sensor.TYPE_ALL)
            .take(5)
            .map { it.name }
            .joinToString(",")
            
        return mapOf(
            "model" to Build.MODEL,
            "brand" to Build.BRAND,
            "hardware" to Build.HARDWARE,
            "sensors_hint" to sensors,
            "bootloader" to Build.BOOTLOADER
        )
    }

    /**
     * Optimized byte formatting for µs/ns performance (SOP 02).
     * Avoids String.format and locale overhead.
     */
    fun formatBytes(bytes: ULong): String = trace("formatBytes") {
        return@trace when {
            bytes >= 1073741824uL -> { // GB
                val gb = bytes.toDouble() / 1073741824.0
                "${fastRound(gb, 2)} GB"
            }
            bytes >= 1048576uL -> { // MB
                val mb = bytes.toDouble() / 1048576.0
                "${fastRound(mb, 1)} MB"
            }
            bytes >= 1024uL -> "${bytes / 1024uL} KB"
            else -> "${bytes} B"
        }
    }

    private fun fastRound(value: Double, decimals: Int): String {
        val multiplier = if (decimals == 1) 10 else 100
        val rounded = (value * multiplier).toLong().toDouble() / multiplier
        val s = rounded.toString()
        return if (decimals == 2 && s.substringAfter('.').length == 1) "${s}0" else s
    }

    fun normalizeActivationCode(code: String): String? {
        val clean = code.uppercase().trim().replace(Regex("[^A-Z0-9]"), "")
        return if (clean.length >= 8) clean else null
    }

    /**
     * Checks if the app is currently in the foreground.
     */
    fun isAppInForeground(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val appProcesses = activityManager.runningAppProcesses ?: return false
        val packageName = context.packageName
        for (appProcess in appProcesses) {
            if (appProcess.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                appProcess.processName == packageName) {
                return true
            }
        }
        return false
    }
}
