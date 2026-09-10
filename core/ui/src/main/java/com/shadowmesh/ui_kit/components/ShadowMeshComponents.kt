package com.shadowmesh.ui_kit.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.graphics.Path
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.testTag
import java.util.Locale
import com.shadowmesh.core_vpn.CoreUtils.formatBytes
import uniffi.shadowmesh.ConnectionStatus
import uniffi.shadowmesh.NetworkReport
import uniffi.shadowmesh.VpnNode
import kotlin.math.min
import kotlin.math.roundToInt

import androidx.compose.ui.graphics.asComposeRenderEffect
import com.shadowmesh.ui_kit.performance.LocalPerformanceProfile
import com.shadowmesh.ui_kit.performance.PerformanceProfile
import android.os.Build
import androidx.compose.ui.tooling.preview.Preview

import com.shadowmesh.ui_kit.theme.LocalShadowMeshColors
import com.shadowmesh.ui_kit.theme.ShadowMeshTheme

/**
 * Fast sine approximation for high-frequency animations (µs performance).
 */
private fun fastSin(x: Float): Double {
    var xMod = x % (2 * PI.toFloat())
    if (xMod < 0) xMod += (2 * PI.toFloat())
    return sin(xMod.toDouble())
}

/**
 * Applies a high-fidelity glassmorphism aesthetic.
 * SOP 03: Precision depth via translucency and light-edge borders.
 */
@Composable
fun Modifier.shadowMeshGlass(
    radius: Float = 20f,
    baseColor: Color? = null,
    borderColor: Color? = null,
    profile: PerformanceProfile = LocalPerformanceProfile.current
): Modifier {
    val smColors = LocalShadowMeshColors.current
    return this.then(
        Modifier
            .shadow(
                elevation = 2.dp,
                shape = RoundedCornerShape(radius.dp),
                ambientColor = (baseColor ?: Color.White).copy(alpha = 0.05f),
                spotColor = Color.Transparent
            )
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        (baseColor ?: Color.White).copy(alpha = 0.08f),
                        (baseColor ?: Color.White).copy(alpha = 0.03f)
                    )
                ),
                shape = RoundedCornerShape(radius.dp)
            )
            .border(
                width = 0.5.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        (borderColor ?: smColors.glassBorder).copy(alpha = 0.25f),
                        (borderColor ?: smColors.glassBorder).copy(alpha = 0.08f)
                    )
                ),
                shape = RoundedCornerShape(radius.dp)
            )
    )
}

/**
 * Adds a tactile spring-based press animation.
 * SOP 02: Immediate visual response (< 16ms) via graphicsLayer optimization.
 */
@Composable
fun Modifier.tactilePressState(
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "tactile_scale"
    )

    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

@Composable
fun StaggeredContainer(
    index: Int,
    content: @Composable () -> Unit
) {
    var isVisible by rememberSaveable { mutableStateOf(false) }
    val profile = LocalPerformanceProfile.current
    
    LaunchedEffect(Unit) {
        if (!isVisible) {
            val baseDelay = if (profile == PerformanceProfile.LOW) 0L else 50L
            val itemDelay = if (profile == PerformanceProfile.LOW) 0L else 20L
            
            kotlinx.coroutines.delay(baseDelay + index * itemDelay)
            isVisible = true
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessLow
            ),
            initialOffsetY = { it / 3 }
        ) + fadeIn(
            animationSpec = spring(stiffness = Spring.StiffnessLow)
        ),
        exit = fadeOut()
    ) {
        content()
    }
}

@Composable
fun QuantumIndicator(modifier: Modifier = Modifier) { 
    val infiniteTransition = rememberInfiniteTransition(label = "quantum")
    val alpha by infiniteTransition.animateFloat(initialValue = 0.3f, targetValue = 1f, animationSpec = infiniteRepeatable(animation = tween(1000, easing = LinearEasing), repeatMode = RepeatMode.Reverse), label = "alpha")
    Row(modifier = modifier.background(Color(0xFF8B5CF6).copy(alpha = 0.1f), RoundedCornerShape(8.dp)).border(0.5.dp, Color(0xFF8B5CF6).copy(alpha = 0.4f), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { 
        Icon(Icons.Default.Grain, contentDescription = null, tint = Color(0xFF8B5CF6).copy(alpha = alpha), modifier = Modifier.size(16.dp))
        Text("QUANTUM ACTIVE", style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF8B5CF6))) 
    } 
}

@Composable
fun NetworkHealthChip(report: NetworkReport) { 
    val color = when { 
        report.dpiDetected -> Color(0xFFF43F5E)
        report.captivePortalDetected -> Color(0xFFF59E0B)
        else -> Color(0xFF10B981) 
    }
    val text = when { 
        report.dpiDetected -> "DPI ALERT"
        report.captivePortalDetected -> "PORTAL DETECTED"
        else -> "SECURE" 
    }
    val icon = when { 
        report.dpiDetected -> Icons.Default.Report
        report.captivePortalDetected -> Icons.Default.WifiLock
        else -> Icons.Default.Hub 
    }
    Surface(color = color.copy(alpha = 0.08f), shape = CircleShape, border = BorderStroke(1.dp, color.copy(alpha = 0.2f))) { 
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { 
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Text(text = text, color = color, style = MaterialTheme.typography.labelSmall) 
        } 
    } 
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShadowMeshAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    text: String,
    confirmButtonText: String,
    onConfirm: () -> Unit,
    dismissButtonText: String? = null,
    onDismiss: (() -> Unit)? = null,
    confirmEnabled: Boolean = true,
    content: (@Composable () -> Unit)? = null,
    themeColor: Color = Color(0xFF6366F1)
) {
    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
            .shadowMeshGlass(radius = 24f)
            .background(Color(0xFF0A0A0F).copy(alpha = 0.85f), RoundedCornerShape(24.dp))
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(themeColor.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                    .border(0.5.dp, themeColor.copy(alpha = 0.3f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = themeColor,
                    modifier = Modifier.size(28.dp)
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        color = Color.White
                    ),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = Color.Gray,
                        lineHeight = 22.sp
                    ),
                    textAlign = TextAlign.Center
                )
            }

            if (content != null) {
                content()
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (dismissButtonText != null && onDismiss != null) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(dismissButtonText, color = Color.Gray, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                }
                
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f).height(48.dp),
                    enabled = confirmEnabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = themeColor.copy(alpha = 0.2f),
                        contentColor = themeColor,
                        disabledContainerColor = Color.White.copy(alpha = 0.05f),
                        disabledContentColor = Color.Gray
                    ),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, if (confirmEnabled) themeColor.copy(alpha = 0.4f) else Color.Transparent)
                ) {
                    Text(confirmButtonText, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                }
            }
        }
    }
}

/**
 * High-fidelity Gradient Loading Spinner.
 * SOP 03: Visual depth via sweep gradients and physical rotation.
 * SOP 05: Motion choreo via infinite transition.
 */
@Composable
fun GradientCircularProgressIndicator(
    modifier: Modifier = Modifier,
    colors: List<Color> = listOf(
        Color(0xFF6366F1), // Indigo
        Color(0xFF34D399), // Cyan/Emerald
        Color.Transparent
    ),
    strokeWidth: androidx.compose.ui.unit.Dp = 4.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "gradient_spinner")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Canvas(
        modifier = modifier
            .size(40.dp)
            .graphicsLayer { rotationZ = rotation }
            .semantics { 
                role = Role.Image
                contentDescription = "Loading"
            }
    ) {
        val sweepGradient = Brush.sweepGradient(
            colors = colors,
            center = center
        )

        // Draw a full circle with the sweep gradient. 
        // The Transparent color in the list creates the "gap" and "tail".
        drawCircle(
            brush = sweepGradient,
            radius = (size.minDimension - strokeWidth.toPx()) / 2,
            style = Stroke(
                width = strokeWidth.toPx(),
                cap = StrokeCap.Round
            )
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0F)
@Composable
fun GradientSpinnerPreview() {
    ShadowMeshTheme {
        Box(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            GradientCircularProgressIndicator(
                colors = listOf(Color.White, Color.White.copy(alpha = 0.5f), Color.Transparent),
                strokeWidth = 3.dp
            )
        }
    }
}

private fun getCountryNameAndFlag(countryCode: String): Pair<String, String> {
    return when (countryCode.uppercase()) {
        "US" -> "United States" to "🇺🇸"
        "GB" -> "United Kingdom" to "🇬🇧"
        "DE" -> "Germany" to "🇩🇪"
        "JP" -> "Japan" to "🇯🇵"
        "CN" -> "China" to "🇨🇳"
        "IR" -> "Iran" to "🇮🇷"
        "SG" -> "Singapore" to "🇸🇬"
        "NL" -> "Netherlands" to "🇳🇱"
        "FR" -> "France" to "🇫🇷"
        "CA" -> "Canada" to "🇨🇦"
        "AU" -> "Australia" to "🇦🇺"
        "IN" -> "India" to "🇮🇳"
        "BR" -> "Brazil" to "🇧🇷"
        "KR" -> "South Korea" to "🇰🇷"
        "HK" -> "Hong Kong" to "🇭🇰"
        "TR" -> "Türkiye" to "🇹🇷"
        "AE" -> "United Arab Emirates" to "🇦🇪"
        "MY" -> "Malaysia" to "🇲🇾"
        "ID" -> "Indonesia" to "🇮🇩"
        "TH" -> "Thailand" to "🇹🇭"
        "VN" -> "Vietnam" to "🇻🇳"
        "PH" -> "Philippines" to "🇵🇭"
        "TW" -> "Taiwan" to "🇹🇼"
        "RU" -> "Russia" to "🇷🇺"
        "UA" -> "Ukraine" to "🇺🇦"
        "CH" -> "Switzerland" to "🇨🇭"
        "SE" -> "Sweden" to "🇸🇪"
        "FI" -> "Finland" to "🇫🇮"
        else -> countryCode to "🌐"
    }
}

/**
 * Groups nodes into country sections preserving the input ordering
 * (favorites first, then name) within each section. Countries appear in
 * first-seen order so favorites never shuffle their section to the top
 * on every keystroke of the search box.
 */
private fun groupByCountry(nodes: List<VpnNode>): List<Pair<String, List<VpnNode>>> {
    val sections = linkedMapOf<String, MutableList<VpnNode>>()
    nodes.forEach { node ->
        sections.getOrPut(node.country.uppercase()) { mutableListOf() }.add(node)
    }
    return sections.map { (country, sectionNodes) ->
        getCountryNameAndFlag(country).first to sectionNodes
    }
}

// Reverse map (country display name → flag emoji) so the section header
// renders the flag without re-running the when-ladder per recomposition.
private val COUNTRY_FLAG_BY_NAME: Map<String, String> = mapOf(
    "United States" to "🇺🇸",
    "United Kingdom" to "🇬🇧",
    "Germany" to "🇩🇪",
    "Japan" to "🇯🇵",
    "China" to "🇨🇳",
    "Iran" to "🇮🇷",
    "Singapore" to "🇸🇬",
    "Netherlands" to "🇳🇱",
    "France" to "🇫🇷",
    "Canada" to "🇨🇦",
    "Australia" to "🇦🇺",
    "India" to "🇮🇳",
    "Brazil" to "🇧🇷",
    "South Korea" to "🇰🇷",
    "Hong Kong" to "🇭🇰",
    "Türkiye" to "🇹🇷",
    "United Arab Emirates" to "🇦🇪",
    "Malaysia" to "🇲🇾",
    "Indonesia" to "🇮🇩",
    "Thailand" to "🇹🇭",
    "Vietnam" to "🇻🇳",
    "Philippines" to "🇵🇭",
    "Taiwan" to "🇹🇼",
    "Russia" to "🇷🇺",
    "Ukraine" to "🇺🇦",
    "Switzerland" to "🇨🇭",
    "Sweden" to "🇸🇪",
    "Finland" to "🇫🇮",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeSelectionSheet(
    nodes: List<VpnNode>,
    selectedNode: VpnNode?,
    favoriteNodeIds: Set<String>,
    searchQuery: String,
    isLoading: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onRefresh: () -> Unit,
    onSelectBest: () -> Unit,
    onNodeSelected: (VpnNode) -> Unit,
    onDismissRequest: () -> Unit,
    themeColor: Color
) {
    val focusManager = LocalFocusManager.current
    
    val filteredNodes = remember(nodes, searchQuery, favoriteNodeIds) {
        nodes.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.region.contains(searchQuery, ignoreCase = true) ||
            it.country.contains(searchQuery, ignoreCase = true)
        }.sortedWith(compareByDescending<VpnNode> { favoriteNodeIds.contains(it.id) }.thenBy { it.name })
    }
    // Country sections: all Singapore nodes together under Singapore, etc.
    // Favorites stay pinned inside their own country section.
    val countrySections = remember(filteredNodes) { groupByCountry(filteredNodes) }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = Color(0xFF0F0F1A),
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.2f)) },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Select Mesh Node",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                    color = Color.White
                )
                
                Row {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = themeColor,
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color.White)
                        }
                    }
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                placeholder = { Text("Search by name or country...", color = Color.Gray) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.Gray)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = themeColor,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                    focusedContainerColor = Color.White.copy(alpha = 0.05f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
            )

            LazyColumn(
                modifier = Modifier.weight(1f).testTag("node_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (searchQuery.isEmpty()) {
                    item {
                        BestServerItem(themeColor = themeColor, onClick = onSelectBest)
                    }
                }

                countrySections.forEach { (countryName, sectionNodes) ->
                    item(key = "header-$countryName") {
                        CountrySectionHeader(
                            countryName = countryName,
                            nodeCount = sectionNodes.size,
                            themeColor = themeColor,
                        )
                    }
                    items(sectionNodes, key = { it.id }) { node ->
                        NodeItem(
                            node = node,
                            isSelected = selectedNode?.id == node.id,
                            isFavorite = favoriteNodeIds.contains(node.id),
                            themeColor = themeColor,
                            onToggleFavorite = { onToggleFavorite(node.id) },
                            onClick = { onNodeSelected(node) }
                        )
                    }
                }

                if (filteredNodes.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                            Text("No nodes found", color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Country section header for the grouped node list: flag, country name,
 * and live-node count. Semantic structure keeps TalkBack announcing the
 * section before its nodes (WCAG 1.3.1).
 */
@Composable
private fun CountrySectionHeader(
    countryName: String,
    nodeCount: Int,
    themeColor: Color,
) {
    val countryCode = remember(countryName) {
        // Reverse lookup for the flag from the display name.
        COUNTRY_FLAG_BY_NAME[countryName] ?: "🌐"
    }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = "$countryName, $nodeCount nodes"
                },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = countryCode, style = MaterialTheme.typography.titleSmall)
        Text(
            text = countryName.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
            ),
            color = themeColor,
        )
        Text(
            text = "• $nodeCount",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.4f),
        )
    }
}

@Composable
fun BestServerItem(
    themeColor: Color,
    onClick: () -> Unit
) {    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadowMeshGlass(
                radius = 16f, 
                baseColor = themeColor.copy(alpha = 0.1f),
                borderColor = themeColor.copy(alpha = 0.3f)
            )
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = themeColor.copy(alpha = 0.2f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.size(40.dp),
                border = BorderStroke(0.5.dp, themeColor.copy(alpha = 0.4f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Bolt, contentDescription = null, tint = themeColor)
                }
            }
            Column {
                Text("Best Server", color = Color.White, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Black))
                Text("Automatically pick optimal node", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun NodeItem(
    node: VpnNode,
    isSelected: Boolean,
    isFavorite: Boolean,
    themeColor: Color,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val scale by animateFloatAsState(targetValue = if (isSelected) 1.02f else 1f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow), label = "scale")
    val countryInfo = remember(node.country) { getCountryNameAndFlag(node.country) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .shadowMeshGlass(
                radius = 16f, 
                baseColor = if (isSelected) themeColor.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.01f),
                borderColor = if (isSelected) themeColor.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.05f)
            )
            .clickable { 
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick() 
            }
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(
                    color = Color.White.copy(alpha = 0.05f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.size(40.dp),
                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.1f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = countryInfo.second,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(node.name, color = Color.White, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Black))
                        if (node.isSovereign) {
                            Icon(
                                Icons.Default.VerifiedUser, 
                                contentDescription = "Sovereign Node", 
                                tint = themeColor, 
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    Text(
                        text = "${countryInfo.first} • ${node.region}",
                        color = Color.Gray,
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.5.sp)
                    )
                }
            }
            
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (node.latency > 0u) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    color = when {
                                        node.latency < 50u -> Color(0xFF10B981)
                                        node.latency < 150u -> Color(0xFFF59E0B)
                                        else -> Color(0xFFF43F5E)
                                    },
                                    shape = CircleShape
                                )
                        )
                        Text(
                            text = "${node.latency.toInt()}ms",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
                        )
                    }
                }

                IconButton(
                    onClick = { 
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onToggleFavorite() 
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = "Favorite",
                        tint = if (isFavorite) Color(0xFFFBBF24) else Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun LiquidStatusOrb(
    status: ConnectionStatus,
    latency: Double,
    isConnecting: Boolean,
    modifier: Modifier = Modifier,
    pausedUntil: Long? = null
) {
    val isConnected = status == ConnectionStatus.CONNECTED
    val isPaused = status == ConnectionStatus.PAUSED
    
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    val isVisible = lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    val infiniteTransition = rememberInfiniteTransition(label = "aurora_orb")
    val haptic = LocalHapticFeedback.current
    val colorScheme = MaterialTheme.colorScheme
    val smColors = LocalShadowMeshColors.current
    val profile = LocalPerformanceProfile.current
    
    val breathingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.03f,
        targetValue = 0.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    val targetColor = when {
        isConnected -> smColors.statusSecure
        isConnecting -> colorScheme.primary
        isPaused -> smColors.statusWarning
        status == ConnectionStatus.ERROR -> smColors.statusError
        else -> smColors.statusReady.copy(alpha = 0.7f)
    }

    val hubColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = if (isConnected) {
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
        } else {
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
        },
        label = "color"
    )

    val wavePhase by if (isVisible) {
        infiniteTransition.animateFloat(
            initialValue = 0f, targetValue = 2 * PI.toFloat(),
            animationSpec = infiniteRepeatable(tween(if (isConnecting) 2500 else 8000, easing = LinearEasing)), label = "phase"
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(260.dp)) {
            if (isVisible) {
                val layerCount = when(profile) {
                    PerformanceProfile.HIGH -> 3
                    PerformanceProfile.MEDIUM -> 2
                    PerformanceProfile.LOW -> 1
                    else -> 2
                }
                
                repeat(layerCount) { i ->
                    val scale by infiniteTransition.animateFloat(
                        initialValue = 1f, targetValue = 1.1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(2400, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ), label = "s$i"
                    )
                    
                    Canvas(modifier = Modifier.fillMaxSize().graphicsLayer { 
                        scaleX = scale; scaleY = scale
                    }) {
                        val path = Path()
                        val radius = size.width / 2.2f
                        val segments = if (profile == PerformanceProfile.HIGH) 60 else 30 
                        for (angle in 0..segments) {
                            val a = angle.toFloat() * (2 * PI.toFloat() / segments)
                            val waveBase = if (isConnecting) 28f else 14f
                            
                            val wave = (
                                fastSin(a * 3 + wavePhase * 1.3f + i) * 0.5f +
                                fastSin(a * 5 + wavePhase * 0.7f + i * 2) * 0.3f
                            ).toFloat() * waveBase
                            
                            val r = radius + wave
                            val x = size.width / 2 + r * cos(a.toDouble()).toFloat()
                            val y = size.height / 2 + r * sin(a.toDouble()).toFloat()
                            if (angle == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        path.close()
                        
                        drawPath(
                            path = path, 
                            brush = Brush.radialGradient(
                                colors = listOf(hubColor.copy(alpha = 0.4f - i * 0.1f), Color.Transparent),
                                center = Offset(size.width / 2, size.height / 2),
                                radius = size.width / 1.8f
                            ),
                            blendMode = BlendMode.Plus
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize(0.72f)
                    .clip(CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(16.dp)
                        .background(
                            brush = Brush.verticalGradient(
                                listOf(
                                    smColors.cyberObsidianStart.copy(alpha = 0.9f),
                                    smColors.cyberObsidianEnd.copy(alpha = 0.7f)
                                )
                            )
                        )
                        .border(0.5.dp, Color.White.copy(alpha = 0.1f), CircleShape)
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val statusText = when {
                        isConnected -> "YOU ARE PROTECTED"
                        isConnecting -> "SHIELD"
                        isPaused -> "PAUSE"
                        else -> "PROTECT"
                    }
                    
                    AnimatedContent(
                        targetState = statusText,
                        transitionSpec = {
                            (fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) + 
                             scaleIn(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.95f))
                                .togetherWith(fadeOut(tween(150)) + scaleOut(targetScale = 1.05f))
                        }, 
                        label = "txt",
                        contentAlignment = Alignment.Center
                    ) { text ->
                        Text(
                            text = text, color = Color.White,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Black, 
                                letterSpacing = 2.sp, 
                                fontSize = 24.sp
                            ),
                            textAlign = TextAlign.Center
                        )
                    }
                    
                    val detailText = if (isConnected) "${latency.toInt()}MS MESH" else "READY"
                    Text(
                        text = detailText,
                        color = if (isConnected) hubColor else Color.White.copy(alpha = 0.4f),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.5.sp
                        ),
                        textAlign = TextAlign.Center
                    )
                }
            }
            
            if (isConnected && isVisible) {
                val rippleScale by infiniteTransition.animateFloat(
                    initialValue = 1f, targetValue = 2.4f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1800, easing = LinearEasing)
                    ), label = "rs"
                )
                val rippleAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f, targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1800, easing = LinearEasing)
                    ), label = "ra"
                )
                
                LaunchedEffect(rippleScale) {
                    if (rippleScale < 1.05f) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }

                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(hubColor.copy(alpha = rippleAlpha), Color.Transparent),
                            center = center,
                            radius = (size.width / 2) * rippleScale
                        ),
                        radius = (size.width / 2) * rippleScale
                    )
                }
            }
        }
    }
}

@Composable
fun FloatingNavBar(themeColor: Color, currentScreen: String, onNavigate: (String) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .fillMaxWidth()
            .height(72.dp)
            .shadowMeshGlass(radius = 36f, baseColor = Color(0xFF0F0F1A).copy(alpha = 0.8f))
            .border(0.5.dp, Color.White.copy(alpha = 0.12f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavTab(
                selected = currentScreen == "Status",
                themeColor = themeColor,
                icon = Icons.Default.Hub,
                label = "NETWORK",
                onClick = {
                    if (currentScreen != "Status") {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onNavigate("Status")
                    }
                }
            )
            NavTab(
                selected = currentScreen == "Settings",
                themeColor = themeColor,
                icon = Icons.Default.Tune,
                label = "CONFIG",
                onClick = {
                    if (currentScreen != "Settings") {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onNavigate("Settings")
                    }
                }
            )
        }
    }
}

@Composable
fun NavTab(selected: Boolean, themeColor: Color, icon: ImageVector, label: String, onClick: () -> Unit) {
    val smColors = LocalShadowMeshColors.current
    val springSpec = spring<Float>(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy)
    val colorSpec = spring<Color>(stiffness = Spring.StiffnessMediumLow)

    val tint by animateColorAsState(
        if (selected) themeColor else smColors.statusReady.copy(alpha = 0.4f), 
        animationSpec = colorSpec, 
        label = "tint"
    )
    val scale by animateFloatAsState(
        if (selected) 1.12f else 1f, 
        animationSpec = springSpec, 
        label = "scale"
    )
    val labelAlpha by animateFloatAsState(
        if (selected) 1f else 0f, 
        animationSpec = springSpec, 
        label = "labelAlpha"
    )
    
    val interactionSource = remember { MutableInteractionSource() }
    
    Column(
        modifier = Modifier
            .width(84.dp)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            // Ripple suppressed in favour of spring press feedback — but it
            // must be REPLACED, not removed (taps were previously dead).
            .tactilePressState(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp).scale(scale)
        )
        AnimatedVisibility(
            visible = selected,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Text(
                text = label,
                color = tint,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black, fontSize = 9.sp),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
fun ConnectButton(
    status: ConnectionStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    themeColor: Color = Color(0xFF6366F1)
) {
    val haptic = LocalHapticFeedback.current
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (status == ConnectionStatus.CONNECTING_DIRECT) 1.1f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = if (status == ConnectionStatus.CONNECTED) 0.8f else 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    // Tactile press physics: the highest-tapped element in the app must give
    // immediate spring feedback (SOP 02 <16ms) like every other surface.
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(120.dp)
            .scale(pulseScale)
            .tactilePressState(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }
            )
    ) {
        if (status == ConnectionStatus.CONNECTED) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(themeColor.copy(alpha = glowAlpha), CircleShape)
            )
        }

        Surface(
            shape = CircleShape,
            color = if (status == ConnectionStatus.CONNECTED) themeColor else Color(0xFF1E1E24),
            shadowElevation = 8.dp,
            modifier = Modifier.size(96.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PowerSettingsNew,
                contentDescription = "Connect/Disconnect",
                tint = if (status == ConnectionStatus.CONNECTED) Color.White else themeColor,
                modifier = Modifier.padding(24.dp).fillMaxSize()
            )
        }
    }
}

@Composable
fun SettingsCardSection(title: String, borderColor: Color, bgColor: Color, trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) { 
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { 
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { 
            Text(title, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            trailing?.invoke() 
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadowMeshGlass(
                    radius = 20f,
                    baseColor = if (bgColor.alpha > 0.1f) bgColor else Color.White,
                    borderColor = if (borderColor.alpha > 0.1f) borderColor else Color.White
                )
                .padding(16.dp), 
            verticalArrangement = Arrangement.spacedBy(16.dp), 
            content = content
        ) 
    } 
}

@Composable
fun SettingsSwitchRow(icon: ImageVector, iconColor: Color, label: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) { 
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tactilePressState(interactionSource)
            .semantics { contentDescription = "$label: ${if (checked) "on" else "off"}. $subtitle" }, 
        horizontalArrangement = Arrangement.SpaceBetween, 
        verticalAlignment = Alignment.CenterVertically
    ) { 
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) { 
            Box(modifier = Modifier.size(40.dp).background(iconColor.copy(alpha = 0.12f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { 
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp)) 
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { 
                Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray) 
            } 
        }
        Switch(
            checked = checked, 
            onCheckedChange = { 
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onChecked(it) 
            },
            interactionSource = interactionSource
        ) 
    } 
}

@Composable
fun SettingsNavRow(icon: ImageVector, iconColor: Color, label: String, subtitle: String, onClick: () -> Unit = {}) { 
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tactilePressState(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }
            )
            .semantics { contentDescription = "$label. $subtitle. Double-tap to open." }, 
        horizontalArrangement = Arrangement.SpaceBetween, 
        verticalAlignment = Alignment.CenterVertically
    ) { 
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) { 
            Box(modifier = Modifier.size(40.dp).background(iconColor.copy(alpha = 0.12f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { 
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp)) 
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { 
                Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray) 
            } 
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray.copy(alpha = 0.5f), modifier = Modifier.size(18.dp)) 
    } 
}

@Composable
fun SettingsDivider() { HorizontalDivider(color = Color.White.copy(alpha = 0.06f), thickness = 1.dp) }

@Composable
fun SettingsSectionLabel(text: String) { 
    Text(text, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = Color.Gray, letterSpacing = 0.5.sp) 
}

@Composable
fun TrafficModeOption(label: String, subtitle: String, selected: Boolean, accentColor: Color, onClick: () -> Unit) { 
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) accentColor.copy(alpha = 0.09f) else Color.White.copy(alpha = 0.03f))
            .border(1.dp, if (selected) accentColor.copy(alpha = 0.3f) else Color.Transparent, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(16.dp)
            .semantics { 
                contentDescription = "$label mode: $subtitle"
                if (selected) stateDescription = "selected" 
            }, 
        horizontalArrangement = Arrangement.SpaceBetween, 
        verticalAlignment = Alignment.CenterVertically
    ) { 
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { 
            Text(label, color = if (selected) accentColor else Color.White, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium))
            Text(subtitle, color = Color.Gray, style = MaterialTheme.typography.bodySmall) 
        }
        if (selected) Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = accentColor, modifier = Modifier.size(24.dp)) 
    } 
}

@Composable
fun ConnectionDetailItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = Color.White.copy(alpha = 0.6f),
    onClick: (() -> Unit)? = null
) { 
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.Start, 
        modifier = modifier
            .tactilePressState(interactionSource)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (onClick != null) { 
                    Modifier
                        .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                        .background(color.copy(alpha = 0.08f))
                        .padding(horizontal = 8.dp, vertical = 6.dp) 
                } else Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
    ) { 
        Text(text = label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, color = Color.Gray.copy(alpha = 0.8f)), maxLines = 1)
        Spacer(modifier = Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { 
            if (icon != null) { 
                Box(modifier = Modifier.size(16.dp).background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp)).border(0.5.dp, color.copy(alpha = 0.3f), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) { 
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(10.dp)) 
                } 
            }
            Text(text = value, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.ExtraBold, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), maxLines = 1)
            if (onClick != null) { 
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray.copy(alpha = 0.4f), modifier = Modifier.size(12.dp)) 
            } 
        } 
    } 
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SlideToConnect(
    status: ConnectionStatus,
    isConnecting: Boolean,
    themeColor: Color,
    onSwipeComplete: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val isConnected = status == ConnectionStatus.CONNECTED
    
    val swipeWidth = 280.dp
    val handleSize = 56.dp
    val pxSwipeWidth = with(density) { (swipeWidth - handleSize).toPx() }

    val interactionSource = remember { MutableInteractionSource() }
    val isDragged by interactionSource.collectIsDraggedAsState()
    
    val decaySpec = rememberSplineBasedDecay<Float>()
    val state = remember {
        AnchoredDraggableState(
            initialValue = if (status == ConnectionStatus.CONNECTED) 1 else 0,
            anchors = DraggableAnchors { 0 at 0f; 1 at pxSwipeWidth },
            positionalThreshold = { it * 0.35f }, // More responsive threshold
            velocityThreshold = { with(density) { 100.dp.toPx() } },
            snapAnimationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
            decayAnimationSpec = decaySpec
        )
    }

    LaunchedEffect(isConnected) {
        if (!isDragged && state.currentValue != (if (isConnected) 1 else 0)) {
            state.animateTo(if (isConnected) 1 else 0)
        }
    }

    val dragProgress by remember {
        derivedStateOf {
            if (pxSwipeWidth > 0) (state.offset / pxSwipeWidth).coerceIn(0f, 1f) else 0f
        }
    }
    
    LaunchedEffect(dragProgress) {
        if (isDragged) {
            if (dragProgress > 0.98f || dragProgress < 0.02f) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            } else if ((dragProgress * 100).toInt() % 10 == 0) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }

    LaunchedEffect(state.targetValue) {
        if (!isDragged) {
            if (isConnected && state.targetValue == 0) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onSwipeComplete()
            } else if (!isConnected && state.targetValue == 1) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onSwipeComplete()
            }
        }
    }

    Box(
        modifier = Modifier
            .width(swipeWidth)
            .height(64.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.05f))
            .border(0.5.dp, Color.White.copy(alpha = 0.1f), CircleShape)
            .drawBehind {
                val trailColor = if (isConnected) Color(0xFF10B981) else themeColor
                val fillWidth = state.offset + handleSize.toPx()
                
                // Physical "Energy" Trail: Gradient follows the handle with a subtle bloom
                // SOP 03: Higher contrast for unactivated state
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        if (isConnected) listOf(Color(0xFF10B981).copy(0.4f), Color.Transparent)
                        else listOf(Color.Transparent, themeColor.copy(0.4f), themeColor.copy(0.1f)),
                        startX = 0f,
                        endX = size.width
                    ),
                    size = size.copy(width = if (isConnected) size.width else fillWidth.coerceIn(0f, size.width)),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
                )
                
                // Edge Highlight
                if (!isConnected && state.offset > 0) {
                    drawRect(
                        color = trailColor.copy(alpha = dragProgress * 0.6f),
                        topLeft = Offset(state.offset + (handleSize.toPx() / 2), 0f),
                        size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)
                    )
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val labelText = when {
            isConnecting -> "SHIELDING..."
            isConnected -> "RELEASE TO UNPROTECT"
            else -> "SLIDE TO PROTECT"
        }
        
        Text(
            text = labelText,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Black, 
                letterSpacing = 2.sp,
                fontSize = 11.sp
            ),
            color = if (isConnected) Color(0xFF10B981) else Color.White.copy(0.5f),
            modifier = Modifier.fillMaxWidth().graphicsLayer {
                translationX = if (isConnected) 0f else (state.offset * 0.2f)
                alpha = if (isConnected) 1f else (1f - dragProgress * 2f).coerceAtLeast(0f)
            },
            textAlign = TextAlign.Center
        )

        val isPressed by interactionSource.collectIsPressedAsState()
        val handleScale by animateFloatAsState(
            targetValue = if (isDragged || isPressed) 1.15f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "handle_scale"
        )

        Box(
            modifier = Modifier
                .offset { IntOffset(state.offset.roundToInt(), 0) }
                .padding(4.dp)
                .size(handleSize - 8.dp)
                .scale(handleScale)
                .anchoredDraggable(state, Orientation.Horizontal, interactionSource = interactionSource)
                .clip(CircleShape)
                .background(
                    if (isConnected) Brush.radialGradient(listOf(Color(0xFF10B981), Color(0xFF059669)))
                    else Brush.radialGradient(listOf(themeColor, themeColor.copy(alpha = 0.7f))),
                    shape = CircleShape
                )
                .border(2.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                .shadow(if (isDragged) 24.dp else 6.dp, CircleShape, spotColor = if (isConnected) Color(0xFF10B981) else themeColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isConnected) Icons.Default.Shield else Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
fun AccountInfoRow(label: String, value: String, themeColor: Color, isBlurrable: Boolean = false, isRevealed: Boolean = true, onToggleReveal: () -> Unit = {}, actionLabel: String? = null, onAction: () -> Unit = {}) { 
    Column(modifier = Modifier.fillMaxWidth()) { 
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { 
            Text(label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Color.White.copy(alpha = 0.3f)))
            if (actionLabel != null) { 
                Surface(onClick = onAction, color = themeColor.copy(alpha = 0.1f), shape = RoundedCornerShape(8.dp), border = BorderStroke(0.5.dp, themeColor.copy(alpha = 0.3f))) { 
                    Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { 
                        Icon(Icons.Default.Add, null, tint = themeColor, modifier = Modifier.size(12.dp))
                        Text(text = actionLabel, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black, color = themeColor, letterSpacing = 0.5.sp)) 
                    } 
                } 
            } 
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { 
            val displayValue = if (isBlurrable && !isRevealed) "•".repeat(value.length.coerceAtLeast(8)) else value
            Text(text = displayValue, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = Color.White.copy(alpha = 0.8f)))
            if (isBlurrable) { 
                IconButton(onClick = onToggleReveal, modifier = Modifier.size(24.dp)) { 
                    Icon(imageVector = if (isRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "Toggle visibility", tint = Color.Gray, modifier = Modifier.size(16.dp)) 
                } 
            } 
        }
        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = Color.White.copy(alpha = 0.05f)) 
    } 
}

@Composable
fun UsageAnalyticsCard(quantumBytes: ULong, realityBytes: ULong, themeColor: Color) {
    val total = (quantumBytes + realityBytes).coerceAtLeast(1uL)
    val quantumPercent = (quantumBytes.toDouble() / total.toDouble()).toFloat()
    val realityPercent = (realityBytes.toDouble() / total.toDouble()).toFloat()

    val animatedQuantumWidth by animateFloatAsState(targetValue = quantumPercent, animationSpec = spring(stiffness = Spring.StiffnessLow), label = "q_width")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadowMeshGlass(radius = 24f, baseColor = Color.White.copy(alpha = 0.02f))
            .border(0.5.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp))
            .padding(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("TRAFFIC COMPOSITION", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black, color = Color.White.copy(alpha = 0.3f), letterSpacing = 1.5.sp))
            
            Row(modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.05f))) {
                Box(modifier = Modifier.fillMaxHeight().weight(animatedQuantumWidth.coerceAtLeast(0.01f)).background(Color(0xFF8B5CF6), CircleShape))
                Box(modifier = Modifier.fillMaxHeight().weight((1f - animatedQuantumWidth).coerceAtLeast(0.01f)).background(themeColor, CircleShape))
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                UsageDetailItem(label = "QUANTUM", value = formatBytes(quantumBytes), color = Color(0xFF8B5CF6))
                UsageDetailItem(label = "REALITY", value = formatBytes(realityBytes), color = themeColor)
            }
        }
    }
}

@Composable
fun UsageDetailItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.Start) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(modifier = Modifier.size(6.dp).background(color, CircleShape))
            Text(label, style = MaterialTheme.typography.labelSmall.copy(color = Color.Gray))
        }
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(color = Color.White, fontWeight = FontWeight.Black, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
    }
}

@Composable
fun NetworkDiagnosticSection(uiState: NetworkReport?, themeColor: Color, onRunDetection: (Boolean) -> Unit, isDetecting: Boolean) { 
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) { 
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { 
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) { 
                Box(modifier = Modifier.size(40.dp).shadowMeshGlass(radius = 12f, baseColor = Color(0xFF10B981).copy(alpha = 0.05f)), contentAlignment = Alignment.Center) { 
                    Icon(Icons.Default.Analytics, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(18.dp)) 
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { 
                    Text("Network Diagnostics", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Black), color = Color.White)
                    Text("Detect DPI and captive portals", style = MaterialTheme.typography.labelSmall, color = Color.Gray) 
                } 
            }
            if (isDetecting) { 
                GradientCircularProgressIndicator(modifier = Modifier.size(24.dp), colors = listOf(Color(0xFF10B981), Color(0xFF10B981).copy(alpha = 0.3f), Color.Transparent))
            } else { 
                Surface(
                    onClick = { onRunDetection(false) },
                    color = Color(0xFF10B981).copy(alpha = 0.1f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(0.5.dp, Color(0xFF10B981).copy(alpha = 0.3f))
                ) {
                    Text("SCAN", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black, letterSpacing = 1.sp), color = Color(0xFF10B981), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) 
                }
            } 
        }
        uiState?.let { r -> 
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadowMeshGlass(radius = 16f, baseColor = Color.White.copy(alpha = 0.02f))
                    .padding(16.dp), 
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) { 
                DiagnosticItem("Connection", if (r.isConnected) "Online" else "Offline", if (r.isConnected) Color(0xFF10B981) else Color(0xFFEF4444))
                DiagnosticItem("Type", r.networkType.toString(), Color.White)
                if (r.dpiDetected) { 
                    DiagnosticItem("DPI Status", "DEEP PACKET INSPECTION DETECTED", Color(0xFFEF4444)) 
                }
                if (r.captivePortalDetected) { 
                    DiagnosticItem("Portal", "Captive Portal Detected", Color(0xFFF59E0B)) 
                }
                r.serverReport?.let { sr -> 
                    DiagnosticItem("Mesh Advice", sr.recommendation, themeColor) 
                } 
            } 
        } 
    } 
}

@Composable
fun DiagnosticItem(label: String, value: String, valueColor: Color) { 
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { 
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Text(value, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = valueColor, textAlign = TextAlign.End, modifier = Modifier.weight(1f).padding(start = 16.dp)) 
    } 
}

@Composable
fun SpeedTestSection(isRunning: Boolean, result: uniffi.shadowmesh.SpeedTestResult?, error: String?, themeColor: Color, onRunSpeedTest: () -> Unit) { 
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) { 
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { 
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) { 
                Box(modifier = Modifier.size(40.dp).shadowMeshGlass(radius = 12f, baseColor = themeColor.copy(alpha = 0.05f)), contentAlignment = Alignment.Center) { 
                    Icon(Icons.Default.Speed, contentDescription = null, tint = themeColor, modifier = Modifier.size(18.dp)) 
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { 
                    Text("Network Speed Test", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Black), color = Color.White)
                    Text(if (isRunning) "Synchronizing mesh..." else "Measure latency and throughput", style = MaterialTheme.typography.labelSmall, color = Color.Gray) 
                } 
            }
            if (isRunning) { 
                GradientCircularProgressIndicator(modifier = Modifier.size(24.dp), colors = listOf(themeColor, themeColor.copy(alpha = 0.3f), Color.Transparent))
            } else { 
                Surface(
                    onClick = onRunSpeedTest,
                    color = themeColor.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(0.5.dp, themeColor.copy(alpha = 0.3f))
                ) {
                    Text("RUN", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black, letterSpacing = 1.sp), color = themeColor, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) 
                }
            } 
        }
        result?.let { r -> 
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadowMeshGlass(radius = 16f, baseColor = Color.White.copy(alpha = 0.02f))
                    .padding(16.dp), 
                horizontalArrangement = Arrangement.SpaceEvenly
            ) { 
                SpeedResultItem("Latency", "${r.latencyMs.toInt()}ms")
                SpeedResultItem("Download", String.format(Locale.US, "%.1f Mbps", r.downloadSpeedMbps))
                SpeedResultItem("Upload", String.format(Locale.US, "%.1f Mbps", r.uploadSpeedMbps)) 
            } 
        }
        error?.let { e -> 
            Text(e, color = Color(0xFFEF4444), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp)) 
        } 
    } 
}

@Composable
fun SpeedResultItem(label: String, value: String) { 
    Column(horizontalAlignment = Alignment.CenterHorizontally) { 
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = Color.White) 
    } 
}

/**
 * High-fidelity 25-character Sovereignty Token input.
 * SOP 02: µs-level response optimized via derived state and draw caching.
 * SOP 03/04: Matrix-style segmentation with hardware-grade precision.
 * SOP 11: Zero-PII sanitization and masking.
 */
@Composable
fun TokenMatrixInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    isActivating: Boolean = false,
    onQrScan: (() -> Unit)? = null,
    onDone: (() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    
    val cleanValue = remember(value) { value.replace("-", "").uppercase().take(25) }
    val isComplete = cleanValue.length == 25
    
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused = remember { mutableStateOf(false) }

    LaunchedEffect(isComplete) {
        if (isComplete && !isActivating) {
            delay(400)
            onDone?.invoke()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .tactilePressState(interactionSource)
            .shadowMeshGlass(
                radius = 20f,
                borderColor = if (isFocused.value && !isActivating) Color(0xFF6366F1) else null
            )
            .border(
                width = if (isFocused.value && !isActivating) 1.5.dp else 0.5.dp,
                brush = Brush.verticalGradient(
                    if (isFocused.value && !isActivating) listOf(Color(0xFF6366F1), Color(0xFF818CF8).copy(alpha = 0.5f))
                    else listOf(Color.White.copy(alpha = 0.15f), Color.White.copy(alpha = 0.05f))
                ),
                shape = RoundedCornerShape(20.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { 
                    focusRequester.requestFocus()
                    keyboardController?.show()
                }
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        TextField(
            value = cleanValue,
            onValueChange = {
                val nextClean = it.replace("-", "").uppercase().take(25)
                if (nextClean != cleanValue) {
                    onValueChange(nextClean)
                    if (nextClean.length > cleanValue.length) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .onFocusChanged { isFocused.value = it.isFocused }
                .graphicsLayer { alpha = 0.01f },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = if (isComplete) ImeAction.Done else ImeAction.None
            ),
            keyboardActions = KeyboardActions(
                onDone = { onDone?.invoke() }
            ),
            enabled = !isActivating
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (cleanValue.isEmpty()) "ENTER ACCESS CODE" else cleanValue.chunked(5).joinToString("-"),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.3.sp,
                    fontSize = 11.sp,
                    color = if (cleanValue.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.15f)
                ),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start
            )
            
            if (!isActivating) {
                AnimatedContent(
                    targetState = isComplete,
                    transitionSpec = {
                        (scaleIn(spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium)) + fadeIn())
                            .togetherWith(scaleOut(spring(stiffness = Spring.StiffnessLow)) + fadeOut())
                    },
                    label = "icon_morph"
                ) { complete ->
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (complete) onDone?.invoke() else onQrScan?.invoke()
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (complete) Icons.AutoMirrored.Filled.ArrowForward else Icons.Default.QrCodeScanner,
                            contentDescription = if (complete) "Submit" else "Scan QR",
                            tint = if (complete) Color(0xFF6366F1) else Color.White.copy(alpha = 0.4f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
        
        if (isFocused.value && !isActivating && !isComplete) {
            var cursorTarget by remember { mutableStateOf(1f) }
            val cursorAlpha by animateFloatAsState(
                targetValue = cursorTarget,
                animationSpec = spring(stiffness = Spring.StiffnessLow),
                label = "alpha",
                finishedListener = { cursorTarget = if (it == 1f) 0f else 1f }
            )
            
            LaunchedEffect(Unit) { cursorTarget = 1f }
            
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 12.dp)
                    .width(30.dp)
                    .height(2.dp)
                    .graphicsLayer { alpha = cursorAlpha }
                    .background(Color(0xFF6366F1), CircleShape)
            )
        }
    }
}

@Composable
fun ResilienceIndicator(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.shadowMeshGlass(radius = 12f, baseColor = Color(0xFF6366F1).copy(alpha = 0.05f)),
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(0.5.dp, Color(0xFF6366F1).copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                Icons.Default.CloudQueue,
                contentDescription = null,
                tint = Color(0xFF6366F1),
                modifier = Modifier.size(14.dp)
            )
            Text(
                "RESILIENCE ACTIVE",
                color = Color(0xFF6366F1),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    fontSize = 10.sp
                )
            )
        }
    }
}

@Composable
fun ConnectionDetailItem(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable { 
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                onClick() 
            } else Modifier)
            .padding(8.dp)
            .semantics(mergeDescendants = true) {
                role = androidx.compose.ui.semantics.Role.Button
                contentDescription = "$label: $value"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                fontSize = 9.sp,
                color = Color.White.copy(alpha = 0.4f)
            )
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Black,
                fontSize = 11.sp,
                color = Color.White
            ),
            maxLines = 1
        )
    }
}

@Composable
fun CanaryIndicator(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.shadowMeshGlass(radius = 12f, baseColor = Color(0xFFF59E0B).copy(alpha = 0.05f)),
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(0.5.dp, Color(0xFFF59E0B).copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                Icons.Default.Security,
                contentDescription = null,
                tint = Color(0xFFF59E0B),
                modifier = Modifier.size(14.dp)
            )
            Text(
                "CANARY / AUDIT",
                color = Color(0xFFF59E0B),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    fontSize = 10.sp
                )
            )
        }
    }
}
