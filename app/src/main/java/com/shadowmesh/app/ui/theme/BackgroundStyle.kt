package com.shadowmesh.app.ui.theme

/**
 * The closed set of background styles the app can draw.
 *
 * ## The defect this locks down
 *
 * Settings offered six styles, but the renderer switched on a raw string with
 * only four branches and no `else`. "Quantum Grid" and "Neon Midnight" therefore
 * matched nothing, the Canvas drew no decoration at all, and the background
 * silently looked unchanged — reported as "the background style does not
 * work sometimes". It worked for four of six options.
 *
 * Making the set a closed enum means a style can no longer be offered without
 * being drawable: [fromName] always resolves, and the renderer switches over the
 * enum exhaustively so the compiler rejects a new style with no artwork.
 */
enum class BackgroundStyle {
    CYBER_NEBULA,
    OBSIDIAN_STEALTH,
    DEEP_SPACE,
    ELECTRIC_HORIZON,
    QUANTUM_GRID,
    NEON_MIDNIGHT,
    CYBERPUNK,
    NEO_BRUTALIST,
    MINIMALIST,
    ;

    /**
     * Whether this style must render with the LOW effect budget.
     *
     * Minimalist is not just another wallpaper: it is the deliberate
     * low-memory path. The measured cost of the decorative styles is dominated
     * by offscreen render targets -- each `Modifier.blur` and each
     * `shadowMeshGlass` shadow allocates one -- which together accounted for
     * ~300 MB of purgeable GPU memory on a mid-range Android 15 device. A style
     * that promises to be cheap has to actually be allowed to drop those.
     */
    val forcesLowProfile: Boolean
        get() = this == MINIMALIST

    /** The label Settings shows and the value persisted in preferences. */
    val displayName: String
        get() = when (this) {
            CYBER_NEBULA -> "Cyber Nebula"
            OBSIDIAN_STEALTH -> "Obsidian Stealth"
            DEEP_SPACE -> "Deep Space"
            ELECTRIC_HORIZON -> "Electric Horizon"
            QUANTUM_GRID -> "Quantum Grid"
            NEON_MIDNIGHT -> "Neon Midnight"
            CYBERPUNK -> "Cyberpunk"
            NEO_BRUTALIST -> "Neo Brutalist"
            MINIMALIST -> "Minimalist"
        }

    companion object {
        /** The style applied on a fresh install and used as the fallback. */
        val DEFAULT: BackgroundStyle = CYBER_NEBULA

        /** Every style name exactly as Settings labels it. */
        val DISPLAY_NAMES: List<String> = listOf(
            "Cyber Nebula",
            "Obsidian Stealth",
            "Deep Space",
            "Electric Horizon",
            "Quantum Grid",
            "Neon Midnight",
            "Cyberpunk",
            "Neo Brutalist",
            "Minimalist",
        )

        /**
         * Resolves a persisted label to a drawable style.
         *
         * Unknown or stale values resolve to [DEFAULT] rather than to "nothing",
         * which is the whole point: a renamed or removed style must degrade to
         * a sensible look, never to a blank canvas.
         */
        fun fromName(name: String?): BackgroundStyle {
            val trimmed = name?.trim().orEmpty()
            if (trimmed.isEmpty()) return DEFAULT
            val normalised = trimmed.replace(Regex("\\s+"), " ").lowercase()
            return entries.firstOrNull { it.displayName.lowercase() == normalised } ?: DEFAULT
        }

    }
}
