package com.shadowmesh.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * No stable device identifier may be written to logcat.
 *
 * ## The defect this pins
 *
 * `IdentityManager.activate` did this:
 *
 * ```kotlin
 * val deviceId = CoreUtils.getAndroidDeviceId(application)
 * ZLog.i(TAG, "Initiating activation for device: $deviceId")
 * ```
 *
 * `CoreUtils.getAndroidDeviceId` is `Settings.Secure.ANDROID_ID` — stable
 * across reboots, resettable by the user, scoped to the app-signing key — run
 * through SHA-256. Writing it to logcat puts a cross-session, cross-diagnostic
 * join key on disk in the one buffer an attacker, a bug reporter, or a
 * `adb bugreport` gets for free.
 *
 * ## Why the test forbids the *name*, not the value
 *
 * A gate written against a literal value only proves that one literal is gone.
 * The next device id gets a different name and walks straight through. This
 * gate names the **identifiers that must never be interpolated into a log
 * call**, so a rename cannot silently re-open it.
 *
 * ## What this gate deliberately does not do
 *
 * It does not require the identifier to be hashed, truncated, or otherwise
 * transformed. The argument is recorded in `ZLog`'s KDoc and in the change
 * report: this log line has no join requirement, so deletion is available and
 * pseudonymisation is strictly worse. If someone later adds a log line that
 * genuinely needs to correlate rows, that line needs a different design and a
 * different review — not a salted hash smuggled in under this test.
 */
class LoggedIdentifierTest {

    /**
     * Identifiers that must not be interpolated into a log call. Matched as
     * whole words against the interpolation expression, so `deviceName` and
     * `tokenType` do not trip `deviceId` and `token`.
     */
    private val forbiddenIdentifiers = listOf(
        "deviceId",
        "androidId",
        "andrnId",
        "authToken",
        "accessToken",
        "sessionToken",
        "activationCode",
        "privateKey",
        "secret",
        "apiKey",
        "refreshToken",
    )

    /** The only logger this gate governs. `android.util.Log` bypass is a separate finding. */
    private val loggerNames = listOf("ZLog")

    @Test
    fun `no ZLog call interpolates a stable device identifier`() {
        val offenders = mutableListOf<String>()

        for ((file, code) in kotlinSources()) {
            for (match in Regex("""\b(?:${loggerNames.joinToString("|")})\s*\.\s*[vd iwe]\s*\(""").let { re ->
                re.findAll(code)
            }) {
                // Take the balanced argument list that follows the call.
                val open = match.range.last + 1
                val close = balancedEnd(code, open) ?: continue
                val call = code.substring(open, close)
                for (name in forbiddenIdentifiers) {
                    if (Regex("""\$\{?\b$name\b""").containsMatchIn(call)) {
                        val line = code.take(open).count { it == '\n' } + 1
                        offenders.add("$file:$line interpolates `$name` into ${match.value.trim()}")
                    }
                }
            }
        }

        assertEquals(
            "A stable device identifier is being written to logcat. Delete the value; do not hash " +
                "or truncate it — a log line with no join requirement does not need a pseudonym, and " +
                "a pseudonym is still an identifier.\n" +
                offenders.joinToString("\n") { "  - $it" },
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the source root this gate reads exists`() {
        // A gate whose search root has moved silently reads zero files and
        // passes. This pins the root.
        assertTrue(
            "source root not found: ${sourceRoot().absolutePath}",
            sourceRoot().isDirectory,
        )
    }

    // -----------------------------------------------------------------------

    /** Index just past the `)` that closes the argument list opened at [open]. */
    private fun balancedEnd(code: String, open: Int): Int? {
        var depth = 1
        var i = open
        var inString: Char? = null
        while (i < code.length) {
            val c = code[i]
            if (inString != null) {
                when (c) {
                    '\\' -> i++
                    inString -> inString = null
                }
            } else {
                when (c) {
                    '"', '\'' -> inString = c
                    '(', '{', '[' -> depth++
                    ')', '}', ']' -> {
                        depth--
                        if (depth == 0) return i
                    }
                    '\n' -> if (depth == 0) return null
                }
            }
            i++
        }
        return null
    }

    private fun sourceRoot(): File = File("src/main/java/com/shadowmesh/app")

    private fun kotlinSources(): List<Pair<String, String>> {
        val dir = sourceRoot()
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .map { it.path.substringAfter("/app/") to it.readText() }
            .toList()
    }
}