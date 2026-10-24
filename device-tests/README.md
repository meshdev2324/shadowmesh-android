# The device-in-the-loop gate

`run.sh` is the only gate in this tree that can answer the question F43 is
about. Everything else — the JVM tests, the unit tests, the UI assertions —
lives off the device and cannot see a routing table, a resolver override, or
the passage of time. The defect was invisible in the UI: three generations of
tests were green while the phone was blackholed.

```
./device-tests/run.sh                    # the fixed build's claims
./device-tests/run.sh --keep-log         # …and the full app logcat
./device-tests/run.sh --apk <path.apk>   # install that APK first, then run
./device-tests/run.sh --apk <path.apk> --control   # the PRE-FIX CONTROL
```

**Exit codes** (`lib/result.sh`): `0` everything ran and passed · `1` something
ran and failed · `2` something did not run · `3` the harness itself is broken.

`2` is not a soft failure. A skipped device suite is not a green suite, and
anything that reports on this suite must propagate the exit code.

`3` is the code for **a broken measuring tool**, and it is the one this suite
now has a way to reach: `run.sh` runs the sampler's own self-test before it
touches the device (see below), and a failed self-test is exit `3`, not
NOTRUN — the suite is not "unable to run", it is unable to *measure*.

---

## The instrument's own self-test

```
bash device-tests/lib/sampler_selftest.sh
```

Everything the suite reports is measured by `lib/sampler.sh`. **A measurement
that is wrong cannot be made right by the thing it measures**, so the harness
proves its own measuring tool first — over fixture sample files, with **no
device attached**.

This is not a formality. L3's finding was that `sampler_capture_installed_at_ms`
anchored **both** windows on `tun0` being UP (column 2) while the captured and
blackhole windows are measured on the **capture's signature** (column 4 — "a
`default` route through `tun0`, in any routing table"):

| column | what it is | which event |
| :--- | :--- | :--- |
| 2 | `tun0` | the **interface** is up |
| 3 | `dns` | the **resolver** answers, or does not |
| 4 | `default_via_tun0` | the **capture** is installed |

The two anchors **coincide on a pre-fix build** — the interface and the
catch-all come up together — and **diverge** the moment they come up at
different moments, which is exactly what RFC-048's reorder (prove → install
capture → attach tun) can produce. So after the reorder the instrument would
have measured the wrong instants, and a "passing" post-fix run could have been
an **artefact of the instrument** rather than a change in the client. That is
the F68 shape applied to the measuring tool: a metric that improves when the
thing it measures is degraded.

⇒ The capture's instant is now anchored on the capture's signature, and the
self-test pins it:

| fixture | what it proves |
| :--- | :--- |
| the capture precedes the proof | the captured window and the blackhole window **differ**, and the pre-capture and pre-proof windows differ |
| the interface precedes the capture | the capture's instant is the **catch-all's**, not the interface's — **the red-proof**; the old anchoring fails this fixture |
| the capture and the proof coincide | the pre-proof window is **zero** |
| the capture is never proved | the captured and the blackhole windows **coincide**, and the pre-proof window is the whole capture window |

The last two rows matter as much as the first: "the two windows differ" on its
own is true of any two numbers, so the **coincide** cases are what make the
"differ" case mean something.

---

## The claims

| # | claim | what it asserts |
| :--- | :--- | :--- |
| D1 | a failed connect leaves the device usable | `tun0` absent, the resolver answers, an HTTP request completes |
| D2 | a silent handshake cannot hold dead DNS past the proof budget | the blackhole is bounded by the production constant, and only on the path that actually walked it |
| D3 | the watchdog stays armed while the lease is PENDING | the watchdog's bound holds on the path where the watchdog is what tore the interface down |
| D4 | the interface never outlives its proof | the platform holds no VPN network agent and no route via `tun0` |
| **D5** | **the capture is not installed before the transport is proven** | **on a failed connect: no interface establishment at all, no `default dev tun0` in any routing table, and the device's own resolver kept working throughout** |
| C1 | *the control* | **the defect MUST reproduce on the build under test** |

### D5 — the F43 claim

The claim is about the **order**, and on the device it is visible as a
**capture window**: the stretch in which the phone holds a default route
through the tunnel together with a resolver that only the tunnel can answer.

Pre-fix, that window covers the whole handshake — up to
`HANDSHAKE_TIMEOUT_MS` + `PROOF_TIMEOUT_MS` — so a connect that **never**
succeeds still blackholes the phone for the whole of it, and the retry
ladder's own control-plane calls go out over that dead resolver. Attempt 1's
config fetch can die on the DNS the failed attempt installed.

Post-fix (RFC-048 §4.1) the transport is proven **first**: a connect that
fails in PHASE 1 installs nothing.

**The two window measurements** are therefore:

| measurement | definition | post-fix, on a failed connect |
| :--- | :--- | :--- |
| the **captured window** | the longest stretch in which a `default` route via `tun0` was installed | `0 ms` |
| the **blackhole window** | the longest stretch in which that route was installed **and** the resolver was dead | `0 ms` |

> ⚠️ **D5 is RED until RFC-048's reorder lands, and that is the correct
> state.** The client half is prepared
> (`app/src/main/java/com/shadowmesh/app/TunnelCaptureCoordinator.kt`,
> `CaptureInstaller.kt`, `TunnelCaptureSpec.kt`, and the red-first
> `CaptureOrderingTest.kt`), but the core's `connect_transport` /
> `attach_tun` are not in the UDL yet, so the production order cannot change
> until they are. The gate holds that state until it can be closed.

### The `default dev tun0` probe

The VPN's routes are **not** in the kernel's `main` table — Android puts them
in a per-network table (the legacy one is 1040). `ip route show` prints the
`main` table only, so the old probe could not see the catch-all the capture
installs at all: "no route via `tun0`" was a claim about a table the VPN
never used.

`probe_default_via_tun0` reads `ip -4 route show table all`, so the catch-all
is either there or it is not. It is sampled on every tick **while the
interface is up** — the only window in which the capture can exist, and a
third adb round trip on every tick would coarsen the timeline the duration
bounds are measured on.

---

## The same-session pre-fix control

**A gate that has never been seen to fail is not a gate.** C1 is the control:
run against the pre-fix binary, it asserts the **opposite** of D5 — the
defect must reproduce (the device must be captured, the catch-all must be
installed, and the resolver must die while it is).

If the control does **not** reproduce, the gate cannot tell the two builds
apart, and a green fixed-build run would mean nothing. That is a **FAIL**,
not a skip.

Run it as a **pair**, in one session, on one device, against one server and
one endpoint:

```
./device-tests/run.sh --apk /path/to/prefix.apk --control   # C1 must PASS
./device-tests/run.sh --apk /path/to/fixed.apk               # D1..D5
```

Only the binary differs between the two runs, so a difference in the outcome
is attributable to the binary and to nothing else. The fixture for each run
records the commit, the APK's hash and the `.so`'s hash, so the two runs can
be tied to the two binaries afterwards.

---

## Provenance

Every fixture carries:

| field | what it is |
| :--- | :--- |
| `commit` | the short hash of the tree the binaries were built from |
| `apk` | the sha256 of the APK **on the device** — the copy that ran |
| `so` | the sha256 of `lib/<abi>/libshadowmesh_core.so` **inside** the APK |

The `.so` is the half that matters for F43: the ordering spans the Kotlin
client **and** the core the `.so` carries, so an APK built against a stale
`.so` proves nothing. `lastUpdateTime` is recorded too, but it is a timestamp
and a timestamp cannot be compared with anything the reader has.

---

## The rules this suite keeps

1. **Never silently green.** Three outcomes — PASS, FAIL, NOTRUN — and NOTRUN
   forces exit code 2. There is no path through `run.sh` that prints PASS for
   a test that did not execute.
2. **Assert on the path, not just the bound.** Every claim declares the
   device-observable event it must observe. A bound that holds trivially on a
   path that was never walked is NOTRUN, never PASS.
3. **Restore the device.** Leaving a phone without DNS *is* the defect. Every
   exit path, including the failure paths and the traps, restores it.
4. **Bounds are read from the production source**, not copied. A bound that
   drifts from the Kotlin constant is worse than no bound.
5. **No PII.** No probe persists a hostname, a local IP, an SSID, a BSSID or a
   MAC. Only counts, booleans and enum-like labels leave `lib/device.sh`.
6. **Prove the instrument before using it.** The sampler's own self-test runs
   first, over fixture sample files, with no device. A failed self-test is
   exit code 3 — the suite cannot measure, so it reports nothing.
