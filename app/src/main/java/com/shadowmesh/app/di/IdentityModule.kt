package com.shadowmesh.app.di

import android.content.Context
import com.shadowmesh.app.identity.AndroidCredentialManagerProvider
import com.shadowmesh.app.identity.CredentialManagerProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class IdentityModule {

    @Binds
    @Singleton
    abstract fun bindCredentialManagerProvider(
        impl: AndroidCredentialManagerProvider
    ): CredentialManagerProvider
}
