package com.shadowmesh.ui_kit.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

val Primary = Color(0xFFB7B9F9)
val Surface = Color(0xFF161821)
val Background = Color(0xFF0A0B10)

/**
 * The app canvas — the near-black every screen is drawn on.
 *
 * This used to be the literal `0xFF0A0A0F` repeated at ten call sites across
 * four files (`MainScreen`, `ErrorScreens`, `AlertHost`, `ShadowMeshComponents`)
 * with nothing tying those ten together, and with `ContrastGateTest` carrying a
 * *fourth* copy of the same number that no declaration owned. That is four
 * places to change the app's background colour and one of them would have been
 * missed silently.
 *
 * Naming it here gives the audit a declaration to read instead of a constant to
 * trust. The call sites are deliberately **not** rewritten to use this constant
 * in this change: `ShadowMeshComponents.kt` takes `background` as a composable
 * parameter in several places, and folding literals into constants is the token
 * work described in the remaining-work register, not a contrast fix. What
 * changes here is that the *gate* now resolves the canvas from the theme rather
 * than asserting a number that nothing owns.
 */
val AppCanvas = Color(0xFF0A0A0F)

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
    val statusSecure: Color = Color(0xFF10BE84), // Success Emerald
    val statusWarning: Color = Color(0xFFF59E0B),
    val statusError: Color = Color(0xFFF37878)
)
