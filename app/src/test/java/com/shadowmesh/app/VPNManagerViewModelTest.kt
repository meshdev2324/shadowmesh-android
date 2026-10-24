package com.shadowmesh.app

import android.content.Context
import android.content.SharedPreferences
import android.net.VpnService
import androidx.lifecycle.SavedStateHandle
import com.shadowmesh.app.identity.IdentityManager
import com.shadowmesh.app.identity.DeviceIdentity
import com.shadowmesh.app.security.SecurityManager
import com.shadowmesh.app.vpn.ConnectionManager
import com.shadowmesh.core_vpn.Config
import com.shadowmesh.core_vpn.SecureStorage
import com.shadowmesh.core_vpn.domain.ConnectionProgress
import com.shadowmesh.core_vpn.domain.MeshNodeEntry
import com.shadowmesh.core_vpn.domain.NodeLoadError
import com.shadowmesh.core_vpn.domain.NodeLoadFailure
import com.shadowmesh.core_vpn.domain.RealitySupport
import com.shadowmesh.core_vpn.domain.SessionSignal
import com.shadowmesh.core_vpn.domain.TrafficModeResolver
import com.shadowmesh.core_vpn.repository.VpnRepository
import io.mockk.*
import com.shadowmesh.app.alert.AlertSeverity
import com.shadowmesh.app.alert.AlertTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import uniffi.shadowmesh.*

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class VPNManagerViewModelTest {

    @get:org.junit.Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var vpnRepository: VpnRepository
    private lateinit var connectionManager: ConnectionManager
    private lateinit var identityManager: IdentityManager
    private lateinit var deviceIdentity: DeviceIdentity
    private lateinit var securityManager: SecurityManager
    private lateinit var secureStorage: SecureStorage
    private lateinit var speedTest: SpeedTest
    private lateinit var savedStateHandle: SavedStateHandle

    private lateinit var viewModel: VPNManagerViewModel
    private lateinit var sharedPreferences: SharedPreferences

    private val connectionProgressFlow = MutableStateFlow<ConnectionProgress>(ConnectionProgress.Idle)
    private val sessionSignalFlow = MutableSharedFlow<SessionSignal>()
    private val isRootedFlow = MutableStateFlow(false)
    private val isIntegrityCompromisedFlow = MutableStateFlow(false)

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.v(any(), any()) } returns 0
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0

        mockkStatic(VpnService::class)
        every { VpnService.prepare(any()) } returns null

        vpnRepository = mockk(relaxed = true)
        connectionManager = mockk(relaxed = true)
        identityManager = mockk(relaxed = true)
        deviceIdentity = mockk(relaxed = true)
        securityManager = mockk(relaxed = true)
        secureStorage = mockk(relaxed = true)
        speedTest = mockk(relaxed = true)
        savedStateHandle = SavedStateHandle()

        sharedPreferences = mockk(relaxed = true)
        every { secureStorage.prefs } returns sharedPreferences

        // Mock manager flows
        every { connectionManager.connectionProgress } returns connectionProgressFlow
        every { connectionManager.sessionSignal } returns sessionSignalFlow
        every { securityManager.isRooted } returns isRootedFlow
        every { securityManager.isIntegrityCompromised } returns isIntegrityCompromisedFlow

        // Mock default state
        every { vpnRepository.getStatus() } returns ConnectionStatus.DISCONNECTED
        every { vpnRepository.isActivated() } returns false
        coEvery { vpnRepository.apiClient.getNodes() } returns NodeRoster(NodeProjection.ANONYMOUS, emptyList())
    }

    private val alerts = com.shadowmesh.app.alert.AlertRepository(
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + mainDispatcherRule.testDispatcher)
    )

    private fun createViewModel() {
        viewModel =
            VPNManagerViewModel(
                vpnRepository,
                connectionManager,
                identityManager,
                deviceIdentity,
                securityManager,
                secureStorage,
                savedStateHandle,
                speedTest,
                alerts,
                ioDispatcher = mainDispatcherRule.testDispatcher
            )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `initialization loads the directory into state`() =
        runTest {
            val client = vpnRepository.apiClient
            coEvery { client.getNodes() } returns NodeRoster(NodeProjection.ANONYMOUS, listOf(
                PublicNode("id1", "SG", "Singapore", true)
            ))
            stageTwoAnswersReality(client, servesReality = true)
            every { vpnRepository.getStatus() } returns ConnectionStatus.DISCONNECTED
            every { vpnRepository.isActivated() } returns true

            createViewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            // Stage 1 only. The entry also carries the capability stage 2
            // established — cold start resolves it now, so comparing whole
            // objects here would assert on stage 2 rather than on discovery.
            val loaded = state.nodes.single()
            assertEquals("id1", loaded.id)
            assertEquals("SG", loaded.country)
            assertEquals("Singapore", loaded.region)
            assertTrue(loaded.isOnline)
            assertTrue(state.isActivated)
            // Discovery succeeded, so there is no error — distinct from the
            // case below, where it also produced an empty list.
            assertNull(state.nodeLoadError)
        }

    /**
     * Stage 2 must run at cold start, and must ask for REALITY explicitly.
     *
     * Two separate regressions, both of which shipped as "the app cannot
     * connect":
     *
     *  - `restoreSelection()` used to assign the selection directly, bypassing
     *    `selectNode()`, so the auto-selected node never had
     *    `GET /api/v1/config/:id` issued at all and capability stayed UNKNOWN
     *    until the user happened to tap a node.
     *  - The probe used to request `mode = null`, which the control plane reads
     *    as its `"normal"` default and answers without a `reality_config` block
     *    — so "I did not ask" was recorded as "this node does not serve
     *    REALITY", REALITY was filtered out of the ladder, and the client dialled
     *    WireGuard over UDP/443 against a node serving VLESS/REALITY on 443.
     *
     * The control plane only populates `reality_config` for `mode=reality`
     * (`services/server/src/api/handlers/vpn.rs:385`), so the probe mode is
     * load-bearing and is asserted here rather than left to inspection.
     */
    @Test
    fun `cold start resolves stage 2 by asking for REALITY`() =
        runTest {
            val client = vpnRepository.apiClient
            coEvery { client.getNodes() } returns NodeRoster(NodeProjection.ANONYMOUS, listOf(
                PublicNode("id1", "SG", "Singapore", true)
            ))
            stageTwoAnswersReality(client, servesReality = true)
            every { vpnRepository.getStatus() } returns ConnectionStatus.DISCONNECTED
            every { vpnRepository.isActivated() } returns true

            createViewModel()
            advanceUntilIdle()

            coVerify(atLeast = 1) { client.getConfig("id1", any(), "reality") }
            // Evidence, not inference: a REALITY-mode response carrying the block
            // is the only thing that may mark a node as serving REALITY.
            assertEquals(
                RealitySupport.SUPPORTED,
                viewModel.uiState.value.nodes.single().realitySupport,
            )
        }

    /**
     * A *refused* REALITY probe must leave capability UNKNOWN, not UNSUPPORTED.
     *
     * The control plane answers `mode=reality` with 400 when an operator never
     * provisioned `vless_uuid` (`vpn.rs:402`). A 400 is a failed question, not a
     * negative answer — recording it as UNSUPPORTED would let the client
     * pre-emptively narrow the transport away from REALITY on the strength of a
     * request that never came back. UNKNOWN keeps the whole ladder available and
     * lets the connect path discover the refusal by trying, with the error
     * surfaced.
     */
    @Test
    fun `a refused REALITY probe does not mark the node unsupported`() =
        runTest {
            val client = vpnRepository.apiClient
            coEvery { client.getNodes() } returns NodeRoster(NodeProjection.ANONYMOUS, listOf(
                PublicNode("id1", "SG", "Singapore", true)
            ))
            coEvery { client.getConfig(any(), any(), any()) } throws
                IllegalStateException("Unexpected status code: 400 Bad Request")
            every { vpnRepository.getStatus() } returns ConnectionStatus.DISCONNECTED
            every { vpnRepository.isActivated() } returns true

            createViewModel()
            advanceUntilIdle()

            val node = viewModel.uiState.value.nodes.single()
            assertEquals(RealitySupport.UNKNOWN, node.realitySupport)
            assertTrue(
                "an unestablished capability must not disable REALITY",
                TrafficModeResolver.supports(node, TrafficMode.REALITY),
            )
        }

    /**
     * Mirrors the real control plane: `reality_config` is populated **only** for
     * a `mode=reality` request, and omitted for every other mode. Stubbing this
     * faithfully is the whole point — a stub that always carries (or never
     * carries) the block would make these tests pass or fail for the wrong
     * reason, which is exactly the confusion that produced the original defect.
     */
    private fun stageTwoAnswersReality(client: uniffi.shadowmesh.ApiClient, servesReality: Boolean) {
        coEvery { client.getConfig(any(), any(), any()) } answers {
            val mode = thirdArg<String?>()
            testVpnConfig(
                realityConfig = if (mode == "reality" && servesReality) testRealityConfig() else null,
            )
        }
    }

    private fun testRealityConfig() = uniffi.shadowmesh.RealityConfig(
        serverIp = "10.0.0.1",
        port = 443u,
        uuid = "uuid",
        publicKey = "reality-key",
        shortId = "",
        sniTarget = "example.com",
        fingerprint = "chrome",
    )

    private fun testVpnConfig(realityConfig: uniffi.shadowmesh.RealityConfig?) =
        uniffi.shadowmesh.VpnConfig(
            privateKey = null,
            publicKey = "server-key",
            address = "10.0.0.1/32",
            endpoint = "10.0.0.1:443",
            dns = "8.8.8.8",
            mtu = 1420u,
            trafficMode = "reality",
            realityConfig = realityConfig,
            shadowsocksConfig = null,
            hysteriaConfig = null,
            vmessConfig = null,
        )

    /**
     * The bug this whole change exists for.
     *
     * Discovery used to be wrapped in `catch (e: Exception) { emptyList() }` and
     * then guarded by `if (nodes.isNotEmpty())`, so a failed request produced a
     * silent empty list. A user staring at zero servers had no error state, no
     * message and no way to learn why. This asserts the failure is now a real,
     * reachable, classified value on the UI state.
     */
    @Test
    fun `a failed discovery surfaces an error state instead of an empty list`() =
        runTest {
            coEvery { vpnRepository.apiClient.getNodes() } throws
                ShadowMeshException.JsonException("missing field `name`")

            createViewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue("discovery failure must be reported", state.nodes.isEmpty())
            val error = state.nodeLoadError
            assertNotNull("a failed discovery must set nodeLoadError", error)
            assertEquals(NodeLoadFailure.MALFORMED_PAYLOAD, error!!.failure)
            assertEquals("The mesh directory came back in an unexpected format.", error.message)
            assertFalse("the spinner must not be left running", state.isDiscoveringNodes)
        }

    @Test
    fun `an empty but successful directory is not reported as a failure`() =
        runTest {
            // The distinction that could not previously be expressed: a mesh with
            // no nodes and a request that failed both produced `emptyList()`.
            coEvery { vpnRepository.apiClient.getNodes() } returns NodeRoster(NodeProjection.ANONYMOUS, emptyList())

            createViewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.nodes.isEmpty())
            assertNull("an empty mesh is not an error", state.nodeLoadError)
            assertFalse(state.isDiscoveringNodes)
        }

    @Test
    fun `discovery waits for the core to be initialized before it runs`() =
        runTest {
            // Ordering, not just correctness. `VpnRepositoryImpl` pushes the
            // device id and session token into the ApiClient on its own
            // background scope during construction. `loadInitialState()` is the
            // ViewModel's first action, so discovery raced that restore and left
            // without a session — losing that race on every cold start.
            val order = mutableListOf<String>()
            coEvery { vpnRepository.awaitInitialized() } coAnswers {
                order += "awaitInitialized"
            }
            coEvery { vpnRepository.apiClient.getNodes() } coAnswers {
                order += "getNodes"
                NodeRoster(NodeProjection.ANONYMOUS, emptyList())
            }

            createViewModel()
            advanceUntilIdle()

            assertEquals(
                listOf("awaitInitialized", "getNodes"),
                order,
            )
        }

    @Test
    fun `discovery still runs when core initialization fails`() =
        runTest {
            // The splash must not hang on the await, and the user must not be
            // left staring at a spinner forever: discovery proceeds and reports
            // whatever it finds.
            coEvery { vpnRepository.awaitInitialized() } throws RuntimeException("core init failed")
            coEvery { vpnRepository.apiClient.getNodes() } returns NodeRoster(NodeProjection.ANONYMOUS, listOf(
                PublicNode("id1", "SG", "Singapore", true)
            ))

            createViewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue("the splash must be dismissed regardless", state.isInitialized)
            assertEquals(1, state.nodes.size)
        }

    @Test
    fun `selection survives a reload that no longer contains the node`() =
        runTest {
            // A transient empty directory must not silently drop the user's
            // choice — and the old `nodes.firstOrNull()` fallback could land on
            // an offline node, which `selectNode` refuses, producing a selection
            // the UI would not let anyone keep.
            every { secureStorage.get(Config.KEY_SELECTED_NODE_ID) } returns "id1"
            coEvery { vpnRepository.apiClient.getNodes() } returns NodeRoster(NodeProjection.ANONYMOUS, listOf(
                PublicNode("id1", "SG", "Singapore", true)
            ))
            createViewModel()
            advanceUntilIdle()
            assertEquals("id1", viewModel.uiState.value.selectedNode?.id)

            coEvery { vpnRepository.apiClient.getNodes() } returns NodeRoster(NodeProjection.ANONYMOUS, emptyList())
            viewModel.refreshNodes()
            advanceUntilIdle()

            assertEquals(
                "an empty directory must not clear a live selection",
                "id1",
                viewModel.uiState.value.selectedNode?.id,
            )
        }

    @Test
    fun `reload falls back to an online node, never an offline one`() =
        runTest {
            every { secureStorage.get(Config.KEY_SELECTED_NODE_ID) } returns "gone"
            coEvery { vpnRepository.apiClient.getNodes() } returns NodeRoster(NodeProjection.ANONYMOUS, listOf(
                PublicNode("offline", "SG", "Singapore", false),
                PublicNode("online", "JP", "Japan", true),
            ))

            createViewModel()
            advanceUntilIdle()

            assertEquals(
                "the fallback must pick a node selectNode will accept",
                "online",
                viewModel.uiState.value.selectedNode?.id,
            )
        }

    @Test
    fun `connection manager progress updates UI state`() =
        runTest {
            createViewModel()
            val connectingProgress =
                ConnectionProgress.Connecting(ConnectionStatus.CONNECTING_DIRECT, "Securing tunnel...", null)
            connectionProgressFlow.value = connectingProgress

            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.isConnecting)
            assertEquals("Securing tunnel...", state.connectionMessage)
        }

    @Test
    fun `zombie detected signal updates UI error message`() =
        runTest {
            createViewModel()
            val zombieSignal = SessionSignal.ZombieDetected("Improving mesh stability. Connection refresh recommended.")

            // We use backgroundScope or launch to emit into the shared flow
            launch {
                sessionSignalFlow.emit(zombieSignal)
            }
            advanceUntilIdle()

            // RFC-028: zombie alerts travel on the alert channel as a sticky
            // CRITICAL, not on uiState.
            val alert = viewModel.alerts.value.single()
            assertEquals(AlertTag.SESSION, alert.tag)
            assertEquals(AlertSeverity.CRITICAL, alert.severity)
            assertTrue(alert.sticky)
            assertEquals(
                "Improving mesh stability. Connection refresh recommended.",
                alert.message,
            )
        }

    @Test
    fun `expired desktop pairing session yields a retryable warning, not a scary critical`() =
        runTest {
            createViewModel()
            advanceUntilIdle()
            // A UUID-shaped session token whose approval window has passed:
            // the server answers "Session expired or not found".
            coEvery { identityManager.authorizeQrSession(any()) } answers {
                throw RuntimeException("Session expired or not found")
            }
            val token = "123e4567-e89b-42d3-a456-426614174000"

            println("DBG classify=${com.shadowmesh.app.vpn.PairingScanPolicy.classify(token)}")
            viewModel.pairWithDesktop(token, pin = null)
            // Deliberately no advanceUntilIdle() here.
            //
            // viewModelScope runs on the rule's UnconfinedTestDispatcher, so the
            // launch body has already executed eagerly by this point. Calling
            // advanceUntilIdle() additionally fast-forwards virtual time, which
            // completes the alert's own AutoDismiss delay and dismisses it
            // before the assertions below run -- which is why this test saw an
            // empty alert list even though post() was called.
            coVerify { identityManager.authorizeQrSession(token) }

            val pairing = viewModel.alerts.value.filter { it.tag == AlertTag.PAIRING }
            assertTrue("pairing alerts=${viewModel.alerts.value}", pairing.isNotEmpty())
            val alert = pairing.single()
            assertEquals(AlertSeverity.WARNING, alert.severity)
            assertTrue(alert.message.contains("Desktop pairing session expired"))
            assertTrue(alert.message.contains("Your activation is unaffected"))
            assertFalse(viewModel.uiState.value.isActivating)
        }

    @Test
    fun `toggleConnection delegates to connectionManager`() =
        runTest {
            val testNode = MeshNodeEntry(id = "id1", country = "USA", region = "US", isOnline = true)
            // Mock state. `getNodes()` returns the anonymous FFI projection;
            // `selectNode` takes the domain entry it maps to.
            coEvery { vpnRepository.apiClient.getNodes() } returns NodeRoster(NodeProjection.ANONYMOUS, listOf(
                PublicNode(id = "id1", country = "USA", region = "US", isOnline = true)
            ))
            createViewModel()
            advanceUntilIdle()
            viewModel.selectNode(testNode)

            val mockContext = mockk<Context>(relaxed = true)
            viewModel.toggleConnection(mockContext)

            verify {
                connectionManager.toggleConnection(
                    node = match { it.id == testNode.id && it.country == testNode.country },
                    preference = any(),
                    isCamouflage = any(),
                    dnsLeakProtection = any()
                )
            }
        }

    @Test
    fun `logout updates activation state`() =
        runTest {
            coEvery { identityManager.logout() } returns Unit
            createViewModel()

            viewModel.logout()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isActivated)
        }

    @Test
    fun `security manager signals update UI state`() =
        runTest {
            createViewModel()
            isRootedFlow.value = true
            isIntegrityCompromisedFlow.value = true

            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.isRooted)
            assertTrue(state.isIntegrityCompromised)
            assertTrue(state.showRootWarning)
        }

    @Test
    fun `activate success updates state`() = runTest {
        val response = ActivationResponse(
            message = "Success",
            token = "t",
            plan = "Solo",
            expiresAt = "",
            remainingDays = 1L,
            subscriptionNotice = "",
            devicesRemaining = 1,
            vpnConfig = null,
            isCanary = null,
            serverLocation = null
        )
        coEvery { identityManager.activate("CODE") } returns Result.success(response)
        createViewModel()

        viewModel.activate("CODE")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isActivated)
        assertFalse(viewModel.uiState.value.isActivating)
    }

    @Test
    fun `selectNode updates current node`() = runTest {
        val node = MeshNodeEntry(id = "id", country = "USA", region = "US", isOnline = true)
        createViewModel()

        viewModel.selectNode(node)
        advanceUntilIdle()

        // Stage 2 resolves capability after selection, so the stored entry is an
        // enriched copy. Assert the selection, not whole-object equality against
        // the pre-enrichment value.
        val selected = viewModel.uiState.value.selectedNode
        assertEquals(node.id, selected?.id)
        assertEquals(node.country, selected?.country)
        assertEquals(node.region, selected?.region)
    }

    /**
     * Regression: a correct PIN must release the Security Lock.
     *
     * MainActivity renders SecurityLockScreen with an empty onUnlocked lambda
     * (commented "Logic handled in VM"), so verifyPin() was the only place that
     * could clear isSecurityLockEnabled. It did not, so the app stayed on
     * "App Locked" forever even with the right PIN. Because the flag is
     * re-derived from the stored hash on every launch, the user was locked out
     * permanently until the app data was cleared.
     */
    @Test
    fun `correct pin releases the security lock`() = runTest {
        // The lock must start ENGAGED, otherwise assertFalse passes trivially
        // and the test proves nothing. A stored hash is what makes
        // loadPersistentSettings() set isSecurityLockEnabled.
        every { sharedPreferences.contains(any()) } returns true
        createViewModel()
        advanceUntilIdle()
        assertTrue(
            "precondition: the lock should be engaged before unlocking",
            viewModel.uiState.value.isSecurityLockEnabled
        )

        every { securityManager.verifyPin("2468") } returns true
        val ok = viewModel.verifyPin("2468")
        advanceUntilIdle()

        assertTrue(ok)
        assertFalse(
            "a verified PIN must clear isSecurityLockEnabled",
            viewModel.uiState.value.isSecurityLockEnabled
        )
    }

    @Test
    fun `wrong pin keeps the security lock engaged`() = runTest {
        createViewModel()
        every { securityManager.verifyPin("0000") } returns false

        val ok = viewModel.verifyPin("0000")
        advanceUntilIdle()

        assertFalse(ok)
    }

    /**
     * Regression: setting the Main PIN must not strand the user on the lock
     * screen before the duress-PIN step can be reached.
     */
    @Test
    fun `setPin does not engage the lock before setup finishes`() = runTest {
        createViewModel()
        every { securityManager.verifyPin(any()) } returns false

        viewModel.setPin("2468")
        advanceUntilIdle()

        assertFalse(
            "setPin must not switch the UI into the locked state mid-setup",
            viewModel.uiState.value.isSecurityLockEnabled
        )
    }

    @Test
    fun `finishPinSetup engages the lock when a pin was stored`() = runTest {
        createViewModel()
        every { sharedPreferences.contains(any()) } returns true

        viewModel.finishPinSetup()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isSecurityLockEnabled)
    }
}
