package com.shadowmesh.app

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shadowmesh.app.di.IoDispatcher
import com.shadowmesh.app.identity.IdentityManager
import com.shadowmesh.app.security.SecurityManager
import com.shadowmesh.app.vpn.ConnectionManager
import com.shadowmesh.core_vpn.Config
import com.shadowmesh.core_vpn.SecureStorage
import com.shadowmesh.core_vpn.domain.ConnectionProgress
import com.shadowmesh.core_vpn.domain.SessionSignal
import com.shadowmesh.core_vpn.repository.VpnRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import uniffi.shadowmesh.*
import javax.inject.Inject

/**
 * Central ViewModel for managing VPN state and user interactions.
 *
 * This implementation follows strict MVI (Model-View-Intent) principles and
 * decomposes complex logic into specialized managers (Identity, Security, Connection).
 *
 * SOP 09 §1: Presentation layer orchestration.
 */
@HiltViewModel
class VPNManagerViewModel @Inject constructor(
    private val vpnRepository: VpnRepository,
    private val connectionManager: ConnectionManager,
    private val identityManager: IdentityManager,
    private val securityManager: SecurityManager,
    private val secureStorage: SecureStorage,
    private val savedStateHandle: SavedStateHandle,
    private val speedTest: SpeedTest,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VPNUiState())

    /**
     * Exposes the current UI state as a read-only [StateFlow].
     */
    val uiState: StateFlow<VPNUiState> = _uiState.asStateFlow()

    init {
        observeManagers()
        loadInitialState()
    }

    private fun observeManagers() {
        // Observe Connection Progress - SOP 02: Confidence, not entertainment
        viewModelScope.launch {
            connectionManager.connectionProgress.collect { progress: ConnectionProgress ->
                updateUiState { currentState: VPNUiState ->
                    val nextState = when (progress) {
                        is ConnectionProgress.Connecting -> currentState.copy(
                            isConnecting = true,
                            status = progress.status,
                            connectionMessage = progress.message,
                            selectedNode = progress.node ?: currentState.selectedNode
                        )
                        is ConnectionProgress.Connected -> currentState.copy(
                            isConnecting = false,
                            status = ConnectionStatus.CONNECTED,
                            assignedIp = progress.assignedIp,
                            isQuantumTunnelingActive = progress.isQuantum,
                            connectionMessage = "You are Protected"
                        )
                        is ConnectionProgress.Error -> currentState.copy(
                            isConnecting = false,
                            status = ConnectionStatus.ERROR,
                            errorMessage = progress.message
                        )
                        is ConnectionProgress.Idle -> currentState.copy(
                            isConnecting = false,
                            status = vpnRepository.getStatus(),
                            connectionMessage = null
                        )
                        else -> currentState
                    }
                    nextState
                }
            }
        }

        // Observe Session Signals - SOP 08: Zombie Connection Detection
        viewModelScope.launch {
            connectionManager.sessionSignal.collect { signal: SessionSignal ->
                updateUiState { currentState: VPNUiState ->
                    val nextState = when (signal) {
                        is SessionSignal.StatsUpdated -> currentState.copy(
                            connectionStats = signal.stats.connectionStats,
                            quantumBytesTotal = signal.stats.protocolStats.quantumSent + signal.stats.protocolStats.quantumReceived,
                            realityBytesTotal = signal.stats.protocolStats.realitySent + signal.stats.protocolStats.realityReceived,
                            totalBytesThisMonth = signal.stats.totalBytesThisMonth,
                            isEbpfActive = signal.stats.isEbpfActive
                        )
                        is SessionSignal.SessionFrozen -> currentState.copy(isSessionFrozen = true)
                        is SessionSignal.Unauthorized -> {
                            logout()
                            currentState
                        }
                        is SessionSignal.ZombieDetected -> currentState.copy(errorMessage = signal.message)
                        else -> currentState
                    }
                    nextState
                }
            }
        }

        // Observe Security State - SOP 11: Security UX
        viewModelScope.launch {
            securityManager.isRooted.collect { isRooted: Boolean -> 
                updateUiState { it.copy(isRooted = isRooted, showRootWarning = isRooted) } 
            }
        }

        viewModelScope.launch {
            securityManager.isIntegrityCompromised.collect { isCompromised: Boolean ->
                updateUiState { it.copy(isIntegrityCompromised = isCompromised) }
            }
        }
        // Horizon 4: XR display glasses monitoring — intentionally NOT polled here.
        // A prior stub loop emitted a constant state every 5s (battery drain) and
        // broke virtual-time test schedulers. Wire it to a real XR SDK callback
        // when the integration lands.
    }

    private fun loadInitialState() {
        viewModelScope.launch(ioDispatcher) {
            // Load settings first (from local storage)
            loadPersistentSettings()
            
            // Immediately show UI with cached nodes
            val cachedNodes = vpnRepository.nodeCache.getAll()
            val restoredNodeId = secureStorage.get(Config.KEY_SELECTED_NODE_ID)
            val selectedNode = cachedNodes.find { it.id == restoredNodeId } ?: cachedNodes.firstOrNull()
            
            updateUiState {
                it.copy(
                    nodes = cachedNodes,
                    selectedNode = selectedNode,
                    status = vpnRepository.getStatus(),
                    isActivated = vpnRepository.isActivated(),
                    isInitialized = true // DISMISS SPLASH NOW
                )
            }

            // TODO(SOP-14 phased rollout): security manifest fetch deferred — the
            // uniffi ApiClient does not expose fetchSecurityManifest() yet. Re-add
            // once the Rust client generates the method.

            // Refresh nodes from network in background using resilient discovery (RFC-004)
            try {
                android.util.Log.i("SHADOWMESH_DEBUG", "🔍 Starting node discovery...")
                
                val nodes = try {
                    vpnRepository.apiClient.getNodes()
                } catch (e: Exception) {
                    android.util.Log.w("SHADOWMESH_DEBUG", "⚠️ Primary discovery failed", e)
                    emptyList()
                }

                android.util.Log.i("SHADOWMESH_DEBUG", "🌍 Discovery complete. Total nodes: ${nodes.size}")

                if (nodes.isNotEmpty()) {
                    vpnRepository.nodeCache.putAll(nodes)
                    vpnRepository.setNodes(nodes)
                    val currentSelected = uiState.value.selectedNode
                    val updatedSelected = nodes.find { it.id == currentSelected?.id } ?: nodes.firstOrNull()
                    updateUiState { it.copy(nodes = nodes, selectedNode = updatedSelected, isDiscoveryResilient = true) }
                }
            } catch (e: Exception) {
                updateUiState { it.copy(isDiscoveryResilient = false) }
            }
        }
    }

    private fun loadPersistentSettings() {
        val prefs = secureStorage.prefs
        
        val trafficMode = runCatching { 
            TrafficModePreference.valueOf(prefs.getString(Config.KEY_TRAFFIC_MODE, null) ?: TrafficModePreference.AUTO.name)
        }.getOrDefault(TrafficModePreference.AUTO)
        
        val stEnabled = prefs.getBoolean(Config.KEY_ST_ENABLED, false)
        val stMode = runCatching {
            SplitTunnelMode.valueOf(prefs.getString(Config.KEY_ST_MODE, null)?.uppercase() ?: SplitTunnelMode.EXCLUDE.name)
        }.getOrDefault(SplitTunnelMode.EXCLUDE)
        
        val stApps = prefs.getStringSet(Config.KEY_ST_APPS, emptySet())?.toList() ?: emptyList()

        val interactionMethod = runCatching {
            ConnectionInteractionMethod.valueOf(prefs.getString(Config.KEY_INTERACTION_METHOD, null) ?: ConnectionInteractionMethod.SLIDE.name)
        }.getOrDefault(ConnectionInteractionMethod.SLIDE)

        updateUiState {
            it.copy(
                isCamouflageEnabled = prefs.getBoolean(Config.KEY_CAMOUFLAGE_ENABLED, false),
                killSwitchEnabled = prefs.getBoolean(Config.KEY_KILL_SWITCH_ENABLED, false),
                isSecurityLockEnabled = prefs.contains(Config.KEY_PIN_HASH),
                isPanicArmed = prefs.contains(Config.KEY_PANIC_PIN_HASH),
                trafficModePreference = trafficMode,
                themeColor = prefs.getLong(Config.KEY_THEME_COLOR, 0xFF6366F1),
                backgroundStyle = prefs.getString(Config.KEY_BACKGROUND_STYLE, "Cyber Nebula") ?: "Cyber Nebula",
                interactionMethod = interactionMethod,
                dnsLeakProtectionEnabled = prefs.getBoolean(Config.KEY_DNS_LEAK_PROTECTION, true),
                advancedStealthQuantum = prefs.getBoolean(Config.KEY_ADVANCED_STEALTH_QUANTUM, false),
                quantumLevel = runCatching {
                    uniffi.shadowmesh.QuantumResistanceLevel.valueOf(prefs.getString("quantum_level", null) ?: uniffi.shadowmesh.QuantumResistanceLevel.NONE.name)
                }.getOrDefault(uniffi.shadowmesh.QuantumResistanceLevel.NONE),
                splitTunnelConfig = SplitTunnelConfig(stEnabled, stMode, stApps),
                customDNSServers = prefs.getStringSet(Config.KEY_CUSTOM_DNS_SERVERS, emptySet())?.toList() ?: emptyList(),
                isScreenshotEnabled = prefs.getBoolean(Config.KEY_SCREENSHOTS_ENABLED, false),
                favoriteNodeIds = prefs.getStringSet(Config.KEY_FAVORITE_NODES, emptySet()) ?: emptySet()
            )
        }
    }

    /**
     * Toggles the VPN connection state.
     * SOP 02 §2: One-Tap Connection.
     */
    fun toggleConnection(context: android.content.Context) {
        val intent = android.net.VpnService.prepare(context)
        if (intent != null) {
            updateUiState { it.copy(prepareVpnTrigger = true) }
            return
        }
        
        val currentState = uiState.value
        val node = currentState.selectedNode ?: currentState.nodes.firstOrNull() ?: return
        
        connectionManager.toggleConnection(
            node = node,
            preference = currentState.trafficModePreference,
            isCamouflage = currentState.isCamouflageEnabled,
            dnsLeakProtection = currentState.dnsLeakProtectionEnabled
        )
    }

    fun onVpnPrepared(success: Boolean) {
        updateUiState { it.copy(prepareVpnTrigger = false) }
        if (success) {
            // SOP 02: Auto-connect after permission is granted
            val currentState = uiState.value
            val node = currentState.selectedNode ?: currentState.nodes.firstOrNull() ?: return
            
            connectionManager.toggleConnection(
                node = node,
                preference = currentState.trafficModePreference,
                isCamouflage = currentState.isCamouflageEnabled,
                dnsLeakProtection = currentState.dnsLeakProtectionEnabled
            )
        }
    }

    /**
     * Activates the device with a license code.
     */
    fun activate(code: String) {
        viewModelScope.launch {
            updateUiState { it.copy(isActivating = true) }
            identityManager.activate(code).onSuccess { response ->
                updateUiState {
                    it.copy(
                        isActivated = true,
                        isActivating = false,
                        showActivationScreen = false,
                        planName = response.plan ?: "Solo",
                        isCanaryToken = response.isCanary ?: false
                    )
                }
            }.onFailure { e ->
                updateUiState { it.copy(isActivating = false, errorMessage = e.localizedMessage) }
            }
        }
    }

    /**
     * Logs in using a Passkey bound to the activation code.
     * SOP 11: Zero-PII Sovereignty Auth.
     */
    fun loginWithPasskey(activity: FragmentActivity, code: String) {
        viewModelScope.launch {
            updateUiState { it.copy(isActivating = true) }
            identityManager.loginWithPasskey(code, activity).onSuccess { response ->
                updateUiState {
                    it.copy(
                        isActivated = true,
                        isActivating = false,
                        showActivationScreen = false,
                        planName = response.plan ?: "Solo",
                        isCanaryToken = response.isCanary ?: false
                    )
                }
            }.onFailure { e ->
                updateUiState { it.copy(isActivating = false, errorMessage = e.localizedMessage) }
            }
        }
    }

    /**
     * Logs out the user and deactivates the mesh locally.
     */
    fun logout() {
        viewModelScope.launch {
            identityManager.logout()
            updateUiState { it.copy(isActivated = false) }
        }
    }

    /**
     * Toggles camouflage mode (Notes app decoy).
     */
    fun toggleCamouflageMode(enabled: Boolean) {
        securityManager.toggleCamouflage(enabled) { isEnabled ->
            updateUiState { it.copy(isCamouflageEnabled = isEnabled) }
            savedStateHandle["camouflage_enabled"] = isEnabled
        }
    }

    /**
     * Verifies if the provided PIN matches the stored hash.
     */
    fun verifyPin(pin: String): Boolean = securityManager.verifyPin(pin)

    /**
     * Sets a new primary security PIN.
     */
    fun setPin(pin: String) {
        securityManager.setPin(pin)
        updateUiState { it.copy(isSecurityLockEnabled = true) }
    }

    /**
     * Sets a duress PIN for panic wipe.
     */
    fun setPanicPin(pin: String) {
        securityManager.setPanicPin(pin)
        updateUiState { it.copy(isPanicArmed = true) }
    }

    private fun updateUiState(reducer: (VPNUiState) -> VPNUiState) {
        _uiState.update(reducer)
    }

    private suspend fun fetchNodes(): List<VpnNode> = withContext(ioDispatcher) {
        try {
            vpnRepository.apiClient.getNodes().ifEmpty { vpnRepository.nodeCache.getAll() }
        } catch (e: Exception) {
            vpnRepository.nodeCache.getAll()
        }
    }

    /**
     * Clears the current UI error message.
     */
    fun clearError() { 
        updateUiState { it.copy(errorMessage = null) } 
    }
    
    /**
     * Updates the selected mesh entry node.
     */
    fun selectNode(node: VpnNode) {
        vpnRepository.setSelectedNode(node)
        secureStorage.set(Config.KEY_SELECTED_NODE_ID, node.id)
        updateUiState { it.copy(selectedNode = node) }
        savedStateHandle["selected_node_id"] = node.id
    }

    /**
     * Toggles a node's favorite status.
     */
    fun toggleFavorite(nodeId: String) {
        val current = uiState.value.favoriteNodeIds.toMutableSet()
        if (current.contains(nodeId)) {
            current.remove(nodeId)
        } else {
            current.add(nodeId)
        }
        secureStorage.prefs.edit().putStringSet(Config.KEY_FAVORITE_NODES, current).apply()
        updateUiState { it.copy(favoriteNodeIds = current) }
    }

    /**
     * Updates the node search query for UI filtering.
     */
    fun setNodeSearchQuery(query: String) {
        updateUiState { it.copy(nodeSearchQuery = query) }
    }

    /**
     * Automatically selects the best node based on latency and load.
     */
    fun selectBestNode() {
        viewModelScope.launch(ioDispatcher) {
            val bestNode = vpnRepository.vpnManager.getBestNode()
            bestNode?.let { node ->
                withContext(Dispatchers.Main) {
                    selectNode(node)
                }
            }
        }
    }

    /**
     * Manually refreshes the node list from the network.
     */
    fun refreshNodes() {
        viewModelScope.launch(ioDispatcher) {
            updateUiState { it.copy(isLoadingNodes = true) }
            try {
                val nodes = try {
                    val res = vpnRepository.apiClient.getNodes()
                    if (res.isEmpty()) vpnRepository.apiClient.getNodesResilient() else res
                } catch (e: Exception) {
                    vpnRepository.apiClient.getNodesResilient()
                }

                if (nodes.isNotEmpty()) {
                    vpnRepository.nodeCache.putAll(nodes)
                    vpnRepository.setNodes(nodes)
                    val currentSelected = uiState.value.selectedNode
                    val updatedSelected = nodes.find { it.id == currentSelected?.id } ?: nodes.firstOrNull()
                    updateUiState { it.copy(nodes = nodes, selectedNode = updatedSelected, isDiscoveryResilient = true, isLoadingNodes = false) }
                } else {
                    updateUiState { it.copy(isLoadingNodes = false) }
                }
            } catch (e: Exception) {
                updateUiState { it.copy(isDiscoveryResilient = false, isLoadingNodes = false) }
            }
        }
    }

    /**
     * Enables or disables the global VPN Kill Switch.
     */
    fun setKillSwitch(enabled: Boolean) {
        viewModelScope.launch {
            updateUiState { it.copy(isKillSwitchLoading = true) }
            try {
                connectionManager.setKillSwitchEnabled(enabled)
                updateUiState { it.copy(killSwitchEnabled = enabled, isKillSwitchLoading = false) }
            } catch (e: Exception) {
                updateUiState { it.copy(isKillSwitchLoading = false, errorMessage = e.localizedMessage) }
            }
        }
    }

    /**
     * Updates the traffic routing preference (Auto, Speed, Stealth).
     */
    fun setTrafficModePreference(pref: TrafficModePreference) {
        secureStorage.prefs.edit().putString(Config.KEY_TRAFFIC_MODE, pref.name).apply()
        connectionManager.setTrafficModePreference(pref)
        updateUiState { it.copy(trafficModePreference = pref) }
    }

    /**
     * Configures split tunneling rules.
     */
    fun setSplitTunnelConfig(config: SplitTunnelConfig) {
        connectionManager.setSplitTunnelConfig(config)
        updateUiState { it.copy(splitTunnelConfig = config) }
    }

    /**
     * Performs a network diagnostic scan.
     */
    fun runNetworkDetection(force: Boolean) {
        viewModelScope.launch {
            updateUiState { it.copy(isDetectingNetwork = true) }
            try {
                val report = connectionManager.runNetworkDetection(force)
                updateUiState { it.copy(networkReport = report, isDetectingNetwork = false) }
            } catch (e: Exception) {
                updateUiState { it.copy(isDetectingNetwork = false, errorMessage = e.localizedMessage) }
            }
        }
    }

    /**
     * Adds a custom DNS server to the configuration.
     */
    fun addCustomDNSServer(dns: String) {
        val current = uiState.value.customDNSServers.toMutableList()
        if (dns !in current) {
            current.add(dns)
            secureStorage.prefs.edit().putStringSet(Config.KEY_CUSTOM_DNS_SERVERS, current.toSet()).apply()
            updateUiState { it.copy(customDNSServers = current) }
        }
    }

    /**
     * Removes a custom DNS server.
     */
    fun removeCustomDNSServer(dns: String) {
        val current = uiState.value.customDNSServers.toMutableList()
        if (current.remove(dns)) {
            secureStorage.prefs.edit().putStringSet(Config.KEY_CUSTOM_DNS_SERVERS, current.toSet()).apply()
            updateUiState { it.copy(customDNSServers = current) }
        }
    }

    /**
     * Shows or hides the custom DNS settings UI.
     */
    fun setShowCustomDNSSettings(show: Boolean) { 
        updateUiState { it.copy(showCustomDNSSettings = show) } 
    }

    /**
     * Shows or hides the VPN setup guide modal.
     */
    fun setShowVPNGuideModal(show: Boolean) { 
        updateUiState { it.copy(showVPNGuideModal = show) } 
    }

    /**
     * Checks if the user has accepted the VPN system consent.
     */
    fun hasAcceptedVpnConsent(): Boolean = 
        secureStorage.prefs.getBoolean(Config.KEY_VPN_CONSENT, false)

    /**
     * Records that the user has accepted the VPN system consent.
     */
    fun acceptVpnConsent() { 
        secureStorage.prefs.edit().putBoolean(Config.KEY_VPN_CONSENT, true).apply() 
    }
    
    /**
     * Initiates the MFA (Multi-Factor Authentication) setup flow.
     */
    fun startMfaSetup() {
        viewModelScope.launch {
            try {
                val (qrCode, secret) = identityManager.setupMfaBegin()
                updateUiState { it.copy(mfaQrCode = qrCode, mfaSecret = secret, showMfaSetup = true) }
            } catch (e: Exception) {
                updateUiState { it.copy(errorMessage = "MFA Setup Failed: ${e.localizedMessage}") }
            }
        }
    }

    /**
     * Completes the MFA verification and enables it.
     */
    fun completeMfaSetup(code: String) {
        viewModelScope.launch {
            try {
                identityManager.setupMfaFinish(code)
                updateUiState { it.copy(isMfaEnabled = true, showMfaSetup = false) }
            } catch (e: Exception) {
                updateUiState { it.copy(errorMessage = "MFA Verification Failed: ${e.localizedMessage}") }
            }
        }
    }

    /**
     * Dismisses the MFA setup UI.
     */
    fun dismissMfaSetup() { 
        updateUiState { it.copy(showMfaSetup = false) } 
    }

    /**
     * Registers a passkey for the user.
     */
    fun registerPasskey(activity: FragmentActivity, userId: String) {
        viewModelScope.launch {
            identityManager.registerPasskey(userId, activity).onSuccess {
                updateUiState { it.copy(isPasskeyEnabled = true) }
            }.onFailure { e ->
                updateUiState { it.copy(errorMessage = "Passkey Registration Failed: ${e.localizedMessage}") }
            }
        }
    }

    /**
     * Updates the app's primary theme color.
     */
    fun setThemeColor(color: Long) {
        secureStorage.prefs.edit().putLong(Config.KEY_THEME_COLOR, color).apply()
        updateUiState { it.copy(themeColor = color) }
    }

    /**
     * Updates the background visual style.
     */
    fun setBackgroundStyle(style: String) {
        secureStorage.prefs.edit().putString(Config.KEY_BACKGROUND_STYLE, style).apply()
        updateUiState { it.copy(backgroundStyle = style) }
    }

    /**
     * Updates the interaction method (Tap vs Slide).
     */
    fun setInteractionMethod(method: ConnectionInteractionMethod) {
        secureStorage.prefs.edit().putString(Config.KEY_INTERACTION_METHOD, method.name).apply()
        updateUiState { it.copy(interactionMethod = method) }
    }

    /**
     * Toggles DNS and IPv6 leak protection.
     */
    fun toggleDnsLeakProtection(enabled: Boolean) {
        secureStorage.prefs.edit().putBoolean(Config.KEY_DNS_LEAK_PROTECTION, enabled).apply()
        updateUiState { it.copy(dnsLeakProtectionEnabled = enabled) }
    }

    /**
     * Toggles advanced packet fragmentation to bypass deep inspection.
     */
    fun toggleAdvancedStealthQuantum(enabled: Boolean) {
        secureStorage.prefs.edit().putBoolean(Config.KEY_ADVANCED_STEALTH_QUANTUM, enabled).apply()
        updateUiState { it.copy(advancedStealthQuantum = enabled) }
    }

    /**
     * Horizon 3: Sets the desired quantum resistance level.
     */
    fun setQuantumLevel(level: uniffi.shadowmesh.QuantumResistanceLevel) {
        secureStorage.prefs.edit().putString("quantum_level", level.name).apply()
        updateUiState { it.copy(quantumLevel = level) }
    }

    /**
     * Disables the security PIN and clears all stored hashes.
     */
    fun disablePin() {
        secureStorage.prefs.edit().apply {
            remove(Config.KEY_PIN_HASH)
            remove(Config.KEY_PIN_SALT)
            remove(Config.KEY_PANIC_PIN_HASH)
            remove(Config.KEY_PANIC_PIN_SALT)
        }.apply()
        updateUiState { it.copy(isSecurityLockEnabled = false, isPanicArmed = false) }
    }

    /**
     * Toggles the ability to take screenshots in the app (SOP 12 §4).
     */
    fun toggleScreenshotMode(enabled: Boolean) {
        secureStorage.prefs.edit().putBoolean(Config.KEY_SCREENSHOTS_ENABLED, enabled).apply()
        updateUiState { it.copy(isScreenshotEnabled = enabled) }
    }

    /**
     * Runs a full mesh speed test.
     */
    fun runSpeedTest() {
        viewModelScope.launch(ioDispatcher) {
            updateUiState { it.copy(speedTestState = SpeedTestState(isRunning = true)) }
            try {
                val result = speedTest.runFullTest()
                updateUiState { it.copy(speedTestState = SpeedTestState(isRunning = false, result = result)) }
            } catch (e: Exception) {
                updateUiState { it.copy(speedTestState = SpeedTestState(isRunning = false, error = e.localizedMessage)) }
            }
        }
    }

    /**
     * Resumes a paused connection.
     */
    fun resumeConnection() { 
        connectionManager.resumeTunnel() 
    }
    
    /**
     * Pauses the connection for a specified duration.
     */
    fun pauseConnection(minutes: UInt) { 
        connectionManager.pauseTunnel(minutes.toInt()) 
    }

    /**
     * Triggers the system biometric authentication prompt.
     */
    fun authenticateWithBiometrics(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        val executor = androidx.core.content.ContextCompat.getMainExecutor(activity)
        val biometricPrompt = androidx.biometric.BiometricPrompt(
            activity, executor,
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onFailure()
                }
                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    onFailure()
                }
            })

        val promptInfo = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
            .setTitle("Biometric Login")
            .setSubtitle("Log in using your biometric credential")
            .setNegativeButtonText("Use PIN")
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    /**
     * Pairs the mobile app with a ShadowMesh Desktop client.
     */
    fun pairWithDesktop(scannedCode: String, pin: String) {
        viewModelScope.launch {
            updateUiState { it.copy(isActivating = true, errorMessage = null) }
            identityManager.pairWithDesktop(scannedCode, pin).onSuccess {
                updateUiState { it.copy(isActivating = false) }
            }.onFailure { e ->
                updateUiState { it.copy(isActivating = false, errorMessage = "Pairing Error: ${e.localizedMessage}") }
            }
        }
    }

    /**
     * RFC-018: issues a member key for pairing a new device. The key is
     * rendered as a QR on this (authorized) device and scanned/typed on
     * the new device's activation screen.
     */
    fun issuePairingToken() {
        viewModelScope.launch {
            try {
                val token = identityManager.issueMemberToken("device-pairing")
                updateUiState { it.copy(qrToken = token, qrStatus = "issued") }
            } catch (e: Exception) {
                updateUiState { it.copy(errorMessage = "Pairing key issue failed: ${e.localizedMessage}") }
            }
        }
    }

    /**
     * RFC-018: approves a pairing session token shown by a new device
     * (e.g., the desktop app). Server mints the member key into the
     * session; the waiting device picks it up on its next poll.
     */
    fun authorizePairing(token: String) {
        viewModelScope.launch {
            try {
                identityManager.authorizeQrSession(token.trim())
                updateUiState { it.copy(qrStatus = "authorized-by-this-device") }
            } catch (e: Exception) {
                updateUiState { it.copy(errorMessage = "Pairing approval failed: ${e.localizedMessage}") }
            }
        }
    }

    /**
     * Generates a QR token for desktop pairing.
     */
    fun generateQrPairingToken() {
        viewModelScope.launch {
            try {
                val token = identityManager.generateQrPairingToken()
                updateUiState { it.copy(qrToken = token, qrStatus = "pending") }
                startQrStatusPolling(token)
            } catch (e: Exception) {
                updateUiState { it.copy(errorMessage = "QR Generation Failed: ${e.localizedMessage}") }
            }
        }
    }

    private fun startQrStatusPolling(token: String) {
        viewModelScope.launch {
            repeat(30) {
                try {
                    val status = identityManager.checkQrStatus(token)
                    updateUiState { it.copy(qrStatus = status) }
                    if (status == "authorized") return@launch
                    delay(10000)
                } catch (e: Exception) {
                    delay(10000)
                }
            }
        }
    }
}
