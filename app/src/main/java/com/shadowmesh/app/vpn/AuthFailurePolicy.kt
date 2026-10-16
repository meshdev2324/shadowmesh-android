package com.shadowmesh.app.vpn

/**
 * How the app responds to a refused request from the control plane.
 *
 * # The failure this exists to prevent
 *
 * The app treated an HTTP 401 on any request - including a routine heartbeat -
 * as proof that the user's subscription had ended, and responded by deleting
 * the stored activation code. The control plane was still holding the device as
 * `active` and not compromised. The user, paying and connected, was returned to
 * the login screen, and the only way back was to retype a code the server
 * would have accepted all along.
 *
 * That is the second time this project produced a lockout by letting the client
 * infer revocation from a signal that did not carry it. The first was the root
 * screen gating on `isActivated` while a tunnel was still live.
 *
 * # The rule
 *
> A refused request is a fact about a request, not about a subscription. The
 * control plane is the only authority on whether a session is revoked, so the
 * client asks it instead of concluding anything.
 *
 * The ordering matters as much as the decision: the tunnel comes down first
 * either way, because an unverified session must not keep forwarding traffic.
 */
enum class AuthFailureAction {
    /** Session is still valid. Reconnect without touching the activation. */
    RETRY,

    /** Control plane confirmed the session is gone. Sign in again. */
    SIGN_IN_AGAIN,
}

data class AuthFailureDecision(
    val action: AuthFailureAction,
    /** Whether the locally stored activation must survive. */
    val keepActivation: Boolean,
    /** Whether to tear the tunnel down before anything else. Always true. */
    val disconnectFirst: Boolean,
    val message: String,
)

object AuthFailurePolicy {

    /**
     * @param controlPlaneSaysActive whether the control plane still reports the
     *   session as active. `null` means the check itself failed, which is
     *   treated as "do not destroy anything".
     */
    fun decide(controlPlaneSaysActive: Boolean?): AuthFailureDecision = when (controlPlaneSaysActive) {
        // Verified as still active: keep the code, reconnect, say nothing about
        // expiry.
        true -> AuthFailureDecision(
            action = AuthFailureAction.RETRY,
            keepActivation = true,
            disconnectFirst = true,
            message = "Connection interrupted. Reconnecting...",
        )

        // Confirmed gone: the user must sign in, and only now may the stored
        // activation be cleared.
        false -> AuthFailureDecision(
            action = AuthFailureAction.SIGN_IN_AGAIN,
            keepActivation = false,
            disconnectFirst = true,
            message = "Your session ended. Sign in again to continue.",
        )

        // Could not determine. Destroying a possibly-valid activation because a
        // status query failed would repeat the original bug with a different
        // trigger.
        null -> AuthFailureDecision(
            action = AuthFailureAction.RETRY,
            keepActivation = true,
            disconnectFirst = true,
            message = "Connection interrupted. Reconnecting...",
        )
    }
}
