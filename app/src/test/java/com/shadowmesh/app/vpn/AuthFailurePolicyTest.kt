package com.shadowmesh.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app should do when the control plane refuses a request.
 *
 * Regression cover for a P0 that occurred twice in this project: a paying user
 * with a valid, unexpired activation code was dropped to the login screen and
 * the stored code was deleted, so the only way back was to retype a code the
 * server was still willing to accept.
 *
 * The invariant, in one sentence: **a refused request is not proof that a
 * subscription ended.** The control plane remains the authority on whether a
 * session is revoked, and the client must ask it rather than infer.
 */
class AuthFailurePolicyTest {

    @Test
    fun `an undeterminable status must not destroy the activation`() {
        // If the status check itself fails, that is not evidence of revocation.
        val decision = AuthFailurePolicy.decide(controlPlaneSaysActive = null)
        assertTrue(decision.keepActivation)
        assertEquals(AuthFailureAction.RETRY, decision.action)
    }

    @Test
    fun `a still-valid activation survives a refused request`() {
        val decision = AuthFailurePolicy.decide(controlPlaneSaysActive = true)
        assertTrue(decision.keepActivation)
        assertEquals(
            "the correct response is to reconnect, not to sign out",
            AuthFailureAction.RETRY,
            decision.action,
        )
    }

    @Test
    fun `a confirmed revocation clears the activation`() {
        val decision = AuthFailurePolicy.decide(controlPlaneSaysActive = false)
        assertTrue(!decision.keepActivation)
        assertEquals(AuthFailureAction.SIGN_IN_AGAIN, decision.action)
    }

    @Test
    fun `a transient refusal never escalates to a sign-out`() {
        // The whole point of the policy: one 401 is a fact about a request, not
        // about the subscription.
        repeat(10) { attempt ->
            val decision = AuthFailurePolicy.decide(controlPlaneSaysActive = true)
            assertEquals(
                "attempt $attempt escalated",
                AuthFailureAction.RETRY,
                decision.action,
            )
        }
    }

    @Test
    fun `the tunnel is always torn down before any state change`() {
        // Whatever the outcome, an unverified session must stop forwarding.
        for (active in listOf(true, false, null)) {
            val decision = AuthFailurePolicy.decide(controlPlaneSaysActive = active)
            assertTrue(decision.disconnectFirst)
        }
    }

    @Test
    fun `the message never claims the code expired`() {
        // The user reported the code as unexpired and was shown a login screen
        // anyway. The copy must not assert an expiry that was never reported.
        for (active in listOf(true, false, null)) {
            val decision = AuthFailurePolicy.decide(controlPlaneSaysActive = active)
            assertTrue(!decision.message.contains("expired", ignoreCase = true))
        }
    }

    @Test
    fun `a retryable failure tells the user the app is working on it`() {
        val decision = AuthFailurePolicy.decide(controlPlaneSaysActive = true)
        assertTrue(
            decision.message.contains("Reconnect", ignoreCase = true) ||
                decision.message.contains("interrupted", ignoreCase = true)
        )
    }
}
