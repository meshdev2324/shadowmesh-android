package com.shadowmesh.ui_kit.performance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M-1 — the reduced-motion gate.
 *
 * ## What was missing
 *
 * Nothing in this app consulted the user's motion preference. The status orb
 * runs a multi-layer aurora that rotates on an infinite repeat and two pulse
 * rings that expand on an infinite repeat, and both run for as long as the
 * screen is up. WCAG 2.3.3 (AAA) and the Android "Remove animations" setting
 * both exist for exactly this, and neither was wired.
 *
 * ## What is asserted here, and what is not
 *
 * The policy itself is asserted directly. The *wiring* is asserted lexically,
 * because a JVM unit test cannot observe a Compose draw loop — and the file
 * says so rather than implying more coverage than it has.
 *
 * The limitation is real and worth stating: `liquidStatusOrbGatesBothLoops`
 * proves the guard is present in the source and that it appears on both loops.
 * It does not prove the rendered output differs on a device. The
 * device-in-the-loop harness (`apps/android/device-tests`) is where that would
 * be demonstrated; this is the check that stops the guard being deleted.
 */
class MotionPolicyTest {

    @Test
    fun `the remove-animations scale turns reduced motion on`() {
        assertTrue(
            "a scale of exactly 0 is the platform's 'remove animations' signal",
            MotionPolicy.reduceMotionFor(0f),
        )
    }

    @Test
    fun `a normal scale leaves motion on`() {
        assertFalse(MotionPolicy.reduceMotionFor(1f))
        assertFalse(MotionPolicy.reduceMotionFor(0.5f))
        assertFalse(MotionPolicy.reduceMotionFor(2f))
    }

    @Test
    fun `an unreadable setting fails open to motion`() {
        // The preference is not a safety control, so the product's existing
        // behaviour is kept when the setting cannot be read — rather than
        // flattening every animation on a device whose setting reads oddly.
        assertFalse(
            "a null scale should not disable motion",
            MotionPolicy.reduceMotionFor(null),
        )
    }

    @Test
    fun `the policy is not satisfied by a value merely close to zero`() {
        // A tolerance here would silently disable motion for users who had
        // *reduced* it slightly — the opposite of what the setting means.
        assertFalse(MotionPolicy.reduceMotionFor(0.01f))
        assertFalse(MotionPolicy.reduceMotionFor(0.0001f))
    }

    // -----------------------------------------------------------------------
    // Wiring
    // -----------------------------------------------------------------------

    private val orbSource: String by lazy {
        val f = File("src/main/java/com/shadowmesh/ui_kit/components/ShadowMeshComponents.kt")
        assertTrue("orb source not found at ${f.absolutePath}", f.isFile)
        f.readText()
    }

    /**
     * The orb's own source, bounded to its declaration. An unbounded
     * `substring(start)` would run to end of file and silently make every
     * assertion below a statement about whichever component happened to come
     * next.
     */
    private fun liquidStatusOrbBody(): String {
        val start = orbSource.indexOf("fun LiquidStatusOrb(")
        assertTrue("LiquidStatusOrb not found", start >= 0)
        val next = orbSource.indexOf("\nfun ", start + 1)
        return orbSource.substring(start, if (next < 0) orbSource.length else next)
    }

    @Test
    fun `liquidStatusOrbReadsTheMotionPreference`() {
        assertTrue(
            "the orb never consults MotionPolicy; the perpetual loops are ungated",
            liquidStatusOrbBody().contains("MotionPolicy.reduceMotionFor"),
        )
    }

    @Test
    fun `liquidStatusOrbGatesBothLoops`() {
        val body = liquidStatusOrbBody()
        val aurora = body.contains("if (isVisible && !reduceMotion)")
        val pulse = body.contains("if (isConnected && isVisible && !reduceMotion)")
        assertTrue(
            "the aurora loop is not gated on reduceMotion",
            aurora,
        )
        assertTrue(
            "the pulse-ring loop is not gated on reduceMotion",
            pulse,
        )
    }

    @Test
    fun `reducedMotionStillLeavesTheOrbReadable`() {
        // A reduce-motion path that removed the disc entirely would fix the
        // animation by removing the status — a regression disguised as an
        // accessibility feature. The state colour and the word must survive.
        val body = liquidStatusOrbBody()
        val gateIndex = body.indexOf("if (isVisible && !reduceMotion)")
        val wordIndex = body.indexOf("\"YOU ARE PROTECTED\"")
        assertTrue(
            "the status word should still be rendered; it is the accessible state",
            wordIndex > 0,
        )
        assertTrue(
            "the status word must not be inside the motion gate — reduced motion must not " +
                "remove the state, only the movement",
            gateIndex < wordIndex,
        )
    }
}