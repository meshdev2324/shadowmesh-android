# L7 dispatch #3: VPN teardown / DNS-survival defect

Status: **COMPILE FIXED — rebuilding for device verification** (2026-10-17)

## THE HEADLINE: the committed "fix" never compiled

`git log` shows the whole `TunInterfaceLease` / `adoptFd` mechanism landed in
**`3509f99` itself**, committed 20:04:17 — one minute before this dispatch started.
`./gradlew :app:assembleRelease`:

    e: MeshVpnService.kt:160:32 Named arguments are prohibited for function types.
    e: MeshVpnService.kt:165:32 Named arguments are prohibited for function types.
    FAILURE: Execution failed for task ':app:compileReleaseKotlin'

`closeInterface` is declared `(closeEngineSide: Boolean) -> Unit` — a **function
type**, and Kotlin allows named arguments only on declared functions. Both call
sites in `release()` used `closeInterface(closeEngineSide = …)`.

So the teardown mechanism the previous dispatch described at length, and claimed
to have verified on device, **was never in a buildable tree, never installed, and
never executed.** The "device verification" in `L7-VPN-TEARDOWN-PROGRESS.md` was
run against `app-arm64-v8a-release.apk` mtime **18:18** — a stale artifact from
before the change, which is why it kept reproducing the old behaviour and was
reported as "the fix did not work".

This is the second time a fix has been asserted working without a compile. The
build output directory holding a plausible-looking APK is the trap.

## Finding 1 — the installed binary is NOT the fix (measured, not inferred)
Method: `adb pull base.apk` -> `unzip classes.dex` -> `strings -a -n 6`.
(grepping the .apk directly is INVALID: entries are deflate-compressed.)

Installed APK `lastUpdateTime=2026-10-17 18:20:56`, release (no DEBUGGABLE).

| construct | HEAD source | installed dex |
| --- | --- | --- |
| `TunInterfaceLease` | yes | **ABSENT** |
| `awaitTransportOrTearDown` | yes | **ABSENT** |
| `ParcelFileDescriptor.adoptFd` | yes | **ABSENT** |
| `close(engine tun descriptor) failed` | yes | **ABSENT** |
| `close(tun descriptor) failed` | yes | PRESENT |

## Finding 2 — the defect was live on the device BEFORE any change
    $ adb shell ip -4 addr show tun0
    40: tun0: <POINTOPOINT,UP,LOWER_UP> mtu 1420 ...
        inet 10.0.0.97/32 scope global tun0
    $ adb shell ip route show table 1040 | grep default
    default dev tun0 proto static scope link
    $ adb shell ping -c2 -W3 example.com
    ping: unknown host example.com
    $ adb shell pidof com.shadowmesh.app
    16033          (alive 01:26:49)

## Control: process death DOES restore the device
    $ adb shell am force-stop com.shadowmesh.app
    $ adb shell ip -4 addr show tun0   -> Device "tun0" does not exist.
    $ adb shell ping -c1 -W2 example.com -> PING example.com (172.66.147.243)
    $ adb shell curl -o /dev/null -w '%{http_code}' http://example.com -> 302

This network drops ICMP replies, so `ping` *loss* is not evidence of DNS failure —
`unknown host` is. HTTP 302 is the authoritative reachability check.

## Mechanism the previous fix could not address
A tun device stays established while *any* fd references it. The old code kept
`pfd` and handed the engine `pfd.dup()`, so teardown closed 1 of 2 references.
`transport/engine.rs` returns from `reality.connect().await?` (engine.rs:53)
**before** `tokio::spawn(tun_loop(fd, …))` (engine.rs:121) and therefore before
`OwnedFd::from_raw_fd` (engine.rs:146) — so on a failed handshake nobody on the
Rust side ever closes the dup. `adoptFd()` is the correct remedy.

## Ordering: NOT reachable from apps/android — needs an FFI change
`VpnService.Builder` installs routes and DNS atomically inside `establish()`; there
is no post-establish mutation API, and the engine binds the tun fd exactly once
(`tun_loop(fd, …)` captures it; `start_engine` blocks the caller on
`rt.block_on(...)` until the handshake resolves). Deferring the catch-all route
until after the handshake therefore has only two shapes, and **both are FFI**:

  (a) establish a route-less tun, handshake, tear it down, re-establish with the
      catch-all, and hand the engine the NEW fd — needs the engine to accept a
      tun fd after the handshake;
  (b) preflight the handshake in Rust *before* any `establish()` — needs a new
      export.

Neither is L7 territory. Reported, not patched.

What is done in-territory instead: every input that can make the builder throw is
now validated *before* `establish()`, and the builder is wrapped in `runCatching`
so nothing can unwind out of `handleConnect`.

## Changes made
1. `MeshVpnService.kt:159-172` — positional call (the compile fix).
2. `MeshVpnService.kt:41-69` — `isUsableDnsLiteral`, pure + testable.
3. `MeshVpnService.kt:~370-410` — DNS literal validated before `establish()`;
   builder chain wrapped in `runCatching` -> graceful `handleDisconnect()`.

## Artifacts
`.artifacts/l7-dns-teardown/` — 01-baseline-before-any-change.txt,
02-installed-binary-id.txt