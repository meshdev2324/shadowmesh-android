package com.shadowmesh.app.ui.screens

import com.shadowmesh.ui_kit.components.NetworkHealthBadge
import uniffi.shadowmesh.NetworkReport

/**
 * The state of the network-health trust badge shown on the status screen.
 *
 * ## The defect this locks down
 *
 * The badge rendered "SECURE" only when a `NetworkReport` happened to be present,
 * and the report was populated *exclusively* by the manual "run diagnostics"
 * button in Settings. It was never produced on connect or at startup. The
 * consequence was a trust indicator that was silent by default — users saw no
 * badge at all for most of a session, and it appeared only if they had happened
 * to open Settings and run a scan. It also never reflected tunnel state, so a
 * stale "SECURE" could outlive a disconnect.
 *
 * A security badge must always be present and must never overstate the
 * protection actually in force.
 */
/**
 * Resolves the badge state. Pure so the trust rules are unit-testable without
 * a device, because "is the app telling the truth about my security" must not
 * be a display detail.
 */
object NetworkHealthPolicy {

    fun resolve(isConnected: Boolean, isDetecting: Boolean, report: NetworkReport?): NetworkHealthBadge {
        // Detection results are about the current network. With no tunnel they
        // say nothing about whether the user is protected, so they must not be
        // promoted to a "secure" claim.
        if (!isConnected) return NetworkHealthBadge.OFFLINE
        if (report == null) return NetworkHealthBadge.CHECKING
        if (report.dpiDetected?.toInt() == 1) return NetworkHealthBadge.DPI_ALERT
        if (report.captivePortal?.toInt() == 1) return NetworkHealthBadge.PORTAL_DETECTED
        return NetworkHealthBadge.SECURE
    }
}
