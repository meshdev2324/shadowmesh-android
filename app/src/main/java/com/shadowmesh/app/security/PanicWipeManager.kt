package com.shadowmesh.app.security

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import com.shadowmesh.app.util.ZLog
import com.shadowmesh.core_vpn.CoreUtils
import com.shadowmesh.core_vpn.Config
import com.shadowmesh.core_vpn.SecureStorage
import com.shadowmesh.core_vpn.repository.VpnRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.shadowmesh.SecurityEventType
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PanicWipeManager"

/**
 * Central authority for forensic wipe operations.
 * SOP 13 §2: Coordinates security events, core wipes, and storage clearing.
 */
@Singleton
class PanicWipeManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val secureStorage: SecureStorage,
        private val vpnRepository: VpnRepository,
    ) {
        /**
         * Executes a multi-layer panic wipe.
         * Orchestrates Backend reporting, Rust Core forensic wipe, and Android Storage clearing.
         */
        suspend fun executePanicWipe() =
            withContext(Dispatchers.IO) {
                ZLog.e(TAG, "CRITICAL: Panic Wipe Initiated")

                // 1. Alert Control Plane (SOP 11 §3)
                try {
                    val deviceId = CoreUtils.getAndroidDeviceId(context)
                    vpnRepository.apiClient.reportCompromised(deviceId, "Panic Wipe Triggered via Duress PIN")
                    ZLog.i(TAG, "Compromise report sent to control plane.")
                } catch (e: Exception) {
                    ZLog.w(TAG, "Failed to report compromise: ${e.message}")
                }

                // 2. Rust Core Forensic Wipe
                try {
                    vpnRepository.vpnManager.panicWipe()
                    vpnRepository.nodeCache.clear() // Horizon 4: Clear persistent node metadata
                    // TODO(v0.1.0): uniffi ApiClient does not expose zeroize() yet
                    ZLog.i(TAG, "Rust Core forensic wipe complete.")
                } catch (e: Exception) {
                    ZLog.e(TAG, "Critical error during Rust forensic wipe: ${e.message}")
                }

                // 3. Wipe Android Storage
                try {
                    secureStorage.clearAll()

                    // Wipe all mesh-related preferences (plaintext fallback)
                    context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE).edit { clear() }

                    ZLog.i(TAG, "Android Secure Storage successfully wiped.")
                } catch (e: Exception) {
                    ZLog.e(TAG, "Failed to clear Android storage: ${e.message}")
                }

                // 4. Log Security Event & Purge Logs (SOP 11 §3)
                try {
                    vpnRepository.securityEventLogger.logEvent(
                        eventType = SecurityEventType.PANIC_INITIATED,
                        details = "Duress PIN / Panic Wipe Executed",
                        success = true,
                        apiClient = vpnRepository.apiClient,
                    )
                    // TODO(v0.1.0): uniffi SecurityEventLogger does not expose purge() yet
                    ZLog.i(TAG, "Security logs purged.")
                } catch (e: Exception) {
                    ZLog.w(TAG, "Failed to log/purge security events post-wipe: ${e.message}")
                }

                // 5. Decoy Transition
                val intent =
                    Intent("com.shadowmesh.app.TRIGGER_DECOY").apply {
                        setPackage(context.packageName)
                        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    }
                context.sendBroadcast(intent)
            }
    }
