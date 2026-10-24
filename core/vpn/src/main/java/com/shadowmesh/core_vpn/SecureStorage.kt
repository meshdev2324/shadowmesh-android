package com.shadowmesh.core_vpn

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
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
        private const val FALLBACK_PREFS_NAME = "shadowmesh_secure_fallback"
        
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
    
    @Volatile
    private var resolvedPrefs: SharedPreferences? = null

    val prefs: SharedPreferences
        get() = resolvedPrefs ?: synchronized(this) {
            resolvedPrefs ?: (manualPrefs ?: createEncryptedPrefs()).also { resolvedPrefs = it }
        }.also {
            // One-time reconciliation. If a previous launch fell back to the
            // mirror (transient Keystore failure) and then wrote a fresh
            // activation there, the encrypted store is still missing it. Without
            // this, the next launch reads the empty primary file and demands the
            // activation code again — the exact flapping this class of failure
            // produces. Runs only when the mirror is non-empty, so the common
            // path costs a single empty-map read.
            if (!usingFallbackMirror) scheduleMirrorReconcile()
        }

    @Volatile
    private var reconcileScheduled = false

    private fun scheduleMirrorReconcile() {
        if (reconcileScheduled) return
        reconcileScheduled = true
        Thread({ migrateMirrorToPrimary() }, "secure-store-reconcile").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /**
     * True when the encrypted store could not be opened and reads/writes are
     * being served from the plaintext mirror instead. Exposed so callers (and
     * diagnostics) can tell a storage fault from a validation fault.
     */
    @Volatile
    var usingFallbackMirror: Boolean = false
        private set

    /**
     * Best-effort re-attempt of the encrypted store. Safe to call on any
     * thread; a failure simply leaves [usingFallbackMirror] set.
     */
    fun retryEncryptedStore() {
        if (!usingFallbackMirror) return
        try {
            val primary = buildEncryptedPrefs()
            synchronized(this) { resolvedPrefs = primary }
            usingFallbackMirror = false
            // Values written while we were serving from the mirror belong in the
            // encrypted store now, or they are orphaned for the next launch.
            migrateMirrorToPrimary()
        } catch (_: Exception) {
            // Still unavailable; the mirror keeps serving reads and writes.
        }
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
            buildEncryptedPrefs()
        } catch (e: Exception) {
            // NOTE: this branch used to call context.deleteSharedPreferences()
            // and then hand back a *different* file ("<name>_fallback"). Both
            // halves were destructive:
            //
            //  1. deleteSharedPreferences() wiped the entire encrypted store,
            //     including the activation code and auth token, so a single
            //     transient Keystore failure silently logged the user out;
            //  2. writing to a second, divergent file meant the token was
            //     orphaned — the next launch read the primary file, found it
            //     empty, and demanded re-entry. Opening the app from the lock
            //     screen after a reboot (Direct Boot, Keystore not yet
            //     unlocked) hits exactly that path, which is why activation
            //     "did not survive a reboot".
            //
            // The store is now never deleted. If it cannot be opened we fall
            // back to a readable mirror, and migrateMirrorToPrimary() copies
            // anything the mirror is missing back once the Keystore is
            // available again.
            Log.e(TAG, "Encrypted store unavailable, using fallback mirror (data preserved)", e)
            usingFallbackMirror = true
            context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun buildEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            Config.PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Copies any entry that exists only in the fallback mirror back into the
     * encrypted store. Called opportunistically on a background thread once the
     * Keystore is usable again, so a transient failure cannot cost the user
     * their activation.
     */
    private fun migrateMirrorToPrimary() {
        val mirror =
            context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        if (mirror.all.isEmpty()) return
        try {
            val primary = buildEncryptedPrefs()
            val missing = mirror.all.filter { (k, _) -> !primary.contains(k) }
            if (missing.isEmpty()) return
            val editor = primary.edit()
            missing.forEach { (k, v) ->
                when (v) {
                    is Boolean -> editor.putBoolean(k, v)
                    is Int -> editor.putInt(k, v)
                    is Long -> editor.putLong(k, v)
                    is String -> editor.putString(k, v)
                    else -> Unit
                }
            }
            if (editor.commit()) {
                Log.i(TAG, "Migrated ${missing.size} entry(ies) from fallback mirror")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Mirror migration deferred: ${e.javaClass.simpleName}")
        }
    }
    
    fun set(key: String, value: String?) {
        if (value == null) {
            remove(key)
        } else {
            prefs.edit().putString(key, value).apply()
        }
    }

    /**
     * Writes a value and blocks until it is on disk.
     *
     * Reserved for credential material whose loss is not recoverable by simply
     * recomputing it — RFC-026's WireGuard identity. `set()` uses apply(), so a
     * process death between generation and the async disk write would silently
     * mint a new identity, which the node would provision as a brand-new peer
     * with a new tunnel address. The same durability class of bug previously
     * cost users their activation code and their PIN.
     */
    fun setDurable(key: String, value: String) {
        prefs.edit(commit = true) { putString(key, value) }
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
