package com.shadowmesh.app.security

import uniffi.shadowmesh.ConnectionStatus

/**
 * Which top-level surface the app should present, given the pair
 * (isActivated, status).
 *
 * The bug this exists to prevent: the root screen used to gate purely on
 * `!isActivated`, so a client that dropped its activation state while the
 * service was still holding a live tunnel rendered the Login screen. The user
 * was asked for their access code while already paying for, and already
 * routing through, a working tunnel. That is both a lockout and a lie about
 * the device's actual state.
 *
 * Routing traffic and holding a session are two different things. Losing the
 * local activation record does not stop the kernel forwarding packets, so the
 * UI has to acknowledge the tunnel rather than pretend it isn't there.
 */
enum class SessionGate {
    /** Activation present: render the main surface. */
    MAIN,

    /** No activation and no live tunnel: the user genuinely needs to sign in. */
    LOGIN,

    /**
     * No activation but a tunnel is still up (or coming up). Do not ask for a
     * code and do not silently keep forwarding. Hand back to the user with a
     * choice, because the only safe outcomes are "prove who you are again" or
     * "tear the tunnel down".
     */
    RECOVER_LIVE_TUNNEL,
}

/**
 * Connection states in which packets are or may be flowing. A tunnel in any of
 * these is not something the login screen gets to hide.
 */
private val LIVE_STATUSES: Set<ConnectionStatus> = setOf(
    ConnectionStatus.CONNECTED,
    ConnectionStatus.PAUSED,
    ConnectionStatus.DEGRADED,
    ConnectionStatus.CONNECTING_DIRECT,
    ConnectionStatus.CONNECTING_FRAGMENTED,
    ConnectionStatus.CONNECTING_REALITY,
    ConnectionStatus.CONNECTING_WEB_SOCKET,
    ConnectionStatus.CONNECTING_SHADOWSOCKS,
    ConnectionStatus.CONNECTING_HYSTERIA,
    ConnectionStatus.CONNECTING_VMESS,
    ConnectionStatus.DISCONNECTING,
)

internal fun isTunnelLive(status: ConnectionStatus): Boolean = status in LIVE_STATUSES

/**
 * Resolve the root surface. Exhaustive over [status] so that a status added to
 * the core enum later fails to compile here instead of silently falling into
 * the login branch.
 */
fun resolveSessionGate(isActivated: Boolean, status: ConnectionStatus): SessionGate = when {
    isActivated -> SessionGate.MAIN
    isTunnelLive(status) -> SessionGate.RECOVER_LIVE_TUNNEL
    else -> SessionGate.LOGIN
}
