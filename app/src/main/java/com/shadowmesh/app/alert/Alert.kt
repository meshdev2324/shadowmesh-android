package com.shadowmesh.app.alert

import com.shadowmesh.ui_kit.a11y.AccessibilityLabels
import java.util.UUID

/**
 * How urgently an alert needs the user's attention. Ordered so a host can
 * pick the most urgent alert with a simple max-by.
 */
enum class AlertSeverity {
    INFO,
    WARNING,
    CRITICAL,
    ;

    /** True when the alert blocks whatever flow is running until dismissed. */
    val isBlocking: Boolean get() = this != INFO
}

/**
 * Closed tag set naming the subsystem that raised an alert. Every producer
 * must use the tag of its own subsystem; the repository coalesces on it, so a
 * subsystem can never stack contradictory alerts against itself.
 */
enum class AlertTag {
    ACTIVATION,
    TUNNEL,
    SESSION,
    NETWORK,
    PAIRING,
    MFA,
    SECURITY,
}

/**
 * One user-visible alert. Immutable and pre-sanitized: the constructor is
 * private and every alert goes through [create], which scrubs the message
 * before the alert ever exists. Alert text is user-facing state, and state
 * must be clean at construction, not at render.
 *
 * @param message already-scrubbed, user-readable text. Never embed IPs,
 *   endpoints, tokens or identifiers — [AlertSanitizer] strips them.
 */
data class Alert private constructor(
    val id: UUID,
    val severity: AlertSeverity,
    val tag: AlertTag,
    val message: String,
    val sticky: Boolean,
) {
    companion object {
        /**
         * The only way to build an [Alert]. Sticky alerts survive navigation
         * and screen changes until the user (or the producing flow) dismisses
         * them; non-sticky alerts auto-expire.
         */
        fun create(
            severity: AlertSeverity,
            tag: AlertTag,
            message: String,
            sticky: Boolean = false,
        ): Alert = Alert(
            id = UUID.randomUUID(),
            severity = severity,
            tag = tag,
            message = AlertSanitizer.scrub(message).ifBlank { "Something went wrong." },
            sticky = sticky,
        )
    }
}

/**
 * Strips forensic value from alert text. `e.localizedMessage` and server
 * messages routinely carry IPs, endpoints, and tokens; the old string field
 * put them straight on screen (and intoFLAG_SECURE-exempt screenshots when
 * the user enabled them). Replacement markers preserve the alert's meaning
 * without its identifying content.
 *
 * The patterns now live in `com.shadowmesh.ui_kit.a11y.AccessibilityLabels` and
 * are delegated to. The accessibility channel — `contentDescription`, read aloud
 * by TalkBack and frequently routed off-device — was carrying the same class of
 * forensic string and had no scrubber at all (E-3). Keeping one implementation
 * means widening the guarantee to labels is the same change that widens it here,
 * rather than two regex sets left to drift.
 */
object AlertSanitizer {

    fun scrub(raw: String): String = AccessibilityLabels.scrubForensic(raw)
}
