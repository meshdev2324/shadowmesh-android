package com.shadowmesh.core_vpn.domain

import uniffi.shadowmesh.ShadowMeshException
import uniffi.shadowmesh.VpnConfig

/**
 * Two-stage node discovery, and the seam between the two stages.
 *
 * The control plane answers two different questions with two different endpoints:
 *
 *  - **Where is the mesh?** — the anonymous directory projection,
 *    `{id, country, region, is_online}`. Deliberately narrow: it carries no
 *    endpoint, no key, no load and no latency. That is a security decision
 *    (L10), not an oversight, and this type is shaped to match it exactly.
 *  - **How do I reach this one node?** — the authenticated
 *    `GET /api/v1/config/:server_id`, which returns a full [VpnConfig].
 *
 * Collapsing those into one type is what produced an empty server list. The
 * anonymous projection was fed to a deserializer that also required `name`,
 * `endpoint`, `public_key`, `load` and `latency`, so *every* response failed,
 * and the failure was then swallowed into `emptyList()` — leaving the user with
 * no servers and no explanation. Two types, and a typed failure, so neither can
 * recur silently.
 */

/**
 * Whether a node is known to serve REALITY, known not to, or simply not asked.
 *
 * Tri-state on purpose. The directory withholds the endpoint, so before stage 2
 * resolves a node the honest answer is "not asked". Collapsing that to `false`
 * renders a confident "DIRECT" label with no evidence behind it; collapsing it
 * to `true` is the same lie in the opposite direction and the more dangerous
 * one — it re-advertises a transport to a node that may not accept it, which is
 * the exact defect `TrafficModeResolver` exists to prevent.
 */
enum class RealitySupport { UNKNOWN, SUPPORTED, UNSUPPORTED }

/**
 * Stage 1 — what the control plane publishes about a node to a caller that has
 * not authenticated. This is what the node list renders; it is deliberately
 * unable to dial anything.
 */
data class MeshNodeEntry(
    val id: String,
    val country: String,
    val region: String,
    val isOnline: Boolean,
    val realitySupport: RealitySupport = RealitySupport.UNKNOWN,
) {
    /**
     * The row's display label.
     *
     * The directory publishes geography, not node identity, so the label is
     * derived from the geography the server *did* publish rather than invented.
     * Falls back to the id only when the server published neither, which keeps
     * two rows distinguishable instead of rendering a wall of identical blanks.
     *
     * This value is display-only. It is never sent to the control plane and
     * never used to reach a node.
     */
    val displayName: String
        get() = region.ifBlank { country }.ifBlank { id }
}

/** Why a discovery attempt did not produce a directory. */
enum class NodeLoadFailure {
    /**
     * The request was refused for want of a usable session. Reported
     * separately from a transport failure because it is the one case with a
     * concrete user action behind it (activate, or sign in again).
     */
    NOT_AUTHENTICATED,

    /** No usable response at all: offline, DNS, TLS, timeout, or a 5xx. */
    TRANSPORT,

    /**
     * A response arrived but did not match the published projection — the shape
     * this bug actually produced. Distinct from [TRANSPORT] because it means
     * client and server disagree about the contract, which no amount of
     * retrying or "check your connection" will fix.
     */
    MALFORMED_PAYLOAD,

    /** Unclassified. Kept so an unknown failure still renders as a failure. */
    UNKNOWN,
}

/** The outcome of one stage-1 discovery attempt. */
sealed interface NodeDirectoryResult {
    /**
     * The directory loaded. [entries] may legitimately be empty — the mesh
     * having no online nodes is a successful answer, and the UI must be able to
     * say so rather than showing an error.
     */
    data class Loaded(val entries: List<MeshNodeEntry>) : NodeDirectoryResult

    data class Failed(val failure: NodeLoadFailure, val detail: String) : NodeDirectoryResult
}

/**
 * A discovery failure, shaped for the screen.
 *
 * [detail] carries the raw cause for logs and for the retry affordance's
 * diagnostics, but it is never rendered verbatim: it can contain a server
 * message, and the UI text is chosen by [message] so that nothing the control
 * plane said is shown to a user unfiltered.
 */
data class NodeLoadError(
    val failure: NodeLoadFailure,
    val message: String,
    val canRetry: Boolean,
) {
    companion object {
        /**
         * User-facing text per failure, and whether retrying without any other
         * action is worth offering.
         *
         * [NOT_AUTHENTICATED] is the only one where retry is pointless: the
         * same request without a session fails the same way, so the fix is to
         * activate, not to try again.
         */
        fun of(failure: NodeLoadFailure): NodeLoadError = when (failure) {
            NodeLoadFailure.NOT_AUTHENTICATED -> NodeLoadError(
                failure = failure,
                message = "Activate your device to see the mesh.",
                canRetry = false,
            )
            NodeLoadFailure.TRANSPORT -> NodeLoadError(
                failure = failure,
                message = "Couldn't reach the mesh directory. Check your connection and try again.",
                canRetry = true,
            )
            NodeLoadFailure.MALFORMED_PAYLOAD -> NodeLoadError(
                failure = failure,
                message = "The mesh directory came back in an unexpected format.",
                canRetry = true,
            )
            NodeLoadFailure.UNKNOWN -> NodeLoadError(
                failure = failure,
                message = "Couldn't load the mesh directory.",
                canRetry = true,
            )
        }
    }
}

/**
 * Classifies a discovery failure into something the UI can act on.
 *
 * Pure and total by construction: every throwable maps to some [NodeLoadFailure],
 * including the unrecognised ones, because a failure that cannot be classified
 * is still a failure and must still be reported.
 */
object NodeLoadFailureClassifier {

    fun classify(error: Throwable): NodeLoadFailure {
        // UniFFI maps transport status codes onto typed variants, so the common
        // cases need no text matching at all.
        if (error is ShadowMeshException.Unauthorized || error is ShadowMeshException.Forbidden) {
            return NodeLoadFailure.NOT_AUTHENTICATED
        }
        if (error is ShadowMeshException.JsonException) {
            return NodeLoadFailure.MALFORMED_PAYLOAD
        }

        // A serde `missing field` crosses FFI as a generic error string rather
        // than a typed variant, so it has to be recognised on text. Bounded on
        // purpose to the two shapes a schema mismatch can take; everything else
        // falls through to TRANSPORT rather than being guessed at.
        val text = buildString {
            error.causeChain().forEach { append(' ').append(it.message.orEmpty()) }
        }.lowercase()

        return when {
            "missing field" in text || "unknown field" in text ||
                "deserializ" in text || "malformed" in text ->
                NodeLoadFailure.MALFORMED_PAYLOAD
            "unauthorized" in text || "forbidden" in text || " 401" in text || " 403" in text ->
                NodeLoadFailure.NOT_AUTHENTICATED
            error is java.io.IOException ||
                "connection" in text || "timeout" in text || "timed out" in text ||
                "dns" in text || "unresolved" in text || "reset" in text ||
                "socket" in text || "handshake" in text ->
                NodeLoadFailure.TRANSPORT
            // Anything else is NOT a transport fault. Reporting it as one offers a
            // retry that cannot help and tells the user to check their wifi, which
            // is the misdiagnosis this enum exists to prevent.
            else -> NodeLoadFailure.UNKNOWN
        }
    }
}

/**
 * This throwable and everything that caused it.
 *
 * An FFI failure frequently carries its real cause one or two levels down, and
 * the classification has to reach it — `ShadowMeshException.Other("...")` is
 * the wrapper, not the message. Bounded, because a cause cycle would otherwise
 * hang the classification on a platform thread.
 */
internal fun Throwable.causeChain(): List<Throwable> {
    val chain = mutableListOf<Throwable>()
    var current: Throwable? = this
    while (current != null && chain.size < MAX_CAUSE_DEPTH) {
        chain += current
        current = current.cause
    }
    return chain
}

private const val MAX_CAUSE_DEPTH = 16

/**
 * Stage 2 — what one specific node is actually connectable as, resolved from the
 * authenticated `GET /api/v1/config/:server_id`.
 *
 * This is the only place an endpoint or a peer public key is allowed to enter
 * the client. Everything the tunnel dials comes from here and from the [config]
 * beside it; the directory entry that led us here carries none of it.
 */
data class NodeDetail(
    val id: String,
    val endpoint: String,
    val publicKey: String,
    val realitySupport: RealitySupport,
    val config: VpnConfig,
)

/** The outcome of one stage-2 resolution. */
sealed interface NodeDetailResult {
    data class Resolved(val detail: NodeDetail) : NodeDetailResult
    data class Failed(val failure: NodeLoadFailure, val detail: String) : NodeDetailResult
}