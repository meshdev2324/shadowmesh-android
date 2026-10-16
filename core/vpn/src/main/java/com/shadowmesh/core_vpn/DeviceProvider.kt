package com.shadowmesh.core_vpn

import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton

interface DeviceProvider {
    val model: String
    val osVersion: String
    val supportedAbis: Array<String>
}

@Singleton
class AndroidDeviceProvider @Inject constructor() : DeviceProvider {
    override val model: String get() = Build.MODEL
    override val osVersion: String get() = Build.VERSION.RELEASE
    override val supportedAbis: Array<String> get() = Build.SUPPORTED_ABIS
}
