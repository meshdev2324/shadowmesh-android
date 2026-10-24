# L7 dispatch #6 — the ~25s silent-handshake window, closed and measured

Device: CPH2363, Android 14 (API 34), arm64-v8a.
Binary: `lastUpdateTime=2026-10-17 22:58:58`, and the fix was confirmed present
in the built dex before installing (`no traffic arrived within` hits=1,
`Interface was torn down while awaiting transport proof` hits=1).

## The change

`startVpnEngine` returning means the REALITY/VLESS handshake resolved and the
node's UDP relay opened. It does not mean the tunnel works — a node behind a
filtering middlebox completes the handshake and then relays nothing. That
return was calling `confirmTransport()`, which flipped the lease to CONFIRMED,
which made the 15s watchdog correctly decline to act. The interface then sat
holding a catch-all route and a resolver at `10.0.0.53` until the connect
ladder's separate 25s traffic probe gave up.

The lease now stays PENDING until the engine's own receive counter moves.
While it is PENDING, both the proof deadline and the watchdog stay armed.
`release()` closes both descriptors on the timeout path too: the engine took
the fd but never proved it works, so this side still owns it.

## Measured, same device, same silent-handshake path

Before (dispatch #5, NORMAL mode — the silent path):

    22:41:10.413  Starting Native Rust engine (mode=normal)
    22:41:35.388  Handshake succeeded but no traffic flowing on mode normal
                   ^ 24.98s with tun0 up and DNS dead

After:

    23:00:01.452  Starting Native Rust engine (mode=normal)
    23:00:09.533  Handshake returned but no traffic arrived within 8000ms;
                   tearing the interface down so the device keeps its DNS
    23:00:09.596  handleDisconnect: Tearing down VPN...
                   ^ 8.08s

**24.98s → 8.08s.** Device sampling agrees, independently of the log:

    T+36  UP    DEAD-DNS
    T+37  UP    DEAD-DNS
     ... 8 consecutive samples ...
    T+43  UP    DEAD-DNS
    T+44  ABSENT -

Eight samples of a dead DNS, then the interface is gone. Previously it stayed
up for the full 25s probe.

## The other teardown path still holds

Attempt 0 of the same run was REALITY and was rejected outright — the fast
path from dispatch #5, unaffected by this change:

    22:59:24.331  Starting Native Rust engine (mode=reality)
    22:59:24.953  FATAL: VPN engine crashed
                   REALITY: server rejected handshake with alert 47
    22:59:24.996  handleDisconnect: Tearing down VPN...
                   ^ 43ms

## Why the ladder can now recover

The ladder's own API calls need DNS. Previously attempt N's corpse was still
black-holing them during attempt N+1. With the interface handed back at 8s
instead of 25s, the device has a resolver for 17 more seconds of every
attempt, and after the ladder settles:

    $ adb shell ip -4 addr show tun0
    Device "tun0" does not exist.
    $ adb shell ping -c1 -W3 example.com
    PING example.com (172.66.147.243) 56(84) bytes of data.
    64 bytes from 172.66.147.243: icmp_seq=1 ttl=53 time=41.2 ms
    $ adb shell curl -o /dev/null -w '%{http_code}' http://example.com
    200

## Per-app routing, stated not hidden

Under split-tunnelling INCLUDE an idle tunnel produces no traffic at all, so a
zero receive counter cannot be distinguished from a dead one and the handshake
return is still accepted there. That acceptance is now bounded by the 8s proof
budget instead of running open-ended, and it is the one case where the tunnel
can be declared good without proof. `TransportProof.isProof` makes that branch
explicit and `TransportProofBudgetTest` pins it.

## Tests

    ./gradlew :app:testDebugUnitTest --offline
    BUILD SUCCESSFUL
    tests=235 failures=0 errors=0 skipped=0
      TransportProofBudgetTest   8
      TunInterfaceLeaseTest      7
      DnsLiteralTest             8

They assert on proof and timers, not UI: that a silent handshake is not proof,
that the worst-case outage (`PROOF_TIMEOUT_MS + PROOF_POLL_MS`) stays inside
the 25000ms it replaced, that the proof budget is shorter than the watchdog
that bounds it, and that polling is fine-grained enough not to spend the budget
blind. A test asserting "an error was shown" would be worthless here — the
defect was never visible in the UI.

## Not done here

Alert 47 is the REALITY key conflation under repair by the parallel L8 dispatch
in `services/`. Observed, untouched.

The ordering fix — deferring `addRoute("0.0.0.0", 0)` and `addDnsServer` until
the transport is proven — remains specified but unbuilt. `VpnService.Builder`
installs routes and DNS atomically inside `establish()` and has no
post-establish mutation API, and the engine binds the tun fd exactly once, so
the smallest shape is a Rust `preflight(config) -> Result<Session>` that
completes REALITY/VLESS with no tun fd, after which `startVpnEngine` accepts the
proven session. One new export, no wire-protocol change. That is an FFI
addition and an owner decision; it is not made here.