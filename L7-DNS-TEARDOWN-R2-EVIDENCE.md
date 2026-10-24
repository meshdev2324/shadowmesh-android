# L7 dispatch #4: VPN teardown / DNS-survival — EVIDENCE LOG

Device: CPH2363, Android 14 (API 34), arm64-v8a, adb 37.0.0
Tree at start: **NOT clean** — HEAD 3509f99 plus uncommitted changes (see below).

## Pre-existing uncommitted state (NOT mine, inherited from dispatch #3)

    M apps/android/app/src/main/java/com/shadowmesh/app/MeshVpnService.kt   (+83/-)
    M services/server/src/api/handlers/vpn.rs        <- parallel L8, NOT mine, untouched
    M services/server/tests/vpn_handlers_test.rs     <- parallel L8, NOT mine, untouched
    ?? apps/android/L7-DNS-TEARDOWN-PROGRESS.md
    ?? apps/android/app/src/test/java/com/shadowmesh/app/DnsLiteralTest.kt

Dispatch #3 claimed its evidence lived in `.artifacts/l7-dns-teardown/`.
**That directory does not exist** (only an older `.artifacts/l7teardown/`).
Its claimed artifacts are unverifiable.

## Finding 1 — the "4 stopVpn references" are a misreading

`grep -n 'stopVpn' MeshVpnService.kt` returns 4 hits, and **not one is an Android
teardown call**:

    :18  import uniffi.shadowmesh.stopVpnEngine     <- Rust engine stop, not VpnService
    :107 * There is no `VpnService.stopVpn()` ...   <- COMMENT
    :635 * There is no VpnService.stopVpn() ...     <- COMMENT
    :647 stopVpnEngine()                            <- Rust engine stop

So `handleDisconnect()` indeed never calls a `VpnService.stopVpn()` — because
**no such public API exists**. Closing the tun descriptors IS the documented
teardown. The premise in the dispatch brief ("stopSelf() is not stopVpn(), and
that distinction is the likely reason it failed") is **incorrect**; adding a
`stopVpn()` call is not possible.

## Finding 2 — the ordering fix is FFI-gated (independently confirmed)

Enumerated the whole generated binding
`apps/android/core/vpn/src/main/java/uniffi/shadowmesh/shadowmesh.kt`.
Engine-related exports are exactly:

    uniffi_shadowmesh_core_fn_func_start_vpn_engine
    uniffi_shadowmesh_core_fn_func_stop_vpn_engine

There is **no preflight/probe export**, and `startVpnEngine(fd, config)` is the
only entry that runs the handshake — it *requires* the tun fd up front. There is
no way to (a) handshake before `establish()`, or (b) hand the engine a tun fd
after the handshake. `VpnService.Builder` has no post-establish mutation API.

=> Deferring `addRoute("0.0.0.0",0)` / `addDnsServer` until the transport is proven
**requires an FFI change. Reported, not patched (L7 has no FFI authority).**

Minimal FFI shape for L9: add `preflight(config) -> Result<Session>` that
completes REALITY/VLESS **without** a tun fd, then let `startVpnEngine` accept the
proven session. One new export; no change to the wire protocol.

## Finding 3 — shell cannot drive the defect repro

    $ adb shell am start-foreground-service -n com.shadowmesh.app/.MeshVpnService \
        -a com.shadowmesh.app.ACTION_CONNECT ...
    Permission Denial: Accessing service com.shadowmesh.app/.MeshVpnService
    from pid=19775, uid=2000 that is not exported from uid 10361

=> Reproducing on device requires driving the app's own UI, or an instrumented
test (runs in-UID). `am` from shell is a dead end.

## Baseline device state BEFORE any change (measured)

    adb shell dumpsys package com.shadowmesh.app | grep lastUpdateTime
        lastUpdateTime=2026-10-17 18:20:56
    adb shell ip -4 addr show tun0        -> Device "tun0" does not exist.
    adb shell ping -c1 -W3 example.com     -> PING example.com (104.20.23.154) ... 46.7 ms
    adb shell curl -w '%{http_code}' ...   -> 200

Device was CLEAN on arrival (no stale tun0). VPN consent present:
`appops get com.shadowmesh.app ACTIVATE_VPN -> allow`.

The **installed APK is stale**: on-disk
`app/build/outputs/apk/release/app-arm64-v8a-release.apk` had mtime **18:18**,
and the installed `lastUpdateTime` is **18:20:56** — both predate the
`TunInterfaceLease` mechanism in HEAD. So the binary on the device does **not**
contain the descriptor-ownership fix. Any "it still doesn't work" report run
against that APK is measuring the OLD code, not the fix.

## Finding 4 — DEVICE PHYSICALLY DISCONNECTED mid-dispatch (blocks device proof)

Partway through this dispatch the phone left the USB bus entirely:

    $ lsusb | grep -iE '18d1|android'
    (nothing — the 'Google Inc. Nexus/Pixel Device (MIDI + debug)' entry that was
     present at the start of the dispatch is gone)
    $ adb devices
    List of devices attached
    (empty)

8 reconnect attempts over 40s all failed. This is the same class of event the
brief warned about ("laptop suspends kill dispatches mid-edit"), and it is the
most likely reason earlier dispatches fell back on stale build artifacts and
reported device results they had not actually measured.

**Consequence: items 1 and 5 of the brief (observe-before-change and prove-on-
device) cannot be completed until the phone returns.** A watcher
(`watch_device.sh`) re-establishes adb and captures the proof automatically when
it does, so a suspend can no longer destroy the evidence. Until that lands,
**no claim is made that the fix works.**