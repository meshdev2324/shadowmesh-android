package com.shadowmesh.app

import android.content.Context
import android.content.SharedPreferences
import android.net.VpnService
import androidx.lifecycle.SavedStateHandle
import com.shadowmesh.app.identity.IdentityManager
import com.shadowmesh.app.security.SecurityManager
import com.shadowmesh.app.vpn.ConnectionManager
import com.shadowmesh.core_vpn.SecureStorage
import com.shadowmesh.core_vpn.domain.ConnectionProgress
import com.shadowmesh.core_vpn.domain.SessionSignal
import com.shadowmesh.core_vpn.repository.VpnRepository
import io.mockk.*
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
        coEvery { vpnRepository.apiClient.getNodes() } returns emptyList()
    }

    private fun createViewModel() {
        viewModel =
            VPNManagerViewModel(
                vpnRepository,
                connectionManager,
                identityManager,
                securityManager,
                secureStorage,
                savedStateHandle,
                speedTest,
                ioDispatcher = mainDispatcherRule.testDispatcher
            )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `initialization updates state from repository with resilient nodes`() =
        runTest {
            val testNodes =
                listOf(
                    VpnNode("id1", "Node 1", "US", "USA", "1.2.3.4", "pub", 10u, 50u, true, true, null),
                )
            // loadInitialState refreshes via apiClient.getNodes() (RFC-004 path).
            coEvery { vpnRepository.apiClient.getNodes() } returns testNodes
            every { vpnRepository.getStatus() } returns ConnectionStatus.DISCONNECTED
            every { vpnRepository.isActivated() } returns true

            createViewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(testNodes, state.nodes)
            assertTrue(state.isActivated)
            assertTrue(state.isDiscoveryResilient)
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

            val state = viewModel.uiState.value
            assertEquals("Improving mesh stability. Connection refresh recommended.", state.errorMessage)
        }

    @Test
    fun `toggleConnection delegates to connectionManager`() =
        runTest {
            val testNode = VpnNode("id1", "Node 1", "US", "USA", "1.2.3.4", "pub", 10u, 50u, true, true, null)
            // Mock state
            coEvery { vpnRepository.apiClient.getNodes() } returns listOf(testNode)
            createViewModel()
            advanceUntilIdle()
            viewModel.selectNode(testNode)

            val mockContext = mockk<Context>(relaxed = true)
            viewModel.toggleConnection(mockContext)

            verify {
                connectionManager.toggleConnection(
                    node = testNode,
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
        val node = VpnNode("id", "N", "US", "USA", "1.1.1.1", "pub", 0u, 0u, true, true, null)
        createViewModel()

        viewModel.selectNode(node)
        advanceUntilIdle()

        assertEquals(node, viewModel.uiState.value.selectedNode)
        verify { vpnRepository.setSelectedNode(node) }
    }
}
