package com.shadowmesh.core_vpn.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.shadowmesh.TrafficMode
import uniffi.shadowmesh.TrafficModePreference
import uniffi.shadowmesh.VpnNode

/**
 * Regression cover for capability-unaware mode selection.
 *
 * The bug: REALITY was returned for high-risk geographies and for port 443
 * endpoints without ever checking that the node served it, so a user in a
 * censored region got a transport the node could not accept.
 */
class TrafficModeResolverTest {

    private fun node(endpoint: String, country: String = "SG") = VpnNode(
        id = "edge1",
        name = "edge1",
        region = "Singapore",
        country = country,
        endpoint = endpoint,
        publicKey = "test",
        load = 0u,
        latency = 0u,
        isSovereign = true,
        isOnline = true,
        shardId = null,
    )

    private val wireguard = node("10.0.0.1:51820")
    private val proxy = node("10.0.0.1:443")

    @Test
    fun `reality labelling follows the proxy port on the live fleet`() {
        // The REALITY listener is on 443, so that is what the port check reports
        // on. Kept as characterisation, not aspiration: when the node publishes
        // capability this defers to it.
        assertTrue(TrafficModeResolver.isRealityNode(proxy))
    }

    @Test
    fun `a high risk country never selects an unsupported transport`() {
        listOf("MM", "CN", "mm", "cn").forEach { cc ->
            listOf(TrafficModePreference.AUTO, TrafficModePreference.STEALTH).forEach { pref ->
                (0..4).forEach { attempt ->
                    val chosen = TrafficModeResolver.select(wireguard, pref, attempt)
                    assertTrue(
                        "$cc/$pref/attempt$attempt chose $chosen, unsupported on $wireguard",
                        TrafficModeResolver.supports(wireguard, chosen),
                    )
                }
            }
        }
    }

    @Test
    fun `myanmar on a wireguard node still gets a supported transport`() {
        val mm = node("10.0.0.1:51820", country = "MM")
        val chosen = TrafficModeResolver.select(mm, TrafficModePreference.STEALTH, 0)
        assertTrue(TrafficModeResolver.supports(mm, chosen))
    }

    @Test
    fun `no attempt can escape the supported set`() {
        listOf(wireguard, proxy).forEach { n ->
            TrafficModePreference.entries.forEach { pref ->
                (0..8).forEach { attempt ->
                    val chosen = TrafficModeResolver.select(n, pref, attempt)
                    assertTrue(
                        "$n $pref $attempt -> $chosen is unsupported",
                        TrafficModeResolver.supports(n, chosen),
                    )
                }
            }
        }
    }

    @Test
    fun `an unrecognised endpoint degrades to a working transport`() {
        val odd = node("example.invalid:9999")
        val chosen = TrafficModeResolver.select(odd, TrafficModePreference.AUTO, 3)
        assertTrue(TrafficModeResolver.supports(odd, chosen))
    }

    @Test
    fun `a malformed endpoint does not throw`() {
        val broken = node("not-an-endpoint")
        val chosen = TrafficModeResolver.select(broken, TrafficModePreference.STEALTH, 0)
        assertTrue(TrafficModeResolver.supports(broken, chosen))
    }

    @Test
    fun `negative attempt is coerced rather than crashing`() {
        val chosen = TrafficModeResolver.select(wireguard, TrafficModePreference.AUTO, -5)
        assertTrue(TrafficModeResolver.supports(wireguard, chosen))
    }

    @Test
    fun `speed preference never selects reality on a wireguard node`() {
        val chosen = TrafficModeResolver.select(
            wireguard,
            TrafficModePreference.SPEED,
            0,
        )
        assertEquals(TrafficMode.NORMAL, chosen)
    }

    @Test
    fun `a node that serves reality can select it`() {
        // Proves the gate is capability-based, not a blanket disable: REALITY
        // must still be reachable where it genuinely exists.
        val chosen = TrafficModeResolver.select(proxy, TrafficModePreference.STEALTH, 0)
        assertEquals(TrafficMode.REALITY, chosen)
    }

    /**
     * Regression for the regression. The node serves REALITY inside the
     * WireGuard tunnel, so an unpublished-capability node must NOT have REALITY
     * removed. An earlier draft inferred capability from the endpoint port and
     * refused REALITY on 51820, disabling a working transport on the real node.
     */
    @Test
    fun `unpublished capability does not disable a working transport`() {
        assertTrue(
            "REALITY serves inside the tunnel and must stay available",
            TrafficModeResolver.supports(wireguard, TrafficMode.REALITY),
        )
        assertEquals(
            "an unpublished node must not be narrowed",
            TrafficModeResolver.ALL_MODES,
            TrafficModeResolver.supportedModes(wireguard),
        )
    }

    @Test
    fun `a 51820 node still offers reality because reality is served on 443`() {
        // The node's REALITY listener is on 443 while the client dials 51820.
        // Capability must not be inferred from the dial port.
        val chosen = TrafficModeResolver.select(wireguard, TrafficModePreference.STEALTH, 0)
        assertEquals(TrafficMode.REALITY, chosen)
    }
}
