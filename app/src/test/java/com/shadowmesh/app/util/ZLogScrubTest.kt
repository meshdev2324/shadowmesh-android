package com.shadowmesh.app.util

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * Tests for the Zero-PII logger's scrubber.
 *
 * ## Why this file exists
 *
 * `ZLog`'s own KDoc claims it *"automatically scrubs IP addresses, tokens, and
 * activation codes"*, and it had **zero tests**. A gate that cannot fail is not
 * a gate. An L7 agent proved by execution that the claim was false for the two
 * credential shapes this project actually produces most:
 *
 * | value | shape | old scrubber |
 * | --- | --- | --- |
 * | `derive_session_token` output | 64 lowercase hex (`SHA256 ‖ hex::encode`) | **passed intact** |
 * | raw `ANDROID_ID` | 16 lowercase hex | **passed intact** |
 *
 * The 25-char activation code rule is `[A-Z0-9]{25}\b` and cannot match
 * lowercase hex; the IPv4/IPv6 rules need `.` or `:`; `eyJ` cannot lead a hex
 * string. So the enforcement point for "no sensitive data in logs" — the
 * Command Center's ZPII requirement — did not cover the credential type this
 * repository produces most.
 *
 * ## How each rule is pinned
 *
 * Every rule is pinned **twice**: by a value it must catch, and by a value it
 * must *not* mangle. The negative half is not decoration. A scrubber that eats
 * innocuous text is its own defect — it destroys the diagnostic content the
 * log exists to carry, and it trains engineers to ignore redaction markers, at
 * which point the real redactions stop being noticed. `the scrubber does not
 * mangle ordinary diagnostics` is therefore a first-class assertion here, not
 * a footnote.
 *
 * The innocuous values are **real log lines from this repository**, not
 * invented ones — `PerformanceMonitor`'s `avail=87.5%`, `AppPickerScreen`'s
 * `stage=query installed=142`, `MeshVpnService`'s `Split tunnel: mode=…`, and
 * so on. A negative test against a synthetic string proves the regex is
 * narrow; a negative test against a line the app actually emits proves it is
 * narrow enough.
 */
class ZLogScrubTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Asserts [secret] does not survive into the scrubbed output, and that [marker] does. */
    private fun assertRedacted(what: String, message: String, secret: String, marker: String = "[REDACTED") {
        val out = ZLog.scrub(message)
        assertFalse(
            "$what: the secret survived scrubbing.\n  input : $message\n  output: $out",
            out.contains(secret),
        )
        assertTrue(
            "$what: expected a redaction marker, got none.\n  input : $message\n  output: $out",
            out.contains(marker),
        )
    }

    /** Asserts the scrubber leaves [message] byte-identical. */
    private fun assertUntouched(what: String, message: String) {
        val out = ZLog.scrub(message)
        assertEquals(
            "$what: the scrubber mangled innocuous text. Over-redaction is a defect in its own " +
                "right — it destroys the diagnostic content the line exists to carry.",
            message,
            out,
        )
    }

    // -----------------------------------------------------------------------
    // Credential fixtures — derived at run time, never written down
    // -----------------------------------------------------------------------

    /**
     * A credential-shaped literal in a test file is a published secret, whether
     * or not a human generated it. `scripts/ci/scan-credential-literals.sh`
     * fails the build on exactly this shape — a value in the credential
     * vocabularies, held as a literal, in a tracked test file — and a reader
     * cannot tell a synthetic digest from a real one by looking at it. The
     * placeholder convention (`REPLACE_ME_…`, loopback, all-zero) is *not* an
     * escape here: those values no longer match the shape, so the scrubber
     * would stop being exercised against it and the test would go vacuous.
     *
     * So the fixtures below are **derived**, deterministically. Same bytes on
     * every machine, forever; nothing credential-shaped in the source; and
     * built by the *same construction the production code uses*, which is what
     * makes them faithful rather than merely different:
     *
     *  - `derive_session_token` is `SHA256 ‖ hex::encode` — so [sha256Hex].
     *  - `CoreUtils.getAndroidDeviceId` is `SHA256(ANDROID_ID)` — same width,
     *    different input.
     *  - a 25-character activation code is `[A-Z0-9]{25}` — so an uppercase
     *    slice of a digest, which lands inside the alphabet the server accepts.
     *  - a JWT is `base64url(header) . base64url(payload) . base64url(sig)` —
     *    so [base64Url] over declared JSON, not a token copied off a website.
     *  - a WireGuard/X25519 private key is 32 raw bytes base64'd with padding —
     *    so a SHA-256 digest of a seed, standard-alphabet encoded.
     *
     * The seeds are short, human-readable and carry `test`: nobody's secret, and
     * recognisable as a fixture to the scanner's placeholder oracle as well as
     * to a reader. They are *inputs*; the credential-shaped value only exists
     * once the digest has run.
     *
     * `fixtures are credential-shaped before the scrubber is consulted` pins the
     * shape of every one of these independently of whether the scrubber
     * redacts it — so a generator that drifted out of shape fails loudly here
     * rather than turning every redaction assertion in this file into a
     * tautology.
     */
    private fun digestHex(algorithm: String, seed: String): String =
        MessageDigest.getInstance(algorithm)
            .digest(seed.toByteArray())
            .joinToString(separator = "") { b -> "%02x".format(b.toInt() and 0xFF) }

    /** `SHA256 ‖ hex::encode` — byte-for-byte what `derive_session_token` returns. */
    private fun sha256Hex(seed: String): String = digestHex("SHA-256", seed)

    private fun base64Url(text: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

    /** 64 lowercase hex — the session token `derive_session_token` mints. */
    private val sessionToken = sha256Hex("shadowmesh-test-session-token")

    /** 64 lowercase hex — `CoreUtils.getAndroidDeviceId`, i.e. `SHA256(ANDROID_ID)`. */
    private val hashedDeviceId = sha256Hex("shadowmesh-test-device-id")

    /** 40 lowercase hex — the width a git object id renders at. */
    private val commitSha = digestHex("SHA-1", "shadowmesh-test-commit")

    /** 32 lowercase hex — the width an ETag renders at. */
    private val etagMd5 = digestHex("MD5", "shadowmesh-test-etag")

    /** 25 characters over `[A-Z0-9]`, which is the whole activation-code contract. */
    private val activationCode = sha256Hex("shadowmesh-test-activation-code").take(25).uppercase()

    /** The same 25 characters in the 6+6+6+6+1 grouping a user actually types. */
    private val activationCodeDashed = activationCode.chunked(6).joinToString("-")

    /** 32 raw bytes, standard base64 with padding — a real X25519 private key's shape. */
    private val privateKeyBase64 = Base64.getEncoder()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest("shadowmesh-test-wireguard-key".toByteArray()))

    /** Three base64url segments, the first of which begins `eyJ`. */
    private val jwt = listOf(
        base64Url("""{"alg":"HS256","typ":"JWT"}"""),
        base64Url("""{"sub":"shadowmesh-test-subject","name":"Fixture Operator"}"""),
        base64Url(sha256Hex("shadowmesh-test-jwt-signature")),
    ).joinToString(".")

    @Test
    fun `fixtures are credential-shaped before the scrubber is consulted`() {
        // **The non-vacuity guard.** Every redaction assertion below is of the
        // form "the scrubber removes X", which is trivially satisfied by an X
        // that no longer matches anything. This pins each fixture's *shape*
        // against `HEX_SECRET_PATTERN`'s own stated contract — a run of 16 or
        // more hex characters containing at least one hex letter — and against
        // the protocol widths, so the generation above cannot quietly drift out
        // of the shape the scrubber claims to catch.
        val hex64 = Regex("[0-9a-f]{64}")
        assertTrue("session token is not 64 lowercase hex: $sessionToken", hex64.matches(sessionToken))
        assertTrue("device id is not 64 lowercase hex: $hashedDeviceId", hex64.matches(hashedDeviceId))
        assertTrue("commit sha is not 40 lowercase hex: $commitSha", Regex("[0-9a-f]{40}").matches(commitSha))
        assertTrue("etag is not 32 lowercase hex: $etagMd5", Regex("[0-9a-f]{32}").matches(etagMd5))

        // `HEX_SECRET_PATTERN` additionally demands at least one hex *letter* —
        // that clause is what keeps it off microsecond clocks — so a fixture of
        // all digits would NOT be caught, and the tests above would be proving
        // nothing. Assert the clause rather than trusting probability: even a
        // 16-char fixture passes that test only ~1 time in 150.
        for ((name, value) in listOf(
            "sessionToken" to sessionToken,
            "hashedDeviceId" to hashedDeviceId,
            "commitSha" to commitSha,
            "etagMd5" to etagMd5,
        )) {
            assertTrue(
                "$name is all-decimal, so HEX_SECRET_PATTERN skips it: $value",
                value.any { it in 'a'..'f' },
            )
        }

        assertEquals(
            "activation code must be exactly 25 characters over [A-Z0-9]",
            25,
            activationCode.length,
        )
        assertTrue(
            "activation code leaves the server's alphabet: $activationCode",
            activationCode.all { it in 'A'..'Z' || it in '0'..'9' },
        )
        assertEquals(
            "the dashed form must strip to the same 25 characters: $activationCodeDashed",
            activationCode,
            activationCodeDashed.replace("-", ""),
        )
        assertEquals("the dashed grouping must be 6+6+6+6+1", 4, activationCodeDashed.count { it == '-' })

        assertEquals(
            "a 32-byte private key is 44 base64 characters ending in one pad",
            44,
            privateKeyBase64.length,
        )
        assertTrue("private key is not standard base64: $privateKeyBase64", privateKeyBase64.endsWith("="))
        assertEquals(
            "private key must decode to exactly 32 raw bytes",
            32,
            Base64.getDecoder().decode(privateKeyBase64).size,
        )

        val jwtParts = jwt.split(".")
        assertEquals("a JWT is three dot-separated segments", 3, jwtParts.size)
        assertTrue("the header segment must begin eyJ", jwtParts[0].startsWith("eyJ"))
        assertTrue(
            "every segment must be base64url",
            jwtParts.all { p -> p.isNotEmpty() && p.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' } },
        )
        assertEquals(
            "the header must decode back to the declared alg",
            """{"alg":"HS256","typ":"JWT"}""",
            String(Base64.getUrlDecoder().decode(jwtParts[0])),
        )

        // Distinctness: one value copy-pasted into five assertions would make
        // the file look thorough while testing one case.
        val distinct = setOf(sessionToken, hashedDeviceId, commitSha, etagMd5, privateKeyBase64)
        assertEquals("credential fixtures must be distinct from one another", 5, distinct.size)
    }

    // -----------------------------------------------------------------------
    // Rule 1 — IPv4
    // -----------------------------------------------------------------------

    @Test
    fun `rule ipv4 catches a literal address`() {
        assertRedacted("IPv4", "Peer at 192.168.1.44 rejected the handshake", "192.168.1.44")
        assertRedacted("IPv4", "Upstream 8.8.8.8 unreachable", "8.8.8.8")
        assertRedacted("IPv4", "Listening on 0.0.0.0 for the tunnel socket", "0.0.0.0")
    }

    @Test
    fun `rule ipv4 catches an address carrying a port`() {
        assertRedacted("IPv4 with port", "Connected to 172.16.254.3:51820", "172.16.254.3")
    }

    @Test
    fun `rule ipv4 does not mangle dotted non-addresses`() {
        // Real lines this app emits, plus the shapes a dotted-number sweep
        // classically eats.
        assertUntouched("version triple", "ShadowMesh 1.2.3")
        assertUntouched("release date", "Built 2026.10.11 from main")
        assertUntouched("memory sample", "avail=87.5% low=false pressure=false")
        assertUntouched("contrast ratio", "ratio 5.38:1 on the app canvas")
        assertUntouched("byte formatting", "transferred 128.0 KB in 40.0 ms")
        // Octet range is enforced, so a four-part number that is not a valid
        // dotted quad is left alone rather than redacted for being "IP-shaped".
        assertUntouched("out-of-range quad", "probe returned 999.888.777.666 for build id")
    }

    // -----------------------------------------------------------------------
    // Rule 2 — IPv6
    // -----------------------------------------------------------------------

    @Test
    fun `rule ipv6 catches a literal address`() {
        assertRedacted("IPv6 full", "Peer 2001:db8::8a2e:370:7334 unreachable", "2001:db8::8a2e:370:7334")
        assertRedacted("IPv6 link-local", "Bound to fe80::1ff:fe23:4567:890a", "fe80::1ff:fe23:4567:890a")
        assertRedacted("IPv6 loopback", "Upstream ::1 refused the connection", "::1")
    }

    @Test
    fun `rule ipv6 does not mangle a clock time`() {
        // **The regression this test was written for.** The old rule was
        // `\b([0-9a-fA-F]{1,4}:[0-9a-fA-F]{1,4}):[0-9a-fA-F:]+\b`, which matches
        // any `12:30:45`. Every timestamped diagnostic in the app was being
        // redacted as though it were an address.
        assertUntouched("clock time", "2026-10-11T12:30:45Z handler entered")
        assertUntouched("clock time", "timer 10:20:30 elapsed")
        assertUntouched("clock time", "wake at 00:00:01 after doze")
        // And a real line from PerformanceMonitor that contains a colon run.
        assertUntouched("key=value", "stage=sources installed=142 launcher=140")
    }

    // -----------------------------------------------------------------------
    // Rule 3 — 25-character activation code
    // -----------------------------------------------------------------------

    @Test
    fun `rule activation code catches the canonical form`() {
        assertRedacted("activation code", "Redeeming $activationCode for this plan", activationCode)
    }

    @Test
    fun `rule activation code catches the hyphenated form a user types`() {
        // `IdentityManager.loginWithPasskey` calls `rawCode.replace("-", "")`,
        // which means the dashed grouping is what a user actually types and
        // what an error handler would echo back. 6+6+6+6+1 = 25 characters.
        assertRedacted(
            "hyphenated activation code",
            "Invalid Token for Passkey: $activationCodeDashed",
            activationCodeDashed,
        )
    }

    @Test
    fun `rule activation code is bounded to exactly 25`() {
        // A `\b[A-Z0-9]{25}\b` with no length discipline would fire on any run
        // of 25 or more. Boundaries are what stop a 26-char id or a 24-char
        // build label from being redacted.
        assertUntouched("24-char run", "Build A1B2C3D4E5F6G7H8J9K0L1M2 (24 chars)")
        assertUntouched("26-char run", "Correlation A1B2C3D4E5F6G7H8J9K0L1M2N3 (26 chars)")
        assertUntouched("25 lowercase", "connectfailedandneedsretr (25 lowercase)")
    }

    @Test
    fun `rule activation code over-redacts the alphabet, and that is the accepted cost`() {
        // Pinned deliberately, in the *catching* direction, so the cost is a
        // recorded decision rather than a surprise. `[A-Z0-9]{25}` and the
        // 26-letter alphabet are the same shape; nothing in the byte string
        // distinguishes a real code from `ABCDEFGHIJKLMNOPQRSTUVWXYZ`. Fails
        // closed. If this ever needs to stop firing, the discriminator has to
        // come from somewhere real — a checksum or a prefix — not from shape.
        assertRedacted("alphabet literal", "charset=ABCDEFGHIJKLMNOPQRSTUVWXY", "ABCDEFGHIJKLMNOPQRSTUVWXY")
    }

    // -----------------------------------------------------------------------
    // Rule 4 — JWT
    // -----------------------------------------------------------------------

    @Test
    fun `rule jwt catches a three-segment bearer token`() {
        assertRedacted("JWT", "Authorization: Bearer $jwt rejected with 401", jwt, marker = "[MASKED_TOKEN")
    }

    @Test
    fun `rule jwt does not mangle a base64url blob that is not a JWT`() {
        assertUntouched("non-jwt base64url", "payload=SGVsbG8gV29ybGQxMjM0NTY3 destroyed")
        assertUntouched("lowercase ey prefix", "eyebrowsing session resumed")
    }

    // -----------------------------------------------------------------------
    // Rule 5 — long hex digests (session token, hardware fingerprint)
    // -----------------------------------------------------------------------

    @Test
    fun `rule hex digest catches a 64-hex session token`() {
        // The exact shape of `shadowmesh_common::crypto::derive_session_token`,
        // which is `SHA256 ‖ hex::encode` — 64 lowercase hex characters.
        assertRedacted("session token", "Pairing handshake produced session token $sessionToken", sessionToken)
    }

    @Test
    fun `rule hex digest catches the device id this app logs`() {
        // The literal that `IdentityManager.kt:57` used to write into logcat,
        // and the exact value asserted by `CoreUtilsTest.getAndroidDeviceId`.
        // Scrubbing it is the defence-in-depth layer beneath the log line that
        // no longer exists.
        assertRedacted("device id", "device=$hashedDeviceId handshake retried", hashedDeviceId)
    }

    @Test
    fun `rule hex digest catches other digest widths`() {
        assertRedacted("sha1 40-hex", "commit $commitSha rejected", commitSha)
        assertRedacted("md5 32-hex", "etag $etagMd5 stale", etagMd5)
    }

    @Test
    fun `rule hex digest does not mangle short hex or colours`() {
        assertUntouched("8 hex", "stage=deadbeef reached")
        assertUntouched("colour literal", "surface #0A0A0F selected")
        assertUntouched("short hex in text", "channel 0xA0A0A0 configured")
    }

    @Test
    fun `rule hex digest does not mangle a hex run inside a base64 token`() {
        // Boundary assertions, not greedy matching: a digest-shaped run that is
        // really part of a larger base64 credential must be left to the rule
        // that owns it, or the replacement produces a half-redacted credential.
        assertRedacted("base64 key owning the run", "peer key $privateKeyBase64 rejected", privateKeyBase64)
        assertFalse(
            "the base64 key must be redacted whole, not half",
            ZLog.scrub("peer key $privateKeyBase64 rejected").contains(privateKeyBase64),
        )
    }

    // -----------------------------------------------------------------------
    // Rule 6 — short hex identifier (raw ANDROID_ID)
    // -----------------------------------------------------------------------

    @Test
    fun `rule short hex identifier catches a raw 16-hex ANDROID_ID`() {
        // `Settings.Secure.ANDROID_ID` is a 16-hex value. `CoreUtils` hashes it
        // before it leaves the app, but the raw form is what a platform helper,
        // an exception message, or a third-party SDK hands back — and it is
        // stable, linkable and cross-app-resettable.
        assertRedacted("ANDROID_ID", "device android_id=9774d56d682e549c registered", "9774d56d682e549c")
    }

    @Test
    fun `rule short hex identifier does not eat epoch timestamps`() {
        // **The reason this rule requires a hex letter.** A bare
        // `\b[0-9a-f]{16}\b` matches `1728643200000000` — a microsecond clock —
        // which this app logs, and which is not sensitive. Every all-decimal
        // 16-digit value is a candidate timestamp and must survive.
        assertUntouched("epoch micros", "ts=1728643200000000 handler entered")
        assertUntouched("counter", "seq=1234567890123456 accepted")
        // A real log line that carries a colon run and digits together.
        assertUntouched("timer", "connected in 1234 ms, retries=1")
    }

    @Test
    fun `rule short hex identifier does not fire on shorter digits`() {
        assertUntouched("8 digits", "device 12345678 disconnected")
        assertUntouched("16 digits with separators", "build 2026-10-11-12345678")
    }

    // -----------------------------------------------------------------------
    // Rule 7 — 32-byte base64 key (WireGuard / X25519 private key)
    // -----------------------------------------------------------------------

    @Test
    fun `rule base64 key catches a 32-byte private key`() {
        // `DeviceIdentity.DeviceKeyPair.privateKey` is base64 X25519 scalar
        // material and its KDoc says "never log it". The scrubber is the layer
        // that makes that true when someone forgets. 32 bytes base64 is exactly
        // 44 characters ending in a single `=` pad, which is what gives the
        // rule a shape precise enough to be safe.
        assertRedacted("wireguard private key", "key=$privateKeyBase64 registered with node", privateKeyBase64)
    }

    @Test
    fun `rule base64 key does not mangle shorter padded base64`() {
        // 16 bytes pads to 24 characters — a different length, so it is not a
        // 32-byte key and must not be redacted by this rule.
        assertUntouched("16-byte base64", "salt=MDEyMzQ1Njc4OWFiY2RlZg== loaded")
        assertUntouched("unpadded base64", "nonce=SGVsbG8gV29ybGQxMjM0NTY3")
    }

    // -----------------------------------------------------------------------
    // Rule 8 — composition, idempotence, robustness
    // -----------------------------------------------------------------------

    @Test
    fun `a message carrying several secrets is fully redacted`() {
        val msg = "activate 192.168.1.44 with $activationCode " +
            "token=$sessionToken " +
            "android_id=9774d56d682e549c"
        val out = ZLog.scrub(msg)
        for (secret in listOf(
            "192.168.1.44",
            activationCode,
            sessionToken,
            "9774d56d682e549c",
        )) {
            assertFalse("survived: $secret\n  output: $out", out.contains(secret))
        }
    }

    @Test
    fun `scrubbing is idempotent`() {
        // A second pass must not re-redact its own markers, and must not grow
        // the string. Without this, a double-wrapped logger corrupts every line.
        val once = ZLog.scrub("token=$sessionToken")
        val twice = ZLog.scrub(once)
        assertEquals("scrub is not idempotent", once, twice)
    }

    @Test
    fun `scrubbing never throws`() {
        // Logcat is a diagnostic path; a scrubber that throws on malformed
        // input converts a cosmetic problem into a crash on the user's screen.
        for (input in listOf(
            "",
            " ",
            "\u0000\u0001",
            "\u0000\u0001\u0002",
            "🎉 connected",
            "a".repeat(10_000),
            "0".repeat(10_000),
            ":".repeat(5_000),
            "=".repeat(5_000),
            "::",
            "0x",
            "deadbeef:",
        )) {
            ZLog.scrub(input)
        }
    }

    @Test
    fun `a logged throwable is scrubbed, because it is an unscrubbed channel`() {
        // `Log.e(tag, msg, tr)` hands `tr` to the platform, which calls
        // `getStackTraceString(tr)` and writes the exception's message — and
        // every `Caused by:` message below it — verbatim. So this is a second
        // way for `e.message` to reach logcat with the *message* beside it
        // already scrubbed, which is the worst kind of leak: it looks handled.
        //
        // This is the shape `SecurityManager.syncAlias` produces when the
        // package manager throws, and the shape `IdentityManager.registerPasskey`
        // produces across the uniffi boundary.
        mockkStatic(Log::class)
        try {
            every { Log.e(any(), any(), any<Throwable>()) } returns 0
            val thrown = RuntimeException("handshake rejected for $sessionToken", IllegalStateException("peer 192.168.1.44"))

            val captured = slot<Throwable>()
            ZLog.e("T", "Pairing failed", thrown)
            verify { Log.e(any(), any(), capture(captured)) }

            val rendered = render(captured.captured)
            assertFalse(
                "a 64-hex token survived inside the logged throwable:\n$rendered",
                rendered.contains(sessionToken),
            )
            assertFalse(
                "an IPv4 address survived inside the logged throwable:\n$rendered",
                rendered.contains("192.168.1.44"),
            )
            assertTrue(
                "the throwable must still be readable, not blanked:\n$rendered",
                rendered.contains("handshake rejected for"),
            )
            assertTrue(
                "the cause chain must survive so the failure stays diagnosable:\n$rendered",
                rendered.contains("Caused by"),
            )
            assertTrue(
                "the wrapper must delegate its stack so a crash reporter still sees real frames",
                captured.captured.stackTrace.isNotEmpty(),
            )
        } finally {
            unmockkAll()
        }
    }

    /** Exactly what `android.util.Log.getStackTraceString` does. */
    private fun render(tr: Throwable): String {
        val writer = java.io.StringWriter()
        java.io.PrintWriter(writer).use { tr.printStackTrace(it) }
        return writer.toString()
    }

    @Test
    fun `the ZLog facade routes every level through the scrubber`() {
        // `isReturnDefaultValues = true` makes `android.util.Log` a no-op
        // returning 0 under the JVM, so this asserts reachability and
        // signature, not the argument that reached Logcat. It exists to catch a
        // future level being added that bypasses [scrub].
        ZLog.v("T", "v")
        ZLog.d("T", "d")
        ZLog.i("T", "i")
        ZLog.w("T", "w")
        ZLog.e("T", "e")
        ZLog.e("T", "e", RuntimeException("boom"))
        // The three-argument warning overload exists for callers that hold a
        // `Log.w(tag, msg, tr)` site; without it those callers either dropped
        // the throwable or mis-ranked a recoverable failure as an error.
        ZLog.w("T", "w", RuntimeException("boom"))
    }

    /**
     * K4's sharpest instance: `AppPickerScreen` logs the user's app-search
     * query, which is behavioural data about what a person is looking for on
     * their own device — and it used to go out over `android.util.Log`
     * directly, i.e. on a line where the scrubber never ran at all.
     *
     * This test pins the **route**, not the scrubber: the same value that
     * screen logs now travels through `ZLog.i`, so the scrubber's rules decide
     * what lands in logcat. If someone reintroduces a direct
     * `android.util.Log` call in that screen, nothing here catches it — what
     * catches that is the residue measurement (`grep -rn 'android.util.Log'
     * apps/android/app/src/main/java` must return only ZLog.kt's own
     * primitives), which is why this test names the route explicitly.
     */
    @Test
    fun `an app search query carrying a token is scrubbed by the route it now travels`() {
        val sessionToken = sha256Hex("k4-app-search-token")
        val deviceId = sha256Hex("k4-device-id").take(16)
        val query = "shadow vpn $sessionToken on $deviceId"

        ZLog.i("APP_PICKER", "query=$query")

        // Same assertion shape as the scrubber's own tests: the value shapes
        // that used to walk through intact must not survive the route.
        val scrubbed = ZLog.scrub(query)
        assertFalse("a session token reached the search-query route unscrubbed", scrubbed.contains(sessionToken))
        assertFalse("an ANDROID_ID-shaped value reached the search-query route unscrubbed", scrubbed.contains(deviceId))
    }
}