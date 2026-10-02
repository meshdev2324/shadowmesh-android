package com.shadowmesh.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the in-tunnel leak check. Probes are injected; the
 * network itself is never touched in tests.
 */
class LeakCheckTest {

    @Test
    fun `egress matching the node endpoint passes`() {
        val check = LeakCheck(fetch = { "203.0.113.7" }, ipv6Probe = { false })
        val report = check.run("203.0.113.7")
        assertEquals("203.0.113.7", report.egressIp)
        assertEquals(true, report.egressMatchesNode)
        assertEquals(false, report.ipv6Leaked)
    }

    @Test
    fun `a carrier egress address is a leak`() {
        val check = LeakCheck(fetch = { "100.64.10.20" }, ipv6Probe = { false })
        val report = check.run("203.0.113.7")
        assertEquals(false, report.egressMatchesNode)
    }

    @Test
    fun `failed egress probe is inconclusive, never a pass`() {
        val check = LeakCheck(fetch = { null }, ipv6Probe = { null })
        val report = check.run("203.0.113.7")
        assertNull(report.egressIp)
        assertNull(report.egressMatchesNode)
        assertNull(report.ipv6Leaked)
    }

    @Test
    fun `ipv6 answering means a leak while protection is on`() {
        val check = LeakCheck(fetch = { "203.0.113.7" }, ipv6Probe = { true })
        val report = check.run("203.0.113.7")
        assertEquals(true, report.ipv6Leaked)
    }

    @Test
    fun `domain endpoints are inconclusive rather than guessed`() {
        val check = LeakCheck(fetch = { "203.0.113.7" }, ipv6Probe = { false })
        val report = check.run("edge1-sgp1.example.net")
        assertNull(report.egressMatchesNode)
    }

    @Test
    fun `the display address is truncated to a bounded length`() {
        // The echo body is display-only and bounded; nothing unbounded from
        // the probe ever reaches the UI layer.
        val longBody = "203.0.113.7 " + "x".repeat(200)
        val check = LeakCheck(fetch = { longBody }, ipv6Probe = { false })
        val report = check.run("203.0.113.7")
        assertTrue(report.egressIp!!.length <= 64)
    }
}
