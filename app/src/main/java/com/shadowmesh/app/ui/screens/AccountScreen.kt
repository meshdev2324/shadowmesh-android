package com.shadowmesh.app.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shadowmesh.app.R
import com.shadowmesh.app.VPNManagerViewModel
import com.shadowmesh.app.VPNUiState
import com.shadowmesh.core_vpn.CoreUtils
import com.shadowmesh.ui_kit.components.*
import com.shadowmesh.ui_kit.theme.ActivationCodeInput

/**
 * Account and Profile management screen.
 * SOP 11: Privacy-first UI, verified states only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    viewModel: VPNManagerViewModel,
    uiState: VPNUiState,
    onLogout: () -> Unit,
    onBack: () -> Unit,
    onOpenScanner: () -> Unit,
) {
    val context = LocalContext.current
    val deviceId = CoreUtils.getAndroidDeviceId(context)
    var isCodeRevealed by remember { mutableStateOf(false) }
    var showActivateDialog by remember { mutableStateOf(false) }
    var newActivationCode by remember { mutableStateOf("") }
    val themeColor = Color(uiState.themeColor)

    if (showActivateDialog) {
        ShadowMeshAlertDialog(
            onDismissRequest = { showActivateDialog = false },
            title = "Identity Verification",
            text = "Enter your mesh access code to authorize this device.",
            content = {
                ActivationCodeInput(
                    value = newActivationCode,
                    onValueChange = {
                        val cleaned = it.replace("-", "").uppercase().take(25)
                        newActivationCode = cleaned
                    },
                    themeColor = themeColor,
                    onScanClick = {
                        showActivateDialog = false
                        onOpenScanner()
                    },
                )
            },
            confirmButtonText = "ACTIVATE",
            onConfirm = {
                if (newActivationCode.length == 25) {
                    viewModel.activate(newActivationCode)
                    showActivateDialog = false
                    newActivationCode = ""
                }
            },
            dismissButtonText = "CANCEL",
            onDismiss = { showActivateDialog = false },
            confirmEnabled = newActivationCode.length == 25,
            themeColor = themeColor,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.account_profile_title), color = Color.White) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                    ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Color.White) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        containerColor = Color.Transparent,
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Color.White.copy(alpha = 0.04f),
                            RoundedCornerShape(24.dp),
                        ).border(0.5.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(24.dp))
                        .padding(24.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier =
                            Modifier
                                .size(
                                    80.dp,
                                ).background(themeColor.copy(alpha = 0.1f), CircleShape)
                                .border(1.dp, themeColor.copy(alpha = 0.3f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.VpnKey, contentDescription = null, tint = themeColor, modifier = Modifier.size(40.dp))
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    val statusLabel = if (uiState.isCamouflageEnabled) "Notes Cloud Sync Active" else "ShadowMesh Network Active"
                    val guestLabel = if (uiState.isCamouflageEnabled) "Sync limited" else "Mesh connection limited"
                    val isTeam = uiState.planName.equals("team", ignoreCase = true)
                    val planColor = if (!uiState.isActivated) Color.Gray else if (isTeam) Color(0xFFA855F7) else Color(0xFF10B981)
                    
                    Text(
                        text = if (uiState.isActivated) uiState.planName.uppercase() + " MEMBER" else "GUEST ACCESS",
                        style =
                            MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = 2.sp,
                                color = planColor,
                            ),
                    )
                    Text(
                        text = if (uiState.isActivated) statusLabel else guestLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.4f),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                AccountInfoRow(label = "ACTIVATION CODE", value = uiState.activationCode ?: "NOT ACTIVATED", themeColor = themeColor, isBlurrable = true, isRevealed = isCodeRevealed, onToggleReveal = {
                    isCodeRevealed = !isCodeRevealed
                }, actionLabel = if (uiState.isActivating) "ACTIVATING..." else "ADD NEW", onAction = {
                    if (!uiState.isActivating) showActivateDialog = true
                })

                val planDisplay = uiState.planName.uppercase()
                AccountInfoRow(label = "SUBSCRIPTION", value = planDisplay, themeColor = themeColor)

                if (uiState.isActivated) {
                    AccountInfoRow(
                        label = "DEVICE USAGE",
                        value = "${uiState.devicesRemaining} SLOTS REMAINING",
                        themeColor = if (uiState.devicesRemaining > 0) Color(0xFF10B981) else Color(0xFFEF4444),
                    )

                    if (uiState.planName.lowercase() == "trial") {
                        AccountInfoRow(
                            label = "TRIAL STATUS",
                            value = if (uiState.remainingDays <= 1L) "EXPIRING SOON" else "${uiState.remainingDays} DAYS LEFT",
                            themeColor = if (uiState.remainingDays <= 1L) Color(0xFFEF4444) else Color(0xFFF59E0B),
                        )
                    }
                }

                AccountInfoRow(label = "DEVICE HASH", value = "0x${deviceId.take(16).uppercase()}", themeColor = themeColor)
                AccountInfoRow(label = "MESH STATUS", value = if (uiState.isActivated) "AUTHORIZED" else "PENDING", themeColor = themeColor)
                AccountInfoRow(
                    label = "2-FACTOR AUTH",
                    value = if (uiState.isMfaEnabled) "ENABLED (HARDENED)" else "DISABLED (NOT SECURE)",
                    themeColor = if (uiState.isMfaEnabled) Color(0xFF10B981) else Color(0xFFF59E0B),
                    actionLabel = if (uiState.isMfaEnabled) null else "ENABLE",
                    onAction = { viewModel.startMfaSetup() },
                )
                AccountInfoRow(
                    label = "PASSKEY IDENTITY",
                    value = if (uiState.isPasskeyEnabled) "HARDWARE BOUND" else "UNLINKED",
                    themeColor = if (uiState.isPasskeyEnabled) Color(0xFF10B981) else Color.Gray,
                    actionLabel = if (uiState.isPasskeyEnabled) null else "LINK DEVICE",
                    onAction = { viewModel.registerPasskey(context as androidx.fragment.app.FragmentActivity, uiState.activationCode ?: "user") },
                )

                if (uiState.isActivated) {
                    Spacer(modifier = Modifier.height(8.dp))
                    UsageAnalyticsCard(
                        quantumBytes = uiState.quantumBytesTotal,
                        realityBytes = uiState.realityBytesTotal,
                        themeColor = themeColor,
                    )
                }
            }

            Button(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEF4444).copy(alpha = 0.1f),
                        contentColor = Color(0xFFEF4444),
                    ),
                border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f)),
            ) {
                Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(stringResource(R.string.account_deactivate_mesh), fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            }
            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}
