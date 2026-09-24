package com.shadowmesh.app.performance

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.shadowmesh.ui_kit.performance.PerformanceProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

import com.shadowmesh.app.util.DeviceInfoProvider

/**
 * Monitors device performance, battery, and thermal state to provide an adaptive [PerformanceProfile].
 *
 * SOP 02 & 05: Ensures perceived performance and battery efficiency via tiered rendering.
 */
@Singleton
class PerformanceMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceInfoProvider: DeviceInfoProvider,
    @com.shadowmesh.app.di.MainDispatcher private val mainDispatcher: CoroutineDispatcher,
) : DefaultLifecycleObserver {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val _profile = MutableStateFlow(calculateInitialProfile())
    
    /**
     * Exposes the current performance profile as a [StateFlow].
     * Uses debounce to prevent rapid UI recompositions during state fluctuations.
     */
    val profile: StateFlow<PerformanceProfile> = _profile.asStateFlow()

    private var monitorScope: CoroutineScope? = null

    @OptIn(FlowPreview::class)
    override fun onStart(owner: LifecycleOwner) {
        monitorScope = CoroutineScope(SupervisorJob() + mainDispatcher).apply {
            combine(
                batterySaverFlow(),
                thermalStatusFlow(),
                flowOf(isLowRamProfile())
            ) { isBatterySaver, thermalStatus, isLowRam ->
                calculateProfile(isBatterySaver, thermalStatus, isLowRam)
            }
            .debounce(300)
            .distinctUntilChanged()
            .onEach { 
                android.util.Log.d("PerformanceMonitor", "Profile changed to: $it")
                _profile.value = it 
            }
            .launchIn(this)
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        monitorScope?.cancel()
        monitorScope = null
    }

    private fun calculateInitialProfile(): PerformanceProfile {
        val initial = calculateProfile(
            powerManager.isPowerSaveMode,
            if (deviceInfoProvider.sdkInt >= 29) { // Build.VERSION_CODES.Q is 29
                powerManager.currentThermalStatus
            } else {
                0 // PowerManager.THERMAL_STATUS_NONE is 0
            },
            isLowRamProfile()
        )
        android.util.Log.d("PerformanceMonitor", "Initial Profile: $initial (SDK: ${deviceInfoProvider.sdkInt}, RAM: ${deviceInfoProvider.totalRam})")
        return initial
    }

    private fun calculateProfile(
        isBatterySaver: Boolean,
        thermalStatus: Int,
        isLowRam: Boolean
    ): PerformanceProfile {
        // Guard 1: API Level < 31 cannot handle RenderEffect blurs safely.
        // Guard 2: Low-RAM or Battery Saver mandates LOW profile.
        // Guard 3: Moderate Thermal Status (2) triggers immediate throttle to LOW.
        if (deviceInfoProvider.sdkInt < 31 || isLowRam || isBatterySaver || thermalStatus >= 2) {
            return PerformanceProfile.LOW
        }
        
        // Tiered logic for HIGH vs MEDIUM based on light thermal stress (1).
        return if (thermalStatus >= 1) {
            PerformanceProfile.MEDIUM
        } else {
            PerformanceProfile.HIGH
        }
    }

    private fun isLowRamProfile(): Boolean {
        return deviceInfoProvider.totalRam < 4 * 1024 * 1024 * 1024L || deviceInfoProvider.isLowRamDevice
    }

    private fun batterySaverFlow() = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(powerManager.isPowerSaveMode)
            }
        }
        context.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        trySend(powerManager.isPowerSaveMode)
        awaitClose { context.unregisterReceiver(receiver) }
    }

    private fun thermalStatusFlow() = callbackFlow {
        if (deviceInfoProvider.sdkInt < 29) {
            trySend(0) // THERMAL_STATUS_NONE
            awaitClose { }
            return@callbackFlow
        }

        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            trySend(status)
        }
        powerManager.addThermalStatusListener(context.mainExecutor, listener)
        trySend(powerManager.currentThermalStatus)
        awaitClose { powerManager.removeThermalStatusListener(listener) }
    }
}
