package com.shadowmesh.app.ui.settings

/**
 * A split-tunnelling target, described without any Android framework types so
 * the visibility rules stay pure and unit-testable on the JVM.
 *
 * @param isPreinstalled true when the package ships in the system image
 *   (`ApplicationInfo.FLAG_SYSTEM`). This is a *distribution* fact, not a
 *   privilege level: on ColorOS, OriginOS and MIUI almost every user-facing app
 *   is preloaded, so treating preinstalled as "system app worth hiding" emptied
 *   the picker.
 * @param hasLauncher true when the package exposes a launcher activity, i.e. it
 *   is something a user can recognise and launch. Background-only services are
 *   filtered out as noise.
 */
data class SplitTunnelCandidate(
    val packageName: String,
    val label: String,
    val isPreinstalled: Boolean,
    val hasLauncher: Boolean,
) {
    /** Label if the package manager supplied one, otherwise the package name. */
    val displayLabel: String
        get() = label.ifBlank { packageName }
}

/**
 * Visibility and ordering rules for the split-tunnelling app picker.
 *
 * Kept free of Compose and of framework calls so the rules that decide what a
 * user is allowed to route can be tested directly, rather than only through
 * instrumentation.
 */
object SplitTunnelAppPolicy {

    /**
     * Packages that are never valid split-tunnelling targets, regardless of the
     * user's selection.
     *
     * The VPN client is excluded because routing the tunnel package through its
     * own tunnel is meaningless and can wedge the service.
     */
    fun visibleApps(
        apps: List<SplitTunnelCandidate>,
        includePreinstalled: Boolean,
        selfPackage: String,
    ): List<SplitTunnelCandidate> =
        apps.asSequence()
            .filter { it.packageName != selfPackage }
            .filter { it.hasLauncher }
            .filter { includePreinstalled || !it.isPreinstalled }
            .sortedWith(
                // User-installed apps first, then preinstalled; alphabetical
                // within each group so the list is predictable.
                compareBy<SplitTunnelCandidate> { it.isPreinstalled }
                    .thenBy { it.displayLabel.lowercase() }
                    .thenBy { it.packageName }
            )
            .toList()

    /** Case-insensitive match on either the display label or the package name. */
    fun search(apps: List<SplitTunnelCandidate>, query: String): List<SplitTunnelCandidate> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return apps
        return apps.filter {
            it.displayLabel.contains(trimmed, ignoreCase = true) ||
                it.packageName.contains(trimmed, ignoreCase = true)
        }
    }
}
