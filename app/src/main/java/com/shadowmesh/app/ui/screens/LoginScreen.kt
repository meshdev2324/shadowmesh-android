package com.shadowmesh.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.shadowmesh.app.VPNManagerViewModel
import com.shadowmesh.app.VPNUiState
import com.shadowmesh.ui_kit.components.*
import com.shadowmesh.ui_kit.theme.LocalShadowMeshColors
import com.shadowmesh.ui_kit.theme.ShadowMeshTheme
import com.shadowmesh.app.ui.pairing.QRScannerScreen
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

import androidx.compose.ui.tooling.preview.Preview

/**
 * High-end Login Experience.
 * SOP 01-12 compliant. Brand mark redesigned per design-system 06: a still,
 * circular Aegis emblem — no rotation, no spinning geometry (visual silence
 * on the entry surface).
 */
@Composable
fun LoginScreen(
    viewModel: VPNManagerViewModel,
    uiState: VPNUiState
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val smColors = LocalShadowMeshColors.current

    var tokenInput by rememberSaveable { mutableStateOf("") }
    val isInputValid = tokenInput.replace("-", "").length == 25

    var showQRScanner by rememberSaveable { mutableStateOf(false) }

    // SOP 07: Error Auto-dismiss logic (3 seconds)
    var displayedError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(uiState.errorMessage) {
        if (uiState.errorMessage != null) {
            displayedError = uiState.errorMessage
            kotlinx.coroutines.delay(3000)
            displayedError = null
            viewModel.clearError() // Synchronize with VM state
        }
    }

    // SOP 01: Organic "Breathing" Aura using Spring Physics (No durations)
    var auraTarget by remember { mutableStateOf(0.08f) }
    val auraAlpha by animateFloatAsState(
        targetValue = auraTarget,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessVeryLow),
        label = "alpha",
        finishedListener = { auraTarget = if (it == 0.08f) 0.04f else 0.08f }
    )

    // Initial trigger
    LaunchedEffect(Unit) { auraTarget = 0.08f }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(smColors.cyberObsidianStart)
    ) {
        // Deep Space Aura: SOP 01 (Calm)
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(700.dp)
                .blur(120.dp)
                .graphicsLayer {
                    alpha = auraAlpha
                    scaleX = 1f + (auraAlpha * 2f)
                    scaleY = 1f + (auraAlpha * 2f)
                }
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF818CF8).copy(alpha = 0.15f),
                            Color.Transparent
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 40.dp)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header: still emblem — calm, no rotation.
            Column(
                modifier = Modifier.padding(top = 72.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                StaggeredContainer(index = 0) {
                    AegisMark(
                        themeColor = smColors.statusReady,
                        modifier = Modifier.semantics { contentDescription = "ShadowMesh Security Core" }
                    )
                }

                StaggeredContainer(index = 1) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "SHADOWMESH",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 6.sp,
                                color = Color.White.copy(alpha = 0.35f)
                            )
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Secure Access",
                            style = MaterialTheme.typography.displaySmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = (-0.5).sp,
                                color = Color.White
                            )
                        )
                    }
                }
            }

            // Central Actions: SOP 11 (Biometric-First)
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Secondary Action: Manual Token Input Enclave
                StaggeredContainer(index = 2) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "ENTER ACCESS CODE",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = Color.White.copy(alpha = 0.25f),
                                letterSpacing = 1.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )

                        TokenMatrixInput(
                            value = tokenInput,
                            onValueChange = { tokenInput = it },
                            isActivating = uiState.isActivating,
                            onQrScan = { showQRScanner = true },
                            modifier = Modifier.fillMaxWidth().testTag("token_input"),
                            onDone = {
                                if (isInputValid && !uiState.isActivating) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.activate(tokenInput)
                                }
                            }
                        )
                    }
                }

                // Primary Action: Passkey Login
                StaggeredContainer(index = 3) {
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            (context as? FragmentActivity)?.let {
                                viewModel.loginWithPasskey(it, tokenInput)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .tactilePressState(),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = smColors.statusReady,
                            contentColor = Color.White
                        )
                    ) {
                        AnimatedContent(
                            targetState = uiState.isActivating,
                            transitionSpec = {
                                fadeIn(tween(140)) togetherWith fadeOut(tween(140))
                            },
                            label = "primaryButtonContent",
                        ) { activating ->
                            if (activating) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    // Branded ring: white sweep, 1.2s/rev.
                                    GradientCircularProgressIndicator(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .testTag("loading_spinner"),
                                        colors = listOf(
                                            Color.White,
                                            Color.White.copy(alpha = 0.35f),
                                            Color.Transparent
                                        ),
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        "VERIFYING…",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Black,
                                            letterSpacing = 1.sp
                                        )
                                    )
                                }
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.Fingerprint,
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Text(
                                        "Sign in with Passkey",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Footer
            Column(
                modifier = Modifier.padding(bottom = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                AnimatedVisibility(
                    visible = displayedError != null,
                    enter = fadeIn(spring(stiffness = Spring.StiffnessLow)) + expandVertically(spring(stiffness = Spring.StiffnessLow)),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    displayedError?.let { msg ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .padding(horizontal = 32.dp)
                                .semantics { liveRegion = LiveRegionMode.Assertive }
                        ) {
                            Icon(
                                Icons.Outlined.ErrorOutline,
                                contentDescription = null,
                                tint = smColors.statusError.copy(alpha = 0.9f),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = msg,
                                color = smColors.statusError.copy(alpha = 0.9f),
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                Text(
                    text = "ShadowMesh Core v1.0",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = Color.White.copy(alpha = 0.18f),
                        letterSpacing = 1.sp
                    )
                )
            }
        }
    }

    if (showQRScanner) {
        QRScannerScreen(
            viewModel = viewModel,
            onDismiss = { showQRScanner = false },
            onResult = { code ->
                tokenInput = code
                showQRScanner = false
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        )
    }
}

/**
 * Aegis Mark — the ShadowMesh brand emblem for the login surface.
 *
 * Design-system 06 (Brand Identity): a still, circular, layered glass emblem.
 * The mesh reads as a network constellation (nodes + chord lines) framing a
 * central radiance; motion is limited to a slow radial breathing of the
 * halo — deliberately NO rotation and NO spinning geometry, so the entry
 * surface stays calm ("visual silence"). All curves are spring-based; the
 * halo is static when accessibility reduces motion.
 */
@Composable
fun AegisMark(
    themeColor: Color,
    modifier: Modifier = Modifier
) {
    // SOP 05: Breathing halo via spring physics (no durations, no rotation).
    var haloTarget by remember { mutableStateOf(1f) }
    val haloScale by animateFloatAsState(
        targetValue = haloTarget,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessVeryLow
        ),
        label = "halo",
        finishedListener = { haloTarget = if (it >= 1f) 0.96f else 1f }
    )
    LaunchedEffect(Unit) { haloTarget = 0.96f }

    // Slow core glow pulse — opacity only, subtle (design-system 03 ambient budget).
    val infiniteTransition = rememberInfiniteTransition(label = "aegis")
    val coreGlow by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "coreGlow"
    )

    // Depth layers (design-system 01): soft ambient halo behind a crisp glass disc.
    Box(
        modifier = modifier
            .size(120.dp)
            .graphicsLayer {
                scaleX = haloScale
                scaleY = haloScale
            },
        contentAlignment = Alignment.Center
    ) {
        // Ambient halo behind the disc (shadow stack: ambient layer).
        Box(
            modifier = Modifier
                .size(148.dp)
                .blur(28.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            themeColor.copy(alpha = 0.25f),
                            Color.Transparent
                        )
                    )
                )
        )

        // Glass disc: crisp 1dp edge highlight, subtle vertical sheen.
        Box(
            modifier = Modifier
                .size(120.dp)
                .shadowMeshGlass(radius = 60f)
                .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(84.dp)) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = size.width / 2f - 8.dp.toPx()

                // Orbital ring: faint, continuous — the "mesh horizon".
                drawCircle(
                    color = themeColor.copy(alpha = 0.22f),
                    radius = radius,
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )

                // Constellation nodes (8) on the ring — optical balance per 01 §4.
                val nodes = 8
                for (i in 0 until nodes) {
                    val angle = (i * 2 * PI / nodes).toFloat()
                    val nodeCenter = Offset(
                        center.x + radius * cos(angle.toDouble()).toFloat(),
                        center.y + radius * sin(angle.toDouble()).toFloat()
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.75f),
                        radius = 1.75.dp.toPx(),
                        center = nodeCenter
                    )
                }

                // Chord lines (skip-one pattern) — a static woven mesh, no spin.
                val chordCount = nodes / 2
                for (i in 0 until nodes) {
                    val j = (i + chordCount) % nodes
                    val a = (i * 2 * PI / nodes).toFloat()
                    val b = (j * 2 * PI / nodes).toFloat()
                    drawLine(
                        color = themeColor.copy(alpha = 0.16f),
                        start = Offset(
                            center.x + radius * cos(a.toDouble()).toFloat(),
                            center.y + radius * sin(a.toDouble()).toFloat()
                        ),
                        end = Offset(
                            center.x + radius * cos(b.toDouble()).toFloat(),
                            center.y + radius * sin(b.toDouble()).toFloat()
                        ),
                        strokeWidth = 0.75f
                    )
                }

                // Central radiance: layered radial gradient (Plus blend) — the shield core.
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            themeColor.copy(alpha = coreGlow),
                            themeColor.copy(alpha = coreGlow * 0.35f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = radius * 0.55f
                    ),
                    radius = radius * 0.55f,
                    center = center,
                    blendMode = androidx.compose.ui.graphics.BlendMode.Plus
                )
                // Solid inner core for optical anchor.
                drawCircle(
                    color = Color.White.copy(alpha = 0.9f),
                    radius = 2.5.dp.toPx(),
                    center = center
                )
            }
        }
    }
}

@Preview
@Composable
fun LoginScreenPreview() {
    ShadowMeshTheme {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A12)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
                AegisMark(themeColor = Color(0xFF818CF8))
                Text("SHADOWMESH", color = Color.White.copy(alpha = 0.35f), letterSpacing = 6.sp, fontSize = 12.sp)
            }
        }
    }
}
