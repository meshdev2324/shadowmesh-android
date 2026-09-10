package com.shadowmesh.app.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Interface for providing device-specific information.
 * Enables clean unit testing by abstracting static system properties.
 */
interface DeviceInfoProvider {
    val sdkInt: Int
    val totalRam: Long
    val isLowRamDevice: Boolean
}

@Singleton
class AndroidDeviceInfoProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : DeviceInfoProvider {
    
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    
    override val sdkInt: Int get() = Build.VERSION.SDK_INT
    
    override val totalRam: Long get() {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.totalMem
    }
    
    override val isLowRamDevice: Boolean get() = activityManager.isLowRamDevice
}
