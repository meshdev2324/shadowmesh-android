package com.shadowmesh.app.vpn

/**
 * How a scanned pairing QR should be interpreted.
 *
 * ## The defect this locks down
 *
 * The desktop (RFC-018) renders `shadowmesh://pair/<token>`: a **plain** session
 * token, and it then polls the server waiting for this phone to approve. The
 * Android scanner, however, fed every scanned pairing code through
 * `Base64.decode` + `decryptQrPairing(ciphertext, pin)`, which requires a
 * PIN-encrypted blob the desktop never produces. Pairing therefore failed with
 * a raw crypto exception surfaced as "Pairing Error: …", and the flow that was
 * actually meant to be used — approving a pending session with
 * `authorizeQrSession` — was never called at all.
 *
 * A second, more serious problem: the plain-token branch approved immediately on
 * scan. Anything able to display a QR would then be authorised by a phone that
 * merely looked at it. Approving a new device must be a deliberate act.
 */
sealed interface PairingScan {
    /** A plain RFC-018 session token awaiting approval by this device. */
    data class SessionToken(val token: String) : PairingScan

    /** A PIN-encrypted payload carrying the pairing details. */
    data class EncryptedPayload(val ciphertext: String, val requiresPin: Boolean) : PairingScan
}

/**
 * Classifies a scanned pairing code. Pure, so the routing is unit-testable and
 * cannot silently regress to "always try to decrypt".
 */
object PairingScanPolicy {

    private const val URI_PREFIX = "shadowmesh://pair/"

    fun classify(scanned: String): PairingScan {
        val raw = scanned.trim()
        if (raw.isEmpty()) {
            return PairingScan.EncryptedPayload(ciphertext = raw, requiresPin = true)
        }

        // Explicit RFC-018 pairing URI: a session token to approve.
        if (raw.startsWith(URI_PREFIX, ignoreCase = true)) {
            val token = raw.substring(URI_PREFIX.length).trim()
            return if (token.isEmpty()) {
                PairingScan.EncryptedPayload(ciphertext = raw, requiresPin = true)
            } else {
                PairingScan.SessionToken(token)
            }
        }

        // A bare token is what the scanner hands over after it has already
        // stripped the scheme prefix.
        if (isSessionToken(raw)) return PairingScan.SessionToken(raw)

        return PairingScan.EncryptedPayload(ciphertext = raw, requiresPin = true)
    }

    /**
     * True when a scanned value is shaped like a pairing session token.
     *
     * The server mints these with `uuid::Uuid::new_v4().to_string()`
     * (services/server/src/api/handlers/auth.rs), i.e. the canonical 8-4-4-4-12
     * hyphenated form. Matching that exact shape is deliberate: a loose
     * "hex or base64url" heuristic also accepts a 25-char activation code — its
     * hyphen-separated groups are valid base64url — which would let a
     * screenshot of a sovereignty code be replayed as approval to enrol a
     * stranger's device.
     */
    private fun isSessionToken(raw: String): Boolean {
        if (raw.length != UUID_LENGTH) return false
        val groups = raw.split('-')
        if (groups.size != 5) return false
        val widths = intArrayOf(8, 4, 4, 4, 12)
        for (i in groups.indices) {
            if (groups[i].length != widths[i]) return false
            if (!groups[i].all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return false
        }
        return true
    }

    private const val UUID_LENGTH = 36
}
