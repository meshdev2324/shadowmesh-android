package com.shadowmesh.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android silently hands the device's single VPN slot to a competing VPN app.
 * These rules decide what the user is told, so they are pinned deliberately:
 * getting them wrong means either a silent drop (the original complaint) or a
 * false accusation that the user removed our permission.
 */
class RevokeReasonPolicyTest {

    @Test
    fun `consent intact means another vpn app took the slot`() {
        assertEquals(
            DisconnectReason.ANOTHER_VPN_TOOK_OVER,
            RevokeReasonPolicy.forRevoke(stillHasVpnConsent = true),
        )
    }

    @Test
    fun `consent withdrawn means the user removed us in settings`() {
        assertEquals(
            DisconnectReason.PERMISSION_REVOKED,
            RevokeReasonPolicy.forRevoke(stillHasVpnConsent = false),
        )
    }

    @Test
    fun `a takeover is explained rather than silent`() {
        val message = RevokeReasonPolicy.message(DisconnectReason.ANOTHER_VPN_TOOK_OVER)
        assertTrue("the user must be told why", message.isNotBlank())
    }

    @Test
    fun `a deliberate disconnect says nothing`() {
        assertEquals(
            "a normal disconnect must not raise an alarm",
            "",
            RevokeReasonPolicy.message(DisconnectReason.USER_REQUESTED),
        )
    }

    @Test
    fun `every non-user reason carries an explanation`() {
        for (reason in DisconnectReason.entries) {
            if (reason == DisconnectReason.USER_REQUESTED) continue
            assertTrue(
                "$reason must explain itself",
                RevokeReasonPolicy.message(reason).isNotBlank(),
            )
        }
    }

    @Test
    fun `the takeover message names the platform constraint`() {
        // Users who search for this phrase should find the real explanation.
        val message = RevokeReasonPolicy.message(DisconnectReason.ANOTHER_VPN_TOOK_OVER)
        assertTrue(message.contains("one VPN", ignoreCase = true))
    }
}
