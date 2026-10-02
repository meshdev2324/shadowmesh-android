package com.shadowmesh.app.security

import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import com.shadowmesh.core_vpn.AppIntegrityManager
import com.shadowmesh.core_vpn.Config
import com.shadowmesh.core_vpn.RootDetection
import com.shadowmesh.core_vpn.SecureStorage
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SecurityManagerTest {

    private val context = mockk<Application>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val panicWipeManager = mockk<PanicWipeManager>(relaxed = true)
    private val packageManager = mockk<PackageManager>(relaxed = true)
    private val sharedPrefs = mockk<android.content.SharedPreferences>(relaxed = true)
    private val editor = mockk<android.content.SharedPreferences.Editor>(relaxed = true)
    
    private lateinit var securityManager: SecurityManager
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        mockkObject(AppIntegrityManager)
        mockkObject(RootDetection)
        mockkObject(SecureStorage.Companion)
        mockkStatic(Log::class)
        
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        
        every { AppIntegrityManager.verifyIntegrity(any()) } returns true
        coEvery { RootDetection.isRooted(any()) } returns false
        every { context.packageManager } returns packageManager
        every { context.packageName } returns "com.shadowmesh.app"
        every { secureStorage.prefs } returns sharedPrefs
        every { sharedPrefs.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.apply() } just Runs
        
        every { SecureStorage.generateSalt() } returns "salt"
        every { SecureStorage.hashPin(any(), any()) } returns "hash"
        
        securityManager = SecurityManager(
            context, 
            secureStorage, 
            panicWipeManager,
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `checkIntegrity success`() = runTest(testDispatcher) {
        every { AppIntegrityManager.verifyIntegrity(any()) } returns true
        securityManager.checkIntegrity()
        advanceUntilIdle()
        assertFalse(securityManager.isIntegrityCompromised.value)
    }

    @Test
    fun `checkIntegrity failure`() = runTest(testDispatcher) {
        every { AppIntegrityManager.verifyIntegrity(any()) } returns false
        securityManager.checkIntegrity()
        advanceUntilIdle()
        assertTrue(securityManager.isIntegrityCompromised.value)
    }

    @Test
    fun `checkRoot updates isRooted state`() = runTest(testDispatcher) {
        coEvery { RootDetection.isRooted(any()) } returns true
        
        val sm = SecurityManager(context, secureStorage, panicWipeManager, testDispatcher, testDispatcher)
        advanceUntilIdle()
        assertTrue(sm.isRooted.value)
    }

    @Test
    fun `setPin hashes and stores PIN`() {
        securityManager.setPin("1234")
        verify { editor.putString(Config.KEY_PIN_HASH, "hash") }
        verify { editor.putString(Config.KEY_PIN_SALT, "salt") }
    }

    @Test
    fun `setPanicPin hashes and stores Panic PIN`() {
        securityManager.setPanicPin("9999")
        verify { editor.putString(Config.KEY_PANIC_PIN_HASH, "hash") }
        verify { editor.putString(Config.KEY_PANIC_PIN_SALT, "salt") }
    }

    @Test
    fun `verifyPin returns false when no hash stored`() {
        every { sharedPrefs.getString(any(), null) } returns null
        assertFalse(securityManager.verifyPin("1234"))
    }

    @Test
    fun `verifyPin returns true for correct primary PIN`() {
        every { sharedPrefs.getString(Config.KEY_PIN_HASH, null) } returns "hash"
        every { sharedPrefs.getString(Config.KEY_PIN_SALT, null) } returns "salt"
        every { sharedPrefs.getString(Config.KEY_PANIC_PIN_HASH, null) } returns null
        every { SecureStorage.hashPin("1234", "salt") } returns "hash"
        
        assertTrue(securityManager.verifyPin("1234"))
    }

    @Test
    fun `verifyPin returns false for wrong primary PIN`() {
        every { sharedPrefs.getString(Config.KEY_PIN_HASH, null) } returns "hash"
        every { sharedPrefs.getString(Config.KEY_PIN_SALT, null) } returns "salt"
        every { SecureStorage.hashPin("1234", "salt") } returns "wrong"
        
        assertFalse(securityManager.verifyPin("1234"))
    }

    @Test
    fun `verifyPin triggers panic wipe for panic PIN`() = runTest(testDispatcher) {
        every { sharedPrefs.getString(Config.KEY_PANIC_PIN_HASH, null) } returns "panicHash"
        every { sharedPrefs.getString(Config.KEY_PANIC_PIN_SALT, null) } returns "salt"
        every { SecureStorage.hashPin("9999", "salt") } returns "panicHash"
        
        assertTrue(securityManager.verifyPin("9999"))
        advanceUntilIdle()
        coVerify { panicWipeManager.executePanicWipe() }
    }

    @Test
    fun `toggleCamouflage updates state and aliases`() = runTest(testDispatcher) {
        val onAliasUpdated = mockk<suspend (Boolean) -> Unit>(relaxed = true)
        securityManager.toggleCamouflage(true, onAliasUpdated)
        verify { secureStorage.setBoolean(Config.KEY_CAMOUFLAGE_ENABLED, true) }
        advanceUntilIdle()
        verify { packageManager.setComponentEnabledSetting(any(), any(), any()) }
        coVerify { onAliasUpdated(true) }
    }

    @Test
    fun `toggleCamouflage handles package manager error`() = runTest(testDispatcher) {
        every { packageManager.setComponentEnabledSetting(any(), any(), any()) } throws Exception("PM error")
        securityManager.toggleCamouflage(false, {})
        advanceUntilIdle()
        verify { Log.e(any(), any(), any()) }
    }
}
