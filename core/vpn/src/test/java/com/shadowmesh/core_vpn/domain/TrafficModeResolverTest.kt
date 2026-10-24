package com.shadowmesh.core_vpn.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.shadowmesh.TrafficMode
import uniffi.shadowmesh.TrafficModePreference

/**
 * Regression cover for capability-unaware mode selection.
 *
 * The bug: REALITY was returned for high-risk geographies without ever checking
 * that the node served it, so a user in a censored region got a transport the
 * node could not accept.
 *
 * The node here is a [MeshNodeEntry] — the stage-1 directory projection. It has
 * no endpoint, which is deliberate: the anonymous directory publishes geography
 * and nothing else. REALITY support therefore arrives as [RealitySupport], set
 * from the authenticated stage-2 config, and the tests below pin the three
 * states apart.
 */
class TrafficModeResolverTest {

    private fun entry(
        country: String = "SG",
        reality: RealitySupport = RealitySupport.UNKNOWN,
    ) = MeshNodeEntry(
        id = "edge1",
        country = country,
        region = "Singapore",
        isOnline = true,
        realitySupport = reality,
    )

    private val unresolved = entry()
    private val servesReality = entry(reality = RealitySupport.SUPPORTED)
    private val noReality = entry(reality = RealitySupport.UNSUPPORTED)

    @Test
    fun `reality labelling answers only from evidence`() {
        assertTrue(TrafficModeResolver.isRealityNode(servesReality))
        assertFalse(TrafficModeResolver.isRealityNode(noReality))
        // The load-bearing assertion: an unresolved node must NOT be reported as
        // non-REALITY. That is the "DIRECT" label the old boolean produced on
        // the first frame, before anyone had asked the node anything.
        assertFalse(TrafficModeResolver.isRealityNode(unresolved))
        assertNull(TrafficModeResolver.publishedCapability(unresolved))
    }

    @Test
    fun `a high risk country never selects an unsupported transport`() {
        listOf("MM", "CN", "mm", "cn").forEach { cc ->
            listOf(TrafficModePreference.AUTO, TrafficModePreference.STEALTH).forEach { pref ->
                (0..4).forEach { attempt ->
                    val node = entry(country = cc, reality = RealitySupport.UNSUPPORTED)
                    val chosen = TrafficModeResolver.select(node, pref, attempt)
                    assertTrue(
                        "$cc/$pref/attempt$attempt chose $chosen, unsupported on $node",
                        TrafficModeResolver.supports(node, chosen),
                    )
                }
            }
        }
    }

    @Test
    fun `myanmar on an unresolved node still gets a supported transport`() {
        val chosen = TrafficModeResolver.select(
            entry(country = "MM"),
            TrafficModePreference.STEALTH,
            0,
        )
        assertTrue(TrafficModeResolver.supports(entry(country = "MM"), chosen))
    }

    @Test
    fun `no attempt can escape the supported set`() {
        listOf(unresolved, servesReality, noReality).forEach { n ->
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
    fun `speed preference never selects reality on an unresolved node`() {
        val chosen = TrafficModeResolver.select(unresolved, TrafficModePreference.SPEED, 0)
        assertEquals(TrafficMode.NORMAL, chosen)
    }

    @Test
    fun `a node that serves reality can select it`() {
        // Proves the gate is capability-based, not a blanket disable: REALITY
        // must still be reachable where it genuinely exists.
        val chosen = TrafficModeResolver.select(servesReality, TrafficModePreference.STEALTH, 0)
        assertEquals(TrafficMode.REALITY, chosen)
    }

    /**
     * Regression for the regression. The node serves REALITY inside the
     * WireGuard tunnel, so an unresolved-capability node must NOT have REALITY
     * removed. An earlier draft inferred capability from the endpoint port and
     * refused REALITY on 51820, disabling a working transport on the real node.
     */
    @Test
    fun `unresolved capability does not disable a working transport`() {
        assertTrue(
            "REALITY serves inside the tunnel and must stay available",
            TrafficModeResolver.supports(unresolved, TrafficMode.REALITY),
        )
        assertEquals(
            "an unresolved node must not be narrowed",
            TrafficModeResolver.ALL_MODES,
            TrafficModeResolver.supportedModes(unresolved),
        )
    }

    @Test
    fun `an unresolved node still offers reality because reality is served on 443`() {
        // The node's REALITY listener is on 443 while the client dials 51820.
        // Capability must not be inferred from the dial port — and the dial port
        // is not even in the directory any more.
        val chosen = TrafficModeResolver.select(unresolved, TrafficModePreference.STEALTH, 0)
        assertEquals(TrafficMode.REALITY, chosen)
    }

    /**
     * New, and the one that matters at the directory stage: positive evidence
     * that a node does not serve REALITY must narrow it, on every attempt.
     */
    @Test
    fun `a node known not to serve reality never gets reality`() {
        (0..8).forEach { attempt ->
            val chosen = TrafficModeResolver.select(noReality, TrafficModePreference.STEALTH, attempt)
            assertFalse(
                "attempt $attempt chose $chosen on a node with no REALITY inbound",
                TrafficModeResolver.supports(noReality, TrafficMode.REALITY),
            )
            assertTrue(TrafficModeResolver.supports(noReality, chosen))
        }
    }

    /**
     * Only REALITY is narrowed on positive evidence. The other transports are
     * never narrowed, because the directory publishes nothing about them and
     * dropping them on a guess would repeat the original defect five times over.
     */
    @Test
    fun `a node known not to serve reality keeps every other transport`() {
        assertEquals(
            TrafficModeResolver.ALL_MODES - TrafficMode.REALITY,
            TrafficModeResolver.supportedModes(noReality),
        )
    }

    @Test
    fun `negative attempt is coerced rather than crashing`() {
        val chosen = TrafficModeResolver.select(unresolved, TrafficModePreference.AUTO, -5)
        assertTrue(TrafficModeResolver.supports(unresolved, chosen))
    }
}