package com.shadowmesh.app.vpn

/**
 * Decision rules for recovering a tunnel after the underlying network changes.
 *
 * ## The defect this replaces
 *
 * `WireGuardService.refreshTunnel()` logged "Refreshing tunnel …" and then did
 * nothing, on the stated assumption that "roaming is handled internally by
 * integrated boringtun". Boringtun does learn a *peer's* new source address,
 * but it cannot repair a local interface change, and it has no way to re-open a
 * socket that the platform has torn down. The result was a tunnel that logged a
 * recovery it never performed, which is indistinguishable from a spontaneous
 * drop — exactly the intermittent disconnects users reported.
 *
 * ## Why this is guarded rather than a blind reconnect
 *
 * Re-establishing a tunnel is expensive (PoW-free, but still a handshake plus a
 * fresh TUN) and drains battery, which the Android workflow budgets for. Network
 * handovers also arrive in bursts — a WiFi→mobile switch fires several callbacks
 * — so an unguarded reconnect storm is a real risk. The rules below therefore
 * require all of:
 *
 *  1. a session that was actually connected,
 *  2. a grace period long enough for the new network to settle, and
 *  3. an attempt budget with exponential backoff, reset only after the tunnel has
 *     been stable for a while.
 *
 * Pure and side-effect free so the rules are unit-testable without a device.
 */
object HandoverRecoveryPolicy {

    /**
     * How long to wait after a handover before judging the tunnel.
     *
     * A handover is not instantaneous: DHCP, DNS and the default route are still
     * settling, and WireGuard needs a fresh handshake. Reconnecting before this
     * elapses usually reconnects onto a network that is not ready yet.
     */
    const val GRACE_PERIOD_MS = 8_000L

    /** Base for exponential backoff between recovery attempts. */
    const val BACKOFF_BASE_MS = 15_000L

    /** Ceiling on the backoff so recovery is still attempted within a session. */
    const val BACKOFF_MAX_MS = 120_000L

    /** Maximum recovery attempts inside one stability window. */
    const val MAX_ATTEMPTS = 3

    /** A tunnel alive this long resets the attempt budget. */
    const val STABILITY_RESET_MS = 5 * 60_000L

    /** Backoff before attempt number [attempt] (1-based). */
    fun backoffMs(attempt: Int): Long {
        if (attempt <= 1) return BACKOFF_BASE_MS
        val scaled = BACKOFF_BASE_MS shl (attempt - 1).coerceAtMost(16)
        return scaled.coerceAtMost(BACKOFF_MAX_MS)
    }

    /**
     * Whether a recovery attempt should be started right now.
     *
     * @param wasConnected whether a tunnel was established when the handover hit
     * @param attemptsInWindow recovery attempts already made in this window
     * @param msSinceLastAttempt time since the previous attempt, or [Long.MAX_VALUE]
     *   when none has been made
     */
    fun shouldAttempt(
        wasConnected: Boolean,
        attemptsInWindow: Int,
        msSinceLastAttempt: Long,
    ): Boolean {
        if (!wasConnected) return false
        if (attemptsInWindow >= MAX_ATTEMPTS) return false
        return msSinceLastAttempt >= backoffMs(attemptsInWindow + 1)
    }

    /** True when the attempt budget should be cleared because the tunnel is healthy. */
    fun shouldResetBudget(connectedForMs: Long): Boolean = connectedForMs >= STABILITY_RESET_MS
}
