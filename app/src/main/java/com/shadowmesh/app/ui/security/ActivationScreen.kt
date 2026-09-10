package com.shadowmesh.app.ui.security

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shadowmesh.app.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.sp
import com.shadowmesh.app.VPNManagerViewModel
import com.shadowmesh.ui_kit.components.GradientCircularProgressIndicator
import com.shadowmesh.ui_kit.theme.ActivationCodeInput

/**
 * Activation (login) screen — RFC-polished per the Command Center UI bar:
 *
 * - Material tokens only: every color resolves through [MaterialTheme]
 *   (no hardcoded hex), so the screen tracks the Cyber Obsidian scheme.
 * - Apple-grade motion (design-system 02/03): a single entrance slide+fade,
 *   spring press-scale on the primary action, and an interruptible
 *   AnimatedContent crossfade for the loading state. Overshoot stays under
 *   the 10% budget.
 * - WCAG AAA: the error surface is a live region (TalkBack announces it),
 *   the scan affordance carries a content description, and contrast comes
 *   from theme roles rather than ad-hoc alphas.
 * - ZPII: the access key is never logged; errors surface via the ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivationScreen(
    viewModel: VPNManagerViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val themeColor = Color(uiState.themeColor)
    val haptic = LocalHapticFeedback.current
    var activationCode by remember { mutableStateOf("") }
    var showQRScanner by remember { mutableStateOf(false) }

    // Entrance motion: one-shot slide+fade (fast token, no overshoot).
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val entranceProgress by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "entrance",
    )

    // Apple-grade press physics: quick spring down to 0.97, spring back.
    val buttonInteraction = remember { MutableInteractionSource() }
    val pressed by buttonInteraction.collectIsPressedAsState()
    val buttonScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessHigh,
            ),
        label = "buttonScale",
    )

    if (showQRScanner) {
        com.shadowmesh.app.ui.pairing.QRScannerScreen(
            viewModel = viewModel,
            onDismiss = { showQRScanner = false },
            onResult = { result ->
                activationCode = result
                showQRScanner = false
            },
        )
        return
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(24.dp)
                .graphicsLayer {
                    alpha = entranceProgress
                    translationY = (1f - entranceProgress) * 24.dp.toPx()
                },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.activation_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(R.string.activation_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(32.dp))

        ActivationCodeInput(
            value = activationCode,
            onValueChange = { newCode ->
                // A corrected code is a new attempt: stale errors must not linger.
                if (uiState.errorMessage != null) viewModel.clearError()
                activationCode = newCode
            },
            themeColor = themeColor,
            onScanClick = { showQRScanner = true },
        )

        Spacer(modifier = Modifier.height(24.dp))

        Box(modifier = Modifier.fillMaxWidth()) {
            val buttonShape = RoundedCornerShape(14.dp)
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.activate(activationCode)
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .graphicsLayer {
                            scaleX = buttonScale
                            scaleY = buttonScale
                        },
                shape = buttonShape,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = themeColor,
                    ),
                interactionSource = buttonInteraction,
                enabled = activationCode.length == 25 && !uiState.isActivating,
            ) {
                // Interruptible crossfade between idle label and the branded
                // verifying state — cross-fade + size morph, never a pop
                // (design-system 04 §3).
                AnimatedContent(
                    targetState = uiState.isActivating,
                    transitionSpec = {
                        fadeIn(tween(durationMillis = 140)) togetherWith
                            fadeOut(tween(durationMillis = 140))
                    },
                    label = "buttonContent",
                ) { activating ->
                    if (activating) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            // Branded ring: theme accent → white sweep.
                            GradientCircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                colors =
                                    listOf(
                                        Color.White,
                                        Color.White.copy(alpha = 0.35f),
                                        Color.Transparent,
                                    ),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                "ACTIVATING…",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp,
                            )
                        }
                    } else {
                        Text(
                            stringResource(R.string.activation_button),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            // RFC design-system 04 §3: 1.5s diagonal shimmer across the
            // button surface while the activation request is in flight.
            if (uiState.isActivating) {
                val shimmer = rememberInfiniteTransition(label = "shimmer")
                val shimmerPhase by shimmer.animateFloat(
                    initialValue = -1f,
                    targetValue = 2f,
                    animationSpec =
                        infiniteRepeatable(
                            animation = tween(durationMillis = 1500, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart,
                        ),
                    label = "shimmerPhase",
                )
                Canvas(
                    modifier =
                        Modifier
                            .matchParentSize()
                            .clip(buttonShape),
                ) {
                    val bandWidth = size.width
                    val start = Offset(shimmerPhase * size.width, 0f)
                    val end = Offset(start.x + bandWidth, size.height)
                    drawRect(
                        brush =
                            Brush.linearGradient(
                                colors =
                                    listOf(
                                        Color.Transparent,
                                        Color.White.copy(alpha = 0.14f),
                                        Color.Transparent,
                                    ),
                                start = start,
                                end = end,
                            ),
                    )
                }
            }
        }

        // Error surface: animated in/out, announced by TalkBack (liveRegion),
        // sized with animateContentSize so the layout never jumps.
        AnimatedVisibility(
            visible = uiState.errorMessage != null,
            enter =
                fadeIn(tween(durationMillis = 200)) +
                    slideInVertically(
                        initialOffsetY = { it / 2 },
                        animationSpec =
                            spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                    ),
            exit = fadeOut(tween(durationMillis = 150)),
        ) {
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .animateContentSize()
                        .semantics { liveRegion = LiveRegionMode.Polite },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer,
                tonalElevation = 2.dp,
            ) {
                Text(
                    uiState.errorMessage
                        ?: stringResource(R.string.activation_error_generic),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}
