package com.shadowmesh.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeshVpnServiceAddressTest {
    @Test
    fun `parses cidr address`() {
        assertEquals(TunnelAddress("10.0.0.2", 32), parseTunnelAddress("10.0.0.2/32"))
    }

    @Test
    fun `bare address defaults to host prefix`() {
        assertEquals(TunnelAddress("10.0.0.2", 32), parseTunnelAddress("10.0.0.2"))
    }

    @Test
    fun `invalid address is rejected`() {
        assertNull(parseTunnelAddress("10.0.0.2/0"))
        assertNull(parseTunnelAddress(""))
    }
}

class Ipv6PostureTest {
    @Test
    fun `leak protection on with an ipv4-only engine blocks the family`() {
        // The production default: leak protection defaults to true and the
        // engine does not carry IPv6, so a dual-stack network must not be able
        // to route IPv6 around the tunnel.
        assertEquals(Ipv6Posture.BLOCK, ipv6Posture(ENGINE_SUPPORTS_IPV6, leakProtectionEnabled = true))
    }

    @Test
    fun `leak protection off passes ipv6 through the underlying network`() {
        assertEquals(
            Ipv6Posture.PASS_THROUGH_UNDERLYING,
            ipv6Posture(engineSupportsIpv6 = false, leakProtectionEnabled = false),
        )
    }

    @Test
    fun `an ipv6-capable engine always routes ipv6 into the tunnel`() {
        assertEquals(
            Ipv6Posture.ROUTE_INTO_TUNNEL,
            ipv6Posture(engineSupportsIpv6 = true, leakProtectionEnabled = true),
        )
        assertEquals(
            Ipv6Posture.ROUTE_INTO_TUNNEL,
            ipv6Posture(engineSupportsIpv6 = true, leakProtectionEnabled = false),
        )
    }
}
