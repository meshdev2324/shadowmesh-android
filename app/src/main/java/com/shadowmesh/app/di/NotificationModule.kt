package com.shadowmesh.app.di

import android.content.Context
import com.shadowmesh.app.ShadowMeshNotificationManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NotificationModule {
    @Provides
    @Singleton
    fun provideShadowMeshNotificationManager(
        @ApplicationContext context: Context,
    ): ShadowMeshNotificationManager = ShadowMeshNotificationManager(context)
}
