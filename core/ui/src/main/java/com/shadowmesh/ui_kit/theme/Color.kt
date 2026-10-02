package com.shadowmesh.ui_kit.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

val Primary = Color(0xFF6366F1)
val Surface = Color(0xFF161821)
val Background = Color(0xFF0A0B10)

/**
 * Data class for ShadowMesh specific premium colors and gradients.
 * SOP 10: Brand consistency across all modules.
 */
data class ShadowMeshColors(
    val glassBackground: Color = Color.White.copy(alpha = 0.08f),
    val glassBorder: Color = Color.White.copy(alpha = 0.12f),
    val cyberObsidianStart: Color = Color(0xFF0A0A12),
    val cyberObsidianEnd: Color = Color(0xFF050508),
    val statusReady: Color = Color(0xFF818CF8), // High-Tech Indigo Ready
    val statusSecure: Color = Color(0xFF10B981), // Success Emerald
    val statusWarning: Color = Color(0xFFF59E0B),
    val statusError: Color = Color(0xFFEF4444)
)
