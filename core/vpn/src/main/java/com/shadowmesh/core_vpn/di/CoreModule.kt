package com.shadowmesh.core_vpn.di

import android.content.Context
import android.os.Build
import android.util.Log
import com.shadowmesh.core_vpn.CoreUtils
import com.shadowmesh.core_vpn.NetworkClient
import com.shadowmesh.core_vpn.NetworkClientImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import uniffi.shadowmesh.*
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CoreModule {

    @Binds
    @Singleton
    abstract fun bindNetworkClient(impl: NetworkClientImpl): NetworkClient

    @Binds
    @Singleton
    abstract fun bindCoreServiceProvider(impl: com.shadowmesh.core_vpn.DefaultCoreServiceProvider): com.shadowmesh.core_vpn.CoreServiceProvider

    @Binds
    @Singleton
    abstract fun bindDeviceProvider(impl: com.shadowmesh.core_vpn.AndroidDeviceProvider): com.shadowmesh.core_vpn.DeviceProvider

    companion object {
        private const val DEFAULT_API_URL = "https://api.shadowmesh.org"

        @Provides
        @Singleton
        fun provideVpnManager(): VpnManager {
            return createVpnManager(getDefaultUserSettings())
        }

        @Provides
        @Singleton
        fun provideApiClient(): ApiClient {
            return createApiClient(DEFAULT_API_URL)
        }

        @Provides
        @Singleton
        fun provideKillSwitchManager(): KillSwitchManagerInterface {
            return createKillSwitchManager()
        }

        @Provides
        @Singleton
        fun provideNodeCache(): NodeCache {
            return createNodeCache(100u, 86400u)
        }

        @Provides
        @Singleton
        fun provideTrafficAnalytics(): TrafficAnalytics {
            return createTrafficAnalytics()
        }

        @Provides
        @Singleton
        fun provideConnectivityManager(
            @ApplicationContext context: Context
        ): android.net.ConnectivityManager {
            return context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        }

        @Provides
        @Singleton
        fun provideSecurityEventLogger(
            @ApplicationContext context: Context
        ): SecurityEventLogger {
            val deviceId = CoreUtils.getAndroidDeviceId(context)
            val appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
            val storageDir = context.filesDir.path
            return createSecurityLogger(deviceId, appVersion, storageDir)
        }

        @Provides
        fun provideNetworkDetector(apiClient: ApiClient, vpnManager: VpnManager): NetworkDetector {
            return createNetworkDetector(apiClient, vpnManager)
        }

        @Provides
        fun provideSpeedTest(apiClient: ApiClient): SpeedTest {
            return createSpeedTest(apiClient)
        }
    }
}
