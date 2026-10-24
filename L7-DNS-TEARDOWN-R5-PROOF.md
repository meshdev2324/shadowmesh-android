# L7 dispatch #5 — device proof of the teardown fix, and one residual gap

Binary: the fix is installed and verified present in the *device's own* dex,
not just the build tree. Pulled `base.apk` via `pm path`, unzipped
`classes*.dex` (grepping the .apk directly is invalid — entries are
deflate-compressed):

    close(engine tun descriptor) failed          hits=1
    Superseding an interface still standing      hits=1
    Engine did not confirm a transport within    hits=1
    dex bytes: 6891192  (identical to the local build)

## The failed-connect path now tears down, on device

Controlled run: force-stop, confirm clean, clear logcat, launch, swipe the
real slider. `tun0` sampled every second.

    22:40:33.137  Selected Mode: REALITY (Attempt: 0)
    22:40:33.320  Starting Native Rust engine (mode=reality)
    22:40:34.029  FATAL: VPN engine crashed
                   REALITY: server rejected handshake with alert 47
    22:40:34.088  handleDisconnect: Tearing down VPN...

    T+1s  ..  T+36s   tun0=ABSENT

Thirty-six consecutive seconds with no `tun0` and DNS resolving. The same
failure on the old binary left `tun0` up indefinitely (see
`L7-DNS-TEARDOWN-R5-EVIDENCE.md` §1, where it was still up with
`unknown host example.com` long after `handleDisconnect` had run twice).

So the descriptor-ownership fix does what it was written to do: on a failed
handshake, `lease.release()` closes both the `ParcelFileDescriptor` and the
engine's duplicate, and the platform takes the interface down.

## The residual gap that is NOT fixed

`tun0` reappeared at T+37s and was up, with dead DNS, at the end of the
window. That is not the leak the fix targeted — it is a different failure mode:

    22:41:10.292  Selected Mode: NORMAL (Attempt: 1)
    22:41:10.413  Starting Native Rust engine (mode=normal)
    22:41:35.388  Handshake succeeded but no traffic flowing on mode normal
    22:41:35.396  handleDisconnect: Tearing down VPN...

`startVpnEngine` blocks for ~25s and then *returns*, so
`lease.confirmTransport()` has already flipped the lease to CONFIRMED and the
15s watchdog correctly declines to tear down ("the engine owns its descriptor
now"). The interface then stays up — catch-all route, resolver
`10.0.0.53`, no traffic — until the separate 25s traffic-flow check fires.

So there are two distinct DNS-outage windows and only one is fixed:

| failure mode | who tears down | device left with no DNS for |
| --- | --- | --- |
| handshake rejected (alert 47) | `MeshVpnService`, ~60ms | **0s — fixed** |
| handshake accepted, no traffic | the traffic-flow check in `core/vpn` | **~25s — still broken** |

The second window is a real client-side gap and it is inside `apps/android`
(`core/vpn`'s `ConnectUseCase`), so it is in scope for L7 — but it is a
separate defect with a separate fix (the traffic check and the interface
lifetime have to be bound together) and it is not what this commit addresses.

## Tests

    ./gradlew :app:testDebugUnitTest --offline
    BUILD SUCCESSFUL
    24 classes, tests=227 failures=0 errors=0 skipped=0

    TunInterfaceLeaseTest: 7 tests, 0 failures
      racing teardown and handshake close the interface exactly once
      a disconnect landing mid-handshake is not reported as a connected tunnel
      release is idempotent so a repeated teardown cannot double close
      release after confirmation still closes the interface exactly once
      a confirmed transport relinquishes the engine descriptor instead of closing it
      failed handshake closes the engine descriptor as well as the interface
      user disconnect after confirmation still closes the interface and not the engine descriptor
    DnsLiteralTest: 8 tests, 0 failures

These assert on descriptor ownership, not on UI. `interfaceCloses` and
`engineSideCloses` stand in for the two descriptors, because a tun device stays
established while any one of them is open — so "the connect reported an error"
is the wrong assertion and "both descriptors closed" is the right one.

There is no automated test for the ~25s no-traffic window above. It cannot be
written until the traffic check and the interface lifetime are bound together;
writing one now would only encode the defect as expected behaviour.

## Device left clean

After the observation the device was deliberately restored, since leaving a
user's phone without DNS is the defect itself:

    $ adb shell am force-stop com.shadowmesh.app
    $ adb shell ip -4 addr show tun0
    Device "tun0" does not exist.
    $ adb shell ping -c1 -W3 example.com
    PING example.com (172.66.147.243) 56(84) bytes of data.
    64 bytes from 172.66.147.243: icmp_seq=1 ttl=53 time=39.4 ms
    $ adb shell curl -o /dev/null -w '%{http_code}' http://example.com
    200

## Not chased

Alert 47 (`REALITY: server rejected handshake ... check public key and
short_id`) is the REALITY key conflation under active repair by the parallel
L8 dispatch in `services/`. Observed, not patched, per scope. It is worth
noting that it is what produced the *fast* teardown above: a rejected
handshake is the well-behaved failure, and the silent no-traffic handshake is
the one that costs the user 25 seconds of DNS.