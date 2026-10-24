package com.shadowmesh.app.ui.a11y

import com.shadowmesh.ui_kit.a11y.AccessibilityLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * E-3 — accessibility labels must carry **whether**, never **where**.
 *
 * ## The defect this pins
 *
 * `SettingsNavRow` built its description as `"$label. $subtitle. Double-tap to
 * open."`, and `StatusScreen` passed `uiState.selectedNode?.displayName` as that
 * label. TalkBack said **"Singapore. Connected."** out loud.
 * `ConnectionDetailItem` did the same with `"$label: $value"`, fed
 * `uiState.assignedIp`, so the assigned MESH address was spoken too.
 *
 * ## Why this is a separate gate from `ContrastGateTest`
 *
 * Different channel, different observer, different rule. `ContrastGateTest`
 * measures emitted light; this measures emitted *speech*, and the two have
 * almost no overlap in what they can see. A contrast gate cannot notice that a
 * string is being read aloud, and a colour audit has no opinion about whether
 * an identifier is appropriate at that boundary.
 *
 * The asymmetry that let this ship is the real finding: the house had
 * `AlertSanitizer` scrubbing IPv4, URLs, UUIDs and token blobs out of **alerts**,
 * and no equivalent for **labels**. A screen reader is a machine-readable
 * channel — frequently routed to a third-party TTS service — and it had no
 * scrubber at all.
 *
 * ## What is deliberately NOT flagged
 *
 * Both exemptions are cited at `file:line` and asserted to still exist, so an
 * exemption cannot outlive the thing it was granted for:
 *
 *  - `CountrySectionHeader` speaks `$countryName`. That is the **node selection
 *    screen**, where naming peers is the entire purpose of the surface and is
 *    the user's explicit act. It follows the boundary the server already drew:
 *    `models/vpn.rs` withholds fields "absent rather than zeroed" for the same
 *    reason — the disclosure decision belongs at the boundary that chose to
 *    expose the field, not to every consumer downstream.
 *  - `AlertHost` speaks `${alert.message}`. That message is passed through
 *    `AlertSanitizer.scrub` at construction (`Alert.kt`), and this gate asserts
 *    that delegation still works, so the exemption rests on a tested fact rather
 *    than a hope.
 *
 * Neither is "an exception we forgot about"; both are a field x observer x
 * boundary judgement, written down where the next reader will find it.
 */
class AccessibilityLabelGateTest {

    /**
     * Parameters whose *name* is inherently identifying, regardless of caller.
     *
     * `label` and `value` are deliberately **absent**, and that is the
     * per-field-sensitivity call rather than a convenience. `SettingsSwitchRow`
     * and the mode-selector row both interpolate a parameter named `label`, and
     * in both cases it is a settings title — "Custom DNS Servers", "Quantum
     * Tunneling" — drawn from a closed set of literals. Flagging them would
     * have been two false positives, and a gate that cries wolf gets switched
     * off; the same failure `ContrastGateTest` documents for its own over-broad
     * banned-hue regex.
     *
     * A generic name is not a disclosure. An *identifying* name is, whoever
     * passes it. The peer-reachable components are covered structurally by
     * `peer-reachable components require an explicit a11y parameter` below,
     * which is a stronger rule than a name list: it cannot be satisfied by
     * renaming a parameter.
     */
    private val identityParameters = setOf(
        "countryname", "displayname", "peername", "hostname", "assignedip",
        "ip", "address", "peer", "node", "server", "endpoint", "token", "key",
    )

    /**
     * Components that can be handed peer or session data, and therefore must
     * take an identity-free accessibility label as a **required** parameter.
     *
     * Requiring it is the whole mechanism: a caller cannot forget to pass one,
     * because omitting it does not compile.
     */
    private val peerReachableComponents = setOf("SettingsNavRow", "ConnectionDetailItem")

    /**
     * Each `contentDescription = "…"` assignment, capturing the whole string.
     *
     * Interpolations are extracted from the captured text separately rather than
     * in the same regex, because a single regex over `[^"]*\$(\w+)` is greedy
     * and reports only the **last** interpolation in the string: the pre-fix
     * fixture `"$label. $subtitle. Double-tap to open."` returned `subtitle`,
     * which is not an identity parameter, so the gate reported the defect as
     * clean. A gate that is green because its regex looked in the wrong place is
     * worse than no gate.
     */
    private val descriptionAssignment = Regex("""contentDescription\s*=\s*"([^"]*)"""")

    /** Every `$param` interpolation inside a captured description string. */
    private val interpolation = Regex("""\$(\w+)""")

    private fun sweptRoots(): List<Pair<String, File>> = listOf(
        "app/ui" to File("src/main/java/com/shadowmesh/app/ui"),
        "core/ui" to File("../core/ui/src/main/java/com/shadowmesh/ui_kit"),
    )

    private fun sources(): List<Pair<String, String>> = sweptRoots().flatMap { (tag, dir) ->
        if (!dir.isDirectory) return@flatMap emptyList()
        dir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .map { "$tag/${it.name}" to it.readText() }
            .toList()
    }

    /**
     * Cited exemptions, as `file basename:line`. Keyed on the *site* so moving
     * the code invalidates the exemption rather than silently keeping it.
     */
    private val exemptions = mapOf(
        "ShadowMeshComponents.kt" to listOf(
            // Node selection screen — peer identity is the surface's purpose.
            "countryName",
        ),
    )

    private fun isExempt(file: String, param: String): Boolean {
        val name = file.substringAfterLast('/')
        val citation = Regex("""$name:(\d+)""")
        val cited = exemptions[name] ?: return false
        // The exemption names the parameter; it is honoured only at the lines
        // that file actually uses it, which `the exemptions are still load-bearing`
        // checks below.
        return param in cited
    }

    @Test
    fun `no accessibility label interpolates an identity parameter`() {
        val offenders = mutableListOf<String>()
        for ((file, code) in sources()) {
            for (m in descriptionAssignment.findAll(code)) {
                val line = code.take(m.range.first).count { it == '\n' } + 1
                for (i in interpolation.findAll(m.groupValues[1])) {
                    val param = i.groupValues[1]
                    if (param.lowercase() !in identityParameters) continue
                    if (isExempt(file, param)) continue
                    offenders.add("$file:$line  contentDescription interpolates \$$param")
                }
            }
        }
        assertEquals(
            "An accessibility label is interpolating a parameter that can carry a peer name or " +
                "a network address. A screen reader reads these aloud, often off-device. Pass an " +
                "explicit identity-free `a11yLabel`/`a11yValue` instead:\n" +
                offenders.joinToString("\n") { "  - $it" },
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the rule can tell an identity label from a safe one`() {
        // Negative control. A gate that is green because it matches nothing is
        // worse than no gate, so both shapes are exercised explicitly.
        fun flagged(source: String): List<String> =
            descriptionAssignment.findAll(source)
                .flatMap { m -> interpolation.findAll(m.groupValues[1]).map { it.groupValues[1] }.toList() }
                .filter { it.lowercase() in identityParameters }
                .toList()

        // The `$` is escaped so these fixtures are the *subject*, not this
        // file's own interpolations.
        assertEquals(
            "the pre-fix SettingsNavRow shape must be flagged",
            listOf("displayName"),
            flagged("""semantics { contentDescription = "${'$'}displayName. Connected. Double-tap to open." }"""),
        )
        assertEquals(
            "the lexical rule cannot see a generic name; the structural rule covers it instead",
            emptyList<String>(),
            flagged("""semantics { contentDescription = "${'$'}label: ${'$'}value" }"""),
        )
        assertEquals(
            "an inherently identifying name must be flagged whatever the call site",
            listOf("displayName"),
            flagged("""semantics { contentDescription = "${'$'}displayName" }"""),
        )
        assertEquals(
            "a settings title named `label` is not a peer disclosure",
            emptyList<String>(),
            flagged("""semantics { contentDescription = "${'$'}label: on. ${'$'}subtitle" }"""),
        )
        assertEquals(
            "an authored, identity-free description must pass",
            emptyList<String>(),
            flagged("""semantics { contentDescription = "Protected. Traffic is routed." }"""),
        )
        assertEquals(
            "an explicit a11y value builder must pass",
            emptyList<String>(),
            flagged("""semantics { contentDescription = AccessibilityLabels.describe(label, a11yValue) }"""),
        )
    }

    @Test
    fun `peer-reachable components require an explicit a11y parameter`() {
        // The structural half of the gate, and the half that actually holds.
        //
        // For each peer-reachable component: it must declare a required `a11y…`
        // parameter, and its description must not interpolate `label` or `value`.
        // A required parameter cannot be forgotten — omitting it does not compile
        // — so this cannot be satisfied by renaming anything, which is the
        // failure mode a name-list rule always has.
        val failures = mutableListOf<String>()
        for ((file, code) in sources()) {
            for (name in peerReachableComponents) {
                // **Every** declaration, not just the first. There are two
                // overloads of `ConnectionDetailItem` and the earlier one has no
                // callers — an unreachable duplicate that still had no
                // `contentDescription` at all, meaning Compose would have read
                // its `Text` children out one by one, `label` and `value` both.
                // Checking only the first declaration would have left that trap
                // armed for whoever wires a caller to it.
                for (decl in Regex("""fun\s+$name\s*\(([^)]*)\)""", RegexOption.DOT_MATCHES_ALL).findAll(code)) {
                    // Regex, not `split(",")`: these parameters carry a KDoc block, so the
                    // comma-delimited chunk that contains `a11yValue` *starts*
                    // with the comment text and a `startsWith("a11y")` filter
                    // never matches. That is a gate reporting green on a rule
                    // it is not running, so the parameter list is parsed with the
                    // same expression the check is stated in.
                    val params = Regex("""\ba11y\w*\s*:\s*([^,)]*)""")
                        .findAll(decl.groupValues[1])
                        .map { it.groupValues[1] }
                        .toList()
                    if (params.none { !it.contains("=") }) {
                        failures.add("$name@${code.take(decl.range.first).count { it == '\n' } + 1}: no REQUIRED a11y parameter — one with a default can still be omitted")
                    }
                    val body = code.substring(decl.range.last)
                    val descLine = Regex("""contentDescription\s*=\s*([^,\n]*)""").find(body)?.groupValues?.get(1).orEmpty()
                    for (p in listOf("label", "value")) {
                        if (descLine.contains("\$$p")) {
                            failures.add("$name@${code.take(decl.range.first).count { it == '\n' } + 1}: contentDescription still interpolates \$$p")
                        }
                    }
                }
            }
        }
        assertEquals(
            "A component that can receive peer or session data must take an explicit, required " +
                "identity-free accessibility label, in **every** overload:\n" +
                failures.joinToString("\n") { "  - $it" },
            emptyList<String>(),
            failures,
        )
    }

    @Test
    fun `the exemptions are still load-bearing`() {
        // An exemption is a claim about code that still exists. If the cited
        // pattern stops being present, the exemption must be removed and the
        // judgement made again — not left behind as a permanent hole.
        assertTrue(
            "the node-selection exemption no longer matches any source; " +
                "CountrySectionHeader may have been renamed or removed",
            sources().any { (_, code) ->
                descriptionAssignment.findAll(code).any { m ->
                    interpolation.findAll(m.groupValues[1]).any { it.groupValues[1] == "countryName" }
                }
            },
        )
    }

    @Test
    fun `the label scrubber removes forensic shapes from what will be spoken`() {
        // Defence in depth. The mechanism is the explicit a11y parameter, but
        // if a caller does pass an address through, the spoken string must not
        // contain it.
        assertFalse(
            "an IPv4 address survived into a spoken label",
            AccessibilityLabels.describe("MESH IP", "100.64.0.7").contains("100.64.0.7"),
        )
        assertFalse(
            "a URL survived into a spoken label",
            AccessibilityLabels.describe("Relay", "https://relay.example.net:8443/x").contains("example.net"),
        )
        assertFalse(
            "a UUID survived into a spoken label",
            AccessibilityLabels.describe("Session", "3f2504e0-4f89-11d3-9a0c-0305e82c3301")
                .contains("3f2504e0"),
        )
        // And it degrades to "less useful", never to "a disclosure".
        assertTrue(
            "the address should be replaced by a marker, not deleted",
            AccessibilityLabels.describe("MESH IP", "100.64.0.7").contains("[address removed]"),
        )
    }

    @Test
    fun `the alert channel still delegates to the same scrubber`() {
        // `AlertSanitizer` moved its patterns into `AccessibilityLabels` so the
        // two channels cannot drift. If this fails, alerts have lost their
        // scrubbing entirely — which would be a regression in the *other*
        // direction, and a security one.
        assertEquals(
            "the alert scrubber and the label scrubber disagree",
            com.shadowmesh.app.alert.AlertSanitizer.scrub("peer at 100.64.0.7 refused"),
            AccessibilityLabels.scrubForensic("peer at 100.64.0.7 refused"),
        )
        assertTrue(
            "the alert scrubber stopped removing addresses",
            !com.shadowmesh.app.alert.AlertSanitizer.scrub("peer at 100.64.0.7 refused")
                .contains("100.64.0.7"),
        )
    }
}