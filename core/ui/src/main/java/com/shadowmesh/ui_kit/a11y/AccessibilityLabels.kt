package com.shadowmesh.ui_kit.a11y

/**
 * The accessibility-label guarantee.
 *
 * ## E-3 — what this closes
 *
 * `SettingsNavRow` built its `contentDescription` by interpolating its `label`
 * parameter, and `StatusScreen` passed `uiState.selectedNode?.displayName` as
 * that label. TalkBack therefore said **"Singapore. Connected."** out loud.
 * `ConnectionDetailItem` did the same with the assigned MESH IP.
 *
 * The structural observation is that the house already had an answer for *this
 * class of leak* and had it in only one place. `AlertSanitizer` strips IPv4,
 * URLs, UUIDs and token-shaped blobs out of alert text before it reaches the
 * screen. There was **no equivalent for accessibility labels** — and a screen
 * reader is a machine-readable channel that outlives the glance, is often
 * routed to a third-party TTS service, and is captured by screen recording in
 * ways a sighted glance is not.
 *
 * ## Why this extends `AlertSanitizer` rather than introducing a sibling
 *
 * Two reasons, and the second is the load-bearing one.
 *
 * **1. Direction.** `AlertSanitizer` lives in `app`, which depends on
 * `:core:ui`. The components that needed fixing live in `core/ui`, which cannot
 * import upward. Duplicating the patterns there would have produced two
 * regex sets to keep in step. Instead the single implementation lives here, and
 * `AlertSanitizer` delegates to it — so widening the guarantee to labels is
 * literally the same code widening it for alerts.
 *
 * **2. A regex cannot fix the peer-name case, and pretending otherwise would
 * have been the real mistake.** `AlertSanitizer` works because the things it
 * removes are *forensic strings that arrived inside free text* — `e.localizedMessage`,
 * server strings — and they have recognisable shapes. A peer display name is
 * not that: "Singapore" is a legitimate product string with no tell. No regex
 * removes it without destroying every legitimate label too.
 *
 * So the pattern scrubber below is **defence in depth, not the mechanism**. The
 * mechanism is structural: a component cannot speak a caller's identity string
 * unless the caller hands it one *as an accessibility label*, and every call
 * site is forced to say explicitly what a screen reader is allowed to know. The
 * unit is field x observer x boundary — the same status fact has different
 * sensitivity at different boundaries — and a peer name is simply not among the
 * facts a status row may broadcast.
 *
 * ## What stays
 *
 * Peer identity **is** retained on the node selection screen, which is the
 * user's explicit act of choosing a node and whose entire purpose is to name
 * them. That follows the boundary the server already drew: `models/vpn.rs`
 * withholds fields "absent rather than zeroed" for the same reason — the
 * disclosure decision belongs at the boundary that chose to expose the field,
 * not at every consumer downstream.
 */
object AccessibilityLabels {

    private val URL = Regex(
        pattern = """\b[a-z][a-z0-9+.-]*://\S+""",
        option = RegexOption.IGNORE_CASE,
    )
    private val IPV4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}(?::\d+)?\b""")
    private val UUID_LIKE = Regex(
        """\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""",
    )
    private val HEX_BLOB = Regex("""\b[0-9a-fA-F]{16,}\b""")
    private val BASE64_BLOB = Regex("""\b[A-Za-z0-9+/]{24,}={0,2}\b""")

    /**
     * Strips forensic shapes from a string that is about to be *spoken*.
     *
     * Shared verbatim with `AlertSanitizer` (which delegates here) so the two
     * channels cannot drift. Used on accessibility labels as a backstop for a
     * value that reached one through a path nobody reviewed — not as the primary
     * defence, which is that peers are never passed in.
     */
    fun scrubForensic(raw: String): String = raw
        .replace(URL, "[endpoint removed]")
        .replace(IPV4, "[address removed]")
        .replace(UUID_LIKE, "[id removed]")
        .replace(HEX_BLOB, "[token removed]")
        .replace(BASE64_BLOB, "[token removed]")

    /**
     * Builds a peer-free description from two parts the caller has already
     * committed to being safe.
     *
     * [subject] is *what* the thing is ("MESH IP", "ENTRY NODE"); [state] is
     * *whether* it is in some condition ("Connected", "Not assigned"). Both are
     * scrubbed on the way through, so a caller that passes a value carrying an
     * address or an endpoint still gets a label with the address removed — the
     * failure degrades to "a little less useful", never to "a disclosure".
     */
    fun describe(subject: String, state: String? = null): String {
        val cleanSubject = scrubForensic(subject).trim()
        val cleanState = state?.let { scrubForensic(it).trim() }.orEmpty()
        return listOf(cleanSubject, cleanState)
            .filter { it.isNotEmpty() }
            .joinToString(". ")
            .ifBlank { "Unlabelled control." }
    }
}