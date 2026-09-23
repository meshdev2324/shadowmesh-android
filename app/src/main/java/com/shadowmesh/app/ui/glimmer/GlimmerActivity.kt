package com.shadowmesh.app.ui.glimmer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.xr.glimmer.theme.GlimmerTheme
import androidx.xr.glimmer.ui.Card
import androidx.xr.glimmer.ui.Text
import com.shadowmesh.app.VPNManagerViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

import androidx.activity.viewModels
import androidx.xr.glimmer.ui.VerticalList
import androidx.xr.glimmer.ui.ListItem
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uniffi.shadowmesh.ConnectionStatus
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.Alignment


/**
 * Projected Activity for Android XR Display Glasses.
 * Horizon 4: Glimmer UI integration.
 */
@AndroidEntryPoint
class GlimmerActivity : ComponentActivity() {

    private val viewModel: VPNManagerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Horizon 4: Enable automatic focus for XR controls
        // Note: This is an internal XR property, often accessed via window or activity context
        // in newer alpha SDKs.
        
        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            
            GlimmerTheme {
                // Mandatory black background for additive displays
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .padding(24.dp)
                ) {
                    GlimmerStatusContent(
                        uiState = uiState,
                        onToggle = { viewModel.toggleConnection() }
                    )
                }
            }
        }
    }
}

@Composable
fun GlimmerStatusContent(
    uiState: com.shadowmesh.app.VPNUiState,
    onToggle: () -> Unit
) {
    val isConnected = uiState.status == ConnectionStatus.CONNECTED
    val isTeam = uiState.planName.equals("team", ignoreCase = true)
    
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Bottom // Bottom align for glasses display
    ) {
        Card(
            onClick = onToggle,
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
import androidx.compose.animation.*
import androidx.compose.animation.core.*
// ...
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AnimatedContent(
                            targetState = isConnected,
                            transitionSpec = {
                                fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
                                        slideInVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) with
                                        fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
                            }, label = "ConnectionStatus"
                        ) { targetConnected ->
                            Text(
                                text = if (targetConnected) "CONNECTED" else "DISCONNECTED",
                                style = GlimmerTheme.typography.titleLarge,
                                color = if (targetConnected) Color(0xFF10B981) else Color.White
                            )
                        }
                        
                        if (uiState.isActivated && !uiState.isCamouflageEnabled) {
                            val badgeColor = if (isTeam) Color(0xFFA855F7) else Color(0xFF6366F1)
                            Box(
                                modifier = Modifier
                                    .background(badgeColor.copy(alpha = 0.2f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (isTeam) "TEAM" else uiState.planName.uppercase(),
                                    style = GlimmerTheme.typography.bodySmall,
                                    color = badgeColor
                                )
                            }
                        }
                    }
                    
                    if (isConnected) {
                        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                        val alpha by infiniteTransition.animateFloat(
                            initialValue = 0.4f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1000, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ), label = "alpha"
                        )

                        Text(
                            text = "${uiState.selectedNode?.latency ?: 0}ms",
                            style = GlimmerTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = alpha)
                        )
                    }
                }
                
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (uiState.selectedNode?.isSovereign == true) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(Color(0xFFA855F7))
                            )
                        }
                        Text(
                            text = uiState.selectedNode?.name ?: "No Node Selected",
                            style = GlimmerTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                    
                    if (isTeam && isConnected) {
                        Text(
                            text = "CLOAKED",
                            style = GlimmerTheme.typography.labelSmall,
                            color = Color(0xFFA855F7)
                        )
                    } else if (isConnected && uiState.isQuantumTunnelingActive) {
                        Text(
                            text = "HARDENED",
                            style = GlimmerTheme.typography.labelSmall,
                            color = Color(0xFFA855F7)
                        )
                    }
                }
                
                if (isConnected) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            text = "IP: ${uiState.assignedIp ?: "..."}",
                            style = GlimmerTheme.typography.bodySmall,
                            color = GlimmerTheme.colors.primary
                        )
                        
                        if (uiState.isQuantumTunnelingActive) {
                             Text(
                                text = "PQC-ACTIVE",
                                style = GlimmerTheme.typography.bodySmall,
                                color = Color(0xFFA855F7)
                            )
                        }
                    }
                }
            }
        }
    }
}
