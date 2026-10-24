# L7 dispatch R3: failed-connect DNS/teardown safety defect

Status: **FIXED AND VERIFIED OFF-DEVICE; device proof pending** (2026-10-18)
Commit: `3573723`

## 1. What the device actually does today — the headline symptom did NOT reproduce

The brief says the device's failed-connect path leaves `tun0` up with no DNS,
flagged T6 unfixed. **I could not reproduce that on the current build, and I
think the reason matters more than the fix.**

Five drives were run against the CPH2363 (14:06, 14:16, 14:24, 14:31, 14:39).
In every one of them the app logged only:

    🚀 Selected Mode: REALITY (Attempt: 0, HighRisk: false, ...)
    🚀 Starting Native Rust engine (mode=reality)
    🛡️ Socket NNN protected from VPN

…followed by `tun0 = UP` that never came down. On the face of it that is the
reported defect. **It is not.** In those runs:

* the UI read `YOU ARE PROTECTED` / `CONNECTED` / `TRAFFIC 3.2 MB`;
* `curl http://example.com` **through the live tunnel** returned 200 three times
  running (dns lookup 0.23s / 0.008s / 0.006s);
* `dumpsys connectivity` showed the VPN agent `VALIDATED`;
* the UI's own `tun0` line is `10.0.0.97/32`, matching the mesh IP shown.

So the tunnel genuinely worked and `tun0` being up was **correct**. The REALITY
pre-auth reject that L8 reported (`alert 47`) **no longer reproduces** — the
server-side repair landed in `76633fd` / `4ed0cf1` / `c836dc6` / `5f5c3be`. The
tunnel is no longer the trigger.

Two measurement traps, both of which cost time and are worth not repeating:

* **`DEAD-noreply` is not DNS failure.** This network drops ICMP through the
  tunnel routinely. `unknown host` is the DNS-failure signal; 100% packet loss
  proves nothing about the resolver. I conflated these on first pass.
* **`ping *loss* is not evidence of a blackhole.** `curl` is the reachability
  check. The device-tests probes already distinguish these correctly; reading a
  raw timeline by eye is what introduced the error.

### A genuine failed connect, and what it did

To exercise the failed path at all the network has to be cut **inside** the
handshake — the ladder reaches `establish()` in ~100 ms, so cutting later lands
after it. With the network cut at t=1 s:

    14:47:29.138  🚀 Starting Native Rust engine (mode=reality)
    14:47:29.573  FATAL: VPN engine crashed
                  uniffi…IoException: REALITY: write failed: Software caused
                  connection abort (os error 103)
    14:47:29.669  🛑 handleDisconnect: Tearing down VPN...
    14:47:29.725  MeshVpnService destroyed, releasing tunnel resources
    …
    14:48:22.329  Handshake returned but no traffic arrived within 8000ms;
                  tearing the interface down so the device keeps its DNS
    …
    14:48:46.256  CRITICAL: Connection failure          <- all 3 attempts spent
    14:48:46.301  🛑 handleDisconnect: Tearing down VPN...

Both known teardown paths fired and the device came back clean:

    $ adb shell ip -4 addr show tun0
    Device "tun0" does not exist.
    $ adb shell dumpsys connectivity | grep -c 'InterfaceName: tun0'
    0
    $ adb shell ping -c1 -W2 example.com
    PING example.com (172.66.147.243) … 64 bytes from 172.66.147.243
    $ adb shell curl -o /dev/null -w '%{http_code}' http://example.com
    200

So the two-descriptor fix from the previous dispatch **is working** for the
paths it was written for.

## 2. The path that does NOT reach the existing teardown

Reading rather than reproducing, because the symptom is gone:

**`MeshVpnService.kt:542` (pre-fix) — `ParcelFileDescriptor.adoptFd` failure was
swallowed to `null` and execution continued.**

The engine's descriptor is produced at `:525` by `pfd.dup().detachFd()`. From
that instant it is owned by **nobody**:

* not by `pfd` — that is a different descriptor;
* not by the engine — `transport/engine.rs` constructs the `OwnedFd` only
  *after* `reality.connect().await?` succeeds, so every failed handshake returns
  before the descriptor is wrapped and nothing on the Rust side closes it;
* not by the service — unless `adoptFd` succeeds and the handle is kept in
  `engineSide`.

`engineSide.set(runCatching { adoptFd(fd) }.getOrNull())` therefore turns a
failure to take ownership into "continue with a descriptor that no code can
close". Every teardown path then closes `pfd` and this duplicate survives, so
`tun0` stays up with its `0.0.0.0/0` route and its `10.0.0.53` resolver — the
blackhole again, through a door the two-descriptor fix does not cover.

This is why "close both descriptors" was necessary but not sufficient: closing
both only helps while the second one is still *reachable*.

## 3. The change

`MeshVpnService.kt` — new `takeDescriptorOwnership(fd, adopt, close)` returning
`DescriptorHandoff.Owned | Refused`. On failure it **closes the orphaned
descriptor and refuses the connect**; the lease is armed before ownership is
taken, so the refusal's `handleDisconnect()` still takes the interface down.

Closing a bare `int` fd needed care: `Os.close` takes a `FileDescriptor`, and
the `FileDescriptor(int)` constructor is hidden API (the build rejected both
attempts — `Too many arguments for 'constructor(): FileDescriptor'`). The fix
adopts into a handle and closes that, using public API only.

## 4. Verification

* **Full suite: 241 tests, 0 failures** (235 before; +6 new).
* **Negative control**: restoring the shipped behaviour turns **5 of 6 red**; the
  sixth is the success-path control and correctly stays green.

    FAIL  ownership failure closes the descriptor exactly once
          expected:<1> but was:<0>
    FAIL  a descriptor that cannot be owned is closed rather than leaked
          expected:<[41]> but was:<[]>
    FAIL  adoption that throws is treated as a refusal, not as an escape
          expected:<Refused> but was:<Owned(handle=null)>
    FAIL  a refused connect still leaves nothing owned
          expected:<1> but was:<0>
    FAIL  the engine is never started with a descriptor nobody owns
          the connect proceeded although the descriptor had no owner
    pass  a successful adoption hands the descriptor to the engine and closes nothing

The tests assert on **descriptor ownership**, not UI. That is deliberate: the
user-visible symptom of this class of defect is a phone that silently stops
resolving while the app's error reporting stays perfectly correct, so
"an error is shown" passes with the interface still blackholed.

An earlier draft of the test re-implemented the decision rather than calling the
production function. That version would have passed whatever the service did, so
it was discarded and the decision extracted into `takeDescriptorOwnership` to be
asserted directly.

## 5. Still unverified / out of scope

* **The device proof for this specific fix is not yet taken.** `adoptFd`
  failing is not reachable through the UI — the shipping build has no path that
  forces it, and `MeshVpnService` is `exported=false` so no intent can inject
  one. The fix is verified by construction and by the negative control, not by
  a device reproduction. **This should be stated plainly: the original T6
  symptom did not reproduce on this build, so there is no device-level
  before/after to show.**
* `handleDisconnect()` calling `stopSelf()`/`stopForeground()` while
  `onStartCommand` may still fall through to `startForeground()` (`:299` → `:312`)
  for a connect that is still mid-handshake — a zombie-foreground hazard, not a
  DNS one. Noted, not changed.
* The thread-name census in `device-tests` greps `shadowmesh` against
  `/proc/<pid>/task/*/comm`, which is truncated to 15 chars and therefore cannot
  distinguish `shadowmesh-handshake-watchdog` from anything else. That probe is
  **inconclusive as written** and should be sharpened before anyone cites it.
* The tunnel's remaining failure (REALITY pre-auth `short_id` reject) is a
  server-side finding owned by the parallel L8 dispatch. Not touched here.

## Artifacts

`.artifacts/l7-failconnect/` — `before/`, `failconnect/`, `threads/`, `dark/`
timelines and app logs. ZPII: only counts, enum-like labels and timings; no
hostnames, local addresses, SSIDs or MACs recorded.