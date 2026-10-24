package com.shadowmesh.app.ui.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WCAG 2.1 AAA contrast for the Android surfaces, computed rather than delegated.
 *
 * ## The defect this pins
 *
 * Seven literals shipped below the AAA floor and were being read by real users:
 * the integrity screen drew its alert in `#F43F5E` (5.38:1), its 11sp monospace
 * error detail in `#64748B` (3.96:1 composited), the `CLOAKED` chip label in
 * `#A855F7` (4.35:1 against its own `A855F7@15%` chip), the `KERNEL ACCELERATED`
 * chip in `#10B981` (5.84:1 on `10B981@20%`), the deactivate button in `#EF4444`
 * (4.86:1 on `EF4444@10%`), and the recovery screen's disconnect button in
 * `#FF6B6B` (6.38:1). Each was individually defensible — it is a saturated hue on
 * a dark app, and 4.5:1 looked fine in review. None of them clears 7:1, and
 * `12.sp` body text at 4.35:1 is exactly the reading that gets worse as the user
 * gets older. The point of this file is that the next one fails the build.
 *
 * ## Why computed, and why not `axe-core`
 *
 * There is no `androidx` accessibility node tree to hand to an automated audit
 * here: `Modifier.background()` and `Surface(color = ...)` are resolved by the
 * Compose layout and draw passes, and a JVM unit test has neither. So the ratio
 * is computed directly with the WCAG 2.x relative-luminance formula against the
 * literals as they appear in the shipped sources.
 *
 * ## What was blind, and what closed (see the test names)
 *
 * The first version of this file swept **one** directory and carried its own list
 * of blind spots. Three of them let real regressions through, and all three are
 * closed here:
 *
 *  1. **Source set.** It swept the `app` module's `ui` tree only. The shared
 *     component kit — `core/ui/.../ShadowMeshComponents.kt`, the single largest
 *     concentration of colour literals in the repository — and the theme's own
 *     `Color.kt` were outside it. `sweptRoots()` now covers both modules, and
 *     `every colour literal in the swept sources is classified` is measured
 *     against *that* set, so the coverage is asserted rather than described.
 *  2. **Surfaces.** `#0A0A0F` was typed into the test. It is not in the theme at
 *     all — it is a literal repeated at ten call sites across four files — so the
 *     gate was pinned to a number that no declaration owned and that nothing
 *     would update if the app canvas changed. `appCanvas` is now **read out of
 *     `Color.kt`**, alongside `Surface` and `Background`, and
 *     `the surfaces are read from the theme, not typed into this test` fails if
 *     any of the three stops resolving.
 *  3. **The banned-hue regex.** It was `color\s*=\s*Color\(<hue>\)`, which is
 *     both too narrow and too blunt:
 *       - too narrow — it misses `contentColor`, `labelColor`, `titleColor` and
 *         the rest of the text-bearing parameters. The
 *         `LiveTunnelRecoveryScreen` disconnect button read
 *         `contentColor = Color(0xFFFF6B6B)` — a violation its own KDoc names by
 *         hex — and this regex would never have seen it.
 *       - too blunt — it cannot tell `color = Color(hue)` (a foreground) from
 *         `color = Color(hue).copy(alpha = 0.15f)` on a `Surface` (a chip
 *         container). At the time of writing it was reporting **two false
 *         positives**, `StatusScreen.kt:200` and `:350`, both of which are the
 *         chip tints that the fixes above deliberately left alone. Its own
 *         failure message says "leave the chip tint alone" while its regex
 *         cannot express that.
 *     It is now role-aware: an assignment counts only when the parameter is
 *     unambiguously text-bearing (`contentColor`, `labelColor`, …) or when it is
 *     a bare `color =` lexically inside a `Text(` call, and only when it is not
 *     followed by `.copy(`. See `BannedHueRoleTest` below for the evidence.
 *
 * ## The blind spot that remains — read this before trusting a green run
 *
 * This gate is a *literal* audit, not a dataflow analysis, and it is still blind
 * in ways that matter:
 *
 *  - **It cannot resolve a background that is not a literal in the same
 *    expression.** A foreground on `MaterialTheme.colorScheme.surface`, or on a
 *    `Surface`/`Card` whose colour is passed in as a parameter, has no computed
 *    background here, so it is classified as an *unresolved pairing* and is
 *    checked only against the declared app surfaces — which is a lower bound, not
 *    the truth about that particular pixel.
 *  - **Most `AccountScreen` and `StatusScreen` accents arrive exactly that way.**
 *    They are `themeColor` parameters sourced from `VPNUiState`, so the hue under
 *    measurement is chosen at runtime, not written at the call site. The literal
 *    audit sees the *default* value and nothing else.
 *  - **A literal has one classification, but it can have several roles.**
 *    `#10B981` is a chip container at `ShadowMeshComponents.kt:1881` and a chip
 *    *label* at `:1885`. The gate can only hold one verdict for it, so it holds
 *    the strict one. That is why `#10B981`'s entry is measured against its own
 *    composited chip rather than against the raw canvas.
 *  - **`Color.*` named constants are still outside the sweep.** Roughly 276
 *    references to `Color.White`, `Color.Gray` and friends are not literals, so
 *    they are not classified here. `Color.Gray` is carried as explicit debt
 *    below; the rest are declared, not audited.
 *
 * A previous attempt at brace-depth dataflow resolution was rejected precisely
 * because it was unsound here: it mis-resolved multi-line `Surface(...)`
 * arguments and read `Color.White.copy(alpha = 0.03f)` as opaque white. A gate
 * that reports confident numbers it did not compute is worse than a gate that
 * declares what it skipped. This one declares it. The
 * `every colour literal in the swept sources is classified` test is what stops
 * the gap from silently widening: a new literal must be added to a table, and
 * adding it means someone had to name a role for it.
 *
 * ## Roles, and why the dark literals are not "unmeasured"
 *
 * Classification is by **role**, not by value:
 *
 *  - [TEXT] paints a glyph. Floor is 7:1 (WCAG 1.4.6 AAA).
 *  - [ACCENT] is an icon tint, a border, a chip fill or a component boundary.
 *    Floor is 3:1 (WCAG 1.4.11).
 *  - [BACKDROP] *is* a background. No contrast obligation exists — but the gate
 *    requires one to be strictly darker than every surface, which is a real
 *    constraint rather than a blank cheque, and which is what keeps a near-black
 *    fill from being quietly re-used as text.
 *  - [THEME_ONLY] is declared in `Color.kt` for the light scheme and is not
 *    applied to any surface this app ships. The gate proves that by asserting it
 *    appears nowhere in the `app` module.
 *
 * ## Recorded debt, not silent passes
 *
 * Six hues below AAA and `Color.Gray` below AAA are listed in [DEBT] with their
 * measured ratios and the site that proves each one paints text. They are *not*
 * fixed here: lightening them is a visual-identity change across the app and
 * belongs to design-charter sign-off, and a colour must never be changed merely
 * to turn this gate green. Recording the number is what keeps the failure
 * visible. `recorded debt is still failing` asserts each row *still* fails, so a
 * colour that gets fixed has to close its row on purpose.
 */
class ContrastGateTest {

    // -----------------------------------------------------------------------
    // WCAG 2.x
    // -----------------------------------------------------------------------

    private fun linearise(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    private fun luminance(hex: String): Double {
        val full = hex.removePrefix("#").let { if (it.length == 3) it.map { c -> "$c$c" }.joinToString("") else it }
        val r = Integer.parseInt(full.substring(0, 2), 16)
        val g = Integer.parseInt(full.substring(2, 4), 16)
        val b = Integer.parseInt(full.substring(4, 6), 16)
        return 0.2126 * linearise(r) + 0.7152 * linearise(g) + 0.0722 * linearise(b)
    }

    private fun ratio(fg: String, bg: String): Double {
        val a = luminance(fg)
        val b = luminance(bg)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    /** Source-order alpha compositing: `Color(hue).copy(alpha = a)` over a surface. */
    private fun composite(fg: String, alpha: Double, bg: String): String {
        fun ch(h: String, i: Int) = Integer.parseInt(h.removePrefix("#").substring(i, i + 2), 16)
        val out = StringBuilder("#")
        for (i in listOf(0, 2, 4)) {
            out.append(String.format("%02X", Math.round(ch(fg, i) * alpha + ch(bg, i) * (1 - alpha))))
        }
        return out.toString()
    }

    /** Additive channel compositing — `BlendMode.Plus`, clamped at 255 per channel. */
    private fun plus(src: String, dst: String): String {
        fun ch(h: String, i: Int) = Integer.parseInt(h.removePrefix("#").substring(i, i + 2), 16)
        val out = StringBuilder("#")
        for (i in listOf(0, 2, 4)) {
            out.append(String.format("%02X", minOf(255, ch(src, i) + ch(dst, i))))
        }
        return out.toString()
    }

    /** `Color(hue).copy(alpha = a)` with no backdrop — the contribution Plus adds. */
    private fun atAlpha(hue: String, alpha: Double): String {
        fun ch(i: Int) = Math.round(Integer.parseInt(hue.removePrefix("#").substring(i, i + 2), 16) * alpha)
        return String.format("#%02X%02X%02X", ch(0), ch(2), ch(4))
    }

    /** WCAG 1.4.6 AAA: 7:1 for normal text. 4.5:1 is the AA floor, 3:1 is 1.4.11. */
    private val aaa = 7.0
    private val aaFloor = 4.5
    private val nonTextFloor = 3.0

    // -----------------------------------------------------------------------
    // Surfaces — read from the theme, never typed in
    // -----------------------------------------------------------------------

    /** The theme declaration file. Outside the `app` module, hence `../core`. */
    private val themeFile = File("../core/ui/src/main/java/com/shadowmesh/ui_kit/theme/Color.kt")

    /**
     * `name -> #RRGGBB` for every `val Name = Color(0x…)` in [themeFile].
     *
     * Parsed rather than duplicated on purpose. `#0A0A0F` used to be a constant
     * in this test and a literal at ten call sites in four files; nothing tied
     * the two together, so a change to the app canvas would have moved the
     * product and left this file asserting against a colour that no longer
     * existed anywhere.
     */
    private fun themeColours(): Map<String, String> {
        assertTrue("theme file not found at ${themeFile.absolutePath}", themeFile.isFile)
        val out = linkedMapOf<String, String>()
        // `[^\n]*?` rather than `[^=]*?`: the data-class members of
        // `ShadowMeshColors` are declared `val cyberObsidianStart: Color =
        // Color(0xFF0A0A12)`, and the `=` in the default blocked the old
        // character class entirely — so **no** token in that class was ever
        // parseable. The gate could not read the orb's own inner-disc colour,
        // which is the backdrop its text actually renders on, and silently fell
        // back to the canvas. Bounded to a single line so a match cannot run on
        // into the next declaration.
        for (m in Regex("""val\s+(\w+)\s*[:=][^\n]*?Color\(0x([0-9A-Fa-f]{8})\)""").findAll(themeFile.readText())) {
            out[m.groupValues[1]] = "#" + m.groupValues[2].substring(2).uppercase()
        }
        return out
    }

    private val appCanvas: String get() = themeColours().getValue("AppCanvas")
    private val themeSurface: String get() = themeColours().getValue("Surface")
    private val themeBackground: String get() = themeColours().getValue("Background")

    /** Every surface a literal foreground in these sources can legitimately rest on. */
    private val surfaces: List<String> by lazy { listOf(appCanvas, themeSurface, themeBackground) }

    // -----------------------------------------------------------------------
    // Classification tables — one row per distinct literal
    // -----------------------------------------------------------------------

    /**
     * Foregrounds with a background this gate *can* name, as
     * [what, foreground, background]. The background is written here rather than
     * inferred, because inference is the part that does not work — see the class
     * KDoc. The first eight are the seven original fixes plus their paired icon.
     */
    private val namedPairs: List<Triple<String, String, String>> = listOf(
        // The 0.9-alpha icon is the *same* hue as the heading. Compositing it is
        // what makes it readable, not a second colour to check: the question is
        // what the user actually sees, against the surface that chip sits on.
        Triple("integrity alert icon (0.9 alpha)", composite("#FDA4AF", 0.9, appCanvas), appCanvas),
        Triple("integrity alert heading", "#FDA4AF", appCanvas),
        Triple("integrity body", "#94A3B8", appCanvas),
        Triple("integrity 11sp monospace error detail", "#94A3B8", composite("#FFFFFF", 0.03, appCanvas)),
        Triple("CLOAKED chip label", "#D8B4FE", composite("#A855F7", 0.15, appCanvas)),
        Triple("KERNEL ACCELERATED chip label", "#6EE7B7", composite("#10B981", 0.20, appCanvas)),
        Triple("deactivate button label", "#FCA5A5", composite("#EF4444", 0.10, appCanvas)),
        Triple("recovery disconnect button", "#FF8A8A", themeSurface),
        // Guide tags sit on the item's own hue at 8% over a WHITE@3% card. The
        // saturated hue only reaches ~4.4-6.7:1 there, so the label takes a
        // lighter step of the same ramp while the chip keeps the original.
        Triple("guide tag — Quantum Tunneling", "#D8B4FE", composite("#A855F7", 0.08, composite("#FFFFFF", 0.03, appCanvas))),
        Triple("guide tag — Camouflage Mode", "#FDBA74", composite("#F97316", 0.08, composite("#FFFFFF", 0.03, appCanvas))),
        Triple("guide tag — Vault Security", "#6EE7B7", composite("#10B981", 0.08, composite("#FFFFFF", 0.03, appCanvas))),
    )

    /**
     * [TEXT] literals that clear AAA on the background they render on, as
     * [what, foreground, background]. Listed per literal rather than per site:
     * the completeness test is value-keyed, so a literal gets one verdict and the
     * strictest one it can carry.
     *
     * Each background is written rather than inferred, because inference is the
     * part that does not work — see the class KDoc. Where a literal's real
     * backdrop is a tinted chip it is named composited, which is also how the
     * chip's own container colour is measured: the user reads the composite, not
     * the source value.
     */
    private val textOnBackdrop: List<Triple<String, String, String>> = listOf(
        Triple("slate body", "#CBD5E1", appCanvas),
        Triple("mint accent", "#6EE7B7", appCanvas),
        Triple("sun yellow", "#FACC15", appCanvas),
        Triple("amber-300", "#FBBF24", appCanvas),
        Triple("pink-80 (light scheme on)", "#EFB8C8", appCanvas),
        Triple("purple-80 (light scheme on)", "#D0BCFF", appCanvas),
        Triple("purple-grey-80", "#CCC2DC", appCanvas),
        Triple("cloak chip label", "#D8B4FE", appCanvas),
        Triple("neon cyan", "#22D3EE", appCanvas),
        Triple("integrity rose", "#FDA4AF", appCanvas),
        Triple("deactivate rose", "#FCA5A5", appCanvas),
        Triple("emerald-400", "#34D399", appCanvas),
        Triple("amber accent", "#F59E0B", appCanvas),
        Triple("recovery rose", "#FF8A8A", appCanvas),
        Triple("cyan accent", "#06B6D4", appCanvas),
        Triple("slate muted", "#94A3B8", appCanvas),
        Triple("neon pink", "#F472B6", appCanvas),
        Triple("red-400, PIN pad error", "#F87171", "#0C0C14"),
        // The 2026-10-12 repaint: one entry per repainted hue, each named with
        // the *strictest* background it renders on — its own chip composite
        // where it has one, the app canvas otherwise. One verdict per literal
        // is the completeness rule (see the KDoc above), so the worst case is
        // the only defensible row.
        Triple("indigo chip label (was #6366F1, 3.67:1)", "#B7B9F9", composite("#B7B9F9", 0.20, appCanvas)),
        Triple("slate chip label (was #64748B, 3.92:1)", "#9AA5B6", composite("#9AA5B6", 0.08, appCanvas)),
        Triple("violet chip label (was #8B5CF6, 4.31:1)", "#B698F9", composite("#B698F9", 0.10, appCanvas)),
        Triple("rose chip label (was #F43F5E, 5.08:1)", "#F87E93", composite("#F87E93", 0.08, appCanvas)),
        Triple("orange chip label (was #F97316, 6.54:1)", "#FA8534", composite("#FA8534", 0.08, appCanvas)),
        Triple("mint chip label (was #10B981, 6.93:1)", "#10BE84", composite("#10BE84", 0.10, appCanvas)),
        Triple("orchid plan label (was #A855F7, 4.99:1)", "#BE81F9", appCanvas),
        Triple("red error text (was #EF4444, 5.25:1)", "#F37878", appCanvas),
        Triple("neutral label (was #888888, 5.57:1)", "#9E9E9E", appCanvas),
    )

    /**
     * [ACCENT] literals — icon tints, borders, chip fills, decorative fills that
     * carry no glyph. Held to 3:1, which is the rule that actually governs them.
     */
    private val accents: List<Pair<String, String>> = listOf(
        "brand pink gradient stop" to "#FF2E88",
        "brutalist blue block" to "#3B82F6",
        "neon violet gradient stop" to "#7C3AED",
        "emerald-600 handle gradient" to "#059669",
        "indigo ready accent" to "#818CF8",
    )

    /**
     * [BACKDROP] literals — the background itself. No contrast obligation, but
     * each must be strictly darker than every declared surface, which the
     * `every backdrop is darker than every surface` test enforces. Naming what
     * each one *is* is the point: a backdrop is an architectural decision, and
     * the table is where it is recorded.
     */
    private val backdrops: List<Pair<String, String>> = listOf(
        "app canvas (MainScreen, ErrorScreens, AlertHost, dialog scrim)" to "#0A0A0F",
        "Color.Surface theme token" to "#161821",
        "Color.Background theme token" to "#0A0B10",
        "OBSIDIAN_STEALTH base" to "#020617",
        "QUANTUM_GRID base" to "#04060E",
        "NEON_MIDNIGHT base" to "#0B0620",
        "NEON_MIDNIGHT horizon band" to "#0D0221",
        "BRUTALIST base" to "#141417",
        "ELECTRIC_HORIZON base" to "#050508",
        "cyberObsidianStart / login canvas" to "#0A0A12",
        "PIN pad and lock-screen canvas" to "#0C0C14",
        "mode-sheet container" to "#0F0F1A",
        "QR scanner card container" to "#13131D",
        "disconnected connect-orb surface" to "#1E1E24",
    )

    /**
     * [THEME_ONLY] — declared in `Color.kt` for the light scheme, applied to no
     * surface this app ships. Asserted absent from the `app` module below.
     */
    private val themeOnly: List<Pair<String, String>> = listOf(
        "PurpleGrey40 (light scheme secondary)" to "#625B71",
        "Purple40 (light scheme primary)" to "#6650A4",
        "Pink40 (light scheme tertiary)" to "#7D5260",
    )

    // -----------------------------------------------------------------------
    // The connected surface — fill / foreground / halo are three roles
    // -----------------------------------------------------------------------

    /**
     * The worst-case pixel the orb can actually present, computed rather than
     * argued.
     *
     * `LiquidStatusOrb` stacks its aurora with `BlendMode.Plus`, which is
     * **additive**: each layer's contribution is summed into the frame and
     * clamped, so the backdrop under the orb's centre is far lighter than any
     * one swatch. On top of that sits the inner disc, a vertical gradient of
     * `cyberObsidianStart@0.9` over `cyberObsidianEnd@0.7` — neither opaque, so
     * the aurora reads through it too.
     *
     * `layerCount` is 3 on the default `PerformanceProfile.HIGH`
     * (`LocalPerformanceProfile` defaults to HIGH), which is the most layers
     * and therefore the lightest frame. The gradient falls off from the centre,
     * and the status word is centred, so the worst pixel is the centre itself
     * where the aurora is still at full alpha.
     *
     * Measuring this on a swatch is what produced the 10.26:1 figure quoted for
     * `--sm-status-secure-fg`: that is `#34D399` against `#0A0A0F` with nothing
     * behind it. The real number is below, and the gate measures the real one.
     */
    private fun worstCaseOrbFrame(hub: String, layerCount: Int = 3): String {
        var frame = appCanvas
        for (i in 0 until layerCount) {
            frame = plus(atAlpha(hub, 0.4 - i * 0.1), frame)
        }
        // The lightest pixel the inner disc presents is its top stop.
        return composite(themeColours().getValue("cyberObsidianStart"), 0.9, frame)
    }

    /**
     * The connected state's three roles, which are **mutually exclusive
     * requirements**, not three shades of one thing:
     *
     *  - [secureFg] is a glyph colour. It must clear AAA against whatever it
     *    renders on — which for the orb is the aurora frame, not the canvas.
     *  - [secureFill] is a container. It must clear 1.4.11 against what sits on
     *    it, and it must be dark enough to carry white.
     *  - [secureHalo] is decorative — an aurora tint, a shadow spot colour, a
     *    chip wash. It is explicitly forbidden from having a glyph on it.
     *
     * The defect this pins: `#10BE84` was all three at once. It clears AAA as a
     * text hue on the canvas and fails 1.4.11 as a fill under white (2.41:1),
     * so no single re-tint of that hue could satisfy both. One verdict per
     * literal cannot express that, which is why it is now three named roles.
     */
    private val secureFg = "#34D399"
    private val secureFill = "#046C4E"
    private val secureHalo = "#10BE84"

    /**
     * Site-addressed pairings the lexical sweep structurally cannot reach.
     *
     * Each row is a real foreground resting on a real background that is **not
     * a literal in the same expression**. Two independent reasons the sweep
     * misses all of these:
     *
     *  1. The foreground is `Color.White` — a *named constant*, which the class
     *     KDoc already declares out of scope.
     *  2. The background is a `Brush.radialGradient(listOf(...))` on a
     *     `.background(...)` chain — there is no `Surface(color = …)` and no
     *     `Text(` boundary for a role-aware regex to key on.
     *
     * So these are written down by hand, with the background named, which is
     * the only thing that keeps them honest.
     */
    private val siteAddressedPairs: List<Triple<String, String, String>> = listOf(
        // The connect control's handle, connected: a white glyph on the secure
        // fill. This is the pairing that shipped at **2.41:1** (a white shield
        // on `#10BE84`) and that now measures against the *fill* role.
        Triple("connect handle icon on the secure fill", "#FFFFFF", secureFill),
        // The orb's detail line, as shipped: the secure hue used as a glyph on
        // the inner disc over the worst-case aurora frame. Now the fg role.
        Triple("orb detail line, secure fg on the aurora frame", secureFg, worstCaseOrbFrame(secureHalo)),
    )

    /**
     * The pre-fix pairings, kept as a negative control.
     *
     * Only the pairing that actually failed is held here: a white glyph on
     * `#10BE84`, at 2.41:1. Its gradient's *outer* stop `#059669` measured
     * 3.77:1 and always passed 1.4.11, so it is not recorded — inflating the
     * negative control with rows that were never broken is how a control stops
     * meaning anything.
     *
     * The orb's old detail glyph (`#10BE84` on the worst-case aurora frame,
     * 8.19:1) is likewise absent on purpose. It cleared AAA. Its removal was a
     * **semantic** decision — the figure is an API round-trip, not tunnel RTT —
     * and recording it as a contrast failure would misattribute it.
     */
    private val retiredPairings: List<Triple<String, String, String>> = listOf(
        Triple("white shield on #10BE84 — the shipped connect handle", "#FFFFFF", "#10BE84"),
    )

    /**
     * The contract for the orb's own text, measured on the frame it renders on.
     * Floor is AAA (7:1) because these are glyphs, not accents.
     */
    private val orbTextPairs: List<Triple<String, String, String>> = listOf(
        Triple("orb status word (24sp) on the worst-case aurora frame", "#FFFFFF", worstCaseOrbFrame("#10BE84")),
        Triple("orb detail line on the worst-case aurora frame (--sm-status-secure-fg)", secureFg, worstCaseOrbFrame(secureHalo)),
    )

    /**
     * Measured failures that are deliberately not fixed here, recorded so the
     * debt is quantified and visible instead of merely absent. The `site` column
     * is the evidence: each is a place where the hue demonstrably paints a glyph,
     * which is what makes the row a [TEXT] failure rather than an [ACCENT] one.
     */
    private val debt: List<DebtRow> = listOf(
        // Found by *widening the pairing* rather than by the literal sweep, which
        // cannot see it: the foreground is `Color.White` (a named constant) and
        // the background is a `Brush.radialGradient` parameter — no literal, no
        // `Text(` boundary, nothing for the lexical rules to key on.
        //
        // The connect handle's **disconnected** state paints a white arrow on
        // `themeColor`, declared as `Primary` `#B7B9F9` — **1.85:1**, which is
        // worse than the shield that prompted this whole pass. Pre-existing, not
        // introduced by the shield removal.
        //
        // Not fixed here. The honest lower bound is the declared `Primary`,
        // because `themeColor` is a runtime argument and a caller can pass
        // anything; fixing it means choosing a different handle fill for the
        // disconnected state, which is a visual-identity change owned by the
        // design charter, not a contrast fix. Recording the number is what keeps
        // it visible.
        DebtRow("#FFFFFF", "#B7B9F9", "ShadowMeshComponents.kt:1858"),
    )

    // The nine sub-AAA text hues this table used to exempt were repainted on
    // 2026-10-12 rather than left as debt: for each hue the replacement is the
    // nearest point in the same HSL hue family whose contrast against its own
    // composite (the hue at its chip alpha over the app canvas) clears 7.2:1 —
    // i.e. the selection rule is a stated minimum-shift derivation, and the
    // values live in `textOnBackdrop` below, so the gate measures them every
    // run instead of exempting them once. ``6366F1 -> B7B9F9 · 64748B ->
    // 9AA5B6 · 8B5CF6 -> B698F9 · F43F5E -> F87E93 · F97316 -> FA8534 ·
    // 10B981 -> 10BE84 · A855F7 -> BE81F9 · EF4444 -> F37878 · 888888 ->
    // 9E9E9E``. A hue is no longer exempt because it fails; it is listed
    // because it passes, and a future edit that darkens a glyph fails the
    // same run that made the old debt row invisible.

    private data class DebtRow(val literal: String, val background: String, val site: String)

    /**
     * True when [literal] is already recorded as debt at exactly `file:line`.
     *
     * A debt row cites its evidence as `Something.kt:NNN`, so the comparison is
     * on the file *basename* — the swept labels are `core/ui/ShadowMeshComponents.kt`.
     */
    private fun recordedAt(literal: String, file: String, line: Int): Boolean {
        // The banned list is written as ARGB (`0xFF10B981`); the debt table is
        // written as RGB (`#10B981`). Compare the six colour digits, which is
        // also what the alpha-blind sweep itself treats as the identity.
        val wanted = literal.uppercase().removePrefix("0X").takeLast(6)
        val fileName = file.substringAfterLast('/')
        return debt.any { row ->
            row.literal.removePrefix("#").uppercase() == wanted &&
                Regex("""([\w.]+\.kt):(\d+)""").find(row.site)?.let {
                    it.groupValues[1] == fileName && it.groupValues[2].toInt() == line
                } == true
        }
    }

    /**
     * Hues lifted out of text during the original fix, kept banned so they cannot
     * creep back as a foreground. These remain legitimate as chip tints, icon
     * tints and container fills — that is not what this rule is about.
     */
    private val bannedHues = listOf(
        "0xFFF43F5E", // 5.38:1 on the app canvas
        "0xFF64748B", // 3.96:1 composited on the error-detail surface
        "0xFFA855F7", // 4.35:1 on its own @15% chip
        "0xFF10B981", // 5.84:1 on its own @20% chip
        "0xFFEF4444", // 4.86:1 on its own @10% container
        "0xFFFF6B6B", // 6.38:1 on the recovery surface
    )

    /** Parameters whose value is unambiguously a text colour. */
    private val textParameters = setOf(
        "contentcolor", "selectedcontentcolor", "disabledcontentcolor",
        "labelcolor", "titlecolor", "bodycolor", "textcolor", "placeholdercolor",
    )

    // -----------------------------------------------------------------------
    // Source set — both modules
    // -----------------------------------------------------------------------

    /**
     * The swept roots. `core/ui` is the shared kit and the theme; the `app`
     * module's `ui` tree is the app's own screens. Before this list existed, the
     * gate read one of them.
     */
    private fun sweptRoots(): List<Pair<String, File>> = listOf(
        "app/ui" to File("src/main/java/com/shadowmesh/app/ui"),
        "core/ui" to File("../core/ui/src/main/java/com/shadowmesh/ui_kit"),
    )

    private fun kotlinSources(): List<Pair<String, String>> =
        sweptRoots().flatMap { (tag, dir) ->
            if (!dir.isDirectory) return@flatMap emptyList()
            dir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".kt") }
                .map { "$tag/${it.name}" to it.readText() }
                .toList()
        }

    // -----------------------------------------------------------------------
    // Lexical scanning
    // -----------------------------------------------------------------------

    /**
     * Blanks comments and string/char literals, preserving offsets and newlines.
     *
     * Without this, `ErrorScreens.kt` reading `"Error Code: 0x8004100E"` is
     * classified as a colour named `#04100E` — a false positive that would have
     * to be explained away in the tables forever. A previous scanner that only
     * handled strings was defeated by a lone apostrophe in a `//` comment.
     */
    private fun blankNonCode(code: String): String {
        val out = code.toCharArray()
        fun wipe(from: Int, to: Int) {
            for (k in from until minOf(to, out.size)) if (out[k] != '\n') out[k] = ' '
        }
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                c == '/' && i + 1 < code.length && code[i + 1] == '/' -> {
                    val j = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                    wipe(i, j); i = j
                }
                c == '/' && i + 1 < code.length && code[i + 1] == '*' -> {
                    val j = code.indexOf("*/", i + 2).let { if (it < 0) code.length else it + 2 }
                    wipe(i, j); i = j
                }
                code.startsWith("\"\"\"", i) -> {
                    val j = code.indexOf("\"\"\"", i + 3).let { if (it < 0) code.length else it + 3 }
                    wipe(i, j); i = j
                }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < code.length && code[j] != c) {
                        if (code[j] == '\\') j++
                        if (code[j] == '\n') break
                        j++
                    }
                    wipe(i, minOf(j + 1, code.length)); i = j + 1
                }
                else -> i++
            }
        }
        return String(out)
    }

    /** Character ranges of the balanced argument lists of every `Text(` call. */
    private fun textCallRanges(code: String): List<IntRange> =
        Regex("""(?<![\w.])Text\s*\(""").findAll(code).mapNotNull { m ->
            var depth = 0
            var j = m.range.last
            while (j < code.length) {
                when (code[j]) {
                    '(' -> depth++
                    ')' -> { depth--; if (depth == 0) return@mapNotNull m.range.first..j }
                }
                j++
            }
            null
        }.toList()

    /** Every `0xAARRGGBB` literal in [code] as `literal to 1-based line`, ignoring comments and strings. */
    private fun hexLiteralSites(code: String): List<Pair<String, Int>> {
        val scannable = blankNonCode(code)
        return Regex("""0x[0-9A-Fa-f]{8}""", RegexOption.IGNORE_CASE).findAll(scannable)
            .map { m ->
                "#" + m.value.substring(4).uppercase() to
                    scannable.take(m.range.first).count { c -> c == '\n' } + 1
            }
            .toList()
    }

    private fun hexLiterals(code: String): List<String> = hexLiteralSites(code).map { it.first }

    // -----------------------------------------------------------------------
    // Layer 1 — named pairs
    // -----------------------------------------------------------------------

    @Test
    fun `every named pair clears AAA against the background it renders on`() {
        val failures = namedPairs.mapNotNull { (what, fg, bg) ->
            val r = ratio(fg, bg)
            if (r < aaa) "$what: $fg on $bg is ${"%.2f".format(r)}:1 (AAA needs $aaa:1)" else null
        }
        assertEquals(
            "These foregrounds fail WCAG AAA on the background they actually sit on:\n" +
                failures.joinToString("\n") { "  - $it" },
            emptyList<String>(),
            failures,
        )
    }

    // -----------------------------------------------------------------------
    // Layer 2 — role measurements
    // -----------------------------------------------------------------------

    @Test
    fun `every TEXT literal clears AAA against the backdrop it renders on`() {
        val failures = textOnBackdrop.mapNotNull { (what, fg, bg) ->
            val r = ratio(fg, bg)
            if (r < aaa) "$what: $fg on $bg is ${"%.2f".format(r)}:1 (AAA needs $aaa:1)" else null
        }
        assertEquals(
            "These are classified as painting text but fall below AAA on their own backdrop:\n" +
                failures.joinToString("\n") { "  - $it" },
            emptyList<String>(),
            failures,
        )
    }

    @Test
    fun `every TEXT literal clears the AA floor against every declared surface`() {
        // The honest lower bound, and the one concession to the blind spot named
        // in the class KDoc. This gate cannot know which backdrop a
        // `themeColor` argument will resolve to at runtime, so it cannot claim
        // 7:1 everywhere. It *can* claim that no declared surface in this app can
        // take a declared text colour below the AA floor — so an unknown
        // background is bad but bounded. Measured against `Color.Surface`, which
        // is the lightest surface declared, `#10B981` is 6.98:1: above AA, below
        // AAA. That gap is real and is why it is a debt row rather than a pass.
        val failures = textOnBackdrop.mapNotNull { (what, fg) ->
            val worst = surfaces.minOf { ratio(fg, it) }
            if (worst < aaFloor) "$what: $fg worst-case ${"%.2f".format(worst)}:1 against the lightest declared surface" else null
        }
        assertEquals(
            "A declared text colour can drop below the AA floor against a declared surface:\n" +
                failures.joinToString("\n") { "  - $it" },
            emptyList<String>(),
            failures,
        )
    }

    @Test
    fun `every ACCENT literal clears the 3 to 1 floor`() {
        val failures = accents.mapNotNull { (what, fg) ->
            val worst = surfaces.minOf { ratio(fg, it) }
            if (worst < nonTextFloor) "$what: $fg worst-case ${"%.2f".format(worst)}:1" else null
        }
        assertEquals(
            "These accents fail WCAG 1.4.11 (3:1):\n" + failures.joinToString("\n") { "  - $it" },
            emptyList<String>(),
            failures,
        )
    }

    @Test
    fun `every BACKDROP is darker than every surface`() {
        // A backdrop has no contrast obligation — it *is* the background. But a
        // backdrop that is lighter than the surface it is declared against is a
        // declaration this gate should refuse, because it is how a near-black
        // fill quietly becomes "just use it for the text too".
        val failures = backdrops.mapNotNull { (what, bg) ->
            val lightest = surfaces.maxOf { ratio(bg, it) }
            if (lightest >= nonTextFloor) "$what: $bg is not darker than every surface (${"%.2f".format(lightest)}:1 vs the lightest)" else null
        }
        assertEquals(
            "These are declared as backdrops but are not darker than every surface:\n" +
                failures.joinToString("\n") { "  - $it" },
            emptyList<String>(),
            failures,
        )
    }

    @Test
    fun `every THEME_ONLY colour appears nowhere in the app module`() {
        // These are declared for the light scheme. If one started being applied
        // to a surface this app ships, it would have to be measured against that
        // surface, and 3.05:1 is nowhere near either floor.
        val offenders = mutableListOf<String>()
        val appRoot = File("src/main/java/com/shadowmesh/app")
        val appCode: List<String> =
            if (!appRoot.isDirectory) emptyList()
            else appRoot.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".kt") }
                .map { it.readText() }
                .toList()
        for ((what, hex) in themeOnly) {
            val argb = "0xFF" + hex.removePrefix("#").uppercase()
            if (appCode.any { it.contains(argb, ignoreCase = true) }) offenders.add("$what ($argb)")
        }
        assertEquals(
            "A light-scheme token is now used in the app module. It must be reclassified against " +
                "the surface it actually lands on:\n" + offenders.joinToString("\n") { "  - $it" },
            emptyList<String>(),
            offenders,
        )
    }

    // -----------------------------------------------------------------------
    // Layer 2b — the connected surface: site-addressed pairs and the aurora frame
    // -----------------------------------------------------------------------

    @Test
    fun `every site-addressed pair clears the floor on the background it renders on`() {
        // These are the pairings the lexical sweep cannot reach — see the table's
        // KDoc. Every one of them is foreground-on-container, so the floor is
        // 1.4.11's 3:1, and each must also clear AAA if it carries a glyph.
        val failures = siteAddressedPairs.mapNotNull { (what, fg, bg) ->
            val r = ratio(fg, bg)
            if (r < nonTextFloor) "$what: $fg on $bg is ${"%.2f".format(r)}:1 (1.4.11 needs $nonTextFloor:1)" else null
        }
        assertEquals(
            "A glyph is painted on a background it cannot be read against. Fix the role split — " +
                "do not re-tint the hue in place:\n" +
                failures.joinToString("\n") { "  - $it" },
            emptyList<String>(),
            failures,
        )
    }

    @Test
    fun `the retired connected-surface pairings still fail`() {
        // The negative control for the role split. Each of these was measured as
        // failing before `--sm-status-secure-fg` / `-fill` / `-halo` existed. If
        // any of them now passes, the gate cannot be trusted on this surface and
        // the ramp is safe to reconsider.
        //
        // This is the assertion that makes "I re-pointed the number" impossible:
        // the old value is still held here and still has to be bad.
        val stillFailing = retiredPairings.mapNotNull { (what, fg, bg) ->
            val r = ratio(fg, bg)
            if (r >= nonTextFloor) null else what
        }
        assertEquals(
            "A retired pairing now clears 1.4.11. That is either a palette change worth recording " +
                "or a gate that has stopped measuring this surface — either way, revisit this list " +
                "deliberately rather than deleting it:\n" +
                retiredPairings.filter { (what, fg, bg) ->
                    ratio(fg, bg) >= nonTextFloor
                }.joinToString("\n") { "  - $it" },
            retiredPairings.size,
            stillFailing.size,
        )
        // And the headline number, recomputed, so the figure quoted in the
        // component's KDoc cannot drift away from what this gate measures.
        assertEquals(2.41, ratio("#FFFFFF", "#10BE84"), 0.01)
    }

    @Test
    fun `every orb text pair clears AAA on the worst-case aurora frame`() {
        val failures = orbTextPairs.mapNotNull { (what, fg, bg) ->
            val r = ratio(fg, bg)
            if (r < aaa) "$what: $fg on $bg is ${"%.2f".format(r)}:1 (AAA needs $aaa:1)" else null
        }
        assertEquals(
            "Orb text does not clear AAA on the frame it actually renders over. The frame is the " +
                "additive aurora stack, not the app canvas:\n" +
                failures.joinToString("\n") { "  - $it" },
            emptyList<String>(),
            failures,
        )
    }

    @Test
    fun `the aurora frame is lighter than the canvas it is stacked on`() {
        // The whole reason `worstCaseOrbFrame` exists. If BlendMode.Plus ever
        // stopped being additive, or the layer alphas changed, this fails and the
        // orb numbers above would silently revert to measuring a swatch.
        val frame = worstCaseOrbFrame("#10BE84")
        assertTrue(
            "the stacked aurora frame should be lighter than the app canvas; " +
                "got $frame on $appCanvas (luminance ${"%.6f".format(luminance(frame))} vs " +
                "${"%.6f".format(luminance(appCanvas))})",
            luminance(frame) > luminance(appCanvas),
        )
        // Compared as **luminance**, not as a contrast ratio. Both the frame and
        // the canvas are near-black, so their ratio against each other is only
        // ~1.12:1 and would look like noise; what matters is that the stack
        // roughly triples the luminance a glyph has to fight, which is what eats
        // the headroom the orb text needs.
        assertTrue(
            "the stacked aurora frame should be materially lighter than the canvas — " +
                "expected at least 2x its luminance, got " +
                "${"%.2f".format(luminance(frame) / luminance(appCanvas))}x",
            luminance(frame) >= 2 * luminance(appCanvas),
        )
    }

    @Test
    fun `the secure hue is not doing two mutually exclusive jobs`() {
        // The structural finding. `#10BE84` clears AAA as a *glyph* on the canvas
        // and fails 1.4.11 as a *fill* under white. Those are opposite
        // requirements on one hue, so it can satisfy neither reliably. The fix is
        // three named roles, and this asserts they stay three.
        val fgOnCanvas = ratio(secureFg, appCanvas)
        val fillUnderWhite = ratio("#FFFFFF", secureFill)
        val haloOnCanvas = ratio(secureHalo, appCanvas)

        assertTrue(
            "--sm-status-secure-fg must clear AAA as a glyph; $secureFg on $appCanvas is " +
                "${"%.2f".format(fgOnCanvas)}:1",
            fgOnCanvas >= aaa,
        )
        assertTrue(
            "--sm-status-secure-fill must clear 1.4.11 under white; $secureFill is " +
                "${"%.2f".format(fillUnderWhite)}:1",
            fillUnderWhite >= nonTextFloor,
        )
        // The halo is decorative. This is a **usage** rule, not a value rule, and
        // deliberately not written as one: `#10BE84` is 8.20:1 on the canvas, so
        // it *passes* AAA as a text hue — which is exactly why it kept being used
        // as one. Asserting it falls below AAA would be asserting a colour choice
        // L6 owns, and it would still not stop the misuse. What stops the misuse is
        // `the halo is never painted behind a glyph or an icon`, below.
        assertTrue(
            "the three roles must not collapse back to one hue",
            secureHalo != secureFg && secureHalo != secureFill,
        )
        // And the three must actually be three values — three names for one hue
        // would restore the ambiguity this replaces.
        assertEquals(
            "the secure roles must be three distinct colours",
            3,
            listOf(secureFg, secureFill, secureHalo).distinct().size,
        )
    }

    @Test
    fun `the halo is never painted behind a glyph or an icon`() {
        // `--sm-status-secure-halo` is the token the aurora, the pulse rings and
        // the shadow spot colour use. It clears AAA on the canvas, so nothing
        // stops it looking like a text colour; the only thing that keeps it
        // decorative is that no call site puts a glyph on it. Enforced at every
        // use of the token, in the same shape as the banned-hue rule.
        val haloUse = Regex("""(?<![\w.])([\w.]+)\s*=\s*Color\((0x[0-9A-Fa-f]{8})\)""", RegexOption.IGNORE_CASE)
        val offenders = mutableListOf<String>()
        for ((file, code) in kotlinSources()) {
            val scannable = blankNonCode(code)
            val texts = textCallRanges(scannable)
            for (m in haloUse.findAll(scannable)) {
                if (m.groupValues[2].uppercase().removePrefix("0X").takeLast(6) != secureHalo.removePrefix("#")) continue
                val name = m.groupValues[1].lowercase()
                if (name !in textParameters && texts.none { m.range.first in it }) continue
                val line = scannable.take(m.range.first).count { it == '\n' } + 1
                offenders.add("$file:$line  $name = ${m.groupValues[2]}")
            }
        }
        assertEquals(
            "The decorative halo token is painting a glyph. `--sm-status-secure-halo` is for " +
                "aurora, rings and shadow spots only — text on the connected surface uses " +
                "`--sm-status-secure-fg`:\n" + offenders.joinToString("\n") { "  - $it" },
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the three secure roles are declared in the theme with the contracted hex`() {
        // The token contract is pinned to source, so a future edit that quietly
        // re-tints one role fails here instead of silently moving the numbers the
        // tests above assert against. Read out of `Color.kt`, not duplicated.
        val colours = themeColours()
        assertEquals(secureFg, colours["StatusSecureFg"])
        assertEquals(secureFill, colours["StatusSecureFill"])
        assertEquals(secureHalo, colours["StatusSecureHalo"])
    }

    @Test
    fun `no declared TEXT literal is also declared as a fill`() {
        // The same defect, caught generically rather than at the one site that
        // happened to ship it. A literal classified as text on one backdrop and
        // reused as a container under a different glyph is a hue whose two
        // obligations cannot both be met.
        val textLiterals = (textOnBackdrop.map { it.second } + orbTextPairs.map { it.second } + namedPairs.map { it.second })
            .map { it.uppercase() }
            .toSet()
        val fillLiterals = (backdrops.map { it.second } + listOf(secureFill))
            .map { it.removePrefix("#").uppercase() }
            .toSet()
        val collisions = textLiterals.filter { it.removePrefix("#") in fillLiterals }
        assertEquals(
            "These colours are classified as painting text AND as a background. A hue that is " +
                "both cannot satisfy either role — split it into a fg/fill pair:\n" +
                collisions.joinToString("\n") { "  - $it" },
            emptyList<String>(),
            collisions,
        )
    }

    // -----------------------------------------------------------------------
    // Layer 3 — the banned hues, by role rather than by spelling
    // -----------------------------------------------------------------------

    @Test
    fun `no banned hue is painted as a glyph`() {
        val assignment = Regex(
            """(?<![\w.])([\w.]+)\s*=\s*Color\((0x[0-9A-Fa-f]{8})\)(\.copy\()?""",
            RegexOption.IGNORE_CASE,
        )
        val offenders = mutableListOf<String>()
        // Upper-case both sides. This was a **vacuous** rule for a while:
        // `bannedHues` is written with a lower-case `0x` and the match is
        // upper-cased to `0X`, so `literal !in bannedHues` was true for every
        // single candidate and the rule skipped all of them. It reported green
        // while being unable to fail — which is the exact failure this file
        // exists to prevent, committed in the file whose subject is not letting
        // gates pass vacuously. It was caught by injecting the violations rather
        // than by the negative control, because the negative control built its
        // own set and therefore never exercised the same comparison.
        val banned = bannedHues.map { it.uppercase() }.toSet()

        for ((file, code) in kotlinSources()) {
            if (file.contains("NotesDecoyScreen")) continue
            val scannable = blankNonCode(code)
            val texts = textCallRanges(scannable)
            for (m in assignment.findAll(scannable)) {
                val literal = m.groupValues[2].uppercase()
                if (literal !in banned) continue
                // `color = Color(hue).copy(alpha = …)` is a chip or container
                // tint. The tint is meant to stay; only the glyph is not.
                if (m.groupValues[3].isNotEmpty()) continue
                val parameterIsText = m.groupValues[1].lowercase() in textParameters
                val insideText = texts.any { m.range.first in it }
                // A bare `color =` outside any `Text(` call is a component
                // parameter (`ConnectionDetailItem(color = …)` is an icon tint).
                // Requiring both is what makes this rule precise instead of blunt.
                if (!parameterIsText && !insideText) continue
                val line = scannable.take(m.range.first).count { it == '\n' } + 1
                // A hue that is **already recorded as debt at this exact site** is
                // not a regression creeping back; it is a known, quantified
                // failure with a register row, and `recorded debt is still
                // failing` is what tracks it. Widening the sweep to `core/ui`
                // surfaced two of them (`ShadowMeshComponents.kt:1885` and
                // `:2026`) that no previous pass had ever looked at. Lifting
                // them needs a design decision on the replacement hue, and the
                // charter that owns the colour contract is not dispatchable, so
                // they are recorded rather than silently repainted.
                //
                // The exemption is keyed on file + line + literal, so a different
                // hue at that line, or the same hue at a different line, is still
                // reported. It is keyed on the *citation*, so editing above a
                // debt site makes this rule fire on it again — a false positive
                // that asks for the citation to be refreshed. For an audit, that
                // is the right direction to fail.
                if (recordedAt(m.groupValues[2].uppercase(), file, line)) continue
                offenders.add("$file:$line  ${m.groupValues[1]} = ${m.groupValues[2]}")
            }
        }

        assertEquals(
            "A hue lifted out of text is being painted as a glyph again. Lighten the foreground, " +
                "leave the chip tint alone:\n" + offenders.joinToString("\n") { "  - $it" },
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the banned-hue rule can tell a glyph from a chip tint`() {
        // Proves the rule discriminates, rather than being a blunt substring
        // search that happens to be green. Both fixtures are the exact shapes
        // that made the previous regex report two false positives.
        val assignment = Regex(
            """(?<![\w.])([\w.]+)\s*=\s*Color\((0x[0-9A-Fa-f]{8})\)(\.copy\()?""",
            RegexOption.IGNORE_CASE,
        )
        val banned = bannedHues.map { it.uppercase() }.toSet()

        fun flagged(source: String): List<String> {
            val scannable = blankNonCode(source)
            val texts = textCallRanges(scannable)
            return assignment.findAll(scannable)
                .filter { m ->
                    m.groupValues[2].uppercase() in banned &&
                        m.groupValues[3].isEmpty() &&
                        (m.groupValues[1].lowercase() in textParameters ||
                            texts.any { m.range.first in it })
                }
                .map { it.groupValues[2].uppercase() }
                .toList()
        }

        // A glyph: the LiveTunnelRecoveryScreen shape its own KDoc names by hex,
        // which the previous `color =` regex could not see.
        assertEquals(
            "`contentColor` with a banned hue must be flagged, and was not by the old regex",
            listOf("0XFFFF6B6B"),
            flagged("""ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF6B6B))"""),
        )
        // Case-insensitivity: the same literal written in lower case.
        assertEquals(
            "the banned-hue rule must be case-insensitive",
            listOf("0XFFFF6B6B"),
            flagged("""ButtonDefaults.outlinedButtonColors(contentColor = Color(0xffff6b6b))"""),
        )
        // A chip tint: the StatusScreen.kt:350 shape the old regex mis-flagged.
        assertEquals(
            "`Surface(color = Color(hue).copy(alpha = …))` is a container, not a glyph",
            emptyList<String>(),
            flagged("""Surface(color = Color(0xFF10B981).copy(alpha = 0.2f)) { Text("x") }"""),
        )
        // An icon tint: the ConnectionDetailItem shape.
        assertEquals(
            "a component `color =` parameter outside Text() is an accent",
            emptyList<String>(),
            flagged("""ConnectionDetailItem(label = "TRAFFIC", color = Color(0xFFA855F7))"""),
        )
        // …but the same hue as a glyph inside Text() is flagged.
        assertEquals(
            "the same hue inside a Text call is a glyph",
            listOf("0XFFA855F7"),
            flagged("""Text("x", color = Color(0xFFA855F7))"""),
        )
    }

    // -----------------------------------------------------------------------
    // Layer 4 — debt, recorded not hidden
    // -----------------------------------------------------------------------

    @Test
    fun `recorded debt is still failing`() {
        // The dashboard does this for NON_TEXT_TOKENS; same contract here. If a
        // debt row is ever lifted, this goes red and the row must be closed in
        // the register rather than silently expiring.
        val paid = debt.mapNotNull { row ->
            val actual = ratio(row.literal, row.background)
            if (actual >= aaa) "$row.literal on ${row.background} is now ${"%.2f".format(actual)}:1" else null
        }
        assertEquals(
            "A recorded debt row now passes AAA. Good — close its register row and delete it here.\n" +
                "DO NOT delete this assertion instead: a debt that stops being asserted stops being " +
                "tracked, which is the failure this file exists to prevent.\n" +
                paid.joinToString("\n") { "  - $it" },
            emptyList<String>(),
            paid,
        )
    }

    @Test
    fun `every debt row names the site that proves it paints text`() {
        // A debt row without evidence is an excuse. Each must point at a call
        // site, and that site must exist.
        val bad = debt.filter { !it.site.contains(Regex("""\.kt:\d+""")) }.map { it.literal }
        assertEquals("debt rows without a call-site citation: $bad", emptyList<String>(), bad)
    }

    // -----------------------------------------------------------------------
    // Layer 5 — completeness: every literal in the swept sources is classified
    // -----------------------------------------------------------------------

    @Test
    fun `every colour literal in the swept sources is classified`() {
        val classified = (textOnBackdrop.map { it.second } +
            accents.map { it.second } +
            backdrops.map { it.second } +
            themeOnly.map { it.second } +
            debt.map { it.literal } +
            namedPairs.map { it.second } +
            // The connected surface's three roles, plus every value measured by
            // the site-addressed and orb tables — otherwise a new token would be
            // reported unclassified while three tests hold measurements of it.
            listOf(secureFg, secureFill, secureHalo) +
            siteAddressedPairs.map { it.second } +
            orbTextPairs.map { it.second })
            .map { it.uppercase() }
            .toSet()

        val unclassified = mutableListOf<String>()
        for ((file, code) in kotlinSources()) {
            if (file.contains("NotesDecoyScreen")) continue
            for ((literal, line) in hexLiteralSites(code)) {
                if (literal in classified) continue
                unclassified.add("$file:$line: $literal")
            }
        }
        assertEquals(
            "These colour literals are in none of the tables above. An unclassified literal is an " +
                "unmeasured one — add it to `clearsAAA`, `accents`, `backdrops`, `themeOnly`, " +
                "`namedPairs` or `debt`, or delete it.\n" +
                unclassified.distinct().joinToString("\n") { "  - $it" },
            emptyList<String>(),
            unclassified.distinct(),
        )
    }

    @Test
    fun `the swept source set covers the shared kit and the theme`() {
        // The completeness test above is only as good as the roots it reads. If
        // `core/ui` stops resolving, that test silently shrinks to the app module
        // and reports green. So the coverage is asserted on its own.
        for ((tag, dir) in sweptRoots()) {
            assertTrue("$tag source root not found at ${dir.absolutePath}", dir.isDirectory)
        }
        val files = kotlinSources().map { it.first }
        assertTrue(
            "the shared component kit is not in the sweep",
            files.any { it == "core/ui/ShadowMeshComponents.kt" },
        )
        assertTrue(
            "the theme is not in the sweep",
            files.any { it == "core/ui/Color.kt" },
        )
    }

    @Test
    fun `the app canvas is declared in the theme, not only at its call sites`() {
        // `#0A0A0F` used to be typed into this test and repeated at ten call
        // sites. It now has a name in `Color.kt`, and this asserts the gate reads
        // it from there.
        val colours = themeColours()
        assertEquals("#0A0A0F", colours["AppCanvas"])
        assertEquals("#161821", colours["Surface"])
        assertEquals("#0A0B10", colours["Background"])
        assertEquals(3, surfaces.size)
    }

    // -----------------------------------------------------------------------
    // Layer 6 — negative controls: the gate can actually fail
    // -----------------------------------------------------------------------

    @Test
    fun `the saturated palette is reported as failing, which is why these steps exist`() {
        // Honest negative control. These are the pre-fix values. If a future
        // palette edit made them compliant this goes red — which is the signal
        // that the ramp can be collapsed.
        val offenders = listOf(
            "#F43F5E" to appCanvas,
            "#64748B" to composite("#FFFFFF", 0.03, appCanvas),
            "#A855F7" to composite("#A855F7", 0.15, appCanvas),
            "#10B981" to composite("#10B981", 0.20, appCanvas),
            "#EF4444" to composite("#EF4444", 0.10, appCanvas),
            "#FF6B6B" to themeSurface,
        ).mapNotNull { (fg, bg) ->
            val r = ratio(fg, bg)
            if (r < aaa) "$fg on $bg = ${"%.2f".format(r)}:1" else null
        }
        assertEquals(
            "These were measured as failing AAA. If they now pass, the palette changed and this " +
                "negative control should be revisited — but the gate must never be edited to make " +
                "this list empty.",
            6,
            offenders.size,
        )
        assertTrue(
            "The gate must be able to report a failure; got no offenders at all",
            offenders.isNotEmpty(),
        )
    }

    @Test
    fun `the luminance formula still produces the textbook values`() {
        // Proves the sweep can fail. If this drifts, every assertion above is
        // measuring the wrong thing and reporting a confident wrong number.
        assertEquals(21.0, ratio("#FFFFFF", "#000000"), 0.001)
        assertEquals(1.0, ratio("#FFFFFF", "#FFFFFF"), 0.001)
        // The figures quoted in the class KDoc, recomputed.
        assertEquals(5.38, ratio("#F43F5E", appCanvas), 0.01)
        assertEquals(3.96, ratio("#64748B", composite("#FFFFFF", 0.03, appCanvas)), 0.01)
        assertEquals(6.38, ratio("#FF6B6B", themeSurface), 0.01)
        // And the fixed values, so a bad "fix" cannot pass the pair test alone.
        assertEquals(10.45, ratio("#FDA4AF", appCanvas), 0.01)
        // The figures recorded for the newly-surfaced debt, so the debt table
        // cannot drift away from the palette it describes.
        assertEquals(5.25, ratio("#EF4444", appCanvas), 0.01)
        assertEquals(4.99, ratio("#A855F7", appCanvas), 0.01)
        assertEquals(6.93, ratio("#10B981", composite("#10B981", 0.10, appCanvas)), 0.01)
    }

    @Test
    fun `hex literals inside strings and comments are not treated as colours`() {
        // `ErrorScreens.kt` renders "Error Code: 0x8004100E" to the user. Without
        // the non-code blanking that becomes a colour named `#04100E` and has to
        // be excused in the tables forever.
        assertFalse(
            "a hex literal inside a string was classified as a colour",
            hexLiterals("""Text("Error Code: 0x8004100E")""").contains("#04100E"),
        )
        assertFalse(
            "a hex literal inside a comment was classified as a colour",
            hexLiterals("""// see also 0x8004100E""").contains("#04100E"),
        )
        assertEquals(
            "an apostrophe in a `//` comment must not swallow the rest of the file",
            listOf("#10B981"),
            hexLiterals("// don't leak\nText(\"x\", color = Color(0xFF10B981))"),
        )
        assertEquals(
            "a real literal must still be found",
            listOf("#10B981"),
            hexLiterals("""Text("x", color = Color(0xFF10B981))"""),
        )
    }
}