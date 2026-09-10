package com.shadowmesh.app.identity

import android.app.Application
import android.util.Log
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import com.shadowmesh.core_vpn.SecureStorage
import com.shadowmesh.core_vpn.repository.VpnRepository
import com.shadowmesh.core_vpn.DeviceProvider
import com.shadowmesh.core_vpn.CoreUtils
import com.shadowmesh.core_vpn.CoreServiceProvider
import com.shadowmesh.core_vpn.NetworkClient
import com.shadowmesh.core_vpn.Config
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import uniffi.shadowmesh.*
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream

/**
 * High-fidelity fake for [CredentialManagerProvider].
 */
class FakeCredentialManagerProvider : CredentialManagerProvider {
    var getCredentialResult: Result<GetCredentialResponse>? = null
    var createCredentialResult: Result<androidx.credentials.CreateCredentialResponse>? = null
    
    override suspend fun getCredential(
        context: android.content.Context,
        request: GetCredentialRequest
    ): GetCredentialResponse {
        return getCredentialResult?.getOrThrow() ?: throw Exception("Fake not configured")
    }

    override suspend fun createCredential(
        context: android.content.Context,
        request: androidx.credentials.CreatePublicKeyCredentialRequest
    ): androidx.credentials.CreateCredentialResponse {
        return createCredentialResult?.getOrThrow() ?: throw Exception("Fake not configured")
    }
}

/**
 * High-fidelity fake for [CoreServiceProvider].
 */
class FakeCoreServiceProvider : CoreServiceProvider {
    var powSolution: PoWSolution? = null
    var decryptedQrPayload: ByteArray? = null

    override fun solvePoW(challenge: PoWChallenge): PoWSolution {
        return powSolution ?: throw Exception("Fake PoW not configured")
    }

    override fun decryptQrPairing(ciphertext: ByteArray, pin: String): ByteArray {
        return decryptedQrPayload ?: throw Exception("Fake Decryption not configured")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class IdentityManagerTest {

    private val application = mockk<Application>(relaxed = true)
    private val vpnRepository = mockk<VpnRepository>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val apiClient = mockk<ApiClient>(relaxed = true)
    private val deviceProvider = mockk<DeviceProvider>(relaxed = true)
    private val networkClient = mockk<NetworkClient>(relaxed = true)
    private val credentialManagerProvider = FakeCredentialManagerProvider()
    private val coreServiceProvider = FakeCoreServiceProvider()
    private val sharedPrefs = mockk<android.content.SharedPreferences>(relaxed = true)
    private val editor = mockk<android.content.SharedPreferences.Editor>(relaxed = true)

    private lateinit var identityManager: IdentityManager
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        mockkObject(CoreUtils)
        mockkStatic(android.util.Base64::class)

        every { Log.e(any<String>(), any<String>(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0

        every { vpnRepository.apiClient } returns apiClient
        every { secureStorage.prefs } returns sharedPrefs
        every { sharedPrefs.edit() } returns editor
        
        every { editor.putString(any(), any()) } returns editor
        every { editor.putInt(any(), any()) } returns editor
        every { editor.putLong(any(), any()) } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.apply() } just Runs

        every { deviceProvider.model } returns "test-model"
        every { deviceProvider.osVersion } returns "14"
        every { deviceProvider.supportedAbis } returns arrayOf("arm64-v8a")
        every { CoreUtils.getAndroidDeviceId(any()) } returns "device-id"
        every { CoreUtils.getHardwareFingerprint(any()) } returns "hw-fingerprint"
        
        identityManager = IdentityManager(
            application, 
            vpnRepository, 
            secureStorage,
            deviceProvider,
            credentialManagerProvider,
            coreServiceProvider,
            networkClient,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `activate success flow`() = runTest {
        val code = "ABCDE-FGHIJ-KLMNO-PQRST-UVWXY"
        val challenge = ActivationChallenge("challenge", 10u)
        val response = ActivationResponse(
            message = "Success",
            token = "auth-token",
            plan = "Pro",
            expiresAt = "2026-12-31",
            remainingDays = 30L,
            subscriptionNotice = "",
            devicesRemaining = 2,
            vpnConfig = null,
            isCanary = null,
            serverLocation = null
        )

        coEvery { apiClient.requestActivationChallenge(any()) } returns challenge
        coreServiceProvider.powSolution = PoWSolution("solution", "challenge")
        coEvery { apiClient.activate(any()) } returns response
        
        val result = identityManager.activate(code)
        
        assertTrue(result.isSuccess)
        verify { vpnRepository.activate(code, "auth-token", "Pro", 2, 30L) }
        verify { editor.putString(Config.KEY_AUTH_TOKEN, "auth-token") }
    }

    @Test
    fun `activate failure flow - invalid token`() = runTest {
        val response = ActivationResponse(
            message = "Fail", token = null, plan = null, expiresAt = "", 
            remainingDays = 0, subscriptionNotice = "", devicesRemaining = 0, 
            vpnConfig = null, isCanary = null, serverLocation = null
        )

        coEvery { apiClient.requestActivationChallenge(any()) } returns ActivationChallenge("c", 1u)
        coreServiceProvider.powSolution = PoWSolution("s", "c")
        coEvery { apiClient.activate(any()) } returns response
        
        val result = identityManager.activate("INVALID")
        assertTrue(result.isFailure)
        assertEquals("Activation Failed: Invalid code", result.exceptionOrNull()?.message)
    }

    @Test
    fun `activate failure flow - exception`() = runTest {
        coEvery { apiClient.requestActivationChallenge(any()) } throws Exception("Network Error")
        val result = identityManager.activate("CODE")
        assertTrue(result.isFailure)
        assertEquals("Network Error", result.exceptionOrNull()?.message)
    }

    @Test
    fun `logout success`() = runTest {
        identityManager.logout()
        coVerify { apiClient.revokeSession() }
        verify { editor.remove(Config.KEY_ACTIVATION_CODE) }
        verify { editor.remove(Config.KEY_AUTH_TOKEN) }
    }

    @Test
    fun `logout handles revocation failure`() = runTest {
        coEvery { apiClient.revokeSession() } throws Exception("Revoke Failed")
        identityManager.logout()
        verify { editor.remove(Config.KEY_AUTH_TOKEN) }
    }

    @Test
    fun `setupMfaBegin returns QR and secret`() = runTest {
        val jsonResponse = """{"qr_code": "base64qr", "secret": "mfa-secret"}"""
        coEvery { apiClient.setupTotpBegin() } returns jsonResponse
        
        val (qr, secret) = identityManager.setupMfaBegin()
        
        assertEquals("base64qr", qr)
        assertEquals("mfa-secret", secret)
    }

    @Test
    fun `setupMfaFinish success`() = runTest {
        identityManager.setupMfaFinish("123456")
        coVerify { apiClient.setupTotpFinish("123456") }
    }

    @Test
    fun `checkQrStatus success`() = runTest {
        coEvery { apiClient.qrStatus("token") } returns "AUTHORIZED"
        val status = identityManager.checkQrStatus("token")
        assertEquals("AUTHORIZED", status)
    }

    @Test
    fun `generateQrPairingToken success`() = runTest {
        coEvery { apiClient.qrGenerate(any(), any(), any(), any(), any()) } returns "token"
        val token = identityManager.generateQrPairingToken()
        assertEquals("token", token)
    }

    @Test
    fun `registerPasskey success`() = runTest {
        // Minimal valid WebAuthn creation options: the AndroidX Credential
        // Manager validates this JSON (user.name/user.id/rp.name required).
        val creationOptionsJson =
            """{"challenge":"challenge","rp":{"name":"ShadowMesh"},""" +
                """"user":{"id":"dXNlcg","name":"user","displayName":"user"},""" +
                """"pubKeyCredParams":[{"type":"public-key","alg":-7}],"timeout":60000,"attestation":"none"}"""
        val registrationResponseJson = """{"id":"cred-id","rawId":"cred-id","response":{},"type":"public-key"}"""

        coEvery { apiClient.passkeyRegisterBegin("user") } returns creationOptionsJson

        val mockResponse = mockk<androidx.credentials.CreatePublicKeyCredentialResponse>()
        val mockBundle = mockk<android.os.Bundle>()
        every { mockResponse.data } returns mockBundle
        every {
            mockBundle.getString("androidx.credentials.BUNDLE_KEY_REGISTRATION_RESPONSE_JSON")
        } returns registrationResponseJson
        credentialManagerProvider.createCredentialResult =
            Result.success(mockResponse)

        coEvery { apiClient.passkeyRegisterFinish("user", registrationResponseJson) } returns Unit

        val result = identityManager.registerPasskey("user", application)
        assertTrue(result.isSuccess)
        coVerify { apiClient.passkeyRegisterFinish("user", registrationResponseJson) }
    }

    @Test
    fun `loginWithPasskey failure - invalid code`() = runTest {
        val result = identityManager.loginWithPasskey("SHORT", application)
        assertTrue(result.isFailure)
        assertEquals("Invalid Token for Passkey", result.exceptionOrNull()?.message)
    }

    @Test
    fun `loginWithPasskey failure - missing bundle key`() = runTest {
        val code = "ABCDE-FGHIJ-KLMNO-PQRST-UVWXY"
        coEvery { apiClient.passkeyLoginStart(any()) } returns "{}"
        
        val mockResponse = mockk<GetCredentialResponse>()
        val mockCredential = mockk<androidx.credentials.Credential>()
        val mockBundle = mockk<android.os.Bundle>()
        every { mockResponse.credential } returns mockCredential
        every { mockCredential.data } returns mockBundle
        every { mockBundle.getString(any()) } returns null // Bundle empty
        
        credentialManagerProvider.getCredentialResult = Result.success(mockResponse)
        
        val result = identityManager.loginWithPasskey(code, application)
        assertTrue(result.isFailure)
        assertEquals("Failed to retrieve Passkey credential", result.exceptionOrNull()?.message)
    }

    @Test
    fun `loginWithPasskey success`() = runTest {
        val code = "ABCDE-FGHIJ-KLMNO-PQRST-UVWXY"
        val sanitizedCode = "ABCDEFGHIJKLMNOPQRSTUVWXY"
        val response = ActivationResponse(
            message = "Success", token = "passkey-token", plan = "Pro", 
            expiresAt = "", remainingDays = 10L, subscriptionNotice = "", 
            devicesRemaining = 1, vpnConfig = null, isCanary = null, serverLocation = null
        )

        coEvery { apiClient.passkeyLoginStart(any()) } returns "{}"
        
        val mockResponse = mockk<GetCredentialResponse>()
        val mockCredential = mockk<androidx.credentials.Credential>()
        val mockBundle = mockk<android.os.Bundle>()
        every { mockResponse.credential } returns mockCredential
        every { mockCredential.data } returns mockBundle
        every { mockBundle.getString(any()) } returns "resp-json"
        
        credentialManagerProvider.getCredentialResult = Result.success(mockResponse)
        coEvery { apiClient.passkeyLoginFinish(any(), any()) } returns response
        
        val result = identityManager.loginWithPasskey(code, application)
        
        assertTrue(result.isSuccess)
        assertEquals(response, result.getOrNull())
        verify { vpnRepository.activate(sanitizedCode, "passkey-token", "Pro", 1, 10L) }
    }

    @Test
    fun `pairWithDesktop success flow`() = runTest {
        val scannedCode = "base64payload"
        val pin = "123456"
        val decryptedPayload = """{"server_url": "https://test.com", "secret": "handshake-secret"}""".toByteArray()
        
        every { android.util.Base64.decode(scannedCode, any()) } returns "ciphertext".toByteArray()
        coreServiceProvider.decryptedQrPayload = decryptedPayload
        coEvery { networkClient.post(any(), any()) } returns 200

        val result = identityManager.pairWithDesktop(scannedCode, pin)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `pairWithDesktop failure - decode error`() = runTest {
        every { android.util.Base64.decode(any<String>(), any()) } throws Exception("Decode error")
        val result = identityManager.pairWithDesktop("bad", "123")
        assertTrue(result.isFailure)
    }

    @Test
    fun `pairWithDesktop failure - server rejection`() = runTest {
        every { android.util.Base64.decode(any<String>(), any()) } returns "ciphertext".toByteArray()
        coreServiceProvider.decryptedQrPayload = """{"server_url": "u", "secret": "s"}""".toByteArray()
        coEvery { networkClient.post(any(), any()) } returns 401
        
        val result = identityManager.pairWithDesktop("code", "pin")
        assertTrue(result.isFailure)
        assertEquals("Pairing rejected by server", result.exceptionOrNull()?.message)
    }
}
