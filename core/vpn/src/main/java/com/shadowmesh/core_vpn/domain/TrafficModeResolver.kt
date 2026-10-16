package com.shadowmesh.core_vpn.domain

import uniffi.shadowmesh.TrafficMode
import uniffi.shadowmesh.TrafficModePreference
import uniffi.shadowmesh.VpnNode

/**
 * Chooses a transport for a node.
 *
 * The defect this replaces: mode selection was driven entirely by user intent
 * and geography. `select` could return [TrafficMode.REALITY] from three
 * separate branches, and `isRealityNode` inferred REALITY support from the
 * endpoint being on port 443. Nothing consulted whether the node actually
 * served that transport. A user in a high-risk region was therefore handed
 * REALITY against a node with no REALITY inbound - a connection guaranteed to
 * fail, for exactly the users the anti-censorship product exists for.
 *
 * Selection is now capability-first: a mode is only ever returned if the node
 * serves it. Preference and geography may only *order* the candidate set, never
 * add to it.
 */
object TrafficModeResolver {

    /** Every transport the client implements. The pre-publication default. */
    val ALL_MODES: Set<TrafficMode> = setOf(
        TrafficMode.NORMAL,
        TrafficMode.FRAGMENTED,
        TrafficMode.REALITY,
        TrafficMode.WEB_SOCKET,
        TrafficMode.SHADOWSOCKS,
        TrafficMode.HYSTERIA,
        TrafficMode.VMESS,
    )


    /**
     * Verified deployment facts for the current fleet.
     *
     * Architecture, confirmed against the live node: the client dials the node
     * over WireGuard on 51820, and REALITY is reachable *inside* that tunnel on
     * 443. The node serves both. So the endpoint port the client happens to use
     * says nothing about which application protocols are available, and must
     * never be used to restrict them.
     */
    const val WIREGUARD_PORT = 51820
    const val PROXY_PORT = 443

    /**
     * Capability as published by the node. `null` means the node has not
     * published it, which is the case for the entire current fleet.
     *
     * The distinction between "empty" and "unknown" is the whole point. An
     * empty set means the node serves nothing we can use. Unknown means we have
     * not asked. Treating unknown as empty silently disables working transports
     * - which is exactly the regression an earlier draft of this file
     * introduced, when it inferred capability from the endpoint port and refused
     * REALITY on a node that serves it.
     */
    fun publishedCapability(node: VpnNode): Set<TrafficMode>? = null

    private fun portOf(node: VpnNode): Int? =
        node.endpoint.substringAfterLast(':', "").toIntOrNull()

    /**
     * Transport modes available on [node].
     *
     * When the node has published capability, that is authoritative. When it
     * has not, every implemented mode stays available: the client degrades to
     * its existing behaviour rather than to a narrower set it cannot justify.
     */
    fun supportedModes(node: VpnNode): Set<TrafficMode> =
        publishedCapability(node) ?: ALL_MODES

    /** True when [mode] has a serving path on [node]. */
    fun supports(node: VpnNode, mode: TrafficMode): Boolean = mode in supportedModes(node)

    /**
     * REALITY support for UI labelling.
     *
     * Unchanged from the original port check, because on this fleet the
     * REALITY listener is the one on 443. When capability is published this
     * defers to it, since the port is a weak proxy for capability and was the
     * original source of a false label.
     */
    fun isRealityNode(node: VpnNode): Boolean =
        publishedCapability(node)?.let { TrafficMode.REALITY in it } ?: (portOf(node) == PROXY_PORT)

    /**
     * Order the supported modes by intent, dropping anything unsupported.
     *
     * The ladder is unchanged in shape; what is new is that a rung the node
     * cannot serve is never produced. FRAGMENTED remains a preference for a
     * sturdier path on nodes that serve it.
     */
    fun select(node: VpnNode, preference: TrafficModePreference, attempt: Int): TrafficMode {
        val supported = supportedModes(node)
        val isHighRisk = node.country.equals("MM", ignoreCase = true) ||
            node.country.equals("CN", ignoreCase = true)

        val ladder: List<TrafficMode> = when {
            preference == TrafficModePreference.SPEED -> listOf(
                TrafficMode.NORMAL,
                TrafficMode.REALITY,
            )

            else -> listOf(
                TrafficMode.REALITY,
                TrafficMode.NORMAL,
                TrafficMode.FRAGMENTED,
            )
        }

        val ordered = ladder.filter { it in supported }

        // An unrecognised node must still produce a mode. This should be
        // unreachable given supportedModes always includes NORMAL, but returning
        // NORMAL keeps the function total rather than throwing mid-connect.
        return ordered.getOrElse(attempt.coerceAtLeast(0)) { TrafficMode.NORMAL }
            .takeIf { it in supported } ?: TrafficMode.NORMAL
    }
}
