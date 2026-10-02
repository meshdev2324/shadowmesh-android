package com.shadowmesh.app.performance

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.BroadcastReceiver
import android.content.res.Configuration
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.Debug
import android.os.Process
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.shadowmesh.ui_kit.performance.PerformanceProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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

    /**
     * Sticky memory-pressure latch.
     *
     * The original profile decision was open-loop: it read device *capability*
     * (SDK level, isLowRamDevice, total RAM, thermal, battery) and never the
     * app's own footprint. Two modern, non-low-RAM phones therefore both ran
     * HIGH and permitted RenderEffect blurs while the process sat at ~540 MB.
     * This latch closes the loop: the OS trim signal and a periodic PSS sample
     * can force LOW, and only a PSS reading comfortably below [PssExitMb] can
     * release it again. The gap between enter and exit is deliberate
     * hysteresis -- without it the profile flaps on every sample.
     */
    private val memoryPressure = MutableStateFlow(false)

    @OptIn(FlowPreview::class)
    override fun onStart(owner: LifecycleOwner) {
        monitorScope = CoroutineScope(SupervisorJob() + mainDispatcher).apply {
            launch { memoryPressureSignal().collect { memoryPressure.value = it } }

            combine(
                batterySaverFlow(),
                thermalStatusFlow(),
                flowOf(isLowRamProfile()),
                memoryPressure
            ) { isBatterySaver, thermalStatus, isLowRam, underPressure ->
                calculateProfile(isBatterySaver, thermalStatus, isLowRam, underPressure)
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
            isLowRamProfile(),
            false
        )
        android.util.Log.d("PerformanceMonitor", "Initial Profile: $initial (SDK: ${deviceInfoProvider.sdkInt}, RAM: ${deviceInfoProvider.totalRam})")
        return initial
    }

    internal fun calculateProfile(
        isBatterySaver: Boolean,
        thermalStatus: Int,
        isLowRam: Boolean,
        isMemoryPressure: Boolean = false
    ): PerformanceProfile {
        // Highest priority: the process is measurably too big. This outranks the
        // device-capability guards, which describe the hardware and say nothing
        // about what we are currently costing it.
        if (isMemoryPressure) return PerformanceProfile.LOW

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

    /** Enter threshold: system memory availability below this fraction is pressure. */
    private val SystemAvailEnterFraction = 0.20

    /** Exit threshold, comfortably above the enter value, to provide hysteresis. */
    private val SystemAvailExitFraction = 0.30

    /**
     * Emits true while the process should be treated as under memory pressure.
     *
     * Two inputs. [ComponentCallbacks2.onTrimMemory] is the OS telling us
     * directly, and costs nothing. The PSS sample is the backstop for the case
     * that actually bit us: being large without (yet) being killed, which the
     * OS has no reason to announce.
     */
    private fun memoryPressureSignal(): Flow<Boolean> = callbackFlow {
        val callbacks = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            override fun onLowMemory() { trySend(true) }
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) trySend(true)
            }
        }
        context.registerComponentCallbacks(callbacks)

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        var latched = false
        while (true) {
            // Self-measured PSS is deliberately NOT the trigger here.
            //
            // Measured on device: dumpsys reported 534 MB total PSS, of which
            // 475 MB was Graphics (433 MB "Gfx dev"), while both
            // ActivityManager.getProcessMemoryInfo().totalPss and
            // Debug.getMemoryInfo().totalPss reported 55 MB for the same
            // instant -- a stable 9x under-read. The Graphics bucket is only
            // populated by the system-side memory update that `dumpsys meminfo`
            // forces, so an in-process read structurally cannot see the memory
            // this app is actually wasting. Thresholding a self-read would
            // have reported "55 MB, no pressure" indefinitely and never fired.
            //
            // The sanctioned in-process signals are used instead: the OS trim
            // callbacks above, plus system-wide availability below. A process
            // holding half a gigabyte of graphics memory necessarily shows up
            // as reduced system availability, so this still closes the loop.
            val systemInfo = ActivityManager.MemoryInfo()
            var availableFraction = 1.0
            var lowMemory = false
            runCatching {
                activityManager.getMemoryInfo(systemInfo)
                availableFraction = if (systemInfo.totalMem > 0) {
                    systemInfo.availMem.toDouble() / systemInfo.totalMem.toDouble()
                } else 1.0
                lowMemory = systemInfo.lowMemory
            }
            val underPressureNow = lowMemory || availableFraction < SystemAvailEnterFraction
            // Hysteresis: release only once availability is comfortably higher.
            latched = if (latched) {
                lowMemory || availableFraction < SystemAvailExitFraction
            } else {
                underPressureNow
            }
            trySend(latched)
            android.util.Log.d(
                "PerformanceMonitor",
                "avail=${"%.1f".format(availableFraction * 100)}% low=$lowMemory pressure=$latched"
            )
            delay(4_000)
        }
    }.buffer(Channel.CONFLATED)

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
