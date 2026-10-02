package com.shadowmesh.ui_kit.components

/**
 * UI-facing result of one in-tunnel leak check run.
 *
 * `null` fields mean "probe inconclusive" (network error, no route) — they
 * must render as unknown, never as a pass: an unmeasured claim on a security
 * surface is a lie.
 */
data class LeakCheckSummary(
    /** Public IPv4 the tunnel egresses from, for display. Never logged. */
    val egressIp: String?,
    /** Whether the egress address matches the selected node's endpoint host. */
    val egressMatchesNode: Boolean?,
    /** Whether IPv6 answered despite leak protection (true = leak). */
    val ipv6Leaked: Boolean?,
)
