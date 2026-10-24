package com.shadowmesh.app

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shadowmesh.app.alert.AlertRepository
import com.shadowmesh.app.identity.DeviceIdentity
import com.shadowmesh.app.identity.IdentityManager
import com.shadowmesh.app.security.PanicWipeManager
import com.shadowmesh.app.security.SecurityManager
import com.shadowmesh.app.vpn.ConnectionManager
import com.shadowmesh.core_vpn.AppIntegrityManager
import com.shadowmesh.core_vpn.RootDetection
import com.shadowmesh.core_vpn.SecureStorage
import com.shadowmesh.core_vpn.repository.VpnRepository
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.shadowmesh.SpeedTest

/**
 * The launcher-alias swap, which is a security control and not a cosmetic one.
 *
 * Camouflage mode swaps which launcher icon the OS will show. The order is the
 * whole point: the decoy alias is enabled **before** the real one is disabled,
 * so there is never a moment where neither is enabled and the user is left with
 * no way to launch the app at all. Reversed, a process death mid-sequence
 * strands the user outside a VPN client on a hostile network.
 *
 * ## Why this file had to be rewritten rather than patched
 *
 * It had not compiled since `VPNManagerViewModel` moved to Hilt and grew to ten
 * constructor parameters, and nothing compiled `:app`'s androidTest source set,
 * so nothing noticed. Three separate layers were broken, not one:
 *
 *  1. **Unresolved types.** `SecureStorage` and `AppIntegrityManager` live in
 *     `com.shadowmesh.core_vpn` and were never imported.
 *  2. **Constructor drift.** `VPNManagerViewModel(app)` passed an `Application`
 *     where a `VpnRepository` was expected; eight further parameters were
 *     missing.
 *  3. **A timing model that could never have worked**, which is the part worth
 *     recording. The test advanced virtual time by 1000 ms and its comment
 *     claimed "500ms initial + 100ms between calls". The real code
 *     (`SecurityManager.toggleCamouflage` -> `updateAppAlias`) is **800 ms then
 *     1000 ms = 1800 ms**, and it runs on `SecurityManager`'s *own* scope, not
 *     the `runTest` scope. So the original test was short by 800 ms *and* was
 *     advancing a clock that had no connection to the code under test. Even
 *     after the compile errors were fixed it would have failed on an empty
 *     `capturedCalls`.
 *
 * Fix (3) properly: `SecurityManager` takes its `mainDispatcher` by constructor,
 * so the test injects an `UnconfinedTestDispatcher` **and passes that same
 * dispatcher as `runTest`'s context**, giving the code under test and the test
 * clock one shared scheduler. The delays are then virtual and instant instead of
 * costing 1.8 real seconds per case.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CamouflageTest {
    private lateinit var app: Application
    private lateinit var pm: PackageManager

    private lateinit var vpnRepository: VpnRepository
    private lateinit var connectionManager: ConnectionManager
    private lateinit var identityManager: IdentityManager
    private lateinit var deviceIdentity: DeviceIdentity
    private lateinit var secureStorage: SecureStorage
    private lateinit var speedTest: SpeedTest
    private lateinit var alerts: AlertRepository

    /**
     * One scheduler, shared by the code under test and by `runTest`, so
     * `advanceTimeBy` actually moves the alias swap forward.
     */
    private val testDispatcher = UnconfinedTestDispatcher()

    private val shadowMeshAlias = ComponentName("com.shadowmesh.app", "com.shadowmesh.app.ShadowMeshAlias")
    private val notesAlias = ComponentName("com.shadowmesh.app", "com.shadowmesh.app.NotesAlias")

    @Before
    fun setup() {
        app = mockk(relaxed = true)
        pm = mockk(relaxed = true)

        every { app.packageManager } returns pm
        every { app.applicationContext } returns app
        every { app.packageName } returns "com.shadowmesh.app"

        // SecureStorage is final with a private constructor, so it is mocked and
        // installed into the singleton rather than constructed.
        secureStorage = mockk(relaxed = true)
        val mockPrefs = mockk<android.content.SharedPreferences>(relaxed = true)
        every { secureStorage.prefs } returns mockPrefs
        SecureStorage.setInstance(secureStorage)

        vpnRepository = mockk(relaxed = true)
        connectionManager = mockk(relaxed = true)
        identityManager = mockk(relaxed = true)
        deviceIdentity = mockk(relaxed = true)
        speedTest = mockk(relaxed = true)

        mockkObject(AppIntegrityManager)
        every { AppIntegrityManager.verifyIntegrity(any()) } returns true

        // `SecurityManager.init` probes root detection eagerly on construction.
        // Left unmocked it shells out to the real filesystem through a mocked
        // Application, which is a test that depends on the machine it runs on.
        mockkObject(RootDetection)
        every { RootDetection.isRooted(any()) } returns false

        every { connectionManager.connectionProgress } returns
            kotlinx.coroutines.flow.MutableStateFlow(
                com.shadowmesh.core_vpn.domain.ConnectionProgress.Idle
            )

        alerts = AlertRepository(CoroutineScope(SupervisorJob() + testDispatcher))

        val securityManager = SecurityManager(
            context = app,
            secureStorage = secureStorage,
            panicWipeManager = PanicWipeManager(app, secureStorage, vpnRepository),
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
        )

        viewModel = VPNManagerViewModel(
            vpnRepository,
            connectionManager,
            identityManager,
            deviceIdentity,
            securityManager,
            secureStorage,
            SavedStateHandle(),
            speedTest,
            alerts,
            ioDispatcher = testDispatcher,
        )
    }

    private lateinit var viewModel: VPNManagerViewModel

    @After
    fun tearDown() {
        unmockkAll()
        SecureStorage.resetInstance()
    }

    /**
     * 800 ms initial settle + 1000 ms between the two swaps, rounded up. The
     * value is derived from `SecurityManager.updateAppAlias`; if those delays
     * change this number must change with them, and the assertions below fail
     * loudly rather than silently passing if it does not.
     */
    private fun TestScope.advanceThroughTheSwap() = advanceTimeBy(2_000)

    @Test
    fun toggleCamouflageMode_toEnabled_followsSecureSequentialPattern() =
        runTest(testDispatcher) {
            val capturedCalls = mutableListOf<Triple<ComponentName, Int, Int>>()

            every {
                pm.setComponentEnabledSetting(any(), any(), any())
            } answers {
                capturedCalls.add(Triple(firstArg(), secondArg(), thirdArg()))
            }

            viewModel.toggleCamouflageMode(true)

            advanceThroughTheSwap()

            assertEquals(
                "the decoy must be enabled before the real launcher is disabled, " +
                    "so the device is never left with no launchable icon",
                2,
                capturedCalls.size,
            )

            // STEP 1: Enable target (Notes)
            assertEquals("Step 1 must enable NotesAlias", notesAlias, capturedCalls[0].first)
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, capturedCalls[0].second)

            // STEP 2: Disable previous (ShadowMesh)
            assertEquals("Step 2 must disable ShadowMeshAlias", shadowMeshAlias, capturedCalls[1].first)
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, capturedCalls[1].second)
        }

    @Test
    fun toggleCamouflageMode_toDisabled_followsSecureSequentialPattern() =
        runTest(testDispatcher) {
            val capturedCalls = mutableListOf<Triple<ComponentName, Int, Int>>()

            every {
                pm.setComponentEnabledSetting(any(), any(), any())
            } answers {
                capturedCalls.add(Triple(firstArg(), secondArg(), thirdArg()))
            }

            viewModel.toggleCamouflageMode(false)

            advanceThroughTheSwap()

            assertEquals(
                "leaving camouflage must also be ordered, or the user is left with " +
                    "no launchable icon",
                2,
                capturedCalls.size,
            )

            // STEP 1: Enable target (ShadowMesh)
            assertEquals("Step 1 must enable ShadowMeshAlias", shadowMeshAlias, capturedCalls[0].first)
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, capturedCalls[0].second)

            // STEP 2: Disable previous (Notes)
            assertEquals("Step 2 must disable NotesAlias", notesAlias, capturedCalls[1].first)
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, capturedCalls[1].second)
        }
}
