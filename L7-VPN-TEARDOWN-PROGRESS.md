# L7 dispatch: VPN teardown / DNS-survival defect

Status: **MECHANISM CONFIRMED ON DEVICE** (2026-10-17, second L7 dispatch)

## WHAT THE PRIOR DISPATCH GOT RIGHT (do not redo)

- `VpnService` has **no `stopVpn()`**; `Builder` has no teardown method. Verified by
  enumerating the full public surface of `android/net/VpnService.class` from
  `platforms/android-35/android.jar`. The brief's premise is wrong on this point.
- Closing the descriptor from `establish()` is the documented teardown —
  AOSP `Builder.establish()` javadoc: *"The application MUST close the file
  descriptor when the VPN connection is terminated. The VPN interface will be
  removed and the network will be restored by the system automatically."*
- `TunInterfaceLease` (MeshVpnService.kt:113-158) and its 6 tests are sound.
- The `engine.rs` finding (stop_engine closes nothing) is still open and is L9's
  tree, not mine.

## THE REAL REASON THE PRIOR FIX DID NOT WORK

The prior fix kept `pfd` and handed the engine `pfd.dup().detachFd()`. **That dup is
what defeats the teardown.**

A tun device stays established for as long as *any* file descriptor references it.
The prior fix left **two** references:

  1. `pfd`  — the ParcelFileDescriptor the lease closes.
  2. `pfd.dup()` — the descriptor handed to the Rust engine.

`lease.release()` closes (1). Reference (2) is still open, because
`transport/engine.rs` `start_engine` does `reality.connect().await?` **before**
`tokio::spawn(tun_loop(...))`, so on a failed handshake it returns early and
`OwnedFd::from_raw_fd` is never constructed — nobody ever closes (2).

So the teardown runs, closes half the references, and the interface stays up.

The old code had the mirror-image bug: a single reference, handed away with
`detachFd()`, leaving Kotlin with zero authority. The fix moved the leak; it did
not remove it.

### Device proof — the fix's failure path DID run, and the interface survived

Release build (cert SHA-256 `750f5a4f…`, fix strings verified present in dex),
CPH2363 / Android 14, node "Singapore", REALITY mode:

    18:45:35.143  I SHADOWMESH_DEBUG: 🚀 Selected Mode: REALITY (Attempt: 0 ...)
    18:45:35.433  I SHADOWMESH_DEBUG: 🚀 Starting Native Rust engine (mode=reality)
    18:45:35.438  I SHADOWMESH_DEBUG: 🛡️ Socket 160 protected from VPN
    18:45:37.345  E SHADOWMESH_DEBUG: FATAL: VPN engine crashed
    18:45:37.345  E SHADOWMESH_DEBUG: uniffi.shadowmesh.ShadowMeshException$Other: Other error:
                   REALITY: server rejected handshake with alert 47
    18:45:37.345  I SHADOWMESH_DEBUG: 🛑 handleDisconnect: Tearing down VPN...   <-- ran
    18:46:00.394  E SHADOWMESH_DEBUG: Handshake succeeded but no traffic flowing on mode reality. Escalating...
    18:46:00.399  I SHADOWMESH_DEBUG: 🛑 handleDisconnect: Tearing down VPN...   <-- ran again
    18:46:07.401  W SHADOWMESH_DEBUG: Attempt 0 failed, retrying... Traffic flow timeout

33 s after `handleDisconnect()` completed, on the device:

    40: tun0    inet 10.0.0.97/32 scope global tun0
    default dev tun0 table 1040 proto static scope link
    $ ping -c1 -W3 example.com   ->  ping: unknown host example.com
    $ ping -c1 -W3 1.1.1.1       ->  (no reply)

### Control experiment: process death DOES tear it down

    $ adb shell am force-stop com.shadowmesh.app
    (no tun0)                       <- interface gone
    $ ping -c2 -W3 example.com
    2 packets transmitted, 2 received, 0% packet loss
    rtt min/avg/max/mdev = 49.975/74.725/99.476/24.752 ms

Process death closes *both* references and the platform restores the network.
This confirms fd ownership is the teardown authority, and that closing only one
of the two references is not enough.

## THE FIX

`ParcelFileDescriptor.adoptFd(int)` is public API (since API 13) and is the exact
inverse of `detachFd()`. Wrapping the engine's descriptor in it at hand-off gives
Kotlin a closable handle on reference (2) that does **not** depend on the engine
thread ever returning — which matters, because a connect can hang rather than fail.

Teardown closes both references, so the interface comes down on every path.
On success the engine has taken ownership, so the handle is *relinquished*
(`detachFd()`, no close) rather than closed — no double close.

## TIDY OBSERVATION (not a bug)

The 18:21 leftover had **zero** service log lines in a buffer spanning
18:21:23–18:42:22, i.e. the process was force-stopped by an install 1 s after
`establish()`. It was never a hung handshake; it was a zombie from a killed
process. The 18:45 run above is the real, clean reproduction.

## NOTE ON THE BRIEF'S LINE NUMBERS

`:196 addRoute`, `:197 addDnsServer`, `:227 establish`, `:166 intent extra` were
correct against the pre-dispatch file. In the current file the builder is
MeshVpnService.kt:293-299 and `establish()` is :326.

## ARTIFACTS

`.artifacts/l7teardown/` — before.txt, exp-forcestop.txt,
live-failed-connect-STATE.txt, build-release.log