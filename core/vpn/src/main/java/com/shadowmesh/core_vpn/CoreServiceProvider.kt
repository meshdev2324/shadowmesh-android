package com.shadowmesh.core_vpn

import uniffi.shadowmesh.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Interface for accessing ShadowMesh core functions (Rust/UniFFI).
 * This allows for faking the core cryptographic operations in tests.
 */
interface CoreServiceProvider {
    fun solvePoW(challenge: PoWChallenge): PoWSolution
    fun decryptQrPairing(ciphertext: ByteArray, pin: String): ByteArray
}

/**
 * Implementation that calls the actual UniFFI generated functions.
 */
@Singleton
class DefaultCoreServiceProvider @Inject constructor() : CoreServiceProvider {
    override fun solvePoW(challenge: PoWChallenge): PoWSolution {
        return solvePow(challenge)
    }

    override fun decryptQrPairing(ciphertext: ByteArray, pin: String): ByteArray {
        return decryptQrPairingPayload(ciphertext, pin)
    }
}
