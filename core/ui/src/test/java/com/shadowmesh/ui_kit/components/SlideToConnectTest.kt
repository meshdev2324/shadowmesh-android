package com.shadowmesh.ui_kit.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideToConnectTest {
    @Test
    fun `connect completes at the outer endpoint`() {
        assertTrue(slideCompletionReached(98f, 100f, false))
        assertFalse(slideCompletionReached(97f, 100f, false))
    }

    @Test
    fun `disconnect completes at the start endpoint`() {
        assertTrue(slideCompletionReached(2f, 100f, true))
        assertFalse(slideCompletionReached(3f, 100f, true))
    }

    @Test
    fun `zero width never completes`() {
        assertFalse(slideCompletionReached(0f, 0f, false))
    }
}
