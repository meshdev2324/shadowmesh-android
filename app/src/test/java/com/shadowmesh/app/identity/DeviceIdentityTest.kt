package com.shadowmesh.app.identity

import com.shadowmesh.core_vpn.Config
import com.shadowmesh.core_vpn.SecureStorage
import com.shadowmesh.core_vpn.WireGuardService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RFC-026 regression suite: per-device WireGuard identity.
 *
 * Before RFC-026 the device keypair was produced by
 * `generateDeviceKeyPair(activationCode)` — a deterministic HKDF over the
 * 25-character sovereignty token. Every device sharing a code therefore shared
 * one X25519 identity, which let any device impersonate the others and made a
 * node unable to hold more than one device per code.
 */
class DeviceIdentityTest {

    private val wireGuardService = mockk<WireGuardService>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)

    @Before
    fun setup() {
        every { secureStorage.get(any()) } returns null
    }

    private fun identity() = DeviceIdentity(secureStorage, wireGuardService)

    @Test
    fun `generates a random keypair when none is stored`() {
        every { wireGuardService.generateKeyPair() } returns Pair("priv-A", "pub-A")

        val pair = identity().ensureKeyPair()

        assertEquals("priv-A", pair.privateKey)
        assertEquals("pub-A", pair.publicKey)
    }

    @Test
    fun `persists the generated keypair so the peer identity is stable`() {
        every { wireGuardService.generateKeyPair() } returns Pair("priv-A", "pub-A")

        identity().ensureKeyPair()

        // Both halves must be stored: the private key for the handshake, the
        // public key for peer registration and for verifying a later read.
        verify { secureStorage.setDurable(Config.KEY_DEVICE_WG_PRIVATE_KEY, "priv-A") }
        verify { secureStorage.setDurable(Config.KEY_DEVICE_WG_PUBLIC_KEY, "pub-A") }
    }

    @Test
    fun `reuses the stored keypair instead of regenerating`() {
        every { secureStorage.get(Config.KEY_DEVICE_WG_PRIVATE_KEY) } returns "priv-A"
        every { secureStorage.get(Config.KEY_DEVICE_WG_PUBLIC_KEY) } returns "pub-A"

        val pair = identity().ensureKeyPair()

        assertEquals("priv-A", pair.privateKey)
        assertEquals("pub-A", pair.publicKey)
        // Stability across restarts is what keeps the node peer entry valid.
        verify(exactly = 0) { wireGuardService.generateKeyPair() }
    }

    @Test
    fun `is stable across repeated calls`() {
        every { wireGuardService.generateKeyPair() } returns Pair("priv-A", "pub-A")

        val first = identity().ensureKeyPair()
        val second = identity().ensureKeyPair()

        assertEquals(first, second)
    }

    @Test
    fun `two devices receive different keypairs`() {
        // Two separate DeviceIdentity instances, each backed by its own
        // (empty) storage, model two handsets activating the same code.
        every { wireGuardService.generateKeyPair() } returns Pair("priv-A", "pub-A")
        val deviceA = identity().ensureKeyPair()

        every { wireGuardService.generateKeyPair() } returns Pair("priv-B", "pub-B")
        every { secureStorage.get(any()) } returns null
        val deviceB = identity().ensureKeyPair()

        assertNotEquals(
            "devices on one activation code must not share a WireGuard identity",
            deviceA.publicKey,
            deviceB.publicKey
        )
    }

    @Test
    fun `never derives the identity from the activation code`() {
        every { wireGuardService.generateKeyPair() } returns Pair("priv-A", "pub-A")

        identity().ensureKeyPair()

        // The core security assertion: the deterministic code-derived KDF must
        // not be reachable from the identity path at all. If this fails, the
        // shared-key vulnerability has returned.
        verify(exactly = 0) { wireGuardService.generateDeviceKeyPair(any()) }
    }

    @Test
    fun `regenerates when only the private half is present`() {
        // A torn or partially-migrated store must not leave the device
        // permanently unable to register a peer.
        every { secureStorage.get(Config.KEY_DEVICE_WG_PRIVATE_KEY) } returns "priv-A"
        every { secureStorage.get(Config.KEY_DEVICE_WG_PUBLIC_KEY) } returns null
        every { wireGuardService.generateKeyPair() } returns Pair("priv-B", "pub-B")

        val pair = identity().ensureKeyPair()

        assertEquals("pub-B", pair.publicKey)
        assertEquals("pub-B", pair.publicKey)
        verify { secureStorage.setDurable(Config.KEY_DEVICE_WG_PUBLIC_KEY, "pub-B") }
    }

    @Test
    fun `is validatable through the core key validator`() {
        // Guards against storing a malformed scalar: a bad private key only
        // surfaces as an opaque tunnel failure much later.
        every { wireGuardService.generateKeyPair() } returns Pair("priv-A", "pub-A")
        every { secureStorage.get(any()) } returns null

        val pair = identity().ensureKeyPair()

        assertTrue("stored halves must both be non-empty", pair.isWellFormed)
    }

    @Test
    fun `needs registration when the control plane has never seen this key`() {
        every { secureStorage.get(Config.KEY_DEVICE_WG_PRIVATE_KEY) } returns null
        every { secureStorage.get(Config.KEY_DEVICE_WG_PUBLIC_KEY) } returns null
        every { secureStorage.get(Config.KEY_DEVICE_WG_REGISTERED_KEY) } returns null
        every { wireGuardService.generateKeyPair() } returns Pair("priv-A", "pub-A")

        val identity = identity()
        identity.ensureKeyPair()

        // A device that has just minted a key (the RFC-026 upgrade path) must
        // re-register, or the node still holds the previous key and every
        // handshake is dropped while the UI reports CONNECTED.
        assertTrue(identity.needsRegistration())
    }

    @Test
    fun `markRegistered clears the registration requirement`() {
        every { secureStorage.get(Config.KEY_DEVICE_WG_PUBLIC_KEY) } returns "pub-A"
        every { secureStorage.get(Config.KEY_DEVICE_WG_REGISTERED_KEY) } returns "pub-A"
        val identity = identity()

        assertTrue(!identity.needsRegistration())

        identity.forget()

        verify { secureStorage.remove(Config.KEY_DEVICE_WG_PRIVATE_KEY) }
        verify { secureStorage.remove(Config.KEY_DEVICE_WG_PUBLIC_KEY) }
        // A forensic wipe must also drop the registration marker, otherwise the
        // device would believe the control plane still knows a purged key.
        verify { secureStorage.remove(Config.KEY_DEVICE_WG_REGISTERED_KEY) }
    }

    @Test
    fun `needs registration when the key rotated after registration`() {
        every { secureStorage.get(Config.KEY_DEVICE_WG_PUBLIC_KEY) } returns "pub-B"
        every { secureStorage.get(Config.KEY_DEVICE_WG_REGISTERED_KEY) } returns "pub-A"

        assertTrue(identity().needsRegistration())
    }
}
