package com.shadowmesh.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Split-tunnelling app picker visibility rules.
 *
 * ## The defect this locks down
 *
 * The picker filtered on `!app.isSystem` with preinstalled apps hidden by
 * default. `ApplicationInfo.FLAG_SYSTEM` is set for anything preloaded into the
 * system image, and on ColorOS / OriginOS / MIUI builds almost every app a user
 * actually cares about — browser, WhatsApp, Firefox — is preloaded. The list
 * therefore rendered as empty, with no error and no explanation, and the only way
 * to find an app was to discover the unlabelled filter toggle. Users reported the
 * picker as "not showing any packages".
 *
 * Preinstallation is a distribution fact, not a privilege. WireGuard's
 * `addDisallowedApplication` / `addAllowedApplication` work on any package, so
 * these apps are legitimate split-tunnelling targets and must be listed.
 */
class SplitTunnelAppPolicyTest {

    private fun candidate(
        pkg: String,
        label: String = pkg,
        preinstalled: Boolean = false,
        hasLauncher: Boolean = true,
    ) = SplitTunnelCandidate(pkg, label, preinstalled, hasLauncher)

    @Test
    fun `preinstalled apps are visible by default`() {
        val apps = listOf(
            candidate("com.android.chrome", "Chrome", preinstalled = true),
            candidate("com.example.userapp", "MyApp"),
        )

        val visible = SplitTunnelAppPolicy.visibleApps(apps, includePreinstalled = true, selfPackage = "com.shadowmesh.app")

        assertEquals(
            "a preloaded app must be listable - this was the reported bug",
            // Order is user-installed first, then preinstalled.
            listOf("com.example.userapp", "com.android.chrome"),
            visible.map { it.packageName },
        )
    }

    @Test
    fun `the vpn client itself is never offered as a target`() {
        val apps = listOf(
            candidate("com.shadowmesh.app", "ShadowMesh"),
            candidate("com.example.other", "Other"),
        )

        val visible = SplitTunnelAppPolicy.visibleApps(apps, includePreinstalled = true, selfPackage = "com.shadowmesh.app")

        assertFalse(
            "routing our own VPN package through our own tunnel is meaningless",
            visible.any { it.packageName == "com.shadowmesh.app" },
        )
        assertEquals(1, visible.size)
    }

    @Test
    fun `apps without a launcher entry are excluded as noise`() {
        val apps = listOf(
            candidate("com.example.background", "Sync Service", hasLauncher = false),
            candidate("com.example.launchable", "Real App"),
        )

        val visible = SplitTunnelAppPolicy.visibleApps(apps, includePreinstalled = true, selfPackage = "com.shadowmesh.app")

        assertEquals(listOf("com.example.launchable"), visible.map { it.packageName })
    }

    @Test
    fun `preinstalled apps can still be hidden when the user filters them out`() {
        val apps = listOf(
            candidate("com.android.chrome", "Chrome", preinstalled = true),
            candidate("com.example.userapp", "MyApp"),
        )

        val visible = SplitTunnelAppPolicy.visibleApps(apps, includePreinstalled = false, selfPackage = "com.shadowmesh.app")

        assertEquals(listOf("com.example.userapp"), visible.map { it.packageName })
    }

    @Test
    fun `user installed apps sort ahead of preinstalled ones`() {
        val apps = listOf(
            candidate("com.android.chrome", "Chrome", preinstalled = true),
            candidate("com.example.zeta", "Zeta"),
            candidate("com.android.settings", "Settings", preinstalled = true),
            candidate("com.example.alpha", "Alpha"),
        )

        val visible = SplitTunnelAppPolicy.visibleApps(apps, includePreinstalled = true, selfPackage = "com.shadowmesh.app")

        assertEquals(
            "user apps first, then preinstalled, each alphabetical",
            listOf("com.example.alpha", "com.example.zeta", "com.android.chrome", "com.android.settings"),
            visible.map { it.packageName },
        )
    }

    @Test
    fun `labels resolve even when the package manager returns a null label`() {
        // A package with no human-readable label is still selectable; falling
        // back to the package name is better than hiding it.
        val policy = SplitTunnelAppPolicy
        val apps = listOf(candidate("com.example.noname", ""))

        val visible = policy.visibleApps(apps, includePreinstalled = true, selfPackage = "com.shadowmesh.app")

        assertEquals(listOf("com.example.noname"), visible.map { it.packageName })
        assertTrue(visible.first().displayLabel.isNotBlank())
    }

    @Test
    fun `search matches label or package and is case insensitive`() {
        val apps = listOf(
            candidate("com.android.chrome", "Chrome"),
            candidate("com.example.firefox", "Firefox"),
            candidate("com.example.other", "Other"),
        )

        assertEquals(
            listOf("com.android.chrome"),
            SplitTunnelAppPolicy.search(apps, "chr").map { it.packageName },
        )
        assertEquals(
            listOf("com.example.firefox"),
            SplitTunnelAppPolicy.search(apps, "FIREFOX").map { it.packageName },
        )
        assertEquals(
            "an empty query must not hide the list",
            3,
            SplitTunnelAppPolicy.search(apps, "").size,
        )
    }
}
