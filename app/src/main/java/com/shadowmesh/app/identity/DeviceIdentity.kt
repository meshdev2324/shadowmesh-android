package com.shadowmesh.app.identity

import com.shadowmesh.core_vpn.Config
import com.shadowmesh.core_vpn.SecureStorage
import com.shadowmesh.core_vpn.WireGuardService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RFC-026: the device's own WireGuard identity.
 *
 * ## Why this class exists
 *
 * The client used to derive its WireGuard keypair with
 * `generateDeviceKeyPair(activationCode)` — a deterministic HKDF-SHA256 over the
 * 25-character sovereignty token, producing the X25519 scalar directly. Because
 * the token is *designed* to be shared (a Family plan covers ten handsets), every
 * device on one token computed the same private key. Consequences:
 *
 *  1. Any device could compute any other device's private key offline and
 *     authenticate as it — the mesh IP and traffic of one device became
 *     forgeable by another, collapsing the anonymity the product is sold on.
 *  2. WireGuard matches peers on the public key, so a node could only hold one
 *     entry per token. The fleet was unrepresentable, which is also why
 *     RFC-025 peer distribution alone could not have fixed it.
 *  3. Per-device accounting was impossible, and revoking one device evicted all.
 *
 * ## The fix
 *
 * The identity is 32 bytes from the platform CSPRNG (the core's existing
 * `generate_wireguard_keys`), generated once and persisted, and is never a
 * function of the activation code. Persistence uses
 * [SecureStorage.setDurable] so the identity cannot be lost to an async write,
 * which would otherwise mint a new peer and change the tunnel address.
 *
 * ## Threat model
 *
 * At rest the store is `EncryptedSharedPreferences` under a Keystore-held
 * [androidx.security.crypto.MasterKey], so the scalar is TEE/StrongBox-protected
 * where the device offers it. A non-exportable *in-keystore* Curve25519 key is
 * deliberately not used: WireGuard performs the Diffie-Hellman in-process and
 * needs the raw scalar in memory regardless, and Android's Keystore does not
 * expose X25519. This is therefore a strict improvement over the previous
 * scheme — it removes a secret that was reproducible from data shared across
 * devices — without introducing a new exposure class.
 *
 * Lifecycle: generated on first use, reused on every later call (this is what
 * keeps the node peer entry valid), and regenerated only if app data is wiped
 * or the store is torn, in which case the device legitimately re-registers as a
 * new peer under a new tunnel address.
 */
@Singleton
class DeviceIdentity @Inject constructor(
    private val secureStorage: SecureStorage,
    private val wireGuardService: WireGuardService,
) {

    /**
     * A WireGuard keypair belonging to this device alone.
     *
     * [privateKey] is base64 X25519 scalar material. It is treated as secret:
     * never log it, never include it in an error message, and never derive it
     * from a value the user or their plan-mates can see.
     */
    data class DeviceKeyPair(
        val privateKey: String,
        val publicKey: String,
    ) {
        /** True when both halves are present and non-blank. */
        val isWellFormed: Boolean
            get() = privateKey.isNotBlank() && publicKey.isNotBlank()
    }

    /**
     * Returns this device's WireGuard identity, generating and persisting one on
     * first use.
     *
     * Thread-safe: the check-then-write is guarded so two concurrent callers
     * cannot mint two identities and race to overwrite each other, which would
     * leave the control plane holding a peer key the device no longer uses.
     */
    fun ensureKeyPair(): DeviceKeyPair = synchronized(this) {
        val storedPrivate = secureStorage.get(Config.KEY_DEVICE_WG_PRIVATE_KEY)
        val storedPublic = secureStorage.get(Config.KEY_DEVICE_WG_PUBLIC_KEY)

        // Both halves must be present. A half-written store (interrupted write,
        // partial migration) would otherwise yield a peer the node cannot match.
        if (!storedPrivate.isNullOrBlank() && !storedPublic.isNullOrBlank()) {
            return@synchronized DeviceKeyPair(storedPrivate, storedPublic)
        }

        // CSPRNG, never the activation code.
        val (privateKey, publicKey) = wireGuardService.generateKeyPair()

        // Durable: losing this write would change the device's identity and its
        // mesh address on the next launch.
        secureStorage.setDurable(Config.KEY_DEVICE_WG_PRIVATE_KEY, privateKey)
        secureStorage.setDurable(Config.KEY_DEVICE_WG_PUBLIC_KEY, publicKey)

        DeviceKeyPair(privateKey, publicKey)
    }

    /**
     * True when the control plane has not yet been told about this device's
     * current identity.
     *
     * The tunnel cannot succeed in this state: WireGuard matches peers on the
     * public key, so a node that still holds the previous key will drop every
     * handshake. This is exactly the upgrade path in RFC-026 §7 — a device
     * provisioned under the old code-derived key mints a new one, and must be
     * re-registered once for the node to learn it.
     */
    fun needsRegistration(): Boolean {
        val current = secureStorage.get(Config.KEY_DEVICE_WG_PUBLIC_KEY)
        if (current.isNullOrBlank()) return true
        return secureStorage.get(Config.KEY_DEVICE_WG_REGISTERED_KEY) != current
    }

    /**
     * Records that the control plane now holds this device's public key.
     * Called immediately after a successful activation, which is the only
     * request that carries the key on the wire.
     */
    fun markRegistered() {
        val current = secureStorage.get(Config.KEY_DEVICE_WG_PUBLIC_KEY)
        if (!current.isNullOrBlank()) {
            secureStorage.setDurable(Config.KEY_DEVICE_WG_REGISTERED_KEY, current)
        }
    }

    /**
     * Discards the stored identity so the next [ensureKeyPair] mints a new one.
     *
     * Only for forensic wipe paths, where retaining a tunnel credential would
     * defeat the wipe. Requires the device to re-register as a new peer.
     */
    fun forget() {
        synchronized(this) {
            secureStorage.remove(Config.KEY_DEVICE_WG_PRIVATE_KEY)
            secureStorage.remove(Config.KEY_DEVICE_WG_PUBLIC_KEY)
            secureStorage.remove(Config.KEY_DEVICE_WG_REGISTERED_KEY)
        }
    }
}
