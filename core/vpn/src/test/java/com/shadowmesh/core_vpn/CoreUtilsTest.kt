package com.shadowmesh.core_vpn

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for CoreUtils.
 * SOP 12: Coverage for critical utility logic.
 */
class CoreUtilsTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `formatBytes should handle various ranges correctly`() {
        // Bytes
        assertEquals("500 B", CoreUtils.formatBytes(500uL))
        
        // KB
        assertEquals("1 KB", CoreUtils.formatBytes(1024uL))
        assertEquals("100 KB", CoreUtils.formatBytes(102400uL))
        
        // MB
        assertEquals("1.0 MB", CoreUtils.formatBytes(1024uL * 1024uL))
        assertEquals("512.5 MB", CoreUtils.formatBytes(1024uL * 1024uL * 512uL + 1024uL * 512uL))
        
        // GB
        assertEquals("1.00 GB", CoreUtils.formatBytes(1024uL * 1024uL * 1024uL))
        assertEquals("2.75 GB", CoreUtils.formatBytes(1024uL * 1024uL * 1024uL * 2uL + 1024uL * 1024uL * 768uL))
    }

    @Test
    fun `normalizeActivationCode should clean and validate code`() {
        // Valid code
        assertEquals("ABCDEFGH", CoreUtils.normalizeActivationCode("abcd-efgh"))
        assertEquals("12345678", CoreUtils.normalizeActivationCode(" 1234 5678 "))
        
        // Too short
        assertNull(CoreUtils.normalizeActivationCode("abc-123"))
        
        // Mixed characters
        assertEquals("SHADOWMESH123", CoreUtils.normalizeActivationCode("shadow#mesh-123!"))
    }

    @Test
    fun `getAndroidDeviceId should return hashed id`() {
        mockkStatic(Settings.Secure::class)
        val context = mockk<Context>()
        val resolver = mockk<ContentResolver>()
        
        every { context.contentResolver } returns resolver
        every { Settings.Secure.getString(resolver, Settings.Secure.ANDROID_ID) } returns "test_id"
        
        val deviceId = CoreUtils.getAndroidDeviceId(context)
        
        // SHA-256 of "test_id"
        assertEquals("0fb27832c685c35889ba3653994bae061237518c40ed57d3b41eae17bf923137", deviceId)
    }

    @Test
    fun `getAndroidDeviceId should handle null android_id`() {
        mockkStatic(Settings.Secure::class)
        val context = mockk<Context>()
        val resolver = mockk<ContentResolver>()
        
        every { context.contentResolver } returns resolver
        every { Settings.Secure.getString(resolver, Settings.Secure.ANDROID_ID) } returns null
        
        val deviceId = CoreUtils.getAndroidDeviceId(context)
        
        // SHA-256 of "anonymous"
        assertEquals("2f183a4e64493af3f377f745eda502363cd3e7ef6e4d266d444758de0a85fcc8", deviceId)
    }
}
