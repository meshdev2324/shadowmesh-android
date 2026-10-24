package com.shadowmesh.app

/**
 * The pure description of the capture a connect attempt installs.
 *
 * RFC-048 §4.3 rules this shape:
 *
 * > `VpnService.Builder` cannot be constructed off-device, so a JVM test cannot
 * > assert on it directly. **Ruling: the capture specification is extracted into
 * > a pure value, and the act of installing it is behind one injectable seam.**
 * >
 * > ```
 * > TunnelCaptureSpec(address, prefixLength, route, routePrefixLength, resolver)
 * >     · capturesTraffic = route != null
 * >     · redirectsDns    = resolver != null
 * >
 * > CaptureInstaller(spec) -> ParcelFileDescriptor?      ← the ONLY call to establish()
 * > ```
 *
 * This is the same shape the tree already uses for `parseTunnelAddress` and
 * `isUsableDnsLiteral` (`MeshVpnService.kt:36`, `:59`): pure functions over
 * plain values, unit-testable without a device.
 *
 * Two properties of the value are load-bearing, and both are asserted by
 * `CaptureOrderingTest`:
 *
 * 1. The resolver is **the config's** literal. It is never a literal written in
 *    this file. The in-tunnel resolver is `10.0.0.53`
 *    (`services/server/src/api/handlers/vpn.rs:635`), and it is answerable only
 *    once the tunnel actually carries traffic — which is why a capture that
 *    points at it *before* the transport is proven is not "protected", it is
 *    broken (RFC-048 §6, item 5).
 * 2. The route is `0.0.0.0/0`. The capture takes traffic away from the
 *    underlying network, so the spec says so explicitly rather than by
 *    omission.
 */
data class TunnelCaptureSpec(
    /** The tunnel address handed to the interface. */
    val address: String,
    /** The prefix length of [address]. */
    val prefixLength: Int,
    /** The route the capture takes over, or null for no traffic capture. */
    val route: String? = null,
    /** The prefix length of [route]. */
    val routePrefixLength: Int = 0,
    /** The resolver the capture points the device at, or null for no DNS redirect. */
    val resolver: String? = null,
) {
    /** Whether this capture takes traffic away from the underlying network. */
    val capturesTraffic: Boolean get() = route != null

    /** Whether this capture points the device's resolver at the tunnel. */
    val redirectsDns: Boolean get() = resolver != null
}
