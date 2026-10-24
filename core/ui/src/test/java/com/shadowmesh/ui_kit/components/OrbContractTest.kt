package com.shadowmesh.ui_kit.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The status orb's contract, asserted where CI can actually run it.
 *
 * ## Why this file exists alongside `ShadowMeshComponentsTest`
 *
 * `core/ui`'s **instrumented** source set (`src/androidTest`) does not compile,
 * and did not before this change either: `ShadowMeshComponentsTest`'s
 * `NodeSelectionSheet` cases still call a signature the component no longer has
 * (`nodes: List<VpnNode>` / `selectedNode: VpnNode?` against a component that
 * now takes `List<MeshNodeEntry>`, `isSovereign` and `shardId`).
 *
 * CI runs `:app:compileDebugKotlin`, `:app:lintDebug` and
 * `:app:testDebugUnitTest` — it never compiles `core/ui`'s androidTest source
 * set, so it has never run those tests and it did not catch the break. That is
 * the same lesson as `ShadowMeshNotificationManager`: a file that does not
 * compile is not evidence about the product, and nobody had run it.
 *
 * The node-selection drift is another component's contract and is **not** fixed
 * here — guessing at `MeshNodeEntry`'s shape would produce a test that compiles,
 * passes, and verifies nothing.
 *
 * So the orb's contract is asserted here at the source level, which CI runs
 * today. That is a weaker form of evidence than a rendered assertion and the
 * distinction is not blurred: this proves the readout is not in the component's
 * source, not that a device renders it absent. The rendered assertions live in
 * `ShadowMeshComponentsTest` and are correct, but unbuilt until that file
 * compiles.
 *
 * ## The defect being pinned
 *
 * The orb rendered `"${latency.toInt()}MS MESH"`. The value is the **mean of 5
 * sequential full HTTP round-trips to the control-plane API**
 * (`detector.rs:82-97` -> `api_client.rs:1366-1376`), frozen at `detect()`. Both
 * numbers are real and they measure different things; the bug was the noun.
 * "MESH" reads as tunnel RTT, and the core exposes no tunnel-RTT field, so there
 * was no honest value to substitute — only a removal.
 */
class OrbContractTest {

    private val orbSource: String by lazy {
        val f = File("src/main/java/com/shadowmesh/ui_kit/components/ShadowMeshComponents.kt")
        assertTrue("orb source not found at ${f.absolutePath}", f.isFile)
        f.readText()
    }

    /**
     * The orb's own source, bounded to its declaration.
     *
     * Bounded deliberately. An earlier version of this helper was
     * `substring(start)`, which runs from the orb to **end of file** — so every
     * assertion below was quietly measuring `ConnectionDetailItem` and whatever
     * else came after it. Three of these tests failed for that reason and the
     * failures pointed at code that was never involved, which is the worst
     * possible failure mode for a gate: confident, and about the wrong thing.
     */
    private fun liquidStatusOrbBody(): String {
        val start = orbSource.indexOf("fun LiquidStatusOrb(")
        assertTrue("LiquidStatusOrb not found", start >= 0)
        val next = orbSource.indexOf("\nfun ", start + 1)
        return orbSource.substring(start, if (next < 0) orbSource.length else next)
    }

    @Test
    fun `the orb takes no latency parameter`() {
        // Not merely "does not render it". The parameter is gone, so putting the
        // number back requires re-adding the whole data path to the composable
        // — not one line, and not by accident.
        val signature = Regex("""fun\s+LiquidStatusOrb\s*\(([^)]*)\)""", RegexOption.DOT_MATCHES_ALL)
            .find(orbSource)?.groupValues?.get(1).orEmpty()
        assertFalse(
            "LiquidStatusOrb still accepts a latency parameter; the readout is meant to be gone",
            signature.contains("latency"),
        )
    }

    @Test
    fun `the orb renders no millisecond readout`() {
        val body = liquidStatusOrbBody()
        // Matched on the *interpolated* form specifically. The shipped defect was
        // `"${latency.toInt()}MS MESH"`; a bare `MS MESH` search also matches the
        // KDoc three lines above this test that has to name the old string in
        // order to explain why it is gone, so it would have been failing on its
        // own documentation.
        assertFalse(
            "the orb builds a millisecond string again: the value is a control-plane API " +
                "round-trip, not tunnel RTT",
            Regex("""\$\{[^{}]*\}\s*MS\b""").containsMatchIn(body),
        )
        assertTrue(
            "the orb should still render the SYNCED state word — reduced motion and the " +
                "latency removal must not remove the state",
            body.contains("\"SYNCED\""),
        )
    }

    @Test
    fun `the orb keeps its status word`() {
        // L10 ruled this stays. "A VPN is active" is already public via the
        // Android-mandated foreground-service notification, so suppressing the
        // on-screen word would add nothing and risks a user believing they are
        // unprotected.
        val body = liquidStatusOrbBody()
        for (word in listOf("YOU ARE PROTECTED", "PROTECT", "SHIELD")) {
            assertTrue("the orb lost the status word \"$word\"", body.contains("\"$word\""))
        }
    }

    @Test
    fun `the orb has one merged peer-free accessibility node`() {
        val body = liquidStatusOrbBody()
        assertTrue(
            "the orb must merge its subtree into a single accessibility node; without this a " +
                "screen reader walks the fragments",
            body.contains("clearAndSetSemantics"),
        )
        assertFalse(
            "the orb's accessibility node must not interpolate a peer name or an address",
            Regex("""contentDescription\s*=\s*"[^"]*\$\w+""").containsMatchIn(body),
        )
        // The authored string carries whether, never where.
        assertTrue(
            "the orb should state whether traffic is routed",
            body.contains("Traffic is routed through the mesh."),
        )
        assertFalse(
            "the orb's accessibility node must not carry a latency figure",
            Regex("""contentDescription[^)]*MS""").containsMatchIn(body),
        )
    }

    @Test
    fun `the connect control carries an action glyph, not a state shield`() {
        // The control shows an action; the orb carries the state. The shield was
        // also white on `#10BE84` at 2.41:1, failing WCAG 1.4.11 outright.
        val start = orbSource.indexOf("fun SlideToConnect(")
        assertTrue("SlideToConnect not found", start >= 0)
        val next = orbSource.indexOf("\nfun ", start + 1)
        val body = orbSource.substring(start, if (next < 0) orbSource.length else next)
        assertFalse(
            "the connect handle still paints the shield state icon",
            body.contains("Icons.Default.Shield"),
        )
        assertTrue(
            "the handle should keep an action affordance",
            body.contains("Icons.AutoMirrored.Filled.ArrowForward"),
        )
    }
}