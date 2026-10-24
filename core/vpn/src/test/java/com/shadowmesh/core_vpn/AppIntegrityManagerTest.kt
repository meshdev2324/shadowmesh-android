package com.shadowmesh.core_vpn

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.util.Log
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

class AppIntegrityManagerTest {

    private val canonicalFingerprint = "750f5a4f7fdeadfb9591f29732103d42013b5c60714eb3c6055037d76c62c513"
    private val originalVerifier: (String, String, String) -> Boolean = AppIntegrityManager.signatureVerifier

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
        AppIntegrityManager.signatureVerifier = originalVerifier
        AppIntegrityManager.expectedSignatureHashes = listOf(canonicalFingerprint)
        unmockkAll()
    }

    private fun releaseContext(): Context {
        val applicationInfo = ApplicationInfo()
        applicationInfo.flags = 0 // Not debuggable
        every { context.applicationInfo } returns applicationInfo
        mockkStatic(android.os.Debug::class)
        every { android.os.Debug.isDebuggerConnected() } returns false
        return context
    }

    private fun stubSignatures(vararg certs: ByteArray) {
        val sigs = certs.map { bytes ->
            mockk<Signature> { every { toByteArray() } returns bytes }
        }
        val pkgInfo = PackageInfo()
        pkgInfo.signatures = sigs.toTypedArray()
        every {
            packageManager.getPackageInfo("com.shadowmesh.app", PackageManager.GET_SIGNATURES)
        } returns pkgInfo
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

    @Test
    fun `verifyIntegrity passes when signature digest matches canonical fingerprint`() {
        releaseContext()
        val certBytes = "canonical-release-cert".toByteArray()
        stubSignatures(certBytes)

        val digestHex = MessageDigest.getInstance("SHA-256").digest(certBytes)
            .joinToString("") { "%02x".format(it) }
        AppIntegrityManager.expectedSignatureHashes = listOf(digestHex)

        val verifiedHashes = mutableListOf<Pair<String, String>>()
        AppIntegrityManager.signatureVerifier = { pkg, current, expected ->
            verifiedHashes.add(current to expected)
            current == expected
        }

        assertTrue(AppIntegrityManager.verifyIntegrity(context))
        assertEquals(listOf(digestHex to digestHex), verifiedHashes)
    }

    @Test
    fun `verifyIntegrity fails when signature digest does not match`() {
        releaseContext()
        stubSignatures("tampered-cert".toByteArray())

        AppIntegrityManager.signatureVerifier = { _, _, _ -> false }

        assertFalse(AppIntegrityManager.verifyIntegrity(context))
    }

    @Test
    fun `verifier receives normalized lowercase hex on both sides`() {
        releaseContext()
        val certBytes = "case-normalization-cert".toByteArray()
        stubSignatures(certBytes)

        AppIntegrityManager.expectedSignatureHashes = listOf(
            "75:0F:5A:4F:7F:DE:AD:FB:95:91:F2:97:32:10:3D:42:01:3B:5C:60:71:4E:B3:C6:05:50:37:D7:6C:62:C5:13"
        )

        var seenExpected: String? = null
        var seenCurrent: String? = null
        AppIntegrityManager.signatureVerifier = { _, current, expected ->
            seenCurrent = current
            seenExpected = expected
            true
        }

        assertTrue(AppIntegrityManager.verifyIntegrity(context))
        assertEquals(
            "750f5a4f7fdeadfb9591f29732103d42013b5c60714eb3c6055037d76c62c513",
            seenExpected
        )
        assertEquals(64, seenCurrent?.length)
    }

    @Test
    fun `verifyIntegrity fails closed when no signing certificate is present`() {
        releaseContext()
        val pkgInfo = PackageInfo()
        pkgInfo.signatures = emptyArray()
        every {
            packageManager.getPackageInfo("com.shadowmesh.app", PackageManager.GET_SIGNATURES)
        } returns pkgInfo

        assertFalse(AppIntegrityManager.verifyIntegrity(context))
    }

    @Test
    fun `verifyIntegrity fails closed when expected fingerprints are empty`() {
        releaseContext()
        AppIntegrityManager.expectedSignatureHashes = emptyList()

        assertFalse(AppIntegrityManager.verifyIntegrity(context))
    }

    @Test
    fun `normalizeHex strips separators and lowercases`() {
        assertEquals(
            "750f5a4f7fdeadfb9591f29732103d42013b5c60714eb3c6055037d76c62c513",
            AppIntegrityManager.normalizeHex("75:0F:5A:4F:7F:DE:AD:FB:95:91:F2:97:32:10:3D:42:01:3B:5C:60:71:4E:B3:C6:05:50:37:D7:6C:62:C5:13")
        )
        assertEquals(
            "750f5a4f",
            AppIntegrityManager.normalizeHex(" 75 0F 5A 4F ")
        )
    }

    @Test
    fun `canonical fingerprints are stored in normalized lowercase hex format`() {
        assertTrue(AppIntegrityManager.expectedSignatureHashes.isNotEmpty())
        AppIntegrityManager.expectedSignatureHashes.forEach { stored ->
            assertEquals(canonicalFingerprint, AppIntegrityManager.normalizeHex(stored))
        }
    }
}
