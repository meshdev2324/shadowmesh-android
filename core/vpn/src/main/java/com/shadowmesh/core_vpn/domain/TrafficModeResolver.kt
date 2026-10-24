package com.shadowmesh.core_vpn.domain

import uniffi.shadowmesh.TrafficMode
import uniffi.shadowmesh.TrafficModePreference

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
 * serves it. Preference may only *order* the candidate set, never add to it.
 *
 * The node is a [MeshNodeEntry], not a fully described node, because the
 * directory the client can actually fetch publishes geography and nothing else.
 * The endpoint — the field the old port heuristic read — is not available at
 * this stage at all, so REALITY support now comes from the authenticated stage-2
 * config and is [RealitySupport.UNKNOWN] until that resolves. The old port check
 * is gone rather than kept as a fallback: it was a weak proxy for capability,
 * and carrying it forward over a field the directory no longer has would have
 * meant reading a value we do not have.
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
     * Capability as established by stage 2, or `null` when we have not asked.
     *
     * The distinction between "empty" and "unknown" is the whole point. An
     * empty set means the node serves nothing we can use. Unknown means we have
     * not asked. Treating unknown as empty silently disables working transports
     * - which is exactly the regression an earlier draft of this file
     * introduced, when it inferred capability from the endpoint port and refused
     * REALITY on a node that serves it.
     *
     * Only REALITY is narrowed, and only on positive evidence that the node does
     * not serve it. The other transports are not narrowed at all: we have no
     * evidence either way, and narrowing them would repeat the original defect
     * against five transports instead of one.
     */
    fun publishedCapability(entry: MeshNodeEntry): Set<TrafficMode>? =
        when (entry.realitySupport) {
            RealitySupport.SUPPORTED -> ALL_MODES
            RealitySupport.UNSUPPORTED -> ALL_MODES - TrafficMode.REALITY
            RealitySupport.UNKNOWN -> null
        }

    /**
     * Transport modes available on [entry].
     *
     * When stage 2 has established capability, that is authoritative. When it
     * has not, every implemented mode stays available: the client degrades to
     * its existing behaviour rather than to a narrower set it cannot justify.
     */
    fun supportedModes(entry: MeshNodeEntry): Set<TrafficMode> =
        publishedCapability(entry) ?: ALL_MODES

    /** True when [mode] has a serving path on [entry]. */
    fun supports(entry: MeshNodeEntry, mode: TrafficMode): Boolean = mode in supportedModes(entry)

    /**
     * REALITY support for UI labelling.
     *
     * Answers only from evidence. `UNKNOWN` is deliberately not reported as
     * false: the UI shows "checking" for it rather than a "DIRECT" label it has
     * no basis for.
     */
    fun isRealityNode(entry: MeshNodeEntry): Boolean =
        entry.realitySupport == RealitySupport.SUPPORTED

    /**
     * Order the supported modes by intent, dropping anything unsupported.
     *
     * The ladder is unchanged in shape; what is new is that a rung the node
     * cannot serve is never produced. FRAGMENTED remains a preference for a
     * sturdier path on nodes that serve it.
     */
    fun select(entry: MeshNodeEntry, preference: TrafficModePreference, attempt: Int): TrafficMode {
        val supported = supportedModes(entry)

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