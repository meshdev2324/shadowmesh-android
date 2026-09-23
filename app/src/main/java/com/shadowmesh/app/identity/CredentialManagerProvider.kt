package com.shadowmesh.app.identity

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Interface for providing CredentialManager functionality.
 * This abstraction allows for high-fidelity faking in unit tests.
 */
interface CredentialManagerProvider {
    suspend fun getCredential(context: Context, request: GetCredentialRequest): GetCredentialResponse
}

/**
 * Standard Android implementation of [CredentialManagerProvider].
 */
@Singleton
class AndroidCredentialManagerProvider @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context
) : CredentialManagerProvider {
    private val credentialManager = CredentialManager.create(context)

    override suspend fun getCredential(context: Context, request: GetCredentialRequest): GetCredentialResponse {
        return credentialManager.getCredential(context, request)
    }
}
