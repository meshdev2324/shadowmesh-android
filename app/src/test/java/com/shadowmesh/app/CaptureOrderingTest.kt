package com.shadowmesh.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F43 — the capture-ordering red-proof.
 *
 * The house pattern for this tree is to assert on the **mechanism**, not on the
 * UI (`TunInterfaceLeaseTest`, `TransportProofBudgetTest`). These follow it.
 *
 * # What is being pinned
 *
 * The defect is that the capture goes up **before** the transport is proven. On
 * the current tree (`MeshVpnService.kt:539` `builder.establish()` against
 * `:638` `awaitTransportProof`) the device spends up to ≈ 23 s holding a
 * catch-all route and a resolver override pointed at `10.0.0.53` — the
 * *in-tunnel* resolver, which is unanswerable until the tunnel carries a
 * packet, which cannot happen before the transport is proven. During that
 * window the device has no working DNS, and the retry ladder's own
 * control-plane calls go out over it.
 *
 * RFC-048 §4.1 rules the fixed order (supersede → prove → install → attach →
 * prove the tunnel). These tests are the client half of that ruling, and they
 * are **red-first**: the ordering half is red against the tree as it stands,
 * and it goes green when the reorder lands.
 *
 * # The seam
 *
 * `TunnelCaptureSpec` (the pure value) and `CaptureInstaller` (the only call
 * to `establish()`), per RFC-048 §4.3. `TunnelCaptureCoordinator` holds the
 * order; `VpnService.Builder` cannot be constructed off-device, so the
 * installer is injected and the handles are stand-ins.
 *
 * **The seam's API is the phase split** — `connectTransport` (PHASE 1),
 * `attachTun` (PHASE 3) and `closeTransport` (the attempt-scoped close) —
 * and NOT one combined `startTransport`, which is the shape the pre-fix FFI
 * forces. `apps/android/ci/check-f43-android-phase-split.sh` is the gate
 * for that, and for the fact that the seam cannot reach the global stop.
 *
 * ⚠️ **The seam's EXECUTED order is still the pre-fix order**, and that is
 * deliberate: RFC-049 RULING F102-8 freezes a red-proof's subject until the
 * fix lands, and the fix — the production reorder — is blocked on the
 * generated bindings. The reds below are the red-proof, and they stay red.
 *
 * **Two handles, not one.** The frozen contract joins a **generation token**
 * (the session) and a **descriptor** (the capture) in PHASE 3, and the seam
 * now carries both: `[H]` is the generation, `[F]` is the descriptor. Before
 * this change the seam had one handle type and it was the installer's
 * descriptor — so the seam had no vocabulary for a *session* at all, and
 * §7.2's tests 6, 7 and 9 could not be written.
 *
 * # The state of each test, stated rather than implied
 *
 * | §7.2 | test | today |
 * | :--- | :--- | :--- |
 * | 1 | `a_failed_transport_installs_no_capture_spec` | red |
 * | 2 | `a_failed_transport_calls_establish_zero_times` | red |
 * | 3 | `a_proven_transport_installs_the_capture_exactly_once` | green |
 * | 4 | `the_installed_resolver_is_the_configs_usable_literal` | green |
 * | 5 | `a_capture_installed_after_the_proof_is_still_torn_down_on_the_proof_timeout` | green |
 * | 6 | `a_failed_capture_install_closes_the_transport` | red |
 * | 7 | `a_stale_attempt_does_not_close_a_newer_attempts_session` | green |
 * | 8 | `the_supersede_runs_before_the_transport_is_proven` | red |
 * | 9 | `a_failed_attempt_does_not_stop_a_newer_attempts_session` | green |
 *
 * **The reds** (1, 2, 6, 8) are the ones the reorder alone can fix: the
 * specification is built unconditionally, `establish()` is called before the
 * transport is proven, a failed capture install has no session to close, and
 * the capture goes up before the transport is connected.
 *
 * **The greens** (3, 4, 5, 7, 9) are guards: they are green today and they
 * have to STAY green through the reorder, and each of them fails if the
 * reorder is done carelessly.
 *
 * # The device gate — the one that actually closes F43
 *
 * The JVM tests below cannot see a routing table. This is the check that does,
 * and it is run by hand on a real device against a **freshly built** APK
 * carrying the **new** `.so`:
 *
 * | # | check | expected after a failed connect |
 * | :--- | :--- | :--- |
 * | 1 | `adb shell ip -4 addr show tun0` | **does not exist** |
 * | 2 | `adb shell ip route show table 1040 \| grep default` | **empty** |
 * | 3 | `adb shell ping -c1 example.com` (and a `curl`) | **resolves normally through the underlying network** |
 *
 * Check 3 is the one that matters: "the tun is gone" is not the property under
 * test, "the device still has DNS" is.
 */
class CaptureOrderingTest {

    /**
     * Stands in for the established interface. The real handle is a
     * `ParcelFileDescriptor`, which cannot be constructed in a JVM unit test.
     */
    private class FakeHandle(val id: Int)

    /**
     * A stand-in for the core's session table.
     *
     * RFC-048 §3.2 rules the asymmetry the client half depends on:
     *
     *  * a **successful** `connect_transport` **supersedes** the live session
     *    — the previous generation is invalidated;
     *  * a **failed** one leaves it **untouched** — the live session keeps its
     *    transport and its generation stays valid.
     *
     * `close` answers `false` for a generation that is not the live one. That
     * false is not an error: it is the answer to *"did I own the session?"*,
     * and nothing is closed when it is given.
     */
    private class FakeCore {
        /** The generation of the live session, if there is one. */
        var live: Long? = null

        /** Every generation the client asked to close, in order. */
        val closes = mutableListOf<Long>()

        // ⚠️ **There is deliberately NO "global stops" list here.** One used to
        // be, with an assertion over it — and it could never fail: the seam has
        // no parameter through which a global stop could be called, so nothing
        // could write to the list. That is the "a test that cannot fail"
        // class, and an unused private member is a detekt failure
        // (`UnusedPrivateMember`, `maxIssues: 0`), so the list is gone rather
        // than left dead.
        //
        // ⇒ The "the seam never reaches the global stop" property is
        // **structural**, and it is checked where a structural fact can be
        // checked: `apps/android/ci/check-f43-android-phase-split.sh` greps
        // the seam for `stopVpnEngine` and for `stop_vpn_engine`. The
        // *behavioural* half — that a failed attempt leaves a newer attempt's
        // session alone — is asserted at runtime, on the real observable:
        // which sessions were closed.

        private var next = 0L

        /**
         * A **successful** connect: it takes the session slot and supersedes
         * whatever was there.
         */
        fun connect(): Long {
            next += 1L
            live = next
            return next
        }

        /**
         * The **attempt-scoped** close. False means "you did not own this
         * session" — and nothing was closed.
         */
        fun close(generation: Long): Boolean {
            closes += generation
            if (generation != live) return false
            live = null
            return true
        }
    }

    /**
     * Records what the seam was asked to do.
     *
     * `specConstructions` counts how many times the specification was built —
     * that is the "no capture spec on the failed path" assertion, and it is
     * deliberately a count of *constructions* rather than a count of installs,
     * because a specification that is built and then discarded has already
     * made the capture decision.
     *
     * `events` is the ORDER the steps happened in, when a test wants to pin
     * the order rather than the counts.
     */
    private class Recorder(
        private val spec: TunnelCaptureSpec,
        private val installSucceeds: Boolean = true,
        private val events: MutableList<String>? = null,
    ) {
        var specConstructions = 0
        var establishCalls = 0
        val installed = mutableListOf<TunnelCaptureSpec>()

        fun specFor(): () -> TunnelCaptureSpec = {
            specConstructions++
            events?.add("spec")
            spec
        }

        fun installer(): CaptureInstaller<FakeHandle> = CaptureInstaller { s ->
            establishCalls++
            events?.add("install")
            installed += s
            if (installSucceeds) FakeHandle(establishCalls) else null
        }
    }

    /** The capture the tree installs today: the tunnel address, a catch-all route, the config's resolver. */
    private fun specFor(resolver: String): TunnelCaptureSpec = TunnelCaptureSpec(
        address = "10.0.0.2",
        prefixLength = 32,
        route = "0.0.0.0",
        routePrefixLength = 0,
        resolver = resolver,
    )

    // --- The regression this file is for ---------------------------------------

    /**
     * RFC-048 §7.2 test 1 — `a_failed_transport_installs_no_capture_spec`.
     *
     * On the failed path the capture specification must never be built. Today
     * it is built unconditionally, before the transport is even attempted, so
     * this is **red** on the tree as it stands: the specification is the
     * decision to capture, and it must not be made for a transport that never
     * came up.
     */
    @Test
    fun `a_failed_transport_installs_no_capture_spec`() {
        val rec = Recorder(specFor("10.0.0.53"))
        val coordinator = TunnelCaptureCoordinator<Long, FakeHandle>(
            specFor = rec.specFor(),
            installer = rec.installer(),
            supersede = { },
            // The transport did not come up: there is no session at all.
            connectTransport = { null },
            attachTun = { _, _ -> true },
            awaitProof = { true },
            closeTransport = { true },
            onFailure = { },
        )

        coordinator.run()

        assertEquals(
            "a capture specification was constructed for a transport that never came up; " +
                "the decision to capture must not be made before the transport is proven",
            0,
            rec.specConstructions,
        )
    }

    /**
     * RFC-048 §7.2 test 2 — `a_failed_transport_calls_establish_zero_times`.
     *
     * `establish()` is the moment the capture goes live on the device: a
     * catch-all route and a resolver override, installed by the act of trying
     * to protect the device. On a failed transport it must never be called.
     *
     * Today it is called before the transport is attempted, so this is **red**
     * on the tree as it stands.
     */
    @Test
    fun `a_failed_transport_calls_establish_zero_times`() {
        val rec = Recorder(specFor("10.0.0.53"))
        val coordinator = TunnelCaptureCoordinator<Long, FakeHandle>(
            specFor = rec.specFor(),
            installer = rec.installer(),
            supersede = { },
            connectTransport = { null },
            attachTun = { _, _ -> true },
            awaitProof = { true },
            closeTransport = { true },
            onFailure = { },
        )

        coordinator.run()

        assertEquals(
            "establish() was called for a transport that never came up; the device was left " +
                "holding a catch-all route and a resolver that cannot answer",
            0,
            rec.establishCalls,
        )
    }

    // --- The guards that must survive the reorder ------------------------------

    /**
     * RFC-048 §7.2 test 3 — `a_proven_transport_installs_the_capture_exactly_once`.
     *
     * The reorder must not turn into "the capture is never installed". The
     * catch-all route and the resolver must both be present, and the capture
     * must be installed exactly once — `VpnService.Builder` is one-shot, and a
     * second `establish()` would supersede the first and invalidate the
     * descriptor the engine holds (RFC-048 §6, item 1).
     *
     * Green today, and it has to stay green through the reorder.
     */
    @Test
    fun `a_proven_transport_installs_the_capture_exactly_once`() {
        val rec = Recorder(specFor("10.0.0.53"))
        val coordinator = TunnelCaptureCoordinator<Long, FakeHandle>(
            specFor = rec.specFor(),
            installer = rec.installer(),
            supersede = { },
            connectTransport = { 1L },
            attachTun = { _, _ -> true },
            awaitProof = { true },
            closeTransport = { true },
            onFailure = { },
        )

        assertTrue("a proven transport was reported as failed", coordinator.run())

        assertEquals("the capture was not installed exactly once", 1, rec.establishCalls)

        val installed = rec.installed.single()
        assertEquals("the catch-all route was not installed", "0.0.0.0", installed.route)
        assertEquals("the catch-all route was not installed at /0", 0, installed.routePrefixLength)
        assertTrue("the capture does not take traffic", installed.capturesTraffic)
        assertTrue("the capture does not redirect DNS", installed.redirectsDns)
    }

    /**
     * RFC-048 §7.2 test 4 — `the_installed_resolver_is_the_configs_usable_literal`.
     *
     * The resolver the device is handed must be **the config's** literal, and
     * it must be a usable one. It is never a literal written into the client:
     * the server decides it (`services/server/src/api/handlers/vpn.rs:635`),
     * it arrives as an intent extra, and an unusable one is a connect to
     * refuse (`MeshVpnService.kt:468`) rather than one to silently downgrade.
     *
     * Green today, and it has to stay green through the reorder.
     */
    @Test
    fun `the_installed_resolver_is_the_configs_usable_literal`() {
        val configDns = "10.0.0.53"
        assertTrue(
            "the config's resolver must be a usable literal before it is installed",
            isUsableDnsLiteral(configDns),
        )

        val rec = Recorder(specFor(configDns))
        val coordinator = TunnelCaptureCoordinator<Long, FakeHandle>(
            specFor = rec.specFor(),
            installer = rec.installer(),
            supersede = { },
            connectTransport = { 1L },
            attachTun = { _, _ -> true },
            awaitProof = { true },
            closeTransport = { true },
            onFailure = { },
        )

        assertTrue(coordinator.run())

        val installed = rec.installed.single()
        assertEquals("the installed resolver is not the config's resolver", configDns, installed.resolver)
        assertTrue(
            "the installed resolver is not a usable literal",
            isUsableDnsLiteral(installed.resolver!!),
        )
    }

    /**
     * RFC-048 §7.2 test 5 —
     * `a_capture_installed_after_the_proof_is_still_torn_down_on_the_proof_timeout`.
     *
     * The reorder moves the capture later; it must not make the teardown
     * weaker. This is the F49/F50 guarantee — a tun device stays established
     * while **any** descriptor references it, so **both** have to go — stated
     * against the reordered sequence.
     *
     * ⚠️ **The proof timeout is a REAL proof timeout now.** It used to be
     * modelled by the transport failing — `connectTransport` returning null,
     * which is a *different* failure with a *different* teardown (a transport
     * that never came up owns no session). The coordinator now has a distinct
     * state for it: the transport came up, and then the tunnel proved nothing.
     *
     * Green today, and it has to stay green through the reorder. If this one
     * goes red, the reorder has traded a dead-DNS window for a permanently
     * established interface, which is strictly worse.
     */
    @Test
    fun `a_capture_installed_after_the_proof_is_still_torn_down_on_the_proof_timeout`() {
        var interfaceCloses = 0
        var engineSideCloses = 0
        val lease = TunInterfaceLease(
            closeInterface = { closeEngineSide ->
                interfaceCloses++
                if (closeEngineSide) engineSideCloses++
            },
            relinquishEngineSide = { },
        )

        val rec = Recorder(specFor("10.0.0.53"))
        val core = FakeCore()
        val coordinator = TunnelCaptureCoordinator<Long, FakeHandle>(
            specFor = rec.specFor(),
            installer = rec.installer(),
            supersede = { },
            // The transport came up ...
            connectTransport = { core.connect() },
            attachTun = { _, _ -> true },
            // ... and the tunnel then proved nothing: the proof timed out.
            awaitProof = { false },
            closeTransport = { core.close(it) },
            onFailure = { lease.release() },
        )

        assertFalse("a timed-out tunnel was reported as working", coordinator.run())

        assertTrue("the interface lease was not released on the proof timeout", lease.isReleased)
        assertEquals("the interface descriptor was left open", 1, interfaceCloses)
        assertEquals(
            "the engine descriptor was left open, so tun0 survives the teardown and the " +
                "device keeps no DNS",
            1,
            engineSideCloses,
        )
    }

    // --- The session half: the generation token, and the scoped close -----------

    /**
     * RFC-048 §7.2 test 6 — `a_failed_capture_install_closes_the_transport`.
     *
     * When `establish()` returns null, the transport must not be left live on
     * the node: `close_transport(handle)` is called **with that attempt's
     * handle**.
     *
     * 🔴 **This is red on the tree as it stands, and the reason is the defect
     * itself.** The frozen contract proves the transport FIRST, so a failed
     * capture install still has a session to close. Today the capture is
     * installed *before* the transport is proven — so when the install fails
     * there is no session at all, and there is nothing to close and nothing to
     * close it with.
     *
     * It goes green when the reorder lands, and it is the test that proves the
     * reorder did not simply leave a live session behind on the node.
     */
    @Test
    fun `a_failed_capture_install_closes_the_transport`() {
        val core = FakeCore()
        val rec = Recorder(specFor("10.0.0.53"), installSucceeds = false)
        val coordinator = TunnelCaptureCoordinator<Long, FakeHandle>(
            specFor = rec.specFor(),
            installer = rec.installer(),
            supersede = { },
            connectTransport = { core.connect() },
            attachTun = { _, _ -> true },
            awaitProof = { true },
            closeTransport = { core.close(it) },
            onFailure = { },
        )

        assertFalse("a refused capture was reported as a working tunnel", coordinator.run())

        assertEquals(
            "the attempt-scoped close was NOT called on the capture-install failure path; " +
                "the frozen contract proves the transport FIRST, so a failed install still has " +
                "a session to close -- the capture is installed before the transport today, so " +
                "there is nothing to close and nothing to close it with",
            1,
            core.closes.size,
        )
    }

    /**
     * RFC-048 §7.2 test 7 — `a_stale_attempt_does_not_close_a_newer_attempts_session`.
     *
     * Attempt *N*'s failure path calls `close_transport(attemptNHandle)` and
     * the core answers `false`; attempt *N+1*'s session is untouched.
     *
     * This is the client half of §3.2's asymmetry: a *successful* connect
     * supersedes the previous session, so attempt *N*'s generation is stale,
     * and the scoped close refuses it without touching anything.
     *
     * Green today, and it has to stay green through the reorder. If it goes
     * red, a stale attempt has started closing sessions it does not own —
     * which is how one attempt's failure becomes every attempt's failure
     * (§4.5), inside the client this time.
     */
    @Test
    fun `a_stale_attempt_does_not_close_a_newer_attempts_session`() {
        val core = FakeCore()
        val rec = Recorder(specFor("10.0.0.53"))
        var attemptGeneration = 0L
        var closedWith = 0L
        var refused = false

        val coordinator = TunnelCaptureCoordinator<Long, FakeHandle>(
            specFor = rec.specFor(),
            installer = rec.installer(),
            supersede = { },
            connectTransport = { core.connect() },
            attachTun = { _, _ -> true },
            awaitProof = { generation ->
                attemptGeneration = generation
                // A newer attempt connects while this one waits for its proof:
                // its success supersedes this attempt's session.
                core.connect()
                // ... and this attempt's proof times out.
                false
            },
            closeTransport = {
                closedWith = it
                refused = !core.close(it)
                refused
            },
            onFailure = { },
        )

        assertFalse("a timed-out tunnel was reported as working", coordinator.run())

        assertEquals(
            "the attempt-scoped close must carry THIS attempt's generation and no other",
            listOf(attemptGeneration),
            core.closes,
        )
        assertEquals("the close was made with the wrong generation", attemptGeneration, closedWith)
        assertTrue("a stale generation was accepted by the close", refused)
        assertNotNull(
            "the newer attempt's session was torn down by a stale attempt",
            core.live,
        )
    }

    /**
     * RFC-048 §7.2 test 8 — `the_supersede_runs_before_the_transport_is_proven`.
     *
     * The previous attempt's lease is released **before** the transport is
     * proven — the ordering §4.2 rules.
     *
     * §4.2 says two things, and both are asserted here:
     *
     *  * the supersede runs before the transport is connected, so the previous
     *    attempt's capture is gone before the new attempt starts its
     *    handshake; and
     *  * **the whole connect sequence, from the transport connection onward,
     *    runs with NO capture installed** — the device is not left holding a
     *    catch-all route and an unanswerable resolver while the handshake is
     *    in flight.
     *
     * 🔴 **The second half is red on the tree as it stands**: the capture is
     * installed *before* the transport is connected, which is the defect.
     */
    @Test
    fun `the_supersede_runs_before_the_transport_is_proven`() {
        val events = mutableListOf<String>()
        val core = FakeCore()
        val rec = Recorder(specFor("10.0.0.53"), events = events)
        val coordinator = TunnelCaptureCoordinator<Long, FakeHandle>(
            specFor = rec.specFor(),
            installer = rec.installer(),
            supersede = { events += "supersede" },
            connectTransport = {
                events += "connect"
                core.connect()
            },
            attachTun = { _, _ -> true },
            awaitProof = { true },
            closeTransport = { core.close(it) },
            onFailure = { },
        )

        assertTrue("a proven transport was reported as failed", coordinator.run())

        val supersedeAt = events.indexOf("supersede")
        val connectAt = events.indexOf("connect")
        val installAt = events.indexOf("install")

        assertTrue("the supersede did not happen at all", supersedeAt >= 0)
        assertTrue("the transport was never connected", connectAt >= 0)
        assertTrue(
            "the previous attempt's capture was not released before the transport was proven; " +
                "§4.2 rules the supersede BEFORE the handshake",
            supersedeAt < connectAt,
        )
        assertTrue(
            "a capture was installed before the transport was connected; §4.2 requires the whole " +
                "connect sequence, from the transport connection onward, to run with NO capture " +
                "installed -- the device must not hold a catch-all route and an unanswerable " +
                "resolver while the handshake is in flight",
            installAt > connectAt,
        )
    }

    /**
     * RFC-048 §7.2 test 9 — `a_failed_attempt_does_not_stop_a_newer_attempts_session`.
     *
     * Attempt *N*'s teardown is **scoped**: it releases its own interface and
     * closes its own session, and it **never** the global `stopVpnEngine()`.
     * A newer attempt's session survives attempt *N*'s failure.
     *
     * The scenario is the asymmetry of §3.2 on the client's side: attempt
     * *N+1* has a live session, and attempt *N*'s **connect fails**. A failed
     * connect creates no session, supersedes nothing, and — because this
     * attempt owns no generation — there is nothing for it to close. The
     * newer session must be exactly where it was. And with the fixed order,
     * the same failure owns **no capture either**: the install block runs
     * only after a proven transport, so a failed connect has never specified
     * or built or installed one — `onFailure` must not have been called, and
     * the interface lease cannot be standing.
     *
     * ⚠️ **The global stop cannot be reached from this seam at all** — there is
     * no parameter through which one could be called, and that is the point:
     * the per-attempt teardown is structurally incapable of being the global
     * stop. The production wiring is checked from the other side by
     * `scripts/ci/check-f43-capture-order.sh` (GATE-F43-3), which greps
     * `MeshVpnService.kt` for a `startVpnEngine(` call.
     */
    @Test
    fun `a_failed_attempt_does_not_stop_a_newer_attempts_session`() {
        val core = FakeCore()
        val rec = Recorder(specFor("10.0.0.53"))
        var tornDown: FakeHandle? = null

        val coordinator = TunnelCaptureCoordinator<Long, FakeHandle>(
            specFor = rec.specFor(),
            installer = rec.installer(),
            supersede = { },
            // The transport did not come up: no session, and nothing superseded.
            connectTransport = { null },
            attachTun = { _, _ -> true },
            awaitProof = { true },
            closeTransport = { core.close(it) },
            onFailure = { tornDown = it },
        )

        // A newer attempt is live before this one even starts.
        val newer = core.connect()

        assertFalse("a failed transport was reported as a working tunnel", coordinator.run())

        assertEquals(
            "a failed attempt closed a session it did not own; a failed connect creates no " +
                "session and supersedes nothing (RFC-048 §3.2)",
            0,
            core.closes.size,
        )
        assertTrue(
            "the newer attempt's session was stopped by a failed attempt",
            core.live == newer,
        )
        assertNull(
            "the failed attempt reported a capture teardown, but a failed transport installs " +
                "no capture: the install block runs only after a proven transport, so there is " +
                "no lease to release and `onFailure` must not have fired",
            tornDown,
        )
        // ⚠️ **The "no global stop" assertion is NOT here, and that is a
        // correction.** It used to be:
        //
        //     assertEquals(emptyList<String>(), core.globalStops)
        //
        // — and `core.globalStops` could never hold anything: the seam has no
        // parameter through which a global stop could be called, so the
        // assertion could not fail. That is the register's "a test that
        // cannot fail" class, and a green assertion that cannot go red is
        // worth less than no assertion at all: it reads as coverage.
        //
        // ⇒ The property is **structural**, so it is checked structurally:
        // `apps/android/ci/check-f43-android-phase-split.sh` greps the seam
        // for `stopVpnEngine` and for `stop_vpn_engine`, and its `--selftest`
        // proves the rule fires on a seam that has one. The *behavioural*
        // half is asserted above, on the observable that can actually move:
        // which sessions were closed, and whether the newer one survived.
    }
}
