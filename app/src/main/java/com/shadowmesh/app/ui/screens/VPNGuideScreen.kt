package com.shadowmesh.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shadowmesh.app.VPNUiState
import kotlinx.coroutines.delay

data class GuideItem(
    val icon: ImageVector,
    val color: Color,
    val title: String,
    val description: String,
    val tags: List<String> = emptyList(),
)

/**
 * Educational screen explaining VPN features.
 * SOP 02: Calm product personality, human-centric copy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VPNGuideScreen(
    uiState: VPNUiState,
    onBack: () -> Unit,
) {
    val themeColor = Color(uiState.themeColor)
    val guideItems =
        remember(uiState.isCamouflageEnabled) {
            if (uiState.isCamouflageEnabled) {
                listOf(
                    GuideItem(
                        Icons.Outlined.Hub,
                        Color(0xFFF59E0B),
                        "Encrypted Sync",
                        "Synchronize your notes across devices through a secure, hidden mesh tunnel.",
                        listOf("PRIVACY", "SECURE"),
                    ),
                    GuideItem(
                        Icons.Outlined.Fingerprint,
                        Color(0xFF10B981),
                        "Vault Security",
                        "Biometric and PIN protection for your private entries.",
                        listOf("BIOMETRIC"),
                    ),
                    GuideItem(
                        Icons.Outlined.Lan,
                        themeColor,
                        "Sync Protocols",
                        "Optimized modes for restricted networks.",
                        listOf("STEALTH"),
                    ),
                    GuideItem(
                        Icons.AutoMirrored.Outlined.MenuBook,
                        Color(0xFFA855F7),
                        "Diary Secret",
                        "Double-tap 'Personal Diary' to reveal the secure mesh control console.",
                        listOf("HIDDEN"),
                    ),
                )
            } else {
                listOf(
                    GuideItem(
                        Icons.Outlined.VpnKey,
                        Color(0xFFF59E0B),
                        "System Kill Switch",
                        "Instantly drops all network traffic if the mesh connection is interrupted.",
                        listOf("SECURITY", "CRITICAL"),
                    ),
                    GuideItem(
                        Icons.Outlined.Memory,
                        Color(0xFF10B981),
                        "Identity Guardian",
                        "Leverages TEE/StrongBox hardware security.",
                        listOf("HARDWARE"),
                    ),
                    GuideItem(
                        Icons.Outlined.Terminal,
                        themeColor,
                        "REALITY Protocol",
                        "A cutting-edge transport layer that masks mesh traffic as standard HTTPS.",
                        listOf("STEALTH", "ADVANCED"),
                    ),
                    GuideItem(
                        Icons.Outlined.Hub,
                        Color(0xFFA855F7),
                        "Quantum Tunneling",
                        "Advanced packet fragmentation at the RFC minimum MTU.",
                        listOf("FORENSIC"),
                    ),
                    GuideItem(
                        Icons.Outlined.Layers,
                        Color(0xFFF97316),
                        "Camouflage Mode",
                        "Transforms the UI into a fully functional Notes app.",
                        listOf("STEALTH"),
                    ),
                )
            }
        }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "SETUP GUIDE",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black, letterSpacing = 3.sp),
                        )
                        Box(modifier = Modifier.width(20.dp).height(2.dp).background(themeColor, CircleShape))
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                    ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Color.White) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    text = "NETWORK OPTIMIZATION",
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            color = Color.White.copy(alpha = 0.4f),
                            letterSpacing = 2.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    modifier = Modifier.padding(bottom = 8.dp, start = 4.dp),
                )
            }
            itemsIndexed(guideItems) { index, item ->
                GuideCard(item = item, index = index)
            }
            item {
                Spacer(modifier = Modifier.height(32.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White.copy(alpha = 0.02f),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.05f)),
                ) {
                    Text(
                        text = if (uiState.isCamouflageEnabled) "Secure Notes v1.0.0 • Guardian Engine Active" else "ShadowMesh v1.0.0 • Kernel-Level Encryption",
                        style =
                            MaterialTheme.typography.bodySmall.copy(
                                color = Color.Gray.copy(alpha = 0.6f),
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontSize = 10.sp,
                            ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun GuideCard(
    item: GuideItem,
    index: Int,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index * 120L)
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter =
            fadeIn(spring(dampingRatio = Spring.DampingRatioLowBouncy)) +
                slideInVertically(initialOffsetY = { 60 }, animationSpec = spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow)),
    ) {
        Surface(
            color = Color.White.copy(alpha = 0.03f),
            shape = RoundedCornerShape(24.dp),
            border =
                BorderStroke(
                    width = 0.5.dp,
                    brush = Brush.verticalGradient(colors = listOf(Color.White.copy(alpha = 0.12f), Color.Transparent)),
                ),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(
                                    48.dp,
                                ).background(
                                    brush =
                                        Brush.radialGradient(
                                            colors = listOf(item.color.copy(alpha = 0.2f), item.color.copy(alpha = 0.05f)),
                                        ),
                                    shape = RoundedCornerShape(14.dp),
                                ).border(0.5.dp, item.color.copy(alpha = 0.3f), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(imageVector = item.icon, contentDescription = null, tint = item.color, modifier = Modifier.size(24.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style =
                                MaterialTheme.typography.titleMedium.copy(
                                    color = Color.White,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 0.5.sp,
                                ),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = item.description,
                            style =
                                MaterialTheme.typography.bodySmall.copy(
                                    color = Color.Gray,
                                    lineHeight = 20.sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                        )
                        if (item.tags.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item.tags.forEach { tag ->
                                    Surface(
                                        color = item.color.copy(alpha = 0.08f),
                                        shape = RoundedCornerShape(6.dp),
                                        border = BorderStroke(0.5.dp, item.color.copy(alpha = 0.15f)),
                                    ) {
                                        Text(
                                            text = tag,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            fontSize = 9.sp,
                                            color = item.color,
                                            fontWeight = FontWeight.Black,
                                            letterSpacing = 1.sp,
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
}
