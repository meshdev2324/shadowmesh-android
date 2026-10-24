package com.shadowmesh.app

/**
 * The connect attempt's capture handling, as one ordered unit.
 *
 * # Why this exists
 *
 * F43 is an **ordering** defect, and an ordering defect cannot be fixed by
 * changing a line — it has to be fixed by putting the order somewhere it can
 * be *seen*. Today the order is smeared across `MeshVpnService.handleConnect`:
 *
 * | `MeshVpnService.kt` | what happens | capture live? | transport proven? |
 * | :--- | :--- | :--- | :--- |
 * | `:479-481` | `addAddress` · `addDnsServer(tunnelDns)` · `addRoute("0.0.0.0", 0)` | — | no |
 * | **`:539`** | **`builder.establish()`** — the capture takes effect | **yes** | **no** |
 * | `:628` | `startVpnEngine(fd, config)` — the handshake happens here | yes | no |
 * | `:638` | `awaitTransportProof(lease)` — the proof, finally | yes | ← the proof |
 * | `:718-719` | on proof timeout: `lease.release()` + `handleDisconnect()` | torn down | no |
 *
 * ⇒ Between `:539` and `:638` the device holds a catch-all route and a resolver
 * override pointed at an address that **cannot answer** — `10.0.0.53` is the
 * *in-tunnel* resolver, so it is unanswerable until the tunnel carries a
 * packet, which cannot happen before the transport is proven — for up to
 * `HANDSHAKE_TIMEOUT_MS` (15 s) + `PROOF_TIMEOUT_MS` (8 s) ≈ 23 s. The retry
 * ladder's own control-plane calls go out over that dead resolver.
 *
 * This class is that order, in one place, with the two effects that can be
 * observed off-device injected: the **specification** and the **installer**.
 *
 * # The two handles
 *
 * The frozen contract (RFC-048 §3.1–§3.3) has **two** of them, joined in
 * PHASE 3:
 *
 * ```
 * PHASE 1  connectTransport(config) -> generation      ← the session
 * PHASE 2  establish()               -> descriptor      ← the capture
 * PHASE 3  attachTun(generation, descriptor)            ← BOTH
 * ```
 *
 * So this class carries two type parameters:
 *
 * * **[H] is the GENERATION TOKEN** — the identifier of the *session* the
 *   attempt owns, not of a transport instance (RFC-048 §3.3). It is what
 *   `close_transport` is scoped to, and it is what makes "the session this
 *   attempt started" distinguishable from "the session that is live now".
 * * **[F] is the DESCRIPTOR** — the `ParcelFileDescriptor` the capture
 *   installer hands back. Production's `F` is a `ParcelFileDescriptor`; the
 *   tests use a stand-in, because the platform's descriptor cannot be
 *   constructed off-device.
 *
 * ⚠️ **The generation token did not exist here before this change.** The seam
 * used to have ONE handle type, and it was the installer's descriptor — so the
 * seam had no vocabulary at all for a session, and §7.2's tests 6, 7 and 9
 * (the ones that need a per-attempt session handle) could not be written.
 *
 * # The phase split
 *
 * The seam's steps are the contract's phases, one parameter each:
 *
 * | phase | parameter | what it is in production |
 * | :--- | :--- | :--- |
 * | PHASE 0b | [supersede] | release the previous attempt's lease |
 * | PHASE 1 | [connectTransport] | `connect_transport(config)` → the generation |
 * | PHASE 2 | [specFor] + [installer] | `establish()` → the descriptor |
 * | PHASE 3 | [attachTun] | `attach_tun(generation, fd)` |
 * | PHASE 4 | [awaitProof] | `awaitTransportProof(lease)` |
 *
 * ⚠️ **The seam's EXECUTED order is the FIXED order.** `run()` proves the
 * transport before the capture is specified, built or installed, so the
 * device does not hold a catch-all route and an unanswerable resolver while
 * the handshake is in flight: `CaptureOrderingTest`'s pre-fix reds (the
 * spec/`establish` counts and the install-failure close) are GREEN against
 * the tree as it stands, and RFC-049 RULING F102-8's red-proof is closed.
 *
 * ⇒ **The reorder was ONE BLOCK.** The generation-carrying bindings
 * (`connect_transport(config)` / `attach_tun(handle, fd)` /
 * `close_transport(handle)`) are in the generated Kotlin surface, so the
 * block
 *
 * ```
 * val f = installer.install(specFor())
 * ```
 *
 * sits between [connectTransport] and [attachTun], and the failure paths
 * follow the session's lifetime: a transport that never came up owns nothing,
 * a capture that will not install owns the session it must close, and an
 * attached-or-proven tunnel owns it until it closes it itself.
 *
 * ⚠️ **Why it is blocked.** The FFI fuses connect and attach into one call
 * that takes the descriptor UP FRONT —
 * `start_vpn_engine(i32 fd, VPNConfig config)`
 * (`libs/core-shared/shadowmesh-core/src/shadowmesh.udl:637`) — so the
 * handshake cannot be proved without a descriptor, and the descriptor only
 * exists after `establish()`. The two-phase surface is what breaks that knot,
 * and it is not in the generated bindings yet (RFC-048 §1.3, route 1).
 *
 * # The three rules this class keeps
 *
 * 1. **The supersede runs BEFORE the transport is proven** (§4.2). The
 *    previous attempt's capture is released first, so the whole connect
 *    sequence — from the handshake onward — runs with no capture installed.
 * 2. **The teardown is the SCOPED close, never the global stop** (§4.5). A
 *    per-attempt failure closes *its own* session by generation; the global
 *    `stopVpnEngine()` closes whichever session the core currently holds,
 *    which is how one attempt's failure becomes every attempt's failure.
 * 3. **A successful connect supersedes; a failed one does not** (§3.2). The
 *    asymmetry is the core's, and the client half of it is that this class
 *    never closes a session it does not own.
 *
 * # What is deliberately NOT here
 *
 * There is no global stop in this class, and that is deliberate: a per-attempt
 * teardown that reaches the global stop is the hazard §4.5 rules out. The
 * production wiring of the global stop belongs to the three service-scoped
 * sites (`ACTION_DISCONNECT`, `onRevoke`, `onDestroy`) and to nothing else.
 */
class TunnelCaptureCoordinator<H, F>(
    /**
     * Builds the capture specification. Called exactly once per attempt — and
     * only once the transport is proven (§4.1: the decision to capture must
     * not be made before the attempt owns a proven session).
     */
    private val specFor: () -> TunnelCaptureSpec,
    /** The only seam through which the capture is installed. */
    private val installer: CaptureInstaller<F>,
    /**
     * Releases the **previous** attempt's capture (RFC-048 §4.2).
     *
     * It runs before the transport is proven, so that the whole connect
     * sequence from the handshake onward runs with no capture installed.
     */
    private val supersede: () -> Unit,
    /**
     * **PHASE 1 — prove the transport.**
     *
     * Starts the transport and returns the **generation token** of the session
     * it opened, or null when the transport did not come up.
     *
     * ⚠️ In production this is `connect_transport(config)`, which takes **no
     * descriptor**: the handshake does not need the tun, and that is the
     * whole point of the split (RFC-048 §1.2 — the core already connects
     * before it spawns the tun loop).
     */
    private val connectTransport: () -> H?,
    /**
     * **PHASE 3 — attach the descriptor to the proven session.**
     *
     * False means the attach failed. The failure is a distinct state from
     * "the transport did not come up": the session EXISTS, so it has to be
     * closed by its generation.
     *
     * ⚠️ In production this is `attach_tun(generation, fd)`, and the binding
     * **throws** on a stale generation or a second tun. The caller wraps it
     * and reports the failure as `false` — the seam carries no exception
     * type, because a teardown path must not raise (RFC-048 §3.2).
     */
    private val attachTun: (H, F) -> Boolean,
    /**
     * Waits for the tunnel to prove itself (PHASE 4). False means the proof
     * timed out.
     *
     * ⚠️ This is a **distinct state** from "the transport did not come up",
     * and it has to be: the two failures need different teardowns. A transport
     * that never came up owns no session to close; a tunnel that came up and
     * then proved nothing owns one, and closing it is what stops a session
     * staying live on the node.
     */
    private val awaitProof: (H) -> Boolean,
    /**
     * The **attempt-scoped** close (RFC-048 §3.2). It answers "did I own the
     * session?" — false for a stale generation, and it touches nothing then.
     *
     * ⚠️ Never the global `stopVpnEngine()`: see §4.5.
     */
    private val closeTransport: (H) -> Boolean,
    /**
     * Tears the attempt's **interface** down: the lease release.
     *
     * It is scoped to the attempt's own descriptor, for the same reason the
     * close is scoped to the attempt's own generation.
     */
    private val onFailure: (F) -> Unit,
) {
    /** The specification that was handed to the installer, if one was built. */
    var spec: TunnelCaptureSpec? = null
        private set

    /** The capture's descriptor, if the capture was installed. */
    var descriptor: F? = null
        private set

    /** The generation token of the session this attempt owns, if it got one. */
    var generation: H? = null
        private set

    /**
     * Runs the attempt, in the fixed order: supersede, transport, capture,
     * attach, proof.
     *
     * @return true if the transport came up AND the tunnel proved itself;
     *   false if the transport did not come up (nothing was built, installed
     *   or owned), the capture could not be installed (the session is closed
     *   by its generation), the attach failed, or the proof timed out.
     */
    fun run(): Boolean {
        // PHASE 0b — the previous attempt's capture goes first (§4.2), so the
        // whole connect sequence — from the handshake onward — runs with no
        // capture installed.
        supersede()

        // PHASE 1 — prove the transport. The handshake does not need the tun,
        // and that is the point: while it is in flight the device's DNS is
        // untouched.
        val h = connectTransport()
        if (h == null) {
            // The transport never came up: it owns no session, and NO capture
            // was specified, built or installed. There is nothing to tear
            // down and nothing to close (§7.2 tests 1 and 2).
            return false
        }
        generation = h

        // PHASE 2 — the capture is specified, built and installed — only now.
        val s = specFor()
        spec = s

        val f = installer.install(s)
        if (f == null) {
            // The transport was proven but the capture refused: the attempt
            // owns a live session it must not leak, so the scoped close (never
            // the global stop, §4.5) is the session's teardown (§7.2 test 6).
            closeTransport(h)
            return false
        }
        descriptor = f

        // PHASE 3 — attach the descriptor to the session that was just proven.
        if (!attachTun(h, f)) {
            // The session exists and the descriptor could not be joined to it:
            // the attempt owns a session now, and the scoped close is the only
            // teardown that may touch it.
            onFailure(f)
            closeTransport(h)
            return false
        }

        // PHASE 4 — prove the tunnel.
        if (!awaitProof(h)) {
            // The tunnel proved nothing. The attempt owns a session now, and
            // the scoped close is the only teardown that may touch it.
            onFailure(f)
            closeTransport(h)
            return false
        }

        return true
    }
}
