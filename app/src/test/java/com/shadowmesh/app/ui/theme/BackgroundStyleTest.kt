package com.shadowmesh.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A background style that can be selected but not drawn renders as a blank
 * canvas. That happened: Settings offered six styles and the renderer
 * implemented four, so two of them silently did nothing. These tests make that
 * class of defect impossible to reintroduce.
 */
class BackgroundStyleTest {

    @Test
    fun `every style offered in settings is drawable`() {
        for (name in BackgroundStyle.DISPLAY_NAMES) {
            val resolved = BackgroundStyle.fromName(name)
            assertTrue(
                "'$name' is offered in Settings but resolves to the fallback, " +
                    "so selecting it would render nothing",
                resolved.displayName == name,
            )
        }
    }

    @Test
    fun `the style count matches what settings offers`() {
        assertEquals(
            BackgroundStyle.entries.size,
            BackgroundStyle.DISPLAY_NAMES.size,
        )
    }

    @Test
    fun `quantum grid and neon midnight are drawable`() {
        // These two were the missing branches.
        assertEquals(BackgroundStyle.QUANTUM_GRID, BackgroundStyle.fromName("Quantum Grid"))
        assertEquals(BackgroundStyle.NEON_MIDNIGHT, BackgroundStyle.fromName("Neon Midnight"))
    }

    @Test
    fun `an unknown or stale value degrades to the default`() {
        assertEquals(BackgroundStyle.DEFAULT, BackgroundStyle.fromName("Nebula Supreme"))
        assertEquals(BackgroundStyle.DEFAULT, BackgroundStyle.fromName(""))
        assertEquals(BackgroundStyle.DEFAULT, BackgroundStyle.fromName(null))
    }

    @Test
    fun `resolution tolerates casing and repeated whitespace`() {
        assertEquals(BackgroundStyle.QUANTUM_GRID, BackgroundStyle.fromName("  quantum   grid "))
        assertEquals(BackgroundStyle.NEON_MIDNIGHT, BackgroundStyle.fromName("NEON MIDNIGHT"))
    }

    @Test
    fun `display names are unique`() {
        val names = BackgroundStyle.entries.map { it.displayName }
        assertEquals(names.size, names.toSet().size)
    }
}
