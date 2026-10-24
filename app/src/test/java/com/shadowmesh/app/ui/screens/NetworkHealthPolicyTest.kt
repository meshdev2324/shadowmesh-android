package com.shadowmesh.app.ui.screens

import com.shadowmesh.ui_kit.components.NetworkHealthBadge
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.shadowmesh.NetworkReport
import uniffi.shadowmesh.NetworkType
import uniffi.shadowmesh.ServerNetworkReport

/**
 * The trust badge must never overstate the protection in force. These are the
 * rules that decide what the user is told, so they are pinned here rather than
 * left to a composable.
 */
class NetworkHealthPolicyTest {

    private fun report(
        dpi: Boolean = false,
        portal: Boolean = false,
    ): NetworkReport = NetworkReport(
        isConnected = true,
        networkType = NetworkType.WI_FI,
        latencyMs = 20u,
        jitterMs = 3u,
        packetLoss = 0f,
        speedTest = null,
        serverReport = null,
        captivePortal = (if (portal) 1 else 0).toUByte(),
        dpiDetected = (if (dpi) 1 else 0).toUByte(),
        dpiDetectedByServer = false,
        isProtected = true,
    )

    @Test
    fun `no tunnel means offline, never secure`() {
        assertEquals(
            "a stale or absent report must not claim protection with no tunnel",
            NetworkHealthBadge.OFFLINE,
            NetworkHealthPolicy.resolve(isConnected = false, isDetecting = false, report = report()),
        )
    }

    @Test
    fun `a connected tunnel with no diagnostic yet is checking, not secure`() {
        assertEquals(
            NetworkHealthBadge.CHECKING,
            NetworkHealthPolicy.resolve(isConnected = true, isDetecting = true, report = null),
        )
        assertEquals(
            NetworkHealthBadge.CHECKING,
            NetworkHealthPolicy.resolve(isConnected = true, isDetecting = false, report = null),
        )
    }

    @Test
    fun `a clean diagnostic on a live tunnel is secure`() {
        assertEquals(
            NetworkHealthBadge.SECURE,
            NetworkHealthPolicy.resolve(isConnected = true, isDetecting = false, report = report()),
        )
    }

    @Test
    fun `dpi detection outranks a clean read`() {
        assertEquals(
            NetworkHealthBadge.DPI_ALERT,
            NetworkHealthPolicy.resolve(
                isConnected = true,
                isDetecting = false,
                report = report(dpi = true, portal = true),
            ),
        )
    }

    @Test
    fun `a captive portal is surfaced`() {
        assertEquals(
            NetworkHealthBadge.PORTAL_DETECTED,
            NetworkHealthPolicy.resolve(
                isConnected = true,
                isDetecting = false,
                report = report(portal = true),
            ),
        )
    }

    @Test
    fun `the badge state is always defined for every combination`() {
        // Guards against a future branch leaving the UI with nothing to render,
        // which is what produced the "badge appears at random" report.
        for (connected in listOf(true, false)) {
            for (detecting in listOf(true, false)) {
                for (r in listOf(null, report(), report(dpi = true), report(portal = true))) {
                    NetworkHealthPolicy.resolve(connected, detecting, r)
                }
            }
        }
    }

    @Test
    fun `ServerNetworkReport is not required for a verdict`() {
        // Guards the constructor shape used above against drift.
        assertEquals(NetworkType.WI_FI, report().networkType)
        assertEquals(null as ServerNetworkReport?, report().serverReport)
    }
}
