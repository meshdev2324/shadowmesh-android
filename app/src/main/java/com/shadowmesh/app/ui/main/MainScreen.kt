package com.shadowmesh.app.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shadowmesh.app.R
import com.shadowmesh.app.VPNManagerViewModel
import com.shadowmesh.app.VPNUiState
import com.shadowmesh.app.ui.pairing.QRScannerScreen
import com.shadowmesh.app.ui.screens.*
import com.shadowmesh.ui_kit.components.*
import uniffi.shadowmesh.*
import androidx.window.core.layout.WindowWidthSizeClass

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.shadowmesh.ui_kit.performance.LocalPerformanceProfile
import com.shadowmesh.ui_kit.performance.PerformanceProfile

/**
 * Modernized Navigation Host for the ShadowMesh application.
 * SOP 02: High-end UI Screen orchestration.
 * SOP 09: Unidirectional Data Flow (UDF).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: VPNManagerViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val localContext = LocalContext.current
    val adaptiveInfo = currentWindowAdaptiveInfo()
    val isExpanded = adaptiveInfo.windowSizeClass.windowWidthSizeClass == androidx.window.core.layout.WindowWidthSizeClass.EXPANDED
    
    var currentScreen by rememberSaveable { mutableStateOf("Status") }
    var showQRScanner by rememberSaveable { mutableStateOf(false) }
    var qrScannerPurpose by rememberSaveable { mutableStateOf("Pairing") }
    var showAccountScreen by rememberSaveable { mutableStateOf(false) }
    var showNodeSheet by rememberSaveable { mutableStateOf(false) }
    var showAppPicker by rememberSaveable { mutableStateOf(false) }
    var pinSetupStep by rememberSaveable { mutableIntStateOf(0) }
    var showModeSelector by rememberSaveable { mutableStateOf(false) }

    val themeColor = Color(uiState.themeColor)

    val isSubScreenActive = remember(uiState.showCustomDNSSettings, uiState.showVPNGuideModal, showQRScanner, showAccountScreen, showAppPicker, showModeSelector, pinSetupStep) {
        uiState.showCustomDNSSettings || uiState.showVPNGuideModal || showQRScanner || showAccountScreen || showAppPicker || showModeSelector || pinSetupStep > 0
    }
    
    BackHandler(enabled = isSubScreenActive || currentScreen != "Status") { 
        when { 
            showQRScanner -> showQRScanner = false
            showAccountScreen -> showAccountScreen = false
            showAppPicker -> showAppPicker = false
            showModeSelector -> showModeSelector = false
            pinSetupStep > 0 -> pinSetupStep = 0
            uiState.showCustomDNSSettings -> viewModel.setShowCustomDNSSettings(false)
            uiState.showVPNGuideModal -> viewModel.setShowVPNGuideModal(false)
            currentScreen == "Settings" -> currentScreen = "Status"
        } 
    }

    Box(modifier = Modifier.fillMaxSize()) {
        VPNConsentWrapper(viewModel, uiState, themeColor)
        
        DynamicBackground(uiState, themeColor)

        NavigationHost(
            currentScreen = currentScreen,
            uiState = uiState,
            viewModel = viewModel,
            isExpanded = isExpanded,
            showAccountScreen = showAccountScreen,
            onOpenAccount = { showAccountScreen = true },
            onOpenNodeSelection = { showNodeSheet = true },
            onOpenModeSelector = { showModeSelector = true },
            onOpenQRScanner = { purpose -> qrScannerPurpose = purpose; showQRScanner = true },
            showAppPicker = showAppPicker,
            onShowAppPicker = { showAppPicker = it },
            pinSetupStep = pinSetupStep,
            onPinSetupStepChange = { pinSetupStep = it },
            onNavigate = { currentScreen = it }
        )

        BottomNavigationLayer(
            isSubScreenActive = isSubScreenActive,
            isExpanded = isExpanded,
            themeColor = themeColor,
            currentScreen = currentScreen,
            onNavigate = { currentScreen = it }
        )
        
        OverlayLayer(
            showQRScanner = showQRScanner,
            showModeSelector = showModeSelector,
            showNodeSheet = showNodeSheet,
            qrScannerPurpose = qrScannerPurpose,
            uiState = uiState,
            themeColor = themeColor,
            viewModel = viewModel,
            onDismissQR = { showQRScanner = false },
            onDismissMode = { showModeSelector = false },
            onDismissNodes = { showNodeSheet = false }
        )
    }
}

@Composable
private fun DynamicBackground(uiState: VPNUiState, themeColor: Color) {
    val performanceProfile = LocalPerformanceProfile.current
    
    // SOP 05: Immediate bailout for LOW performance profile to save battery.
    if (performanceProfile == PerformanceProfile.LOW) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A0F)))
        return
    }

    val isConnected = uiState.status == ConnectionStatus.CONNECTED
    
    // Lifecycle-aware pausing to zero-out GPU/CPU when backgrounded.
    val lifecycleOwner = LocalLifecycleOwner.current
    var isLifecycleActive by remember { mutableStateOf(true) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isLifecycleActive = event != Lifecycle.Event.ON_STOP
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!isLifecycleActive) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A0F)))
        return
    }

    val infiniteTransition = rememberInfiniteTransition(label = "background_breathing")
    
    // SOP 02 & 05: Breathing background animation for "Living UI"
    val breathingScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isConnected) 1.25f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_scale"
    )

    val breathingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.03f,
        targetValue = if (isConnected) 0.08f else 0.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A0F))) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            when (uiState.backgroundStyle) {
                "Cyber Nebula" -> { 
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                if (isConnected) Color(0xFF10B981).copy(alpha = breathingAlpha) 
                                else themeColor.copy(alpha = 0.03f), 
                                Color.Transparent
                            ), 
                            center = Offset(size.width * 0.5f, size.height * 0.3f), 
                            radius = size.width * 1.5f * (if (isConnected) breathingScale else 1f)
                        )
                    ) 
                }
                "Obsidian Stealth" -> { drawRect(Color(0xFF020617)) }
                "Deep Space" -> { drawRect(Color.Black) }
                "Electric Horizon" -> { 
                    val horizonY = size.height * 0.7f
                    drawRect(Color(0xFF050508))
                    drawLine(
                        brush = Brush.horizontalGradient(listOf(Color.Transparent, themeColor.copy(alpha = 0.2f), Color.Transparent)), 
                        start = Offset(0f, horizonY), 
                        end = Offset(size.width, horizonY), 
                        strokeWidth = 1.dp.toPx()
                    ) 
                }
            }
        }
    }
}

@Composable
private fun NavigationHost(
    currentScreen: String,
    uiState: VPNUiState,
    viewModel: VPNManagerViewModel,
    isExpanded: Boolean,
    showAccountScreen: Boolean,
    onOpenAccount: () -> Unit,
    onOpenNodeSelection: () -> Unit,
    onOpenModeSelector: () -> Unit,
    onOpenQRScanner: (String) -> Unit,
    showAppPicker: Boolean,
    onShowAppPicker: (Boolean) -> Unit,
    pinSetupStep: Int,
    onPinSetupStepChange: (Int) -> Unit,
    onNavigate: (String) -> Unit
) {
    val localContext = LocalContext.current
    val target = remember(uiState.showCustomDNSSettings, uiState.showVPNGuideModal, showAccountScreen, currentScreen) { 
        when { 
            uiState.showCustomDNSSettings -> "DNS"
            uiState.showVPNGuideModal -> "Guide"
            showAccountScreen -> "Account"
            currentScreen == "Status" -> "Status"
            currentScreen == "Settings" -> "Settings"
            else -> "Status" 
        } 
    }
    
    Crossfade(
        targetState = target, 
        label = "main_navigation",
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy)
    ) { targetState ->
        when (targetState) {
            "DNS" -> CustomDNSSettingsScreen(uiState = uiState, onAddDNS = { viewModel.addCustomDNSServer(it) }, onRemoveDNS = { viewModel.removeCustomDNSServer(it) }, onBack = { viewModel.setShowCustomDNSSettings(false) })
            "Guide" -> VPNGuideScreen(uiState = uiState, onBack = { viewModel.setShowVPNGuideModal(false) })
            "Account" -> AccountScreen(viewModel = viewModel, uiState = uiState, onLogout = { viewModel.logout(); onNavigate("Status") }, onBack = { onNavigate("Status") }, onOpenScanner = { onOpenQRScanner("Activation") })
            "Status" -> StatusScreen(
                viewModel = viewModel,
                uiState = uiState,
                onToggleConnection = { viewModel.toggleConnection(localContext) },
                onOpenNodeSelection = onOpenNodeSelection,
                onClearError = { viewModel.clearError() },
                onOpenAccount = onOpenAccount,
                onOpenModeSelector = onOpenModeSelector,
                isExpanded = isExpanded
            )
            "Settings" -> SettingsScreen(
                viewModel = viewModel,
                uiState = uiState,
                onSetKillSwitch = { viewModel.setKillSwitch(it) },
                onSetTrafficModePreference = { viewModel.setTrafficModePreference(it) },
                onSetSplitTunnelConfig = { viewModel.setSplitTunnelConfig(it) },
                onToggleCamouflage = { viewModel.toggleCamouflageMode(it) },
                onShowCustomDNSSettings = { viewModel.setShowCustomDNSSettings(true) },
                onShowVPNGuide = { viewModel.setShowVPNGuideModal(true) },
                onShowQRScanner = { onOpenQRScanner("Pairing") },
                runNetworkDetection = { viewModel.runNetworkDetection(it) },
                showAppPicker = showAppPicker,
                onShowAppPicker = onShowAppPicker,
                pinSetupStep = pinSetupStep,
                onPinSetupStepChange = onPinSetupStepChange,
                isExpanded = isExpanded
            )
        }
    }
}

@Composable
private fun BoxScope.BottomNavigationLayer(
    isSubScreenActive: Boolean,
    isExpanded: Boolean,
    themeColor: Color,
    currentScreen: String,
    onNavigate: (String) -> Unit
) {
    AnimatedVisibility(
        visible = !isSubScreenActive, 
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(), 
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(), 
        modifier = Modifier
            .align(if (isExpanded) Alignment.CenterStart else Alignment.BottomCenter)
            .then(if (isExpanded) Modifier.systemBarsPadding() else Modifier.navigationBarsPadding())
    ) { 
        Box(modifier = Modifier.padding(if (isExpanded) 32.dp else 0.dp).padding(bottom = if (isExpanded) 0.dp else 16.dp)) {
            FloatingNavBar(themeColor = themeColor, currentScreen = currentScreen, onNavigate = onNavigate)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OverlayLayer(
    showQRScanner: Boolean,
    showModeSelector: Boolean,
    showNodeSheet: Boolean,
    qrScannerPurpose: String,
    uiState: VPNUiState,
    themeColor: Color,
    viewModel: VPNManagerViewModel,
    onDismissQR: () -> Unit,
    onDismissMode: () -> Unit,
    onDismissNodes: () -> Unit
) {
    if (showQRScanner) { 
        QRScannerScreen(
            viewModel = viewModel, 
            onDismiss = onDismissQR, 
            onResult = { result -> 
                if (qrScannerPurpose == "Activation") { viewModel.activate(result) }
                onDismissQR()
            }
        ) 
    }
    
    if (showModeSelector) {
        ModalBottomSheet(
            onDismissRequest = onDismissMode,
            containerColor = Color(0xFF0F0F1A),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            ModeSelectorContent(uiState, themeColor, viewModel, onDismissMode)
        }
    }

    if (showNodeSheet) { 
        NodeSelectionSheet(
            nodes = uiState.nodes,
            selectedNode = uiState.selectedNode,
            favoriteNodeIds = uiState.favoriteNodeIds,
            searchQuery = uiState.nodeSearchQuery,
            isLoading = uiState.isLoadingNodes,
            onSearchQueryChange = { viewModel.setNodeSearchQuery(it) },
            onToggleFavorite = { viewModel.toggleFavorite(it) },
            onRefresh = { viewModel.refreshNodes() },
            onSelectBest = { 
                viewModel.selectBestNode()
                onDismissNodes()
            },
            onNodeSelected = { node ->
                viewModel.selectNode(node)
                onDismissNodes()
            },
            onDismissRequest = onDismissNodes,
            themeColor = themeColor
        )
    }
}

@Composable
private fun ModeSelectorContent(uiState: VPNUiState, themeColor: Color, viewModel: VPNManagerViewModel, onDismiss: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { 
        Text(stringResource(R.string.settings_quick_mode_switch), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black), color = Color.White)
        val haptic = LocalHapticFeedback.current
        val modes = listOf(
            Triple(TrafficModePreference.AUTO, "Auto", "Optimized mesh routing"), 
            Triple(TrafficModePreference.SPEED, "Speed", "Direct WireGuard performance"), 
            Triple(TrafficModePreference.STEALTH, "Stealth", "REALITY obfuscation protocol")
        )
        modes.forEach { (pref, label, subtitle) ->
            TrafficModeOption(
                label = label, 
                subtitle = subtitle, 
                selected = uiState.trafficModePreference == pref, 
                accentColor = themeColor, 
                onClick = { 
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.setTrafficModePreference(pref)
                    onDismiss()
                }
            ) 
        } 
    }
}

@Composable
private fun VPNConsentWrapper(viewModel: VPNManagerViewModel, uiState: VPNUiState, themeColor: Color) {
    var showConsentDialog by remember { mutableStateOf(!viewModel.hasAcceptedVpnConsent()) }
    if (showConsentDialog) {
        val consentTitle = if (uiState.isCamouflageEnabled) stringResource(R.string.permission_sync_title) else stringResource(R.string.permission_vpn_title)
        val consentText = if (uiState.isCamouflageEnabled) stringResource(R.string.permission_sync_text) else stringResource(R.string.permission_vpn_text)
        ShadowMeshAlertDialog(
            onDismissRequest = { },
            title = consentTitle,
            text = consentText,
            confirmButtonText = stringResource(R.string.permission_accept_button),
            onConfirm = {
                viewModel.acceptVpnConsent()
                showConsentDialog = false
            },
            themeColor = themeColor
        )
    }
}
