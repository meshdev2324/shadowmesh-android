package com.shadowmesh.core_vpn

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import android.util.Log
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import uniffi.shadowmesh.*

class AppIntegrityManagerTest {

    private lateinit var context: Context
    private lateinit var packageManager: PackageManager
    private lateinit var packageInfo: PackageInfo

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0

        context = mockk(relaxed = true)
        packageManager = mockk(relaxed = true)
        packageInfo = mockk(relaxed = true)
        
        every { context.packageManager } returns packageManager
        every { context.packageName } returns "com.shadowmesh.app"
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `verifyIntegrity returns true in debuggable builds`() {
        val applicationInfo = ApplicationInfo()
        applicationInfo.flags = ApplicationInfo.FLAG_DEBUGGABLE
        every { context.applicationInfo } returns applicationInfo
        
        assertTrue(AppIntegrityManager.verifyIntegrity(context))
    }

    @Test
    fun `verifyIntegrity returns false if debugger is connected`() {
        val applicationInfo = ApplicationInfo()
        applicationInfo.flags = 0 // Not debuggable
        every { context.applicationInfo } returns applicationInfo
        
        mockkStatic(android.os.Debug::class)
        every { android.os.Debug.isDebuggerConnected() } returns true
        
        assertFalse(AppIntegrityManager.verifyIntegrity(context))
        unmockkStatic(android.os.Debug::class)
    }
}
