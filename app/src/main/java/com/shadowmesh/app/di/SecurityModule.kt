package com.shadowmesh.app.di

import android.content.Context
import com.shadowmesh.core_vpn.SecureStorage
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {
    @Provides
    @Singleton
    fun provideSecureStorage(
        @ApplicationContext context: Context,
    ): SecureStorage = SecureStorage.getInstance(context)
}
