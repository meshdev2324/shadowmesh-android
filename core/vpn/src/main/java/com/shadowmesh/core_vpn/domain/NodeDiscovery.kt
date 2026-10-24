package com.shadowmesh.core_vpn.domain

import kotlinx.coroutines.CancellationException
import uniffi.shadowmesh.ApiClient
import uniffi.shadowmesh.PublicNode
import uniffi.shadowmesh.VpnConfig
import uniffi.shadowmesh.VpnNode

/**
 * Stage 1 — the anonymous directory.
 *
 * ### This is the FFI seam
 *
 * `loadDirectory` is the one function in the Android client that turns a
 * control-plane node listing into a [MeshNodeEntry]. When the core's projection
 * type settles, the change lands here and nowhere else: [toEntry] is the whole
 * adapter.
 *
 * The core now exposes `PublicNode` for exactly this payload -- the server's
 * deliberate anonymous projection, which omits `endpoint` and `public_key` on
 * purpose. Deserialising that projection into the full `VpnNode` is what used
 * to fail *inside* Rust, leaving `getNodes()` throwing on every call.
 *
 * A malformed response is still reported as a failure rather than papered over:
 * no field is defaulted, substituted or invented.
 *
 * ### The roster is an ENVELOPE, and this seam reads the rows out of it
 *
 * ⚠️ **[ApiClient.getNodes] returns a `NodeRoster`, not a list.**
 *
 * The FFI's roster accessor changed shape when the core gained the roster
 * envelope (RFC-049 §15.4, F102): `get_nodes()` used to hand the client
 * `sequence<PublicNode>` and now hands it `NodeRoster { projection, nodes }`.
 * The **rows** are still the four published fields; the **envelope** is what
 * says which projection the server applied.
 *
 * ⇒ This seam reads `.nodes` and maps the rows. It does not read
 * `.projection`, and that is a **deliberate, recorded** gap rather than an
 * oversight: the Android has no roster-state line yet (the desktop's is
 * specified in `design-system/ROSTER-ROW-READOUT.md` §6 and the dashboard's
 * banner is specified in `design-system/ROSTER-STATE-CONTRACT.md` §2.3), and
 * inventing one here would be authoring copy that L6 owns. Nothing false is
 * asserted in the meantime -- the four-field rows are the four-field rows
 * whichever projection arrived, and the classification below reads the
 * **exception type**, never a message.
 *
 * ⚠️ **The shape is not negotiable at the call site.** `getNodes().map(…)` was
 * the pre-envelope call, and it does not compile against the envelope: the
 * compiler is what caught the drift, and it is the reason this seam is the one
 * place the shape is read. See `apps/android/ci/check-binding-consumer-shape.sh`
 * -- the gate that keeps the consumer and the regenerated binding in step.
 *
 * ### Blocking
 *
 * [ApiClient.getNodes] is a blocking FFI call. Callers must be off the main
 * thread; the ViewModel uses the IO dispatcher.
 */
object NodeDirectorySource {

    fun loadDirectory(apiClient: ApiClient): NodeDirectoryResult = try {
        NodeDirectoryResult.Loaded(apiClient.getNodes().nodes.map(::toEntry))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        NodeDirectoryResult.Failed(
            failure = NodeLoadFailureClassifier.classify(e),
            detail = e.message.orEmpty(),
        )
    }

    private fun toEntry(node: PublicNode): MeshNodeEntry = MeshNodeEntry(
        id = node.id,
        country = node.country,
        region = node.region,
        isOnline = node.isOnline,
    )
}

/**
 * Stage 2 — the authenticated, per-node detail.
 */
object NodeDetailSource {

    /**
     * The one mode whose *response* can prove whether a node serves REALITY.
     *
     * `GET /api/v1/config/:server_id` is mode-parameterised, not
     * capability-advertising. The server populates `reality_config` **only**
     * when the request carries `mode=reality`
     * (`services/server/src/api/handlers/vpn.rs:385`); for any other mode —
     * including the server's own `"normal"` default when no mode is sent at
     * all — the field is always absent, whether or not the node can serve
     * REALITY.
     *
     * So the absence of `reality_config` is evidence of nothing, and reading it
     * as evidence of absence is what steered this client off REALITY and onto
     * WireGuard. Capability may therefore only ever be established by asking
     * for REALITY explicitly.
     */
    const val REALITY_PROBE_MODE = "reality"

    /**
     * Resolves the connectable detail for one directory entry.
     *
     * The endpoint and the peer public key are read off the [VpnConfig] the
     * control plane returned, never off the [MeshNodeEntry]. That is the whole
     * division of labour: the directory says where a node is, this says how to
     * reach it, and the second answer is available only to a caller that has
     * proved a session.
     *
     * REALITY support is read off [VpnConfig.realityConfig], and **only** from a
     * response that was asked for REALITY ([mode]). A REALITY-mode response
     * carrying the block is [RealitySupport.SUPPORTED]. Anything else is
     * [RealitySupport.UNKNOWN] — "not established", which the UI renders as
     * "checking" and the mode ladder treats as "keep every transport
     * available". [RealitySupport.UNSUPPORTED] is deliberately **not** produced
     * here: no response this client can currently obtain constitutes positive
     * evidence that a node cannot serve REALITY. A node whose operator never
     * provisioned `vless_uuid` makes the control plane answer the REALITY probe
     * with a 400, and a 400 is a failed question, not a negative answer — so it
     * also resolves to UNKNOWN, and the connect ladder discovers the refusal by
     * trying, with the error surfaced, rather than being pre-emptively
     * downgraded to a transport that cannot work either.
     *
     * @param mode the transport mode [config] was actually requested with, or
     *   `null` when the caller does not know. Anything but a REALITY request
     *   yields UNKNOWN.
     */
    fun detailFrom(entry: MeshNodeEntry, config: VpnConfig, mode: String? = null): NodeDetail = NodeDetail(
        id = entry.id,
        endpoint = config.endpoint,
        publicKey = config.publicKey,
        realitySupport = realitySupportFrom(config, mode),
        config = config,
    )

    /**
     * REALITY capability, as far as [config] actually establishes it.
     *
     * Single source of truth for the projection, so the connect path and the
     * capability probe can never disagree about what a given response means.
     */
    fun realitySupportFrom(config: VpnConfig, requestedMode: String?): RealitySupport =
        when {
            config.realityConfig != null -> RealitySupport.SUPPORTED
            requestedMode == REALITY_PROBE_MODE -> RealitySupport.UNSUPPORTED
            else -> RealitySupport.UNKNOWN
        }

    /**
     * Fetches stage-2 detail for [entry] from `GET /api/v1/config/:server_id`.
     *
     * [mode] must be the transport the caller actually intends to use — in
     * practice [REALITY_PROBE_MODE] when this is being called to establish
     * capability. It is forwarded verbatim to the control plane, so it is both
     * the question asked and, because the answer's shape depends on it, part of
     * how the answer must be read. See [detailFrom].
     *
     * A failed probe stays [NodeDetailResult.Failed] and the entry stays
     * [RealitySupport.UNKNOWN]. It is never converted into
     * [RealitySupport.UNSUPPORTED]: "the question did not come back" and "the
     * node said no" are different answers, and only the second one may narrow
     * the transport ladder.
     *
     * [tokenProvider] is invoked lazily and only on the unauthorized path, so a
     * normal call costs exactly one request. The refresh-then-retry is
     * behaviour, not policy: without it an expired session turns into a dead
     * node that the user cannot recover from without signing in again.
     */
    suspend fun resolve(
        apiClient: ApiClient,
        entry: MeshNodeEntry,
        mode: String?,
        devicePublicKey: String,
        tokenProvider: suspend () -> String?,
        onTokenRefreshed: (String) -> Unit,
    ): NodeDetailResult = try {
        val config = runCatching { fetch(apiClient, entry, devicePublicKey, mode) }
            .getOrElse { first ->
                // Only an expired session is worth a second attempt, and only
                // with a token we have not already tried.
                if (!first.isExpiredSession()) throw first
                val refreshed = tokenProvider()
                    ?: throw first
                onTokenRefreshed(refreshed)
                apiClient.setAuthToken(refreshed)
                fetch(apiClient, entry, devicePublicKey, mode)
            }
        NodeDetailResult.Resolved(detailFrom(entry, config, mode))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        NodeDetailResult.Failed(
            failure = NodeLoadFailureClassifier.classify(e),
            detail = e.message.orEmpty(),
        )
    }

    private suspend fun fetch(
        apiClient: ApiClient,
        entry: MeshNodeEntry,
        devicePublicKey: String,
        mode: String?,
    ): VpnConfig = apiClient.getConfig(entry.id, devicePublicKey, mode)

    private fun Throwable.isExpiredSession(): Boolean {
        if (this is uniffi.shadowmesh.ShadowMeshException.Unauthorized) return true
        val text = causeChain().joinToString(" ") { it.message.orEmpty() }.lowercase()
        return "expiredsignature" in text || "token expired" in text || "unauthorized" in text
    }
}

/**
 * Builds the [VpnNode] the Rust `VpnManager.initiate_connection` still requires.
 *
 * ### Why this exists, and when it should die
 *
 * `initiate_connection(node: VPNNode, _device_public_key: String)` reads exactly
 * one field off the node — `region`, to pick a mode for high-risk geographies —
 * and then discards the node entirely. Nothing is dialled from it: the endpoint,
 * the peer key and the tunnel address all come from the authenticated
 * [VpnConfig]. The signature has not been narrowed to match, so this function
 * exists to satisfy it.
 *
 * Every field below is either copied from stage 1, copied from stage 2, or is a
 * statement that we do not have the value:
 *
 *  - `endpoint` / `publicKey` — from the authenticated config. Never defaulted.
 *    A connect with no key cannot be dialled, so there is no safe value to
 *    invent and none is offered.
 *  - `name` — derived from the geography the server published, for the audit
 *    log line. Display only.
 *  - `load` / `latency` — `0u`, meaning "not published". The core does not read
 *    them here, and the status screen already prefers the latency actually
 *    measured by the post-connect network report over an advertised figure.
 *  - `isSovereign` — `false`, because the directory does not publish it and the
 *    sovereign badge is a hardware-attestation claim (RFC-002) that must not be
 *    asserted from a geography projection.
 *
 * When the core's node projection is split into a directory type, this collapses
 * into a field-for-field copy and should be deleted rather than kept.
 */
object NodeAssembler {

    fun forRust(entry: MeshNodeEntry, detail: NodeDetail): VpnNode = VpnNode(
        id = entry.id,
        name = entry.displayName,
        region = entry.region,
        country = entry.country,
        endpoint = detail.endpoint,
        publicKey = detail.publicKey,
        load = 0u,
        latency = 0u,
        isSovereign = false,
        isOnline = entry.isOnline,
        shardId = null,
    )
}