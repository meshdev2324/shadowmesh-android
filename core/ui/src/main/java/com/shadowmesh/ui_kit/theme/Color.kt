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
 * The connected state's three roles, which are **mutually exclusive
 * requirements** rather than three shades of one idea.
 *
 * ## Why one hue could not do all three
 *
 * `#10BE84` was previously both the fill and the foreground of the connected
 * surface. Those are opposite demands on a single colour:
 *
 *  - as a **fill**, white has to be legible on top of it — that needs a low
 *    enough luminance, and `#10BE84` gives white only **2.41:1**, failing WCAG
 *    1.4.11 (3:1) outright;
 *  - as a **foreground**, it has to clear AAA against the surface behind it,
 *    and on the app canvas it measures 8.20:1.
 *
 * One hue therefore sat just above one floor and just below the other, and no
 * re-tint in place could satisfy both — which is why the shield in the connect
 * control could not be fixed by darkening the brand mint. The fix is three named
 * roles, and the three values are chosen independently.
 *
 * [StatusSecureHalo] keeps `#10BE84` because it *is* the brand mint and it *is*
 * the right value for an aurora, a pulse ring and a shadow spot colour — none
 * of which carry a glyph. It is **decorative only**. It clears AAA on the canvas
 * (8.20:1), which is precisely what makes it dangerous, so the invariant that
 * keeps it decorative is a usage rule, not a value one. See
 * `ContrastGateTest.the halo is never painted behind a glyph or an icon`.
 *
 * ## The orb's number is measured, not swatched
 *
 * The orb's backdrop is an additive `BlendMode.Plus` aurora stack, so its real
 * contrast is **not** `#34D399` on `#0A0A0F` (10.27:1) — that figure is what a
 * swatch says. On the worst-case frame the orb actually presents, with three
 * stacked aurora layers and the inner disc's 0.9-alpha gradient over them, it
 * measures **9.18:1**. Both clear AAA; only the second one is true. The gate
 * computes the frame.
 *
 * ## Values, and where each is measured
 *
 *  - [StatusSecureFg] `#34D399` — glyphs on the connected surface. 9.18:1 on the
 *    worst-case orb frame, 10.27:1 on the app canvas. Floor 7:1.
 *  - [StatusSecureFill] `#046C4E` — containers under a white glyph. 6.44:1
 *    against white; clears 1.4.11 (3:1) comfortably. Note it is **below AAA**
 *    under white, so it is a non-text fill only — no small white text on it.
 *  - [StatusSecureHalo] `#10BE84` — decoration. Never behind a glyph.
 *
 * These replace the single `statusSecure` field for *rendering*; the field is
 * retained below for the state-machine colour and is now aliased, so one value
 * can no longer silently reach both a glyph and a fill.
 */
val StatusSecureFg = Color(0xFF34D399)
val StatusSecureFill = Color(0xFF046C4E)
val StatusSecureHalo = Color(0xFF10BE84)

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
    /**
     * The connected-state **halo** only — decoration: the orb's aurora, its
     * pulse rings, shadow spot colours.
     *
     * It used to be the single "secure" colour for the whole connected surface,
     * which meant the same value reached the connect control's fill, the orb's
     * glyphs and the aurora. Those are mutually exclusive requirements (see
     * [StatusSecureFg]); a caller wanting to paint a glyph on the connected
     * surface must now ask for [statusSecureFg], and one wanting a container
     * must ask for [statusSecureFill].
     */
    val statusSecure: Color = StatusSecureHalo,
    /** Glyph colour for the connected surface. AAA on the worst-case orb frame. */
    val statusSecureFg: Color = StatusSecureFg,
    /** Container colour for the connected surface, for a white glyph on top. */
    val statusSecureFill: Color = StatusSecureFill,
    val statusWarning: Color = Color(0xFFF59E0B),
    val statusError: Color = Color(0xFFF37878)
)
