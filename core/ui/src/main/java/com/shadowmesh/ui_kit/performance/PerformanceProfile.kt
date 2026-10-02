package com.shadowmesh.ui_kit.performance

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Defines the rendering and animation fidelity for the application.
 * Tiered system to balance visual depth with battery and performance.
 */
enum class PerformanceProfile {
    /** High fidelity: Full RenderEffect blurs, complex physics, high-freq animations. */
    HIGH,
    /** Balanced: Simplified blurs, standard physics. */
    MEDIUM,
    /** Low fidelity: Static backgrounds, translucency fallbacks, reduced motion. */
    LOW
}

/**
 * CompositionLocal to provide the current [PerformanceProfile] to UI components.
 */
val LocalPerformanceProfile = staticCompositionLocalOf { PerformanceProfile.HIGH }
