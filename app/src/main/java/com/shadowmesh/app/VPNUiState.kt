package com.shadowmesh.app

import com.shadowmesh.core_vpn.domain.MeshNodeEntry
import com.shadowmesh.core_vpn.domain.NodeDetail
import com.shadowmesh.core_vpn.domain.NodeLoadError
import com.shadowmesh.core_vpn.domain.RealitySupport
import uniffi.shadowmesh.*

enum class ConnectionInteractionMethod { TAP, SLIDE }

data class SpeedTestState(
    val isRunning: Boolean = false,
    val result: SpeedTestResult? = null,
    val error: String? = null,
    val progress: Float = 0f,
)

data class VPNUiState(
    val vpnManager: VpnManager? = null,
    val apiClient: ApiClient? = null,
    val killSwitchManager: KillSwitchManagerInterface? = null,
    val securityEventLogger: SecurityEventLogger? = null,
    val networkDetector: NetworkDetector? = null,
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    /**
     * Stage 2 of discovery: the authenticated, connectable detail for
     * [selectedNode], resolved from `GET /api/v1/config/:server_id`.
     *
     * `null` until it resolves. Nothing about the tunnel may be read from
     * [selectedNode] — the directory entry carries no endpoint and no key, and
     * the only source for either is this.
     */
    val selectedNodeDetail: NodeDetail? = null,
    /** Stage 1 of discovery: the anonymous directory projection. */
    val selectedNode: MeshNodeEntry? = null,
    val nodes: List<MeshNodeEntry> = emptyList(),
    val favoriteNodeIds: Set<String> = emptySet(),
    val nodeSearchQuery: String = "",
    val isLoadingNodes: Boolean = false,
    /**
     * A stage-1 discovery attempt is in flight.
     *
     * Separate from [isLoadingNodes] (a user-initiated refresh) because the two
     * have different origins and the cold-start one fires before the user has
     * done anything at all.
     */
    val isDiscoveringNodes: Boolean = false,
    /**
     * Why the directory could not be loaded, or `null` if it could — including
     * when it loaded and was genuinely empty.
     *
     * This field is the fix. The previous code caught every discovery failure,
     * returned `emptyList()`, and then skipped its own update on the empty
     * result, so a malformed response and a mesh with no nodes were the same
     * value on screen and neither produced a message. An empty list with no
     * explanation is the bug that was reported.
     */
    val nodeLoadError: NodeLoadError? = null,
    val isConnecting: Boolean = false,
    val isActivated: Boolean = false,
    val activationCode: String? = null,
    val showActivationScreen: Boolean = false,
    val isActivating: Boolean = false,
    val connectionMessage: String? = null,
    val userSettings: UserSettings = UserSettings(QuantumResistanceLevel.NONE, false, false, false, emptyList()),
    val connectionStats: ConnectionStats = ConnectionStats(0uL, 0uL, 0uL, 0uL, 0L, 0L),
    val killSwitchEnabled: Boolean = false,
    val killSwitchState: KillSwitchState? = null,
    val killSwitchAuditLog: List<KillSwitchEvent> = emptyList(),
    val dnsLeakProtectionEnabled: Boolean = true,
    val isKillSwitchLoading: Boolean = false,
    val killSwitchError: String? = null,
    val trafficModePreference: TrafficModePreference = TrafficModePreference.AUTO,
    /** The transport the ladder actually selected for the live session (e.g. "reality"). */
    val activeTransport: String? = null,
    /** Result of the in-tunnel leak check, when one has been run. */
    val leakCheck: com.shadowmesh.ui_kit.components.LeakCheckSummary? = null,
    val isRunningLeakCheck: Boolean = false,
    val isSecurityLockEnabled: Boolean = false,
    val isPanicArmed: Boolean = false,
    val splitTunnelConfig: SplitTunnelConfig = SplitTunnelConfig(false, SplitTunnelMode.EXCLUDE, emptyList()),
    val customDNSServers: List<String> = emptyList(),
    val showCustomDNSSettings: Boolean = false,
    val showVPNGuideModal: Boolean = false,
    val interactionMethod: ConnectionInteractionMethod = ConnectionInteractionMethod.SLIDE,
    val isInitialized: Boolean = false,
    val isPanicTriggered: Boolean = false,
    val isCamouflageEnabled: Boolean = false,
    val isQuantumTunnelingActive: Boolean = false,
    val isScreenshotEnabled: Boolean = false,
    val isIntegrityCompromised: Boolean = false,
    val isSessionFrozen: Boolean = false,
    val assignedIp: String? = null,
    val advancedStealthQuantum: Boolean = false,
    val speedTestState: SpeedTestState = SpeedTestState(),
    val planName: String = "Solo",
    val devicesRemaining: Int = 0,
    val remainingDays: Long = 0,
    val quantumBytesTotal: ULong = 0uL,
    val realityBytesTotal: ULong = 0uL,
    val networkReport: NetworkReport? = null,
    val isDetectingNetwork: Boolean = false,
    val pausedUntil: Long? = null,
    val totalBytesThisMonth: ULong = 0uL,
    val securityEvents: List<SecurityEvent> = emptyList(),
    val qrToken: String? = null,
    val qrStatus: String? = null,
    val isAdmin: Boolean = false,
    val isMfaEnabled: Boolean = false,
    val isPasskeyEnabled: Boolean = false,
    val showMfaSetup: Boolean = false,
    val mfaQrCode: String? = null,
    val mfaSecret: String? = null,
    val themeColor: Long = 0xFF6366F1,
    val backgroundStyle: String = "Cyber Nebula",
    val isRooted: Boolean = false,
    val showRootWarning: Boolean = false,
    val isTunnelVerified: Boolean = false,
    val lastVerificationTime: Long = 0L,
    val isEbpfActive: Boolean = false,
    val isDiscoveryResilient: Boolean = false,
    val isCanaryToken: Boolean = false,
    val isSovereignNode: Boolean = false,
    val isGlassesConnected: Boolean = false,
    val quantumLevel: uniffi.shadowmesh.QuantumResistanceLevel = uniffi.shadowmesh.QuantumResistanceLevel.NONE,
    val featureFlags: Map<String, Boolean> = emptyMap(),
    val prepareVpnTrigger: Boolean = false,
)

/**
 * Whether the Stealth (REALITY) option can be chosen for the current node.
 *
 * Three states, because the directory does not publish whether a node serves
 * REALITY — only the authenticated per-node config does. The old gate was a
 * boolean `isRealityNode(node)` that read the endpoint port, and on a
 * directory entry there is no endpoint to read: rendering "Requires a REALITY
 * node" before asking would have been an answer, just a wrong one, shown to
 * every user on the first frame.
 */
enum class StealthAvailability { AVAILABLE, UNAVAILABLE, CHECKING }

fun VPNUiState.stealthAvailability(): StealthAvailability =
    when (selectedNode?.realitySupport) {
        RealitySupport.SUPPORTED -> StealthAvailability.AVAILABLE
        RealitySupport.UNSUPPORTED -> StealthAvailability.UNAVAILABLE
        // No node chosen, or the node's capabilities have not been resolved yet.
        else -> StealthAvailability.CHECKING
    }
