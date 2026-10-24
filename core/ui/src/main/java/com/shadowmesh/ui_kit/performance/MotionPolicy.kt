package com.shadowmesh.ui_kit.performance

/**
 * M-1 — the reduced-motion policy.
 *
 * ## What was missing
 *
 * Nothing in this app consulted the user's motion preference. `LiquidStatusOrb`
 * runs a three-layer aurora that rotates continuously and two pulse rings that
 * expand continuously, and both loops run while the screen is up — which, for
 * the one screen a user leaves open all day, is a sustained animation. WCAG
 * 2.3.3 (AAA, Animation from Interactions) and the platform "Remove animations"
 * accessibility setting both exist for this case, and neither was wired.
 *
 * ## Why this reads the platform scale rather than a Compose flag
 *
 * Compose's `LocalAccessibilityManager` exposes only
 * `calculateRecommendedTimeoutMillis` at the version this module builds
 * against (1.11.4) — there is no `isReduceMotionEnabled` to read, verified by
 * scanning the resolved `ui-android` artifact rather than assumed. The platform
 * scale is the actual signal the OS toggle writes, and it is available on every
 * API level this app supports.
 *
 * The mapping is a separate function precisely so it can be unit-tested on the
 * JVM: `ContentResolver` and `Settings.Global` are not available in a local
 * unit test, so a policy that could only be reached through Android APIs could
 * not be proven to do anything. `MotionPolicyTest` exercises the decision
 * directly.
 */
object MotionPolicy {

    /**
     * The system-wide animation scale Android writes when "Remove animations"
     * is on. `null` means the setting could not be read.
     *
     * A scale of exactly `0f` is the documented signal. Anything else — including
     * a scale an OEM has set to something unusual — is treated as "motion
     * allowed", because failing open on a *preference* keeps the product's
     * existing behaviour rather than silently flattening the whole interface
     * on a device whose setting happens to read oddly.
     */
    const val REMOVED_ANIMATIONS_SCALE = 0f

    /** The default when [Settings.Global.ANIMATOR_DURATION_SCALE] is unset. */
    const val DEFAULT_SCALE = 1f

    /** True when the user has asked the system to remove animation. */
    fun reduceMotionFor(animatorDurationScale: Float?): Boolean =
        animatorDurationScale != null && animatorDurationScale == REMOVED_ANIMATIONS_SCALE
}