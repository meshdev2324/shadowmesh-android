package com.shadowmesh.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the recovery rules for network-handover drops. A regression here either
 * strands the user on a dead tunnel or burns battery in a reconnect storm, so
 * each bound is pinned deliberately.
 */
class HandoverRecoveryPolicyTest {

    @Test
    fun `backoff grows exponentially and is capped`() {
        assertEquals(15_000L, HandoverRecoveryPolicy.backoffMs(1))
        assertEquals(30_000L, HandoverRecoveryPolicy.backoffMs(2))
        assertEquals(60_000L, HandoverRecoveryPolicy.backoffMs(3))
        assertEquals(HandoverRecoveryPolicy.BACKOFF_MAX_MS, HandoverRecoveryPolicy.backoffMs(20))
    }

    @Test
    fun `backoff is monotonic and never negative for absurd attempt numbers`() {
        var previous = 0L
        for (attempt in 1..40) {
            val value = HandoverRecoveryPolicy.backoffMs(attempt)
            assertTrue("attempt $attempt must not decrease the delay", value >= previous)
            assertTrue(value > 0)
            previous = value
        }
        assertEquals(HandoverRecoveryPolicy.BACKOFF_BASE_MS, HandoverRecoveryPolicy.backoffMs(0))
        assertEquals(HandoverRecoveryPolicy.BACKOFF_BASE_MS, HandoverRecoveryPolicy.backoffMs(-5))
    }

    @Test
    fun `no recovery is attempted when no tunnel was connected`() {
        assertFalse(
            HandoverRecoveryPolicy.shouldAttempt(
                wasConnected = false,
                attemptsInWindow = 0,
                msSinceLastAttempt = Long.MAX_VALUE,
            )
        )
    }

    @Test
    fun `the first handover of a connected tunnel recovers immediately`() {
        assertTrue(
            HandoverRecoveryPolicy.shouldAttempt(
                wasConnected = true,
                attemptsInWindow = 0,
                msSinceLastAttempt = Long.MAX_VALUE,
            )
        )
    }

    @Test
    fun `burst handovers are throttled by the backoff`() {
        // A WiFi -> mobile switch fires several callbacks in a burst. The
        // second must not immediately trigger a second reconnect.
        assertFalse(
            HandoverRecoveryPolicy.shouldAttempt(
                wasConnected = true,
                attemptsInWindow = 1,
                msSinceLastAttempt = 500L,
            )
        )
    }

    @Test
    fun `recovery stops after the attempt budget is spent`() {
        assertFalse(
            "must not loop forever on a network that cannot come up",
            HandoverRecoveryPolicy.shouldAttempt(
                wasConnected = true,
                attemptsInWindow = HandoverRecoveryPolicy.MAX_ATTEMPTS,
                msSinceLastAttempt = Long.MAX_VALUE,
            )
        )
    }

    @Test
    fun `a long lived tunnel resets the attempt budget`() {
        assertFalse(
            HandoverRecoveryPolicy.shouldResetBudget(connectedForMs = 1_000L)
        )
        assertTrue(
            HandoverRecoveryPolicy.shouldResetBudget(
                HandoverRecoveryPolicy.STABILITY_RESET_MS
            )
        )
    }

    @Test
    fun `grace period gives the new network time to settle`() {
        assertTrue(
            "recovery must wait for the new network to be routable",
            HandoverRecoveryPolicy.GRACE_PERIOD_MS >= 3_000L
        )
    }
}
