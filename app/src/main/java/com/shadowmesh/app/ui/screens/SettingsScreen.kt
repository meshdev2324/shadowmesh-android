package com.shadowmesh.app.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.clickable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.shadowmesh.app.ConnectionInteractionMethod
import com.shadowmesh.app.R
import io.nayuki.qrcodegen.QrCode
import com.shadowmesh.app.VPNManagerViewModel
import com.shadowmesh.app.VPNUiState
import com.shadowmesh.app.ui.settings.AppPickerScreen
import com.shadowmesh.app.ui.settings.PINPadScreen
import com.shadowmesh.ui_kit.components.*
import uniffi.shadowmesh.SplitTunnelConfig
import uniffi.shadowmesh.TrafficModePreference

/**
 * Configuration screen for VPN settings and app preferences.
 * SOP 13: Technical gap fulfillment and Big Tech standards.
 */
@Composable
fun SettingsScreen(
    viewModel: VPNManagerViewModel,
    uiState: VPNUiState,
    onSetKillSwitch: (Boolean) -> Unit,
    onSetTrafficModePreference: (TrafficModePreference) -> Unit,
    onSetSplitTunnelConfig: (SplitTunnelConfig) -> Unit,
    onToggleCamouflage: (Boolean) -> Unit,
    onShowCustomDNSSettings: () -> Unit,
    onShowVPNGuide: () -> Unit,
    onShowQRScanner: () -> Unit,
    runNetworkDetection: (Boolean) -> Unit,
    showAppPicker: Boolean,
    onShowAppPicker: (Boolean) -> Unit,
    pinSetupStep: Int,
    onPinSetupStepChange: (Int) -> Unit,
    isExpanded: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var tempPin by remember { mutableStateOf("") }
    var authorizeToken by remember { mutableStateOf("") }
    val themeColor = Color(uiState.themeColor)

    if (showAppPicker) {
        AppPickerScreen(
            uiState = uiState,
            currentConfig = uiState.splitTunnelConfig,
            onSaveConfig = { config: SplitTunnelConfig ->
                onSetSplitTunnelConfig(config)
                onShowAppPicker(false)
            },
            onBack = { onShowAppPicker(false) },
        )
        return
    }

    if (pinSetupStep > 0) {
        val title =
            when (pinSetupStep) {
                1 -> "Set Main PIN"
                2 -> "Confirm Main PIN"
                3 -> "Set Duress PIN"
                else -> ""
            }
        val subtitle =
            when (pinSetupStep) {
                1 -> "Enter a 4-digit security code"
                2 -> "Repeat your Main PIN"
                3 -> "Wipes app data if entered at login"
                else -> ""
            }
        PINPadScreen(
            title = title,
            subtitle = subtitle,
            themeColor = themeColor,
            onPinComplete = { pin: String ->
                when (pinSetupStep) {
                    1 -> {
                        tempPin = pin
                        onPinSetupStepChange(2)
                    }
                    2 -> {
                        if (pin == tempPin) {
                            viewModel.setPin(pin)
                            onPinSetupStepChange(3)
                        } else {
                            onPinSetupStepChange(1)
                            tempPin = ""
                        }
                    }
                    3 -> {
                        if (pin != tempPin) {
                            viewModel.setPanicPin(pin)
                            onPinSetupStepChange(0)
                        }
                    }
                }
            },
            onCancel = { onPinSetupStepChange(0) },
        )
        return
    }

    val green = Color(0xFF10B981)
    val amber = Color(0xFFF59E0B)
    val purple = Color(0xFFA855F7)
    val orange = Color(0xFFF97316)
    val bgCard = Color.White.copy(alpha = 0.04f)
    val border = Color.White.copy(alpha = 0.07f)

    // SOP 14: Dynamic Feature Toggling
    val isCamouflageEnabledByFlag = uiState.featureFlags["camouflage_mode"] ?: true
    val isQuantumFragEnabledByFlag = uiState.featureFlags["quantum_fragmentation"] ?: true
    val isQuantumResEnabledByFlag = uiState.featureFlags["quantum_resistance"] ?: true
    val isRealityEnabledByFlag = uiState.featureFlags["reality_protocol"] ?: true

    LazyColumn(
        modifier =
            modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = if (isExpanded) 48.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(bottom = 120.dp)
    ) {
        item(key = "title") {
            StaggeredContainer(index = 0) {
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = Color.White)
            }
        }

        item(key = "guide") {
            StaggeredContainer(index = 1) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(bgCard, shape = RoundedCornerShape(20.dp))
                            .border(1.dp, border, shape = RoundedCornerShape(20.dp))
                            .padding(16.dp)
                            .clickable(onClick = onShowVPNGuide),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFF59E0B).copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Lightbulb, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(18.dp))
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "VPN Setup Guide",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White,
                        )
                        Text(stringResource(R.string.settings_guide_subtitle), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = Color.Gray.copy(alpha = 0.6f))
                }
            }
        }

        item(key = "appearance") {
            StaggeredContainer(index = 2) {
                SettingsCardSection(title = "APPEARANCE", borderColor = border, bgColor = bgCard) {
                    Text(stringResource(R.string.settings_theme_accent), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf(0xFF6366F1, 0xFF10B981, 0xFFF59E0B, 0xFFA855F7, 0xFFF43F5E, 0xFF06B6D4).forEach { colorLong ->
                            val color = Color(colorLong)
                            val isSelected = uiState.themeColor == colorLong
                            Box(
                                modifier =
                                    Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .border(
                                            width = if (isSelected) 3.dp else 0.dp,
                                            color = if (isSelected) Color.White else Color.Transparent,
                                            shape = CircleShape,
                                        ).clickable { viewModel.setThemeColor(colorLong) },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Background Style",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            listOf("Cyber Nebula", "Obsidian Stealth", "Deep Space"),
                            listOf("Electric Horizon", "Quantum Grid", "Neon Midnight"),
                        ).forEach { rowStyles ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                                rowStyles.forEach { style ->
                                    val isSelected = uiState.backgroundStyle == style
                                    Surface(
                                        onClick = { viewModel.setBackgroundStyle(style) },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isSelected) themeColor.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f),
                                        border = BorderStroke(1.dp, if (isSelected) themeColor else Color.Transparent),
                                    ) {
                                        Text(
                                            text = style,
                                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 2.dp),
                                            textAlign = TextAlign.Center,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isSelected) themeColor else Color.Gray,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "protection") {
            StaggeredContainer(index = 3) {
                SettingsCardSection(title = "VPN PROTECTION", borderColor = border, bgColor = bgCard) {
                    SettingsSwitchRow(
                        icon = Icons.Outlined.Swipe,
                        iconColor = orange,
                        label = "Slide to Connect",
                        subtitle = "Use slider instead of tap",
                        checked =
                            uiState.interactionMethod == ConnectionInteractionMethod.SLIDE,
                        onChecked = {
                            viewModel.setInteractionMethod(if (it) ConnectionInteractionMethod.SLIDE else ConnectionInteractionMethod.TAP)
                        },
                    )
                    SettingsDivider()
                    SettingsSwitchRow(
                        icon = Icons.Outlined.VpnKey,
                        iconColor = amber,
                        label = "Kill Switch",
                        subtitle = "Block traffic if VPN drops",
                        checked = uiState.killSwitchEnabled,
                        onChecked = onSetKillSwitch,
                    )
                    SettingsDivider()
                    SettingsSwitchRow(icon = Icons.Outlined.Public, iconColor = green, label = "DNS Leak Protection", subtitle = "Force IPv6 and DNS into tunnel", checked = uiState.dnsLeakProtectionEnabled, onChecked = {
                        viewModel.toggleDnsLeakProtection(it)
                    })
                    SettingsDivider()
                    SettingsNavRow(icon = Icons.AutoMirrored.Filled.AltRoute, iconColor = themeColor, label = "Split Tunneling", subtitle = if (uiState.splitTunnelConfig.enabled) "${uiState.splitTunnelConfig.appList.size} apps routed" else "Route specific apps", onClick = {
                        onShowAppPicker(true)
                    })
                    SettingsDivider()
                    SettingsNavRow(
                        icon = Icons.Outlined.Lan,
                        iconColor = green,
                        label = "Custom DNS Servers",
                        subtitle = if (uiState.customDNSServers.isEmpty()) "Use default (1.1.1.1, 8.8.8.8)" else "${uiState.customDNSServers.size} configured",
                        onClick = onShowCustomDNSSettings,
                    )
                }
            }
        }

        item(key = "traffic") {
            StaggeredContainer(index = 4) {
                SettingsCardSection(title = "TRAFFIC MODE", borderColor = border, bgColor = bgCard) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOfNotNull(
                            Triple(TrafficModePreference.AUTO, "Smart Routing", "Optimized mesh node selection"),
                            Triple(TrafficModePreference.SPEED, "High Speed", "Direct WireGuard performance"),
                            if (isRealityEnabledByFlag) Triple(TrafficModePreference.STEALTH, "Reality Stealth", "Advanced obfuscation & fragmentation") else null,
                        ).forEach { (pref, label, subtitle) ->
                            val color =
                                when (pref) {
                                    TrafficModePreference.AUTO -> themeColor
                                    TrafficModePreference.SPEED -> green
                                    TrafficModePreference.STEALTH -> purple
                                }
                            TrafficModeOption(label = label, subtitle = subtitle, selected = uiState.trafficModePreference == pref, accentColor = color, onClick = {
                                onSetTrafficModePreference(pref)
                            })
                        }
                    }
                    if (uiState.trafficModePreference == TrafficModePreference.STEALTH && isQuantumFragEnabledByFlag) {
                        SettingsDivider()
                        SettingsSectionLabel("STEALTH ENGINE")
                        Spacer(modifier = Modifier.height(4.dp))
                        SettingsSwitchRow(
                            icon = Icons.Outlined.Hub,
                            iconColor = purple,
                            label = "Packet Fragmentation",
                            subtitle = "RFC-minimum MTU to bypass DPI",
                            checked = uiState.advancedStealthQuantum,
                            onChecked = { viewModel.toggleAdvancedStealthQuantum(it) },
                        )
                    }

                    if (isQuantumResEnabledByFlag) {
                        SettingsDivider()
                        SettingsSectionLabel("QUANTUM RESISTANCE (HORIZON 3)")
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            uniffi.shadowmesh.QuantumResistanceLevel.entries.forEach { level ->
                                val isSelected = uiState.quantumLevel == level
                                Surface(
                                    onClick = { viewModel.setQuantumLevel(level) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) themeColor.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f),
                                    border = BorderStroke(1.dp, if (isSelected) themeColor else Color.Transparent),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier.size(24.dp).clip(CircleShape).background(if (isSelected) themeColor else Color.Gray.copy(alpha = 0.2f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isSelected) Icon(Icons.Outlined.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(14.dp))
                                        }
                                        Column {
                                            Text(
                                                text = when(level) {
                                                    uniffi.shadowmesh.QuantumResistanceLevel.NONE -> "Standard (X25519)"
                                                    uniffi.shadowmesh.QuantumResistanceLevel.HYBRID -> "Hybrid (Kyber768)"
                                                    uniffi.shadowmesh.QuantumResistanceLevel.FULL -> "Pure PQC (Experimental)"
                                                },
                                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                                color = if (isSelected) Color.White else Color.Gray
                                            )
                                            Text(
                                              text = when(level) {
                                                  uniffi.shadowmesh.QuantumResistanceLevel.NONE -> "Industry-standard elliptic curve"
                                                  uniffi.shadowmesh.QuantumResistanceLevel.HYBRID -> "Safe against current and future computers"
                                                  uniffi.shadowmesh.QuantumResistanceLevel.FULL -> "Mandatory Post-Quantum Handshake"
                                              },
                                              style = MaterialTheme.typography.labelSmall,
                                              color = Color.Gray.copy(alpha = 0.6f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "security") {
            StaggeredContainer(index = 5) {
                SettingsCardSection(title = "SECURITY", borderColor = border, bgColor = bgCard) {
                    SettingsSwitchRow(icon = Icons.Outlined.Fingerprint, iconColor = green, label = "Security Lock", subtitle = "Require PIN or biometrics on open", checked = uiState.isSecurityLockEnabled, onChecked = { enabled: Boolean ->
                        if (enabled) onPinSetupStepChange(1) else viewModel.disablePin()
                    })
                    SettingsDivider()
                    SettingsNavRow(icon = Icons.Outlined.Dialpad, iconColor = purple, label = "Set PIN & Duress PIN", subtitle = if (uiState.isPanicArmed) "Guardian Mode Active" else "Configure primary and panic PIN", onClick = {
                        onPinSetupStepChange(1)
                    })
                    SettingsDivider()
                    SettingsSwitchRow(icon = Icons.Outlined.CameraAlt, iconColor = themeColor, label = "Allow Screenshots", subtitle = "DANGER: May leak sensitive keys", checked = uiState.isScreenshotEnabled, onChecked = {
                        viewModel.toggleScreenshotMode(it)
                    })
                    if (isCamouflageEnabledByFlag) {
                        SettingsDivider()
                        SettingsSwitchRow(icon = Icons.Outlined.Layers, iconColor = orange, label = "Camouflage Mode", subtitle = "Appear as a Notes app. Long-press avatar or double-tap 'Personal Diary' to reveal VPN.", checked = uiState.isCamouflageEnabled, onChecked = {
                            onToggleCamouflage(it)
                        })
                    }
                }
            }
        }

        item(key = "pairing") {
            StaggeredContainer(index = 6) {
                SettingsCardSection(title = "DESKTOP PAIRING", borderColor = border, bgColor = bgCard) {
                    // RFC-018: issue a member key and render it as a QR for
                    // the new device's activation screen (scan or type).
                    if (uiState.qrToken == null) {
                        SettingsNavRow(
                            icon = Icons.Outlined.QrCodeScanner,
                            iconColor = themeColor,
                            label = stringResource(R.string.pairing_issue_key),
                            subtitle = "Mint a 25-char key for a new device (Team plan)",
                            onClick = { viewModel.issuePairingToken() },
                        )
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            QrCodeImage(
                                content = uiState.qrToken!!,
                                size = 200.dp,
                                modifier = Modifier.clip(RoundedCornerShape(12.dp))
                            )
                            Text(
                                text = uiState.qrToken!!.chunked(5).joinToString(" "),
                                color = themeColor,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            )
                            Text(
                                text = stringResource(R.string.pairing_scan_hint),
                                color = Color.White.copy(alpha = 0.5f),
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Text(
                                text = stringResource(R.string.pairing_reissue),
                                color = themeColor,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
                                modifier =
                                    Modifier
                                        .clickable { viewModel.issuePairingToken() }
                            )
                        }
                    }

                    // RFC-018: approve a pairing session shown by a new
                    // device (e.g., the desktop app) — scan its QR or paste
                    // its token. Scanning prefers the shadowmesh://pair URI
                    // the desktop renders; manual paste stays for fallback.
                    SettingsDivider()
                    var showPairingScanner by remember { mutableStateOf(false) }
                    if (showPairingScanner) {
                        com.shadowmesh.app.ui.pairing.QRScannerScreen(
                            viewModel = viewModel,
                            onDismiss = { showPairingScanner = false },
                            onResult = { token ->
                                viewModel.authorizePairing(token)
                                showPairingScanner = false
                            },
                            scanMode = com.shadowmesh.app.ui.pairing.QrScanMode.Pairing,
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "SCAN DESKTOP QR",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.sp,
                                ),
                                color = themeColor,
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .clickable { showPairingScanner = true }
                                        .padding(vertical = 8.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            Text(
                                text = "OR PASTE TOKEN",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = Color.White.copy(alpha = 0.3f),
                                    letterSpacing = 1.sp,
                                ),
                            )
                        }
                        OutlinedTextField(
                            value = authorizeToken,
                            onValueChange = { authorizeToken = it },
                            label = { Text("Pairing token from new device", style = MaterialTheme.typography.labelSmall) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "APPROVE DEVICE",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp,
                            ),
                            color = if (authorizeToken.isBlank()) Color.White.copy(alpha = 0.3f) else themeColor,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = authorizeToken.isNotBlank()) {
                                        viewModel.authorizePairing(authorizeToken)
                                        authorizeToken = ""
                                    }
                                    .padding(vertical = 8.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }

        item(key = "diagnostics") {
            StaggeredContainer(index = 7) {
                SettingsCardSection(title = "DIAGNOSTICS", borderColor = border, bgColor = bgCard) {
                    NetworkDiagnosticSection(
                        uiState = uiState.networkReport,
                        themeColor = themeColor,
                        onRunDetection = runNetworkDetection,
                        isDetecting = uiState.isDetectingNetwork,
                    )
                    SettingsDivider()
                    SpeedTestSection(
                        isRunning = uiState.speedTestState.isRunning,
                        result = uiState.speedTestState.result,
                        error = uiState.speedTestState.error,
                        themeColor = themeColor,
                        onRunSpeedTest = { viewModel.runSpeedTest() },
                    )
                }
            }
        }

        item(key = "about") {
            StaggeredContainer(index = 8) {
                SettingsCardSection(title = "ABOUT", borderColor = border, bgColor = bgCard) {
                    val aboutLabel = if (uiState.isCamouflageEnabled) "Notes v1.0.0" else "ShadowMesh v1.0.0"
                    val aboutDesc = if (uiState.isCamouflageEnabled) "Secure Personal Notebook" else "Anonymous by Design"
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(Icons.Outlined.Info, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                aboutLabel,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = Color.White,
                            )
                            Text(aboutDesc, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}


/**
 * RFC-018: renders `content` as a scannable QR (dark modules on a white
 * card — inverted codes defeat many scanners). Recomputed only when the
 * content changes; bitmap pixels are set directly (no alloc per frame).
 */
@Composable
fun QrCodeImage(content: String, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val bitmap = remember(content) {
        val qr = QrCode.encodeText(content, QrCode.Ecc.MEDIUM)
        val scale = 8
        val quiet = 4
        val dim = (qr.size + quiet * 2) * scale
        val bmp = android.graphics.Bitmap.createBitmap(dim, dim, android.graphics.Bitmap.Config.ARGB_8888)
        for (y in 0 until dim) {
            for (x in 0 until dim) {
                val mx = x / scale - quiet
                val my = y / scale - quiet
                val dark = mx in 0 until qr.size && my in 0 until qr.size && qr.getModule(mx, my)
                bmp.setPixel(x, y, if (dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bmp
    }
    androidx.compose.foundation.Image(
        bitmap = bitmap.asImageBitmap(),
        // A11y (WCAG 1.1.1): the QR carries a live pairing/activation credential —
        // TalkBack users must know what this image is, even though they consent by
        // not showing it. The token itself is never announced (ZPII).
        contentDescription = stringResource(R.string.settings_pairing_qr_desc),
        modifier = modifier.then(Modifier.size(size))
    )
}
