package com.shadowmesh.app.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.shadowmesh.app.ConnectionInteractionMethod
import com.shadowmesh.app.R
import com.shadowmesh.app.VPNManagerViewModel
import com.shadowmesh.app.VPNUiState
import com.shadowmesh.core_vpn.CoreUtils.formatBytes
import com.shadowmesh.ui_kit.components.*
import uniffi.shadowmesh.ConnectionStatus
import uniffi.shadowmesh.TrafficModePreference

import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight

/**
 * The main dashboard for the VPN.
 * SOP 02: One-tap connection, obvious state, human-centric.
 */
@Composable
fun StatusScreen(
    viewModel: VPNManagerViewModel,
    uiState: VPNUiState,
    onToggleConnection: () -> Unit,
    onOpenNodeSelection: () -> Unit,
    onClearError: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenModeSelector: () -> Unit,
    modifier: Modifier = Modifier,
    isExpanded: Boolean = false,
) {
    val context = LocalContext.current
    val isConnected = uiState.status == ConnectionStatus.CONNECTED
    val isConnecting = uiState.isConnecting
    val themeColor = Color(uiState.themeColor)
    val haptic = LocalHapticFeedback.current

    val stableConnectionText = stringResource(R.string.status_connected)
    val syncingMeshText = stringResource(R.string.status_sync)

    if (uiState.errorMessage != null) {
        ShadowMeshAlertDialog(
            onDismissRequest = onClearError,
            title = "System Error",
            text = uiState.errorMessage,
            confirmButtonText = "OK",
            onConfirm = onClearError,
            themeColor = themeColor,
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (isExpanded) {
            // High-Fidelity Two-Pane Layout for Tablets/Foldables
            Row(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalArrangement = Arrangement.spacedBy(48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Pane: The Orb (Physical Core)
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    OrbSection(uiState, isConnected, isConnecting, themeColor)
                }
                
                // Right Pane: The Controls (Command Logic)
                Column(
                    modifier = Modifier.weight(1.2f),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    NodeAndSessionCards(uiState, isConnected, isConnecting, themeColor, haptic, onOpenNodeSelection, onOpenModeSelector, isExpanded)
                    ControlSection(uiState, isConnected, themeColor, haptic, { onToggleConnection() })
                }
            }
        } else {
            // Standard Portrait Mobile Layout
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                HeaderSection(uiState, themeColor, onOpenAccount)
                OrbSection(uiState, isConnected, isConnecting, themeColor)
                NodeAndSessionCards(uiState, isConnected, isConnecting, themeColor, haptic, onOpenNodeSelection, onOpenModeSelector, false)
                ControlSection(uiState, isConnected, themeColor, haptic, { onToggleConnection() })
                Spacer(modifier = Modifier.height(120.dp))
            }
        }
    }
}

@Composable
private fun HeaderSection(uiState: VPNUiState, themeColor: Color, onOpenAccount: () -> Unit) {
    StaggeredContainer(index = 0) {
        Surface(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(top = 12.dp, bottom = 12.dp),
            color = Color.Transparent,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    val primaryLabel = if (uiState.isCamouflageEnabled) "SECURE NOTES" else "SHADOW MESH"
                    Text(
                        primaryLabel,
                        style = MaterialTheme.typography.labelLarge.copy(color = Color.White.copy(alpha = 0.9f)),
                    )
                    Text(
                        "COMMAND CONSOLE",
                        style = MaterialTheme.typography.labelSmall.copy(color = themeColor.copy(alpha = 0.4f)),
                    )
                }
                
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconButton(
                        onClick = onOpenAccount,
                        modifier = Modifier.size(44.dp).clip(CircleShape).shadowMeshGlass(radius = 22f, baseColor = Color.White.copy(alpha = 0.1f)),
                    ) {
                        Icon(Icons.Outlined.VpnKey, contentDescription = "Account", tint = Color.White.copy(alpha = 0.7f))
                    }
                }
            }
        }
    }
}

@Composable
private fun OrbSection(uiState: VPNUiState, isConnected: Boolean, isConnecting: Boolean, themeColor: Color) {
    StaggeredContainer(index = 1) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (uiState.isQuantumTunnelingActive && isConnected) {
                    QuantumIndicator()
                }
                if (uiState.planName.equals("team", ignoreCase = true) && isConnected) {
                    TeamCloakIndicator()
                }
                if (uiState.isCanaryToken) {
                    CanaryIndicator()
                }
                if (uiState.isDiscoveryResilient && !uiState.isQuantumTunnelingActive) {
                    ResilienceIndicator()
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            LiquidStatusOrb(
                status = uiState.status,
                latency = uiState.selectedNode?.latency?.toDouble() ?: 0.0,
                isConnecting = isConnecting,
                pausedUntil = uiState.pausedUntil
            )
        }
    }
}

@Composable
private fun TeamCloakIndicator() {
    Surface(
        color = Color(0xFFA855F7).copy(alpha = 0.15f),
        shape = CircleShape,
        border = BorderStroke(0.5.dp, Color(0xFFA855F7).copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                Icons.Outlined.VpnKey,
                contentDescription = null,
                tint = Color(0xFFA855F7),
                modifier = Modifier.size(10.dp)
            )
            Text(
                "CLOAKED",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                ),
                color = Color(0xFFA855F7)
            )
        }
    }
}

@Composable
private fun NodeAndSessionCards(
    uiState: VPNUiState,
    isConnected: Boolean,
    isConnecting: Boolean,
    themeColor: Color,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onOpenNodeSelection: () -> Unit,
    onOpenModeSelector: () -> Unit,
    isExpanded: Boolean
) {
    val stableConnectionText = stringResource(R.string.status_connected)
    val syncingMeshText = stringResource(R.string.status_sync)
    
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StaggeredContainer(index = 2) {
            SettingsCardSection(title = "ENTRY NODE", borderColor = Color.Transparent, bgColor = Color.Transparent) {
                SettingsNavRow(
                    icon = Icons.Outlined.Hub,
                    iconColor = if (isConnected) Color(0xFF10B981) else if (isConnecting) Color(0xFFF59E0B) else Color.Gray,
                    label = uiState.selectedNode?.name ?: "Select Node",
                    subtitle = when {
                        isConnected -> stableConnectionText
                        isConnecting -> syncingMeshText
                        else -> stringResource(R.string.status_idle)
                    },
                    onClick = if (isConnecting) ({}) else {
                        {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            onOpenNodeSelection()
                        }
                    },
                )
            }
        }

        StaggeredContainer(index = 3) {
            SettingsCardSection(title = "LIVE SESSION", borderColor = Color.Transparent, bgColor = Color.Transparent, trailing = {
                uiState.networkReport?.let { NetworkHealthChip(it) }
            }) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ConnectionDetailItem(
                        label = "MESH IP",
                        value = uiState.assignedIp ?: "---.---.---.---",
                        icon = Icons.Outlined.Lan,
                        color = themeColor,
                        modifier = Modifier.weight(1f)
                    )
                    val meshTotalBytes = uiState.connectionStats.bytesSent + uiState.connectionStats.bytesReceived
                    val statsValue = remember(meshTotalBytes) { com.shadowmesh.core_vpn.CoreUtils.formatBytes(meshTotalBytes) }
                    ConnectionDetailItem(
                        label = "TRAFFIC",
                        value = statsValue,
                        icon = Icons.Outlined.SwapVert,
                        color = Color(0xFFA855F7),
                        modifier = Modifier.weight(1f)
                    )
                    val (modeIcon, modeColor) = when (uiState.trafficModePreference) {
                        TrafficModePreference.AUTO -> Icons.Outlined.AutoMode to themeColor
                        TrafficModePreference.SPEED -> Icons.Outlined.Bolt to Color(0xFF10B981)
                        TrafficModePreference.STEALTH -> Icons.Outlined.VpnKey to Color(0xFFA855F7)
                    }
                    ConnectionDetailItem(
                        label = "MODE",
                        value = if (uiState.planName.equals("team", ignoreCase = true)) "HARDENED" else uiState.trafficModePreference.name,
                        icon = modeIcon,
                        color = if (uiState.planName.equals("team", ignoreCase = true)) Color(0xFFA855F7) else modeColor,
                        onClick = {
                            if (!uiState.planName.equals("team", ignoreCase = true)) {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                onOpenModeSelector()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlSection(
    uiState: VPNUiState,
    isConnected: Boolean,
    themeColor: Color,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onToggleConnection: () -> Unit
) {
    StaggeredContainer(index = 4) {
        val report = uiState.networkReport
        val hasCriticalIssue = report?.dpiDetected == true || report?.captivePortalDetected == true
        val controlBorder = if (hasCriticalIssue && isConnected) Color(0xFFF43F5E).copy(alpha = 0.5f) else Color.Transparent
        val controlBg = if (hasCriticalIssue && isConnected) Color(0xFFF43F5E).copy(alpha = 0.08f) else Color.Transparent
        
        SettingsCardSection(
            title = "MESH CONTROL",
            borderColor = controlBorder,
            bgColor = controlBg,
            trailing = {
                if (uiState.isEbpfActive && isConnected) {
                    Surface(
                        color = Color(0xFF10B981).copy(alpha = 0.2f),
                        shape = CircleShape,
                        border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.4f)),
                    ) {
                        Text(
                            "KERNEL ACCELERATED",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            color = Color(0xFF10B981),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            },
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (uiState.interactionMethod == ConnectionInteractionMethod.SLIDE) {
                    SlideToConnect(
                        status = uiState.status,
                        isConnecting = uiState.isConnecting,
                        themeColor = if (hasCriticalIssue && isConnected) Color(0xFFF43F5E) else themeColor,
                        onSwipeComplete = {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            onToggleConnection()
                        },
                    )
                } else {
                    ConnectButton(
                        status = uiState.status,
                        onClick = {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            onToggleConnection()
                        },
                        themeColor = if (hasCriticalIssue && isConnected) Color(0xFFF43F5E) else themeColor,
                    )
                }
            }
        }
    }
}
