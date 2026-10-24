# F43-a — the capture-ordering contract, checked against the committed tree

**Date:** 2026-10-21 · **Owner:** L7 (`apps/android/**`) · **Status:** prepared, not
implemented — and the reason is not mine to remove.

This note answers one question and records one state:

> **Do the committed JVM tests still express the frozen contract** — the handle
> is a generation token, `connect_transport` supersedes **on success only**, and
> `stop_engine` closes the transport?

The answer is **half**, and the half that is missing is missing for a reason
that is written down in RFC-048 §8.1, not for want of a test. The honest
split is below, with the file:line for every claim.

---

## 1. What the committed JVM tests DO express

`66a6d66` landed four files, and they are still exactly as committed (verified
by reading them at `fddb4d0`):

| file | what it is |
| :--- | :--- |
| `app/src/main/java/com/shadowmesh/app/TunnelCaptureSpec.kt` | the pure capture value (RFC-048 §4.3) |
| `app/src/main/java/com/shadowmesh/app/CaptureInstaller.kt` | the single seam through which `establish()` is called (§4.3) |
| `app/src/main/java/com/shadowmesh/app/TunnelCaptureCoordinator.kt` | the connect attempt's capture handling, as one ordered unit |
| `app/src/test/java/com/shadowmesh/app/CaptureOrderingTest.kt` | RFC-048 §7.2 tests 1–9 |

They express **the ordering half** of F43, and they express it correctly:

| §7.2 test | asserts | today |
| :--- | :--- | :--- |
| 1 `a_failed_transport_installs_no_capture_spec` | the spec is never constructed on the failed path | red, by design |
| 2 `a_failed_transport_calls_establish_zero_times` | `establish()` is called **zero** times on the failed path | red, by design |
| 3 `a_proven_transport_installs_the_capture_exactly_once` | the catch-all route and the resolver are both present, exactly once | green, a guard |
| 4 `the_installed_resolver_is_the_configs_usable_literal` | the resolver is the **config's** literal and is usable | green, a guard |
| 5 `a_capture_installed_after_the_proof_is_still_torn_down_on_the_proof_timeout` | the reorder does not weaken the teardown: both descriptors close, the lease is released | green, a guard — now against a **real** proof timeout |
| 6 `a_failed_capture_install_closes_the_transport` | a refused capture closes the attempt's session | red, by design |
| 7 `a_stale_attempt_does_not_close_a_newer_attempts_session` | the scoped close is **refused** for a stale generation, and the newer session survives | green, a guard |
| 8 `the_supersede_runs_before_the_transport_is_proven` | the supersede precedes the connection, and **no capture is installed** while the transport is being proven | red, by design |
| 9 `a_failed_attempt_does_not_stop_a_newer_attempts_session` | a failed connect supersedes nothing and stops nothing | green, a guard |

And the discipline that matters for the three points below is already in the
tree: the coordinator's teardown is a **callback** — the attempt-scoped close —
and **not** the global `stopVpnEngine()`.

---

## 2. The rest of the contract — what is expressed, and what still is not

### 2.1 The handle is a generation token — **NOW EXPRESSED, and it was the blocker**

RFC-048 §3.3: *"The handle identifies the **session** (the stack), **not** a
particular transport instance."* And §3.1 freezes it as a `u64` generation, `0`
reserved for "no session".

The seam used to have **one** handle type, `H`, and it was the handle the
**installer** returns (`TunnelCaptureCoordinator.kt:58`, `:65`, `:72`): the
descriptor of the capture that was just installed. So the seam modelled the
**pre-fix** order — install the capture, then start the transport on the
descriptor — and it had **no vocabulary at all** for a generation:

* there was nowhere to put a `u64`,
* and nothing in it could distinguish *"the session this attempt started"* from
  *"the session that is live now"* — which is exactly the distinction §3.3
  exists to make, and exactly what a stale `attach_tun` has to be refused
  against.

⇒ **The seam now carries both handles**: `[H]` is the **generation token**
and `[F]` is the **descriptor**. That is what made §7.2's tests 6, 7 and 9
writable at all — they need a per-attempt session handle, and the seam had
none.

⚠️ **The seam's ORDER is unchanged, and that is the point.** The generation
token is vocabulary, not behaviour. The coordinator still installs the capture
**before** it proves the transport, so tests 1 and 2 are still **red** and
the red-proof still bites. Changing the order to make them green is the one
thing this change must not do: it would be a green client-side test that no
production code calls, while `MeshVpnService.kt` still installs the capture
first. The coordinator has no production caller **on purpose**, and its order
stays the pre-fix order until the UDL lands.

### 2.2 `connect_transport` supersedes **on success only** — the client half is now written

RFC-048 §3.2's asymmetry is a property of **two** attempts, and the core is
where it is decided:

* **the core's half** — §7.1 test 2, `a_failed_connect_leaves_a_live_session_untouched`,
  and test 3, `a_successful_connect_supersedes_the_previous_session` — is
  **L9's**, in `libs/`.
* **the client's half** — §7.2 tests **7** (`a_stale_attempt_does_not_close_a_newer_attempts_session`)
  and **9** (`a_failed_attempt_does_not_stop_a_newer_attempts_session`) — is
  **mine**, and is now **written**: the seam carries the scoped close
  (`closeTransport(generation)`) and the fake core models the asymmetry — a
  successful connect supersedes, a failed one touches nothing, and a close
  with a stale generation is refused and closes nothing.

### 2.3 `stop_engine` closes the transport — **L9's, entirely**

RFC-048 §3.4 makes `stop_engine()` close the live session's active transport
and invalidate the generation. That is a **Rust** behaviour in
`libs/core-shared/shadowmesh-core/src/transport/engine.rs`, and its red-proof
is §7.1 test 7, `stop_engine_closes_the_active_transport` — **L9's** test, in
**L9's** tree, with a close-counting spy transport.

No JVM test on this side of the FFI can assert it, and none should try: the
client's obligation is the mirror image — *never* rely on the global stop for a
per-attempt teardown — which the seam does express.

---

## 3. The seam as it stands, and what the UDL changes

```kotlin
class TunnelCaptureCoordinator<H, F>(   // H = generation, F = descriptor
    private val specFor: () -> TunnelCaptureSpec,
    private val installer: CaptureInstaller<F>,
    private val supersede: () -> Unit,          // §4.2 — the previous attempt's lease
    private val startTransport: (F) -> H?,      // the session this attempt opened
    private val awaitProof: (H) -> Boolean,     // PHASE 4 — a DISTINCT state
    private val closeTransport: (H) -> Boolean,  // the attempt-scoped close
    private val onFailure: (F) -> Unit,         // the attempt-scoped interface teardown
)
```

Three things in there are new, and each of them is a contract the seam could
not previously even *say*:

1. **`supersede`** — the previous attempt's lease is released before the
   transport is proven (§4.2).
2. **`awaitProof`** — the proof timeout is a **distinct state** from "the
   transport did not come up". The two need different teardowns: a transport
   that never came up owns no session, and a tunnel that came up and then
   proved nothing owns one. Test 5 used to model the proof timeout by making
   the transport fail, which conflated the two.
3. **`closeTransport`** — the **attempt-scoped** close, carrying the
   generation. ⚠️ There is **no global stop** in the seam, and that is
   deliberate: a per-attempt teardown that could reach the global stop is the
   hazard §4.5 rules out.

**What changes when L9's UDL lands.** The `startTransport` seam splits into
`connect_transport(config)` and `attach_tun(generation, fd)`, and the ORDER of
the steps changes:

```
today (the defect)          after the reorder
  spec                       supersede
  supersede                  connectTransport -> generation
  install                    spec
  startTransport             install
  awaitProof                 attachTun(generation, descriptor)
                             awaitProof
```

⚠️ **The order is the fix**, and it is not this session's. The reorder makes
tests 1, 2, 6 and 8 go green — and at the same moment the production caller
appears. A green client-side test that no production code calls is the trap
`66a6d66` named, and the seam's order is what keeps that trap closed until
the caller exists.

---

## 4. The device gate — ready, and the one that actually closes F43

RFC-048 §7.3: *"The JVM tests below cannot see a routing table. This is the
check that does."* The harness in `apps/android/device-tests/` now carries it:

| what | where |
| :--- | :--- |
| the capture's signature (`default dev tun0`, **in any routing table**) | `lib/device.sh` — `probe_default_via_tun0`, `default_via_tun0_count` |
| the fourth sampled column, so the capture is on the timeline | `lib/sampler.sh` — `sampler_run` |
| the two windows nobody has measured | `lib/sampler.sh` — `sampler_pre_capture_window_ms`, `sampler_pre_proof_window_ms` |
| the D5 claim: on a failed connect, nothing was installed | `run.sh` — `D5_the_capture_is_not_installed_before_the_transport_is_proven` |
| the **same-session pre-fix control** | `run.sh` — `--control`, `C1_the_defect_reproduces_on_this_build` |
| the APK + `.so` + commit provenance | `run.sh` — `installed_apk_sha256`, `apk_native_sha256` |

The runbook is `apps/android/device-tests/README.md`.

**The two windows** are the numbers RFC-048 §7.3 asks for, and they are what
makes "≈ 23 s → ≤ 8 s" a measurement instead of arithmetic:

| window | pre-fix | post-fix |
| :--- | :--- | :--- |
| **pre-capture** (drive → the capture going up) | ~0 — the capture goes up at once | the handshake duration, and the device keeps its own resolver throughout |
| **pre-proof** (the capture going up → the tunnel proving itself) | the whole handshake **plus** the proof budget | the proof budget alone |

**What the gate still needs, and who owes it:** a device, two APKs (the
pre-fix build and the fixed build), and a run of the pair. That is L3's or the
operator's — RFC-048 §8.1 step 6 — and it is **not** something this tree can
do for itself.

**D5 is red on today's build, and that is correct.** The production order
cannot change until the UDL carries the three functions; the gate holds that
state until it can be closed.

---

## 5. What is NOT done, and what is owed

| # | item | owner | state |
| :--- | :--- | :--- | :--- |
| 1 | `connect_transport` / `attach_tun` / `close_transport` in the UDL; `TransportStack::close()`; `stop_engine` extended; `start_engine` re-composed | **L9** | not landed — RFC-048 §3.1, §8.1 steps 1–2 |
| 2 | the core's red-proofs (§7.1 tests 1–11) | **L9** | not written |
| 3 | the UniFFI Kotlin regeneration + the rebuilt `.so` for both ABIs | L9 runs, L7 lands | blocked on 1 |
| 4 | the phase split in `MeshVpnService.kt`; the coordinator's reorder; §7.2 tests 6–9 | **L7 (me)** | tests 6–9 **written**; the reorder is blocked on 1 — the shape is written down in §3 above |
| 5 | the device gate run: the pair (control + fixed), on a device | L3 / the operator | the harness is ready; the run needs a device and two APKs |
| 6 | `scripts/ci/check-f43-capture-order.sh` + its `--selftest` | L9 | not written |

**Nothing in this tree was run.** No `gradle`, no `adb`, no device: the box's
build slot is shared and this session's rule is *edit and commit, no builds*.
The shell was checked for syntax (`bash -n` on all four files) and the sampler's
measurements were checked against a hand-made timeline, which is what the two
window measurements were verified with. The Kotlin was **read**, not compiled.

⚠️ **And the suite is RED today, by design.** Tests 1, 2, 6 and 8 are red
against the tree as it stands — that is the red-proof, and it is what the
reorder is measured against. The reorder makes them green **at the same moment
the production caller appears**, and not before: a green client-side test that
no production code calls is the trap `66a6d66` named.
