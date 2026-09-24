package com.shadowmesh.core_vpn

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Senior-level Secure Storage implementation using EncryptedSharedPreferences.
 * Handles Keystore corruption and provides robust recovery.
 *
 * This class is the single source of truth for secure preferences in the app.
 */
@Suppress("DEPRECATION")
class SecureStorage private constructor(
    private val context: Context,
    private val manualPrefs: SharedPreferences? = null
) {
    companion object {
        private const val TAG = "SecureStorage"
        private const val HASH_ITERATIONS = 100000
        private const val HASH_KEY_LENGTH = 256
        private const val SALT_LENGTH = 32
        
        @Volatile
        private var instance: SecureStorage? = null
        
        fun getInstance(context: Context): SecureStorage {
            return instance ?: synchronized(this) {
                instance ?: SecureStorage(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Allows injecting a mock instance or custom storage for testing.
         */
        fun setInstance(storage: SecureStorage) {
            instance = storage
        }

        /**
         * Resets the singleton instance. Useful for tests.
         */
        fun resetInstance() {
            instance = null
        }

        /**
         * Factory method primarily for testing.
         */
        internal fun createForTest(context: Context, prefs: SharedPreferences): SecureStorage {
            return SecureStorage(context, prefs)
        }

        /**
         * Generates a cryptographically secure random salt.
         */
        fun generateSalt(): String {
            val random = SecureRandom()
            val saltBytes = ByteArray(SALT_LENGTH)
            random.nextBytes(saltBytes)
            return saltBytes.joinToString("") { "%02x".format(it) }
        }

        /**
         * Hashes a PIN using PBKDF2WithHmacSHA256 with the provided salt.
         */
        fun hashPin(pin: String, salt: String): String {
            val spec = PBEKeySpec(
                pin.toCharArray(),
                salt.toByteArray(),
                HASH_ITERATIONS,
                HASH_KEY_LENGTH
            )
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val hash = factory.generateSecret(spec).encoded
            return hash.joinToString("") { "%02x".format(it) }
        }
    }
    
    val prefs: SharedPreferences by lazy {
        manualPrefs ?: createEncryptedPrefs()
    }

    /**
     * Pre-warms the EncryptedSharedPreferences on a background thread.
     * This initializes the MasterKey and Keystore entries early in the app lifecycle.
     */
    fun preWarm() {
        try {
            val _unused = prefs
        } catch (e: Exception) {
            Log.e(TAG, "Pre-warm failed", e)
        }
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                Config.PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating EncryptedSharedPreferences, attempting recovery", e)
            try {
                context.deleteSharedPreferences(Config.PREFS_NAME)
                
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                EncryptedSharedPreferences.create(
                    context,
                    Config.PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (recoveryException: Exception) {
                Log.e(TAG, "Recovery failed, falling back to plaintext-ish fallback", recoveryException)
                context.getSharedPreferences("${Config.PREFS_NAME}_fallback", Context.MODE_PRIVATE)
            }
        }
    }
    
    fun set(key: String, value: String?) {
        if (value == null) {
            remove(key)
        } else {
            prefs.edit().putString(key, value).apply()
        }
    }

    fun setBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }
    
    fun get(key: String): String? {
        return prefs.getString(key, null)
    }

    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return prefs.getBoolean(key, defaultValue)
    }
    
    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    /**
     * Wipes all data from secure storage.
     * SOP 13 §3: Used during Panic Wipe to ensure zero residual forensic data.
     */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    // Keep instance methods for compatibility, delegating to companion object
    fun generateSalt(): String = SecureStorage.generateSalt()
    fun hashPin(pin: String, salt: String): String = SecureStorage.hashPin(pin, salt)
    
    fun getPersistentDeviceId(): String {
        var deviceId = get(Config.KEY_DEVICE_ID)
        if (deviceId == null) {
            deviceId = generateRandomDeviceId()
            set(Config.KEY_DEVICE_ID, deviceId)
        }
        return deviceId
    }
    
    private fun generateRandomDeviceId(): String {
        val random = SecureRandom()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }
}
