package com.shadowmesh.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.sentry.android.core.SentryAndroid
import kotlinx.coroutines.*

@HiltAndroidApp
class ShadowMeshApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // v11.1 Optimized Bootloader: Parallelize initialization
        val initScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

        initScope.launch {
            // Pre-warm Secure Storage (Keystore initialization)
            com.shadowmesh.core_vpn.SecureStorage.getInstance(this@ShadowMeshApplication).preWarm()
        }

        initScope.launch {
            // Load native library globally
            try {
                System.loadLibrary("uniffi_shadowmesh")
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e("ShadowMeshApp", "Failed to load uniffi_shadowmesh", e)
            }
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
