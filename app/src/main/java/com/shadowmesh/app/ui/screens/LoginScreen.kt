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
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.testTag
import com.shadowmesh.ui_kit.theme.ShadowMeshTheme
import androidx.compose.ui.semantics.Role
import androidx.fragment.app.FragmentActivity
import com.shadowmesh.app.VPNManagerViewModel
import com.shadowmesh.app.VPNUiState
import com.shadowmesh.ui_kit.components.*
import com.shadowmesh.ui_kit.theme.LocalShadowMeshColors
import com.shadowmesh.app.ui.pairing.QRScannerScreen
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

import androidx.compose.ui.tooling.preview.Preview

/**
 * High-end Login Experience.
 * SOP 01-12 compliant.
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

    // SOP 05: Organic "Breathing" Aura using Spring Physics (No durations)
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
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header
            Column(
                modifier = Modifier.padding(top = 64.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StaggeredContainer(index = 0) {
                    SovereignOrb(
                        themeColor = smColors.statusReady,
                        modifier = Modifier.semantics { contentDescription = "Security Core" }
                    )
                }

                StaggeredContainer(index = 1) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "SHADOWMESH",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 6.sp,
                                color = Color.White.copy(alpha = 0.3f)
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
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding(), // SOP 02: Hardware-aware padding
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Secondary Action: Manual Token Input Enclave (MOVED TO TOP)
                StaggeredContainer(index = 2) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "ENTER ACCESS CODE",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = Color.White.copy(alpha = 0.2f),
                                letterSpacing = 1.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TokenMatrixInput(
                                value = tokenInput,
                                onValueChange = { tokenInput = it },
                                isActivating = uiState.isActivating,
                                onQrScan = { showQRScanner = true },
                                modifier = Modifier.weight(1f).testTag("token_input"),
                                onDone = {
                                    if (isInputValid && !uiState.isActivating) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.activate(tokenInput)
                                    }
                                }
                            )
                        }
                    }
                }

                // Primary Action: Passkey Login (MOVED TO BOTTOM)
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
                        if (uiState.isActivating) {
                            GradientCircularProgressIndicator(
                                modifier = Modifier
                                    .size(24.dp)
                                    .testTag("loading_spinner"),
                                strokeWidth = 3.dp
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Outlined.Fingerprint, contentDescription = null, modifier = Modifier.size(22.dp))
                                Text(
                                    "Sign in with Passkey",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
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
                        Text(
                            text = msg,
                            color = smColors.statusError.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                    }
                }

                Text(
                    text = "ShadowMesh Core v1.0",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = Color.White.copy(alpha = 0.15f),
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
 * High-End Sovereign Brand Orb.
 * SOP 03: Precision depth via canvas-drawn geometry.
 */
@Composable
fun SovereignOrb(
    themeColor: Color,
    modifier: Modifier = Modifier
) {
    // SOP 05: Floating Physics (No durations)
    var floatTarget by remember { mutableStateOf(8f) }
    val floatingOffset by animateFloatAsState(
        targetValue = floatTarget,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessVeryLow),
        label = "float",
        finishedListener = { floatTarget = if (it == 8f) -8f else 8f }
    )

    val infiniteTransition = rememberInfiniteTransition(label = "orb")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2 * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(12000, easing = LinearEasing)),
        label = "phase"
    )

    // Central Pulsing Core Animation
    val corePulse by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(2000, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "core"
    )

    // Initial trigger
    LaunchedEffect(Unit) { floatTarget = 8f }

    Box(
        modifier = modifier
            .size(110.dp)
            .graphicsLayer { translationY = floatingOffset }
            .shadowMeshGlass(radius = 32f)
            .border(0.5.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(32.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize(0.55f).graphicsLayer { rotationZ = phase * (180 / PI).toFloat() }) {
            val radius = size.width / 2
            val center = Offset(size.width / 2, size.height / 2)
            
            // SOP 05: Simplified Mesh for "Visual Silence"
            val points = 4 
            val strokeWidth = 1.dp.toPx()
            
            for (i in 0 until points) {
                val angle = (i * 2 * PI / points).toFloat()
                val x = center.x + radius * cos(angle.toDouble()).toFloat()
                val y = center.y + radius * sin(angle.toDouble()).toFloat()
                
                for (j in i + 1 until points) {
                    val nextAngle = (j * 2 * PI / points).toFloat()
                    val nx = center.x + radius * cos(nextAngle.toDouble()).toFloat()
                    val ny = center.y + radius * sin(nextAngle.toDouble()).toFloat()
                    
                    drawLine(
                        color = themeColor.copy(alpha = 0.2f),
                        start = Offset(x, y),
                        end = Offset(nx, ny),
                        strokeWidth = strokeWidth
                    )
                }
                
                drawCircle(
                    color = Color.White.copy(alpha = 0.8f),
                    radius = 1.5.dp.toPx(),
                    center = Offset(x, y)
                )
            }
            
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(themeColor.copy(alpha = 0.5f), Color.Transparent),
                    center = center,
                    radius = radius * corePulse
                ),
                radius = radius * corePulse,
                blendMode = BlendMode.Plus
            )
        }
    }
}

@Preview
@Composable
fun SimpleGradientSpinnerPreview() {
    ShadowMeshTheme {
        Box(modifier = Modifier.size(100.dp).background(Color.Black), contentAlignment = Alignment.Center) {
            GradientCircularProgressIndicator(
                colors = listOf(Color.White, Color.White.copy(alpha = 0.5f), Color.Transparent),
                strokeWidth = 3.dp
            )
        }
    }
}
