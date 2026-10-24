# L7 dispatch #5 — DNS blackhole: observed BEFORE, with the mechanism proven

Device: CPH2363, Android 14 (API 34), arm64-v8a.
Binary under test: installed APK `lastUpdateTime=2026-10-17 18:20:56`, which
predates the teardown fix (confirmed by dex string audit, below).

## 1. Observed device state BEFORE any change of mine — the defect is live

Captured while a connect attempt was in flight, not simulated:

    $ adb shell ip -4 addr show tun0
    42: tun0: <POINTOPOINT,UP,LOWER_UP> mtu 1420 qdisc pfifo_fast state UNKNOWN group default qlen 500
        inet 10.0.0.97/32 scope global tun0
           valid_lft forever preferred_lft forever

    $ adb shell ping -c1 -W3 example.com
    ping: unknown host example.com

    $ adb shell curl -s -m 8 -o /dev/null -w '%{http_code}' http://example.com
    000

    $ adb shell appops get com.shadowmesh.app ACTIVATE_VPN
    ACTIVATE_VPN: allow; time=+55s405ms ago

`tun0` up, device has **no working DNS**, no HTTP egress, VPN consent live.

## 2. Why the previous fix did not work — mechanism, from the log

`handleDisconnect()` provably ran. Twice. The interface survived anyway:

    22:16:25.023 I/SHADOWMESH_DEBUG( 6087): handleDisconnect: Tearing down VPN...
    22:16:25.024 I/SHADOWMESH_RUST( 6087): engine: ShadowMesh Unified Engine Stopped.
    22:16:50.097 I/SHADOWMESH_DEBUG( 6087): handleDisconnect: Tearing down VPN...
    22:16:50.098 I/SHADOWMESH_RUST( 6087): engine: ShadowMesh Unified Engine Stopped.

So the teardown path executed and `tun0` was still established afterwards. The
cause is descriptor count, not a missing call: `establish()` yields **two**
descriptors for the interface — the `ParcelFileDescriptor` it returns, and the
duplicate handed to the Rust engine. A tun device stays established while *any*
descriptor references it. The old code closed only the first, and the engine only
takes ownership of its duplicate **after** the handshake succeeds
(`transport/engine.rs` returns from `reality.connect()` before it spawns
`tun_loop`). On a failed handshake nothing in Rust ever closes it, so the
interface outlives every teardown.

Corollary: the brief's premise that `handleDisconnect()` is missing a
`VpnService.stopVpn()` call is **incorrect**. No such public API exists on either
`VpnService` or `VpnService.Builder`; closing the descriptors *is* the documented
teardown. `stopVpnEngine()` is the Rust engine stop, unrelated.

## 3. "Each attempt degrades the next" — proven literally

    22:16:04.962  Selected Mode: REALITY (Attempt: 0)
    22:16:10.973  API_FAILURE_DIAGNOSTIC ... url=.../api/v1/config/1?...&mode=reality
                  source: ... "dns error" ... "failed to lookup address information:
                  No address associated with hostname"
    22:16:25.008  Attempt 0 failed, retrying...
    22:16:25.023  handleDisconnect: Tearing down VPN...
    22:16:30.015  Selected Mode: NORMAL (Attempt: 1)
    22:16:36/43/50  same dns error, three times
    22:16:50.082  Attempt 1 failed, retrying...

The retry ladder needs DNS for its own `getConfig` call. The dead `tun0` left by
attempt N is what makes attempt N+1's API call fail. Every ladder rung therefore
fails for the same self-inflicted reason, and no rung can ever reach the node.

## 4. The installed binary did not contain the fix (dex audit)

`unzip classes*.dex` then `strings -a -n 6` — grepping the .apk directly is
invalid because its entries are deflate-compressed.

| construct | built APK 22:07 | installed APK 18:20:56 |
| --- | --- | --- |
| `close(engine tun descriptor) failed` | PRESENT | ABSENT |
| `Superseding an interface still standing` | PRESENT | ABSENT |
| `adoptFd` | PRESENT | ABSENT |

(`TunInterfaceLease` / `awaitTransportOrTearDown` are renamed by R8 and are not
reliable markers; the three strings above are unique to the fix.)

## 5. Compile gate — the previous dispatch's other failure

Dispatch #3 landed a mechanism that **did not compile**: `closeInterface` is a
function type, and Kotlin forbids named arguments on those. So the whole
teardown mechanism was never in a buildable tree and never reached a device.

    $ ./gradlew :app:compileReleaseKotlin --offline
    BUILD SUCCESSFUL in 19s