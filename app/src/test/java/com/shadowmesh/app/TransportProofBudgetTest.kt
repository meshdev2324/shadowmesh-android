package com.shadowmesh.app

import com.shadowmesh.core_vpn.TransportProof
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the rule that closed the ~25s DNS blackhole.
 *
 * The assertion here is deliberately about *timers and proof*, not about UI. A
 * test that checks "an error was shown" proves nothing: the defect was that the
 * connect reported itself fine while the device was left holding a live
 * `0.0.0.0/0` route and a resolver at `10.0.0.53` with nothing behind them.
 * What matters is that the interface comes back within a stated, bounded time.
 *
 * The failure being pinned, measured on a CPH2363 (Android 14):
 *
 *     T+37s   tun0=UP   ping: unknown host example.com
 *
 * A handshake that completed but carried no traffic left the phone with no DNS
 * for ~25 seconds and no error anywhere for the user to react to.
 */
class TransportProofBudgetTest {

    // --- What counts as proof -------------------------------------------------

    @Test
    fun `a handshake that completed but carried nothing is not proof`() {
        // This is the whole fix. Returning from startVpnEngine is not a signal
        // that the tunnel works; a zero receive counter means nothing arrived
        // from the transport, so the interface must not be treated as good.
        assertFalse(
            "a silent handshake was accepted as a working tunnel",
            TransportProof.isProof(bytesReceived = 0uL, perAppRouting = false),
        )
    }

    @Test
    fun `a byte arriving from the transport is proof`() {
        assertTrue(TransportProof.isProof(bytesReceived = 1uL, perAppRouting = false))
    }

    @Test
    fun `per-app routing accepts an idle handshake because a zero is uninformative`() {
        // Under split-tunnelling INCLUDE an idle device legitimately produces no
        // tunnelled traffic, so a zero counter cannot be distinguished from a
        // dead tunnel and must not be read as one.
        assertTrue(TransportProof.isProof(bytesReceived = 0uL, perAppRouting = true))
    }

    @Test
    fun `per-app routing still accepts real traffic`() {
        assertTrue(TransportProof.isProof(bytesReceived = 4096uL, perAppRouting = true))
    }

    // --- The budget, which is the part that was actually broken ----------------

    @Test
    fun `the proof gate resolves well inside the 25s window it replaces`() {
        // 25_000 was the connect ladder's own traffic-probe budget, and it is the
        // number the device was measured blackholing for. The interface has to be
        // handed back before it, with room for one polling interval of overshoot.
        val worstCase = TransportProof.PROOF_TIMEOUT_MS + TransportProof.PROOF_POLL_MS
        assertTrue(
            "worst-case DNS outage of ${worstCase}ms is not tighter than the 25000ms it replaced",
            worstCase < 25_000L,
        )
    }

    @Test
    fun `the proof gate governs before the handshake watchdog backstop`() {
        // The watchdog only declines to act once the transport is confirmed. If
        // the proof budget outlived it, the interface would sit unconfirmed and
        // untorn-down with no authority willing to close it.
        assertTrue(
            "the proof budget outlives the watchdog that bounds it",
            TransportProof.PROOF_TIMEOUT_MS < MeshVpnService.HANDSHAKE_TIMEOUT_MS,
        )
    }

    @Test
    fun `polling is fine grained enough to notice proof promptly`() {
        // A poll interval anywhere near the budget would turn "give up at 8s"
        // into "notice success at up to 8s late", reinstating most of the window.
        assertTrue(
            "one poll interval costs too much of the budget",
            TransportProof.PROOF_POLL_MS * 4 < TransportProof.PROOF_TIMEOUT_MS,
        )
        assertEquals(400L, TransportProof.PROOF_POLL_MS)
    }

    @Test
    fun `the budget is long enough not to fail a genuinely working tunnel`() {
        // The failure mode of setting this too tight is worse than the defect: a
        // connect that works but is slow would be torn down and reported as
        // failed. A working tunnel shows traffic in about a second on real
        // hardware, so 8s is a wide margin — this asserts it never regresses
        // into something that would reject slow-but-real connects.
        assertTrue(
            "budget too tight to admit a slow but working tunnel",
            TransportProof.PROOF_TIMEOUT_MS >= 5_000L,
        )
    }
}