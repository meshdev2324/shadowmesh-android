package com.shadowmesh.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A QR code is an untrusted input from a device that may not be yours. These
 * rules decide whether a scan means "approve this device" or "decrypt this blob",
 * and getting them wrong either breaks legitimate pairing or lets a stranger
 * enrol a device just by showing a QR.
 */
class PairingScanPolicyTest {

    // Exactly what the server mints: uuid::Uuid::new_v4().to_string().
    private val serverToken = "550e8400-e29b-41d4-a716-446655440000"

    @Test
    fun `a full pairing uri is a session token to approve`() {
        val scan = PairingScanPolicy.classify("shadowmesh://pair/$serverToken")
        assertEquals(PairingScan.SessionToken(serverToken), scan)
    }

    @Test
    fun `the uri scheme is matched case insensitively`() {
        val scan = PairingScanPolicy.classify("SHADOWMESH://PAIR/$serverToken")
        assertEquals(PairingScan.SessionToken(serverToken), scan)
    }

    @Test
    fun `a bare uuid token is a session token`() {
        // What the scanner hands over after stripping the scheme prefix.
        val scan = PairingScanPolicy.classify(serverToken)
        assertEquals(PairingScan.SessionToken(serverToken), scan)
    }

    @Test
    fun `a hex token is a session token`() {
        val hex = "a3f19c7b-e40d-4825-6f1c-0ab93d7e5f21"
        assertEquals(PairingScan.SessionToken(hex), PairingScanPolicy.classify(hex))
    }

    @Test
    fun `an encrypted payload is not mistaken for a token`() {
        // Base64 with padding and a trailing "==" is a ciphertext, not a token.
        val blob = "U2FsdGVkX1+abcdefghijklmnop=="
        val scan = PairingScanPolicy.classify(blob)
        assertTrue("must fall through to the decrypt path", scan is PairingScan.EncryptedPayload)
        assertTrue((scan as PairingScan.EncryptedPayload).requiresPin)
    }

    @Test
    fun `an activation code is never treated as a pairing approval`() {
        // A 25-char sovereignty token must not be interpretable as "approve
        // this device", or a screenshot of a code could authorise a stranger.
        val activationCode = "ABCDE-FGHIJ-KLMNO-PQRST-UVWXY"
        val scan = PairingScanPolicy.classify(activationCode)
        assertTrue(
            "activation codes must not authorise a device",
            scan is PairingScan.EncryptedPayload,
        )
    }

    @Test
    fun `a short value is never a session token`() {
        val scan = PairingScanPolicy.classify("abc123")
        assertTrue(scan is PairingScan.EncryptedPayload)
    }

    @Test
    fun `empty input is handled without throwing`() {
        assertTrue(PairingScanPolicy.classify("") is PairingScan.EncryptedPayload)
        assertTrue(PairingScanPolicy.classify("   ") is PairingScan.EncryptedPayload)
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        val scan = PairingScanPolicy.classify("  $serverToken  ")
        assertEquals(PairingScan.SessionToken(serverToken), scan)
    }
}
