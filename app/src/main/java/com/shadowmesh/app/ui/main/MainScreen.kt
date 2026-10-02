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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import com.shadowmesh.app.R
import com.shadowmesh.app.VPNManagerViewModel
import com.shadowmesh.app.VPNUiState
import com.shadowmesh.core_vpn.domain.TrafficModeResolver
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

/** Flat positional data for one Neo-Brutalist block: position and size as
 *  fractions of the canvas, plus its fill color. */
private data class BrutalistBlock(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val color: Color,
)

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

    // SOP 02 & 05 "Living UI" breathing glow, rendered static.
    //
    // These values used to come from an infinite transition, and
    // breathingAlpha is read inside the full-screen background Canvas below,
    // so while connected every frame rebuilt full-screen gradient shaders and
    // fed Skia's purgeable GPU cache -- the same per-frame-allocation
    // mechanism that fills it to its ~370 MB cap (PERF-ISSUES.md 0b). The
    // mid-point static value is indistinguishable for a glow this subtle and
    // stops the per-frame invalidation entirely.
    val breathingAlpha = 0.055f

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A0F))) {
        // The breathing glow is drawn as a small, bounded element rather than
        // inside the full-screen Canvas.
        //
        // It used to be drawn inside the full-screen Canvas, so every frame of
        // the infinite transition rebuilt a radial-gradient shader spanning 1.5x
        // the screen width. Measured on device (OPPO CPH2363, Android 14) that
        // drove Graphics memory to 472 MB and total PSS to 539 MB, against a
        // 24 MB native heap - which is what OEM low-memory management kills on
        // a long session. The full-screen Canvas now paints only static
        // artwork, so it is drawn once per recomposition rather than per frame.
        if (isConnected) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.75f)
                    .fillMaxHeight(0.30f)
                    .align(Alignment.TopCenter)
                    .padding(top = 80.dp)
                    .graphicsLayer { alpha = (breathingAlpha * 6f).coerceIn(0f, 1f) }
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF10B981), Color.Transparent),
                            center = androidx.compose.ui.geometry.Offset.Unspecified,
                            radius = 900f,
                        ),
                        shape = CircleShape,
                    )
            )
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Switching over the closed enum, so a style cannot be offered
            // without artwork. The previous switch ran on a raw string with only
            // four branches: "Quantum Grid" and "Neon Midnight" matched nothing,
            // drew nothing, and left a flat canvas.
            when (com.shadowmesh.app.ui.theme.BackgroundStyle.fromName(uiState.backgroundStyle)) {
                com.shadowmesh.app.ui.theme.BackgroundStyle.CYBER_NEBULA -> { 
                    // Static base wash. The live breathing glow is the bounded
                    // element above, so this no longer rebuilds a full-screen
                    // shader on every animation frame.
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(themeColor.copy(alpha = 0.03f), Color.Transparent),
                            center = Offset(size.width * 0.5f, size.height * 0.3f),
                            radius = size.width * 0.9f
                        )
                    )
                }
                com.shadowmesh.app.ui.theme.BackgroundStyle.OBSIDIAN_STEALTH -> { drawRect(Color(0xFF020617)) }
                com.shadowmesh.app.ui.theme.BackgroundStyle.DEEP_SPACE -> { drawRect(Color.Black) }
                com.shadowmesh.app.ui.theme.BackgroundStyle.QUANTUM_GRID -> {
                    // Technical lattice, in the same language as the other
                    // styles: dark base, signature accent, breathing when the
                    // tunnel is up.
                    drawRect(Color(0xFF04060E))
                    val step = size.minDimension / 14f
                    val accent = (if (isConnected) themeColor else themeColor.copy(alpha = 0.5f))
                    var gx = step
                    while (gx < size.width) {
                        drawLine(
                            color = accent.copy(alpha = if (isConnected) 0.07f * breathingAlpha else 0.04f),
                            start = Offset(gx, 0f),
                            end = Offset(gx, size.height),
                            strokeWidth = 1f,
                        )
                        gx += step
                    }
                    var gy = step
                    while (gy < size.height) {
                        drawLine(
                            color = accent.copy(alpha = if (isConnected) 0.07f * breathingAlpha else 0.04f),
                            start = Offset(0f, gy),
                            end = Offset(size.width, gy),
                            strokeWidth = 1f,
                        )
                        gy += step
                    }
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accent.copy(alpha = if (isConnected) 0.10f * breathingAlpha else 0.05f),
                                Color.Transparent,
                            ),
                            center = Offset(size.width * 0.5f, size.height * 0.5f),
                            radius = size.minDimension * 0.9f,
                        )
                    )
                }
                com.shadowmesh.app.ui.theme.BackgroundStyle.NEON_MIDNIGHT -> {
                    // Midnight base with two crossing neon bands.
                    drawRect(Color(0xFF0B0620))
                    val neonA = Color(0xFF22D3EE)
                    val neonB = Color(0xFFF472B6)
                    val bandHeight = size.height * 0.42f
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, neonA.copy(alpha = 0.10f), Color.Transparent),
                            startY = size.height * 0.5f - bandHeight,
                            endY = size.height * 0.5f + bandHeight,
                        ),
                        topLeft = Offset(0f, size.height * 0.5f - bandHeight),
                        size = androidx.compose.ui.geometry.Size(size.width, bandHeight * 2f),
                    )
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, neonB.copy(alpha = 0.08f), Color.Transparent),
                            startY = 0f,
                            endY = size.height * 0.6f,
                        ),
                        topLeft = Offset(0f, size.height * 0.2f),
                        size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.6f),
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                neonA.copy(alpha = if (isConnected) 0.12f * breathingAlpha else 0.05f),
                                Color.Transparent,
                            ),
                            center = Offset(size.width * 0.5f, size.height * 0.45f),
                            radius = size.width * 0.9f,
                        )
                    )
                }
                com.shadowmesh.app.ui.theme.BackgroundStyle.CYBERPUNK -> {
                    // Night-city vocabulary: dark violet base, a magenta sun
                    // on the horizon, and a cyan perspective grid receding
                    // from it. Everything is static geometry -- lines fan from
                    // the vanishing point and horizontal rows bunch up near
                    // the horizon -- so nothing here redraws differently
                    // per frame.
                    drawRect(Color(0xFF0D0221))
                    val horizonY = size.height * 0.62f
                    val vanish = Offset(size.width * 0.5f, horizonY)
                    val neon = Color(0xFF22D3EE)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFFF2E88).copy(alpha = 0.35f),
                                Color(0xFF7C3AED).copy(alpha = 0.12f),
                                Color.Transparent,
                            ),
                            center = vanish,
                            radius = size.width * 0.45f,
                        )
                    )
                    for (i in -6..6) {
                        drawLine(
                            color = neon.copy(alpha = 0.10f),
                            start = vanish,
                            end = Offset(size.width * 0.5f + i * size.width * 0.16f, size.height),
                            strokeWidth = 1.5f,
                        )
                    }
                    var rowT = 0.04f
                    while (rowT < 1f) {
                        val y = horizonY + (size.height - horizonY) * rowT
                        drawLine(
                            color = neon.copy(alpha = 0.08f),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1f,
                        )
                        rowT *= 1.55f
                    }
                }
                com.shadowmesh.app.ui.theme.BackgroundStyle.NEO_BRUTALIST -> {
                    // Brutalist vocabulary translated to a dark UI: flat
                    // fills, thick near-black borders and hard offset
                    // shadows. Deliberately no gradient, no glow, no blur --
                    // every soft effect here would be an offscreen render
                    // target, and flatness IS the style.
                    drawRect(Color(0xFF141417))
                    val blocks = listOf(
                        BrutalistBlock(0.08f, 0.14f, 0.36f, 0.10f, Color(0xFFFACC15)),
                        BrutalistBlock(0.56f, 0.30f, 0.34f, 0.08f, Color(0xFFEF4444)),
                        BrutalistBlock(0.12f, 0.52f, 0.28f, 0.07f, Color(0xFF3B82F6)),
                    )
                    val shadowShift = 6.dp.toPx()
                    val border = 3.dp.toPx()
                    for (b in blocks) {
                        val topLeft = Offset(size.width * b.x, size.height * b.y)
                        val block = androidx.compose.ui.geometry.Size(size.width * b.w, size.height * b.h)
                        drawRect(
                            color = Color.Black,
                            topLeft = topLeft + Offset(shadowShift, shadowShift),
                            size = block,
                        )
                        drawRect(color = b.color, topLeft = topLeft, size = block)
                        drawRect(
                            color = Color(0xFF0A0A0F),
                            topLeft = topLeft,
                            size = block,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = border),
                        )
                    }
                }
                // Minimalist draws nothing decorative at all. Every branch of
                // the other styles allocates offscreen render targets, which is
                // the cost this style exists to avoid.
                com.shadowmesh.app.ui.theme.BackgroundStyle.MINIMALIST -> { drawRect(Color(0xFF0A0A0F)) }
                com.shadowmesh.app.ui.theme.BackgroundStyle.ELECTRIC_HORIZON -> { 
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
                when (qrScannerPurpose) {
                    "Activation" -> viewModel.activate(result)
                    // RFC-018: a desktop-as-new-device pairing token. This branch
                    // did not exist, so a scanned desktop QR was silently
                    // discarded and the screen just closed.
                    "Pairing" -> viewModel.pairWithDesktop(result, null)
                    else -> Unit
                }
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
        val selectedNodeSupportsStealth = uiState.selectedNode?.let { TrafficModeResolver.isRealityNode(it) } == true
        val modes = listOf(
            Triple(TrafficModePreference.AUTO, "Auto", "Choose the best compatible route"),
            Triple(TrafficModePreference.SPEED, "Direct", "Prefer the fastest compatible route"),
            Triple(TrafficModePreference.STEALTH, "Stealth", if (selectedNodeSupportsStealth) "Use REALITY obfuscation" else "Requires a REALITY node")
        )
        modes.forEach { (pref, label, subtitle) ->
            TrafficModeOption(
                label = label, 
                subtitle = subtitle, 
                selected = uiState.trafficModePreference == pref,
                accentColor = themeColor,
                enabled = pref != TrafficModePreference.STEALTH || selectedNodeSupportsStealth,
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
