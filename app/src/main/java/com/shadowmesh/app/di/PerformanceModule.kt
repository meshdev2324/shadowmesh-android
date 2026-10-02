package com.shadowmesh.app.di

import com.shadowmesh.app.util.AndroidDeviceInfoProvider
import com.shadowmesh.app.util.DeviceInfoProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PerformanceModule {

    @Binds
    @Singleton
    abstract fun bindDeviceInfoProvider(
        impl: AndroidDeviceInfoProvider
    ): DeviceInfoProvider
}
