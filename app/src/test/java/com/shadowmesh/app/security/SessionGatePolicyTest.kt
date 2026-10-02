package com.shadowmesh.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import uniffi.shadowmesh.ConnectionStatus

/**
 * Regression cover for the lockout: a live tunnel plus a missing activation
 * record must never resolve to LOGIN.
 */
class SessionGatePolicyTest {

    private companion object {
        val INERT_STATUSES = setOf(ConnectionStatus.DISCONNECTED, ConnectionStatus.ERROR)
    }

    @Test
    fun `activated shows the main surface`() {
        assertEquals(
            SessionGate.MAIN,
            resolveSessionGate(isActivated = true, status = ConnectionStatus.DISCONNECTED),
        )
    }

    @Test
    fun `activated while connected shows the main surface`() {
        assertEquals(
            SessionGate.MAIN,
            resolveSessionGate(isActivated = true, status = ConnectionStatus.CONNECTED),
        )
    }

    @Test
    fun `inactive and disconnected asks for the code`() {
        assertEquals(
            SessionGate.LOGIN,
            resolveSessionGate(isActivated = false, status = ConnectionStatus.DISCONNECTED),
        )
    }

    @Test
    fun `inactive but connected is the live tunnel lockout`() {
        assertEquals(
            SessionGate.RECOVER_LIVE_TUNNEL,
            resolveSessionGate(isActivated = false, status = ConnectionStatus.CONNECTED),
        )
    }

    @Test
    fun `inactive but degraded is still live`() {
        assertEquals(
            SessionGate.RECOVER_LIVE_TUNNEL,
            resolveSessionGate(isActivated = false, status = ConnectionStatus.DEGRADED),
        )
    }

    @Test
    fun `inactive but connecting is still live`() {
        listOf(
            ConnectionStatus.CONNECTING_DIRECT,
            ConnectionStatus.CONNECTING_FRAGMENTED,
            ConnectionStatus.CONNECTING_REALITY,
            ConnectionStatus.CONNECTING_WEB_SOCKET,
            ConnectionStatus.CONNECTING_SHADOWSOCKS,
            ConnectionStatus.CONNECTING_HYSTERIA,
            ConnectionStatus.CONNECTING_VMESS,
            ConnectionStatus.DISCONNECTING,
        ).forEach { status ->
            assertEquals(
                "expected $status to count as live",
                SessionGate.RECOVER_LIVE_TUNNEL,
                resolveSessionGate(isActivated = false, status = status),
            )
        }
    }

    @Test
    fun `no status other than disconnected can ever fall through to login`() {
        // Guards the exhaustiveness intent: a new core status must be considered
        // here rather than inheriting the login branch by accident.
        ConnectionStatus.entries
            .filterNot { it in INERT_STATUSES }
            .forEach { status ->
                assertNotEquals(
                    "inactive session in $status must not render the login screen",
                    SessionGate.LOGIN,
                    resolveSessionGate(isActivated = false, status = status),
                )
            }
    }

    @Test
    fun `only inert statuses resolve to login`() {
        // DISCONNECTED and ERROR are both terminal: nothing is being forwarded,
        // so asking for the access code is the honest response. DISCONNECTING is
        // deliberately NOT inert, because packets still flow until it lands.
        ConnectionStatus.entries.forEach { status ->
            val expected = if (status in INERT_STATUSES) SessionGate.LOGIN else SessionGate.RECOVER_LIVE_TUNNEL
            assertEquals(
                "unexpected gate for $status",
                expected,
                resolveSessionGate(isActivated = false, status = status),
            )
        }
    }

    @Test
    fun `a failed tunnel is not a live tunnel`() {
        assertEquals(
            SessionGate.LOGIN,
            resolveSessionGate(isActivated = false, status = ConnectionStatus.ERROR),
        )
    }

    @Test
    fun `disconnecting is still live until it lands`() {
        assertEquals(
            SessionGate.RECOVER_LIVE_TUNNEL,
            resolveSessionGate(isActivated = false, status = ConnectionStatus.DISCONNECTING),
        )
    }
}
