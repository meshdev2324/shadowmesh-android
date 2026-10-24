package com.shadowmesh.core_vpn

/**
 * What counts as proof that the tunnel actually carries traffic.
 *
 * ## Why returning is not proof
 *
 * `startVpnEngine` returns as soon as the REALITY/VLESS handshake resolves and
 * the node's UDP relay opens. That says the *outer* transport came up. It does
 * not say the tunnel works: the node can accept the handshake and then relay
 * nothing, and on a filtered network it frequently does exactly that.
 *
 * Treating the return as confirmation is what produced a ~25 second window in
 * which the device held a live `0.0.0.0/0` route and a resolver at
 * `10.0.0.53` with nothing behind them — measured on a CPH2363 (Android 14):
 * `tun0` up, `ping example.com` returning `unknown host`, `curl` returning
 * `000`. On a censored network that is the worst failure to get right, because
 * the user gets no error to react to; the phone simply stops resolving.
 *
 * So confirmation is gated on the engine's own receive counter instead. Bytes
 * arriving *from* the transport and written into the TUN are the only signal
 * that distinguishes "the handshake completed" from "the tunnel works", and
 * they cost nothing to read.
 *
 * ## The per-app exception is a real limitation, not a convenience
 *
 * Under split tunnelling in INCLUDE mode the tunnel is restricted to the
 * selected apps, so an idle device legitimately produces no tunnelled traffic
 * at all and the counter stays at zero indefinitely. There is no other signal
 * available for that case — `lastHandshake` is written on the same receive
 * path and is equally zero. So in per-app mode the handshake return is
 * accepted as proof, and that acceptance is bounded by the caller's proof
 * budget rather than being open-ended.
 */
object TransportProof {

    /**
     * @param bytesReceived the engine's receive counter, which only advances
     *   when a packet arrives from the transport and is written into the TUN.
     * @param perAppRouting true under split-tunnelling INCLUDE with a non-empty
     *   app list, where an idle tunnel cannot be distinguished from a dead one.
     * @return whether this observation proves the tunnel works.
     */
    fun isProof(bytesReceived: ULong, perAppRouting: Boolean): Boolean {
        // No per-app routing: any byte from the transport is real proof.
        if (bytesReceived > 0uL) return true
        // Per-app routing: an idle tunnel never moves the counter, so a zero
        // here is uninformative rather than damning. Accept the handshake.
        return perAppRouting
    }

    /**
     * How long to wait for that proof before giving the interface back.
     *
     * Deliberately shorter than the 25s the connect ladder used to spend in its
     * own traffic probe, and shorter than the handshake watchdog that bounds a
     * hung engine. A working tunnel shows traffic in about a second on a real
     * device, so 8s is generous by a wide margin; the cost of being wrong in
     * the cautious direction is a slow connect, and the cost of being wrong in
     * the other direction is a user with no DNS and no error message.
     */
    const val PROOF_TIMEOUT_MS = 8_000L

    /** How often to read the counter while waiting. */
    const val PROOF_POLL_MS = 400L
}