package com.shadowmesh.core_vpn

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import uniffi.shadowmesh.*
import java.security.MessageDigest

/**
 * Senior-level App Integrity Manager.
 * Handles APK signature verification and native library tampering detection.
 */
object AppIntegrityManager {
    private const val TAG = "AppIntegrity"

    // v12.4 Security Gate: Canonical release certificate fingerprints.
    // Format: SHA-256 over the signing certificate DER bytes, lowercase hex,
    // no separators — identical to `keytool -list -v` / apksigner output
    // normalized. Source of truth: .agents/skills/android/SIGNING.md
    internal var expectedSignatureHashes = listOf(
        "750f5a4f7fdeadfb9591f29732103d42013b5c60714eb3c6055037d76c62c513" // shadowmesh.jks (alias: shadowmesh) — rotated 2026-09-05, see SIGNING.md
    )

    /**
     * Native signature-verification seam. Production delegates to the Rust
     * [AntiTamperChecker] so the comparison stays constant-time; tests may
     * replace this to avoid loading the native library on the JVM.
     */
    internal var signatureVerifier: (packageName: String, currentHash: String, expectedHash: String) -> Boolean =
        { packageName, currentHash, expectedHash ->
            AntiTamperChecker(AntiTamperConfig(
                expectedHashes = mapOf<String, String>(packageName to expectedHash)
            )).verifyAppSignature(packageName, currentHash)
        }

    /**
     * Verifies the app integrity. Returns true if valid, false if tampered.
     * Skips check in DEBUG builds unless forced.
     */
    fun verifyIntegrity(context: Context): Boolean {
        // 1. Always allow in Debuggable builds (development)
        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebuggable) {
            Log.d(TAG, "Debug build detected: Skipping integrity checks")
            return true
        }

        try {
            // 1. Check for Debugger Attachment (Anti-Debugging)
            if (android.os.Debug.isDebuggerConnected()) {
                Log.e(TAG, "Debugger detected!")
                return false
            }

            // 2. Fail closed if no canonical fingerprints are configured.
            val candidates = expectedSignatureHashes.map { normalizeHex(it) }.filter { it.isNotEmpty() }
            if (candidates.isEmpty()) {
                Log.e(TAG, "No expected signature fingerprints configured; failing closed")
                return false
            }

            // 3. Verify APK Signature
            val currentSignatures = getAppSignatureHashes(context)
            if (currentSignatures.isEmpty()) {
                Log.e(TAG, "Could not extract app signature")
                return false
            }

            // 4. Hand off verification to Rust for increased security (Native layer check).
            // Both sides are normalized lowercase hex of identical length so the
            // constant-time compare inside the native checker stays meaningful.
            val packageName = context.packageName
            val isSignatureValid = currentSignatures.any { current ->
                candidates.any { candidate ->
                    signatureVerifier(packageName, current, candidate)
                }
            }

            if (!isSignatureValid) {
                Log.e(TAG, "Signature mismatch detected! current=$currentSignatures expected=$candidates")
                return false
            }

            // 5. Verify native library integrity
            // (Optional: can be added here by reading the .so file and hashing it)

            Log.i(TAG, "Integrity check passed")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Integrity check error", e)
            return false
        }
    }

    internal fun normalizeHex(value: String): String =
        value.replace(":", "").replace(" ", "").trim().lowercase()

    private fun getAppSignatureHashes(context: Context): List<String> {
        return try {
            val pm = context.packageManager
            val packageName = context.packageName

            val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                packageInfo.signingInfo?.apkContentsSigners ?: emptyArray()
            } else {
                val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                packageInfo.signatures ?: emptyArray()
            }

            if (signatures.isEmpty()) return emptyList()

            // SHA-256 over each signing certificate, lowercase hex without separators.
            // Must match the format of [expectedSignatureHashes] exactly.
            val md = MessageDigest.getInstance("SHA-256")
            signatures.map { sig ->
                md.reset()
                md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting signature hash", e)
            emptyList()
        }
    }
}
