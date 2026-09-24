package com.shadowmesh.app.identity

import android.app.Application
import androidx.core.content.edit
import android.os.Build
import com.shadowmesh.app.util.ZLog
import com.shadowmesh.core_vpn.Config
import com.shadowmesh.core_vpn.CoreUtils
import com.shadowmesh.core_vpn.SecureStorage
import com.shadowmesh.core_vpn.DeviceProvider
import com.shadowmesh.core_vpn.repository.VpnRepository
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import uniffi.shadowmesh.*
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "IdentityManager"

/**
 * Manages user identity, activation, and secure session lifecycle.
 * SOP 09 §3: Specialized manager for identity logic.
 *
 * This manager handles device authorization, Proof-of-Work (PoW) challenges for anti-spam,
 * Multi-Factor Authentication (MFA) setup, and desktop device pairing.
 */
@Singleton
class IdentityManager @Inject constructor(
    private val application: Application,
    private val vpnRepository: VpnRepository,
    private val secureStorage: SecureStorage,
    private val deviceProvider: DeviceProvider,
    private val credentialManagerProvider: CredentialManagerProvider,
    private val coreServiceProvider: com.shadowmesh.core_vpn.CoreServiceProvider,
    private val networkClient: com.shadowmesh.core_vpn.NetworkClient,
    @com.shadowmesh.app.di.IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val securePrefs = secureStorage.prefs

    /**
     * Activates the device using a 25-character license code.
     * This flow includes solving a cryptographic PoW challenge to prevent brute-force activation.
     *
     * @param code The activation code provided by the user.
     * @return [Result] containing the [ActivationResponse] on success.
     */
    suspend fun activate(code: String): Result<ActivationResponse> = withContext(ioDispatcher) {
        val client = vpnRepository.apiClient
        try {
            val deviceId = CoreUtils.getAndroidDeviceId(application)
            ZLog.i(TAG, "Initiating activation for device: $deviceId")

            val activationChallenge = client.requestActivationChallenge(deviceId)

            val powChallenge = PoWChallenge(
                challenge = activationChallenge.challenge,
                difficulty = activationChallenge.difficulty
            )

            val powSolution = coreServiceProvider.solvePoW(powChallenge)
            client.setPowSolution(powSolution.solution, powSolution.challenge)

            val req = ActivationRequest(
                code = code,
                deviceName = deviceProvider.model,
                deviceType = "android",
                deviceId = deviceId,
                hardwareFingerprint = CoreUtils.getHardwareFingerprint(application),
                publicKey = null,
                deepFingerprint = null,
                oobNonce = null,
                oobSig = null,
                oobTs = null
            )

            val response = client.activate(req)
            if (response.token != null) {
                saveActivationState(code, response)
                vpnRepository.activate(
                    code = code,
                    token = response.token,
                    plan = response.plan,
                    devicesRemaining = response.devicesRemaining,
                    remainingDays = response.remainingDays
                )
                Result.success(response)
            } else {
                Result.failure(Exception("Activation Failed: Invalid code"))
            }
        } catch (e: Exception) {
            ZLog.e(TAG, "Activation error", e)
            Result.failure(e)
        }
    }

    private fun saveActivationState(code: String, response: ActivationResponse) {
        securePrefs.edit {
            putString(Config.KEY_ACTIVATION_CODE, code)
            putString(Config.KEY_AUTH_TOKEN, response.token)
            putString(Config.KEY_PLAN_NAME, response.plan ?: "Solo")
            putInt(Config.KEY_DEVICES_REMAINING, response.devicesRemaining)
            putLong(Config.KEY_REMAINING_DAYS, response.remainingDays)
        }
        response.token?.let { vpnRepository.apiClient.setAuthToken(it) }
    }

    /**
     * Revokes the current session and clears local authorization state.
     */
    suspend fun logout() = withContext(ioDispatcher) {
        try {
            vpnRepository.apiClient.revokeSession()
        } catch (e: Exception) {
            ZLog.w(TAG, "Session revocation failed")
        }

        securePrefs.edit {
            remove(Config.KEY_ACTIVATION_CODE)
            remove(Config.KEY_AUTH_TOKEN)
        }
    }

    /**
     * Initiates the TOTP-based Multi-Factor Authentication setup.
     * @return A Pair containing the QR code (Base64) and the raw secret.
     */
    suspend fun setupMfaBegin(): Pair<String, String> = withContext(ioDispatcher) {
        val responseJson = vpnRepository.apiClient.setupTotpBegin()
        val json = Json.parseToJsonElement(responseJson).jsonObject
        val qrCode = json["qr_code"]?.jsonPrimitive?.content ?: ""
        val secret = json["secret"]?.jsonPrimitive?.content ?: ""
        qrCode to secret
    }

    /**
     * Completes MFA setup by verifying the first 6-digit code.
     */
    suspend fun setupMfaFinish(code: String) = withContext(ioDispatcher) {
        vpnRepository.apiClient.setupTotpFinish(code)
    }

    /**
     * Generates a temporary token for pairing this mobile device with a desktop client.
     */
    suspend fun generateQrPairingToken(): String = withContext(ioDispatcher) {
        vpnRepository.apiClient.qrGenerate(
            deviceId = CoreUtils.getAndroidDeviceId(application),
            deviceName = deviceProvider.model,
            osName = "Android",
            osVersion = deviceProvider.osVersion,
            arch = deviceProvider.supportedAbis.firstOrNull() ?: "unknown"
        )
    }

    /**
     * Checks the status of a pending QR pairing request.
     */
    suspend fun checkQrStatus(token: String): String = withContext(ioDispatcher) {
        vpnRepository.apiClient.qrStatus(token)
    }

    /**
     * RFC-018: approves a pairing session (desktop-as-new-device) with this
     * device's identity. The server mints a member key into the session for
     * the waiting device; Team-plan guard applies server-side.
     */
    suspend fun authorizeQrSession(token: String) = withContext(ioDispatcher) {
        vpnRepository.apiClient.qrAuthorize(token)
    }

    /**
     * RFC-018: issues a 25-char member key bound to this device's plan —
     * the credential a new device consumes to pair (Telegram-style flow).
     */
    suspend fun issueMemberToken(label: String): String = withContext(ioDispatcher) {
        vpnRepository.apiClient.generateMemberToken(label)
    }

    /**
     * Begins registration of a hardware-bound Passkey for passwordless login.
     */
    suspend fun registerPasskey(userId: String, context: android.content.Context): Result<Unit> =
        withContext(ioDispatcher) {
            val client = vpnRepository.apiClient
            try {
                // 1. Get challenge from server
                val challengeJson = client.passkeyRegisterBegin(userId)

                // 2. Create credential using Android Credential Manager
                val createOption = androidx.credentials.CreatePublicKeyCredentialRequest(challengeJson)
                val result = credentialManagerProvider.createCredential(context, createOption)

                val responseJson = result.data.getString("androidx.credentials.BUNDLE_KEY_REGISTRATION_RESPONSE_JSON")
                    ?: return@withContext Result.failure(Exception("Failed to get registration response"))

                // 3. Finish registration on server
                client.passkeyRegisterFinish(userId, responseJson)

                securePrefs.edit().putBoolean(Config.KEY_PASSKEY_ENABLED, true).apply()
                Result.success(Unit)
            } catch (e: Exception) {
                ZLog.e(TAG, "Passkey registration error", e)
                Result.failure(e)
            }
        }

    /**
     * Logs in using a hardware-bound Passkey bound to the activation code.
     * SOP 11: Zero-PII Sovereignty Auth.
     */
    suspend fun loginWithPasskey(rawCode: String, context: android.content.Context): Result<ActivationResponse> =
        withContext(ioDispatcher) {
            val client = vpnRepository.apiClient
            try {
                val code = rawCode.replace("-", "").uppercase().trim()
                if (code.length != 25) {
                    return@withContext Result.failure(Exception("Invalid Token for Passkey"))
                }

                // 1. Get challenge from server
                val challengeJson = client.passkeyLoginStart(code)

                // 2. Request credential from Android Credential Manager
                val getOption = androidx.credentials.GetPublicKeyCredentialOption(challengeJson)
                val getRequest = androidx.credentials.GetCredentialRequest(listOf(getOption))

                val result = credentialManagerProvider.getCredential(context, getRequest)
                val responseJson =
                    result.credential.data.getString("androidx.credentials.BUNDLE_KEY_REGISTRATION_RESPONSE_JSON")
                        ?: result.credential.data.getString("androidx.credentials.BUNDLE_KEY_AUTHENTICATION_RESPONSE_JSON")
                        ?: return@withContext Result.failure(Exception("Failed to retrieve Passkey credential"))

                // 3. Finish login with core
                val response = client.passkeyLoginFinish(code, responseJson)

                if (response.token != null) {
                    saveActivationState(code, response)
                    vpnRepository.activate(
                        code = code,
                        token = response.token!!,
                        plan = response.plan,
                        devicesRemaining = response.devicesRemaining,
                        remainingDays = response.remainingDays
                    )
                    Result.success(response)
                } else {
                    Result.failure(Exception("Passkey Auth Failed"))
                }
            } catch (e: Exception) {
                ZLog.e(TAG, "Passkey login error", e)
                Result.failure(e)
            }
        }

    /**
     * Pairs with a desktop client by decrypting the scanned payload using a local PIN.
     *
     * @param scannedCode The Base64 encoded payload from the desktop QR.
     * @param pin The 6-digit PIN shown on the desktop screen.
     */
    suspend fun pairWithDesktop(scannedCode: String, pin: String): Result<Unit> = withContext(ioDispatcher) {
        try {
            val ciphertext = android.util.Base64.decode(scannedCode, android.util.Base64.DEFAULT)
            val decrypted = coreServiceProvider.decryptQrPairing(ciphertext, pin)
            val payload = String(decrypted)
            val json = Json.parseToJsonElement(payload).jsonObject
            val serverUrl = json["server_url"]?.jsonPrimitive?.content ?: ""
            val handshakeSecret = json["secret"]?.jsonPrimitive?.content ?: ""

            val pairUrl = "$serverUrl/api/v1/sessions/pair/$handshakeSecret"
            val requestBody = buildJsonObject {
                put("device_id", CoreUtils.getAndroidDeviceId(application))
                put("device_name", deviceProvider.model)
            }

            val responseCode = networkClient.post(pairUrl, requestBody.toString())

            if (responseCode == 200) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Pairing rejected by server"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
