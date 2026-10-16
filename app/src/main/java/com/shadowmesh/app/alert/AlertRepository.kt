package com.shadowmesh.app.alert

import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Qualifies the application-lifetime [CoroutineScope] used for work that must
 * outlive any ViewModel, such as alert auto-expiry.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Single channel for user-visible alerts (RFC-028).
 *
 * Replaces the untyped `errorMessage: String?` on [VPNUiState], which 37
 * call-sites wrote and 6 screens each rendered with its own severity,
 * lifetime and styling — and where one screen's transient toast could
 * overwrite another subsystem's critical failure.
 *
 * Deterministic state rules:
 * - One live alert per [AlertTag]: a subsystem's new alert replaces its own
 *   previous one rather than stacking.
 * - The host renders the highest-severity alert, newest first on ties.
 * - Non-sticky alerts auto-expire after [AutoDismissMillis]; sticky alerts
 *   remain until dismissed by the user or the producing flow.
 * - Every message is sanitized at construction (see [AlertSanitizer]).
 */
@Singleton
class AlertRepository @Inject constructor(
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())

    /** Currently live alerts, newest first. Render the first non-empty max-by severity. */
    val alerts: StateFlow<List<Alert>> = _alerts.asStateFlow()

    /**
     * Posts an alert, replacing any previous alert with the same tag.
     *
     * @param sticky keep the alert until explicitly dismissed, instead of
     *   auto-expiring. Only producers that require a user decision should set
     *   this; everything else must stay transient.
     */
    fun post(
        severity: AlertSeverity,
        tag: AlertTag,
        message: String,
        sticky: Boolean = false,
    ) {
        val alert = Alert.create(severity, tag, message, sticky)
        _alerts.update { current ->
            listOf(alert) + current.filterNot { it.tag == tag }
        }
        scheduleExpiry(alert)
    }

    /** Removes one alert by id; a no-op when it already expired. */
    fun dismiss(id: java.util.UUID) {
        _alerts.update { current -> current.filterNot { it.id == id } }
    }

    /** Removes every live alert for [tag]. */
    fun dismissTag(tag: AlertTag) {
        _alerts.update { current -> current.filterNot { it.tag == tag } }
    }

    /** Removes all transient (non-sticky) alerts. Used by generic "clear" intents. */
    fun dismissTransient() {
        _alerts.update { current -> current.filter { it.sticky } }
    }

    private fun scheduleExpiry(alert: Alert) {
        if (alert.sticky) return
        scope.launch {
            delay(AutoDismissMillis)
            dismiss(alert.id)
        }
    }

    private companion object {
        /** Long enough to read a sentence; short enough not to linger. */
        const val AutoDismissMillis = 4_000L
    }
}
