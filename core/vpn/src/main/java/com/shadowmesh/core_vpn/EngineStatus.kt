package com.shadowmesh.core_vpn

/**
 * Truthful signal that the engine's outer transport is up.
 *
 * Why this exists: the connect health gate waits for
 * `stats.packetsReceived > 0`, which the engine increments only when a packet
 * arrives *from* the transport and is written into the TUN. With split
 * tunnelling in INCLUDE (per-app) mode the tunnel is restricted to the selected
 * apps, so an idle device produces no tunelled traffic at all and the gate can
 * never be satisfied — the app reported "could not connect" for a tunnel that
 * was fully established and handshaking at the node.
 *
 * `stats.lastHandshake` is not a substitute: the engine writes it on the same
 * receive path, so it is equally zero while per-app traffic is idle.
 *
 * `MeshVpnService` sets this immediately after `startVpnEngine` returns without
 * throwing, which is the point at which the REALITY/VLESS session is established
 * and the node's UDP relay is open. It is reset on teardown.
 */
object EngineStatus {

    @Volatile
    var transportEstablished: Boolean = false
        private set

    @Volatile
    var transportDetail: String = "none"
        private set

    fun markEstablished(detail: String) {
        transportDetail = detail
        transportEstablished = true
    }

    fun markStopped() {
        transportEstablished = false
        transportDetail = "stopped"
    }

    fun reset() {
        transportEstablished = false
        transportDetail = "none"
    }
}
