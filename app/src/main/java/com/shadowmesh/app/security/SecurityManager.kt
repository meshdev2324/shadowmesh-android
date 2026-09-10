package com.shadowmesh.app.security

import android.app.Application
import androidx.core.content.edit
import android.content.ComponentName
import android.content.pm.PackageManager
import com.shadowmesh.app.util.ZLog
import com.shadowmesh.core_vpn.AppIntegrityManager
import com.shadowmesh.core_vpn.Config
import com.shadowmesh.core_vpn.RootDetection
import com.shadowmesh.core_vpn.SecureStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SecurityManager"

/**
 * Manages security policies, integrity checks, and camouflage.
 * SOP 11: Privacy-first logic and verified states.
 *
 * This manager is responsible for hardware-level security checks (Root detection),
 * app integrity verification, and "Camouflage Mode" which swaps the app's launcher
 * identity to hide the VPN's presence.
 */
@Singleton
class SecurityManager @Inject constructor(
    private val context: Application,
    private val secureStorage: SecureStorage,
    private val panicWipeManager: PanicWipeManager,
    @com.shadowmesh.app.di.MainDispatcher private val mainDispatcher: CoroutineDispatcher,
    @com.shadowmesh.app.di.IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val securePrefs = secureStorage.prefs

    /**
     * State of device root status. High-security environments may block connection if true.
     */
    private val _isRooted = MutableStateFlow(false)
    val isRooted = _isRooted.asStateFlow()

    /**
     * State of application binary integrity. If true, the app has been tampered with.
     */
    private val _isIntegrityCompromised = MutableStateFlow(false)
    val isIntegrityCompromised = _isIntegrityCompromised.asStateFlow()

    init {
        scope.launch(ioDispatcher) {
            checkIntegrityInternal()
        }
        checkRoot()
    }

    /**
     * Performs a deep integrity check of the app signature and package environment.
     */
    fun checkIntegrity() {
        scope.launch(ioDispatcher) {
            checkIntegrityInternal()
        }
    }

    private fun checkIntegrityInternal() {
        if (!AppIntegrityManager.verifyIntegrity(context)) {
            ZLog.e(TAG, "FATAL: App Integrity Compromised!")
            _isIntegrityCompromised.value = true
        }
    }

    private fun checkRoot() {
        scope.launch(ioDispatcher) {
            if (RootDetection.isRooted(context)) {
                _isRooted.value = true
            }
        }
    }

    /**
     * Hashes and stores the user's primary security PIN.
     */
    fun setPin(pin: String) {
        val salt = SecureStorage.generateSalt()
        val hash = SecureStorage.hashPin(pin, salt)
        securePrefs.edit {
            putString(Config.KEY_PIN_HASH, hash)
            putString(Config.KEY_PIN_SALT, salt)
        }
    }

    /**
     * Hashes and stores a "Panic PIN" which triggers data destruction if entered.
     */
    fun setPanicPin(pin: String) {
        val salt = SecureStorage.generateSalt()
        val hash = SecureStorage.hashPin(pin, salt)
        securePrefs.edit {
            putString(Config.KEY_PANIC_PIN_HASH, hash)
            putString(Config.KEY_PANIC_PIN_SALT, salt)
        }
    }

    /**
     * Verifies the provided PIN against stored hashes.
     * If the PIN matches the Panic PIN, a full local wipe is initiated.
     *
     * @return true if the PIN matches either the primary or panic hash.
     */
    fun verifyPin(pin: String): Boolean {
        val savedHash = securePrefs.getString(Config.KEY_PIN_HASH, null)
        val salt = securePrefs.getString(Config.KEY_PIN_SALT, null)
        val panicHash = securePrefs.getString(Config.KEY_PANIC_PIN_HASH, null)
        val panicSalt = securePrefs.getString(Config.KEY_PANIC_PIN_SALT, null)

        if (panicHash != null && panicSalt != null) {
            if (SecureStorage.hashPin(pin, panicSalt) == panicHash) {
                triggerPanicWipe()
                return true
            }
        }

        if (savedHash != null && salt != null) {
            return SecureStorage.hashPin(pin, salt) == savedHash
        }
        return false
    }

    private fun triggerPanicWipe() {
        scope.launch {
            panicWipeManager.executePanicWipe()
        }
    }

    /**
     * Toggles "Camouflage Mode" by swapping the app's launcher alias.
     * This requires a brief delay and potentially an app kill to force the OS to refresh icons.
     *
     * @param enabled If true, app appears as "Notes". If false, appears as "ShadowMesh".
     * @param onAliasUpdated Callback invoked after the alias switch is requested.
     */
    fun toggleCamouflage(enabled: Boolean, onAliasUpdated: suspend (Boolean) -> Unit) {
        secureStorage.setBoolean(Config.KEY_CAMOUFLAGE_ENABLED, enabled)
        scope.launch {
            delay(800)
            updateAppAlias(enabled, forceKill = true)
            onAliasUpdated(enabled)
        }
    }

    private suspend fun updateAppAlias(isCamouflage: Boolean, forceKill: Boolean = false) {
        val pm = context.packageManager
        val pkgName = context.packageName
        val shadowMeshAlias = ComponentName(pkgName, "$pkgName.ShadowMeshAlias")
        val notesAlias = ComponentName(pkgName, "$pkgName.NotesAlias")

        try {
            if (isCamouflage) {
                pm.setComponentEnabledSetting(notesAlias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                delay(1000)
                pm.setComponentEnabledSetting(shadowMeshAlias, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, if (forceKill) 0 else PackageManager.DONT_KILL_APP)
            } else {
                pm.setComponentEnabledSetting(shadowMeshAlias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                delay(1000)
                pm.setComponentEnabledSetting(notesAlias, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, if (forceKill) 0 else PackageManager.DONT_KILL_APP)
            }
        } catch (e: Exception) {
            ZLog.e(TAG, "Alias sync error", e)
        }
    }
}
