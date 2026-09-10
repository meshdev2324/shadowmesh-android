package com.shadowmesh.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.sentry.android.core.SentryAndroid
import kotlinx.coroutines.*
import uniffi.shadowmesh.registerSocketProtector
import uniffi.shadowmesh.SocketProtector

@HiltAndroidApp
class ShadowMeshApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // v6.9.4: Setup internal logging for Rust core
        try {
            uniffi.shadowmesh.setupLogging()
        } catch (e: Exception) {
            android.util.Log.e("ShadowMeshApp", "Failed to setup Rust logging", e)
        }

        // Fix for WireGuard GoBackend UAPI path permission issues
        // Must be set as early as possible before any backend instantiation.
        try {
            // v5.9 Stability: Use filesDir/wg for sockets as cacheDir can be purged 
            // or restricted by some Android flavors in Myanmar.
            val socketDir = java.io.File(filesDir, "wg")
            if (socketDir.exists()) {
                socketDir.listFiles()?.forEach { it.delete() }
            } else {
                socketDir.mkdirs()
            }
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                android.system.Os.setenv("WG_UAPI_DIR", socketDir.absolutePath, true)
            }
            System.setProperty("wireguard.uapi_dir", socketDir.absolutePath)
            android.util.Log.i("ShadowMeshApp", "WireGuard UAPI directory finalized at: ${socketDir.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.w("ShadowMeshApp", "Failed to set WireGuard environment", e)
        }

        // v11.1 Optimized Bootloader: Parallelize initialization
        val initScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

        // Load native library globally (Blocking call to ensure it's ready for UniFFI)
        try {
            System.loadLibrary("shadowmesh_core")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("ShadowMeshApp", "Failed to load native library", e)
        }

        initScope.launch {
            // Pre-warm Secure Storage (Keystore initialization)
            com.shadowmesh.core_vpn.SecureStorage.getInstance(this@ShadowMeshApplication).preWarm()
        }

        initScope.launch {
            // Initialize Sentry/GlitchTip
            SentryAndroid.init(this@ShadowMeshApplication) { options ->
                if (options.dsn.isNullOrEmpty()) {
                    options.isEnabled = false
                }
                options.isEnableAutoSessionTracking = true
                options.isEnableAppLifecycleBreadcrumbs = true
                options.isSendDefaultPii = false
            }
        }
    }
}
