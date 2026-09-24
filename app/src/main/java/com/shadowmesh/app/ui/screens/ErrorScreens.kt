package com.shadowmesh.app.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shadowmesh.app.VPNManagerViewModel
import com.shadowmesh.app.VPNUiState
import com.shadowmesh.core_vpn.CoreUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Screen displayed when a session is frozen due to security violations.
 */
@Composable
fun SessionFrozenScreen(viewModel: VPNManagerViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val themeColor = Color(uiState.themeColor)

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A0F)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Outlined.Hub, contentDescription = null, tint = themeColor.copy(alpha = 0.8f), modifier = Modifier.size(80.dp))
            Spacer(modifier = Modifier.height(24.dp))
            Text("SESSION PAUSED", color = Color.White, fontWeight = FontWeight.Black, fontSize = 24.sp, letterSpacing = 2.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Your protection has been temporarily paused for verification. This happens when the security engine detects unusual network transitions.",
                color = Color(0xFF94A3B8),
                textAlign = TextAlign.Center,
                fontSize = 15.sp,
                lineHeight = 24.sp
            )
            Spacer(modifier = Modifier.height(48.dp))
            Button(
                onClick = { viewModel.logout() },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = themeColor.copy(alpha = 0.2f), contentColor = themeColor),
                border = BorderStroke(1.dp, themeColor.copy(alpha = 0.4f))
            ) {
                Text("RE-AUTHENTICATE", fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            }
        }
    }
}

/**
 * Screen displayed when app integrity verification fails (fail-closed).
 *
 * Design bar: calm, authoritative, and honest — no bypass affordance exists
 * because access restriction IS the security response. The technical detail
 * is quarantined in a monospace surface so the message hierarchy stays
 * readable, and the alert is a live region for TalkBack.
 */
@Composable
fun IntegrityErrorScreen(message: String) {
    // Entrance motion: one-shot slide+fade, no overshoot (security gravity).
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val entrance by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = 240),
        label = "entrance",
    )

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0A0F))
                .semantics { liveRegion = LiveRegionMode.Assertive },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 28.dp, vertical = 32.dp)
                    .graphicsLayer {
                        alpha = entrance
                        translationY = (1f - entrance) * 28.dp.toPx()
                    },
        ) {
            Icon(
                imageVector = Icons.Outlined.GppBad,
                contentDescription = "Integrity verification failed",
                tint = Color(0xFFF43F5E).copy(alpha = 0.9f),
                modifier = Modifier.size(72.dp),
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "VERIFICATION ALERT",
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 24.sp,
                letterSpacing = 2.sp,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Security Violation",
                color = Color(0xFFF43F5E),
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 1.sp,
            )
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text =
                    "ShadowMesh could not verify the authenticity of this application. " +
                        "To ensure your keys and data remain secure, access has been restricted.",
                color = Color(0xFF94A3B8),
                textAlign = TextAlign.Center,
                fontSize = 15.sp,
                lineHeight = 24.sp,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Uninstall this copy and reinstall from your official distribution channel.",
                color = Color(0xFFCBD5E1),
                textAlign = TextAlign.Center,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            Spacer(modifier = Modifier.height(28.dp))
            // Technical detail, visually quarantined so the hierarchy stays calm.
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.White.copy(alpha = 0.03f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Error Details: $message",
                    color = Color(0xFF64748B),
                    fontSize = 11.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
    }
}

/**
 * Screen displayed when the decoy/panic wipe mode is triggered.
 */
@Composable
fun DecoyErrorScreen(uiState: VPNUiState) {
    var isRetrying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF050508)), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val gridCount = 10
            val cellW = size.width / gridCount
            val cellH = size.height / gridCount
            for (i in 0..gridCount) {
                drawLine(
                    color = Color.White.copy(alpha = 0.01f),
                    start = Offset(i * cellW, 0f),
                    end = Offset(i * cellW, size.height),
                    strokeWidth = 0.5.dp.toPx(),
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.01f),
                    start = Offset(0f, i * cellH),
                    end = Offset(size.width, i * cellH),
                    strokeWidth = 0.5.dp.toPx(),
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 40.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Hub,
                contentDescription = null,
                tint = Color(0xFFEF4444).copy(alpha = 0.8f),
                modifier = Modifier.size(64.dp),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SYNC FAILED", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Text(
                    "Error Code: 0x8004100E\nUnable to synchronize notes with the cloud server. Connection timeout.",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    lineHeight = 18.sp,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    scope.launch {
                        isRetrying = true
                        delay(3000)
                        isRetrying = false
                    }
                },
                enabled = !isRetrying,
                shape = RoundedCornerShape(12.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.05f),
                        disabledContainerColor = Color.White.copy(alpha = 0.02f),
                    ),
                border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.1f)),
                modifier = Modifier.width(200.dp).align(Alignment.CenterHorizontally),
            ) {
                if (isRetrying) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Gray, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("RE-SYNCING...", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text("RETRY SYNC", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            val buildId = "1.0.0-STABLE"
            val deviceId = CoreUtils.getAndroidDeviceId(LocalContext.current)
            Text(
                "BUILD: $buildId | DEVICE_ID: 0x${deviceId.take(12).uppercase()}...${deviceId.takeLast(4).uppercase()}",
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp).navigationBarsPadding(),
                color = Color.White.copy(alpha = 0.1f),
                fontSize = 9.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }
    }
}
