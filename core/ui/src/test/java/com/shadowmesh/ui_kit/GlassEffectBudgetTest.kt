package com.shadowmesh.ui_kit

import com.shadowmesh.ui_kit.components.glassAllocatesRenderLayer
import com.shadowmesh.ui_kit.performance.PerformanceProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the effect budget that Minimalist depends on.
 *
 * `shadowMeshGlass` used to accept a [PerformanceProfile] and ignore it, so the
 * `forcesLowProfile` pin looked correctly wired while every call site carried
 * on allocating an offscreen layer. The signature never changed, so nothing
 * caught it; only asserting on the decision does.
 */
class GlassEffectBudgetTest {

    @Test
    fun `low profile must not allocate a render layer`() {
        assertFalse(
            "LOW must skip the shadow so Minimalist actually costs less",
            glassAllocatesRenderLayer(PerformanceProfile.LOW),
        )
    }

    @Test
    fun `high profile keeps the glass render layer`() {
        assertTrue(glassAllocatesRenderLayer(PerformanceProfile.HIGH))
    }

    @Test
    fun `medium profile keeps the glass render layer`() {
        assertTrue(glassAllocatesRenderLayer(PerformanceProfile.MEDIUM))
    }
}
