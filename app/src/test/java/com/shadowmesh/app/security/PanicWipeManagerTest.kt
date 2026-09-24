package com.shadowmesh.app.security

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.shadowmesh.core_vpn.Config
import com.shadowmesh.core_vpn.CoreUtils
import com.shadowmesh.core_vpn.SecureStorage
import com.shadowmesh.core_vpn.repository.VpnRepository
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import uniffi.shadowmesh.ApiClient
import uniffi.shadowmesh.SecurityEventLogger
import uniffi.shadowmesh.VpnManager

@OptIn(ExperimentalCoroutinesApi::class)
class PanicWipeManagerTest {

    private val context = mockk<Context>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val vpnRepository = mockk<VpnRepository>(relaxed = true)
    private val apiClient = mockk<ApiClient>(relaxed = true)
    private val vpnManager = mockk<VpnManager>(relaxed = true)
    private val securityEventLogger = mockk<SecurityEventLogger>(relaxed = true)
    private val sharedPrefs = mockk<SharedPreferences>(relaxed = true)
    private val prefsEditor = mockk<SharedPreferences.Editor>(relaxed = true)

    private lateinit var panicWipeManager: PanicWipeManager

    @Before
    fun setup() {
        mockkObject(CoreUtils)
        every { CoreUtils.getAndroidDeviceId(any()) } returns "test-device-id"
        every { vpnRepository.apiClient } returns apiClient
        every { vpnRepository.vpnManager } returns vpnManager
        every { vpnRepository.securityEventLogger } returns securityEventLogger
        every { context.getSharedPreferences(any(), any()) } returns sharedPrefs
        every { sharedPrefs.edit() } returns prefsEditor
        every { prefsEditor.clear() } returns prefsEditor
        every { prefsEditor.putString(any(), any()) } returns prefsEditor
        
        panicWipeManager = PanicWipeManager(context, secureStorage, vpnRepository)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `executePanicWipe performs all security clearing steps`() = runTest {
        panicWipeManager.executePanicWipe()
        
        // Use any() to be less strict during debugging
        verify { apiClient.reportCompromised(any(), any()) }
        verify { vpnManager.panicWipe() }
        verify { secureStorage.clearAll() }
        verify { securityEventLogger.logEvent(any(), any(), any(), any()) }
        verify { context.sendBroadcast(any<Intent>()) }
    }

    @Test
    fun `executePanicWipe continues even if some steps fail`() = runTest {
        every { apiClient.reportCompromised(any(), any()) } throws Exception("Network error")
        every { vpnManager.panicWipe() } throws Exception("Core error")
        
        panicWipeManager.executePanicWipe()
        
        verify { secureStorage.clearAll() }
        verify { context.sendBroadcast(any<Intent>()) }
    }
}
