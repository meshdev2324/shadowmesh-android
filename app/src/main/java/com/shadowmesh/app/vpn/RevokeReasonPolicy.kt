package com.shadowmesh.app.vpn

/**
 * Why an established tunnel ended without the user asking.
 *
 * Android permits exactly one active VPN per device. When a second VPN app is
 * launched, the platform calls [android.net.VpnService.onRevoke] on ours and
 * tears the tunnel down. Users reported this as "the VPN stops by itself",
 * because nothing in the UI ever explained it — the tunnel simply went quiet.
 *
 * It is not a fault in the tunnel; it is a platform constraint. The defect is
 * failing to say so, and failing to distinguish it from the case where the
 * user actually withdrew permission in Settings. Both arrive through the same
 * callback, so the two are told apart by whether the app still holds VPN
 * consent.
 */
enum class DisconnectReason {
    /** The user asked for it. No message needed. */
    USER_REQUESTED,

    /** Another VPN app claimed the device's single VPN slot. */
    ANOTHER_VPN_TOOK_OVER,

    /** The user withdrew VPN permission in system settings. */
    PERMISSION_REVOKED,

    /** A transport-level failure; see the connection error for detail. */
    TRANSPORT_FAILURE,
}

/**
 * Resolves the reason for a platform-initiated revoke.
 *
 * Pure, so the rule is unit-testable without a device.
 */
object RevokeReasonPolicy {

    /**
     * @param stillHasVpnConsent false when [android.net.VpnService.prepare]
     *   returns an intent, meaning the user removed ShadowMesh from the allowed
     *   VPN apps list. True means consent is intact and the slot was taken by a
     *   competing VPN app.
     */
    fun forRevoke(stillHasVpnConsent: Boolean): DisconnectReason =
        if (stillHasVpnConsent) {
            DisconnectReason.ANOTHER_VPN_TOOK_OVER
        } else {
            DisconnectReason.PERMISSION_REVOKED
        }

    /**
     * User-facing explanation. Empty for a deliberate disconnect, so the UI can
     * stay silent in the common case and speak only when something
     * unexpected happened.
     */
    fun message(reason: DisconnectReason): String =
        when (reason) {
            DisconnectReason.USER_REQUESTED -> ""
            DisconnectReason.ANOTHER_VPN_TOOK_OVER ->
                "Disconnected — another VPN app took over. Android allows only one VPN at a time."
            DisconnectReason.PERMISSION_REVOKED ->
                "Disconnected — VPN permission was removed in Android settings."
            DisconnectReason.TRANSPORT_FAILURE -> "Connection lost."
        }
}
