package com.shadowmesh.app

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CamouflageTest {
    private lateinit var app: Application
    private lateinit var pm: PackageManager
    private lateinit var viewModel: VPNManagerViewModel

    private val shadowMeshAlias = ComponentName("com.shadowmesh.app", "com.shadowmesh.app.ShadowMeshAlias")
    private val notesAlias = ComponentName("com.shadowmesh.app", "com.shadowmesh.app.NotesAlias")

    @Before
    fun setup() {
        app = mockk(relaxed = true)
        pm = mockk(relaxed = true)

        every { app.packageManager } returns pm
        every { app.applicationContext } returns app
        every { app.packageName } returns "com.shadowmesh.app"

        // Mock SecureStorage to avoid EncryptedSharedPreferences failures in tests
        val mockStorage = mockk<SecureStorage>(relaxed = true)
        val mockPrefs = mockk<android.content.SharedPreferences>(relaxed = true)
        val mockEditor = mockk<android.content.SharedPreferences.Editor>(relaxed = true)
        every { mockStorage.prefs } returns mockPrefs
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putBoolean(any(), any()) } returns mockEditor
        every { mockEditor.apply() } just Runs
        SecureStorage.setInstance(mockStorage)

        mockkStatic("uniffi.shadowmesh.ShadowmeshKt")
        // Note: Using relaxed static mock and providing common functions to avoid crashes
        every { uniffi.shadowmesh.`getDefaultUserSettings`() } returns mockk(relaxed = true)

        mockkObject(AppIntegrityManager)
        every { AppIntegrityManager.verifyIntegrity(any()) } returns true

        viewModel = VPNManagerViewModel(app)
    }

    @After
    fun tearDown() {
        unmockkAll()
        SecureStorage.resetInstance()
    }

    @Test
    fun toggleCamouflageMode_toEnabled_followsSecureSequentialPattern() =
        runTest {
            val capturedCalls = mutableListOf<Triple<ComponentName, Int, Int>>()

            every {
                pm.setComponentEnabledSetting(any(), any(), any())
            } answers {
                capturedCalls.add(Triple(firstArg(), secondArg(), thirdArg()))
            }

            // 1. Initial State: Both DEFAULT
            every { pm.getComponentEnabledSetting(notesAlias) } returns PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            every { pm.getComponentEnabledSetting(shadowMeshAlias) } returns PackageManager.COMPONENT_ENABLED_STATE_DEFAULT

            viewModel.toggleCamouflageMode(true)

            // Wait for delays: 500ms initial + 100ms between calls
            advanceTimeBy(1000)

            // Verify sequential enabling pattern (from sequential pattern requirement)
            // STEP 1: Enable target (Notes)
            assertEquals("Step 1 must enable NotesAlias", "com.shadowmesh.app.NotesAlias", capturedCalls[0].first.className)
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, capturedCalls[0].second)

            // STEP 2: Disable previous (ShadowMesh)
            assertEquals("Step 2 must disable ShadowMeshAlias", "com.shadowmesh.app.ShadowMeshAlias", capturedCalls[1].first.className)
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, capturedCalls[1].second)
        }

    @Test
    fun toggleCamouflageMode_toDisabled_followsSecureSequentialPattern() =
        runTest {
            val capturedCalls = mutableListOf<Triple<ComponentName, Int, Int>>()

            every {
                pm.setComponentEnabledSetting(any(), any(), any())
            } answers {
                capturedCalls.add(Triple(firstArg(), secondArg(), thirdArg()))
            }

            // Simulate currently being in camouflage mode
            every { pm.getComponentEnabledSetting(notesAlias) } returns PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            every { pm.getComponentEnabledSetting(shadowMeshAlias) } returns PackageManager.COMPONENT_ENABLED_STATE_DISABLED

            viewModel.toggleCamouflageMode(false)
            advanceTimeBy(1000)

            // STEP 1: Enable target (ShadowMesh)
            assertEquals("Step 1 must enable ShadowMeshAlias", "com.shadowmesh.app.ShadowMeshAlias", capturedCalls[0].first.className)
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, capturedCalls[0].second)

            // STEP 2: Disable previous (Notes)
            assertEquals("Step 2 must disable NotesAlias", "com.shadowmesh.app.NotesAlias", capturedCalls[1].first.className)
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, capturedCalls[1].second)
        }
}
