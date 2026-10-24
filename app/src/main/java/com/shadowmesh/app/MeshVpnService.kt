package com.shadowmesh.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import android.content.pm.PackageManager
import android.net.VpnService
import android.system.OsConstants
import com.shadowmesh.core_vpn.Config
import com.shadowmesh.app.util.ZLog
import com.shadowmesh.core_vpn.EngineStatus
import com.shadowmesh.core_vpn.SecureStorage
import com.shadowmesh.core_vpn.TransportProof
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import uniffi.shadowmesh.VpnManager
import uniffi.shadowmesh.stopVpnEngine
import uniffi.shadowmesh.VpnConfig
import uniffi.shadowmesh.RealityConfig
import uniffi.shadowmesh.registerSocketProtector
import uniffi.shadowmesh.SocketProtector
import android.os.ParcelFileDescriptor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal data class TunnelAddress(
    val address: String,
    val prefixLength: Int
)

internal fun parseTunnelAddress(raw: String): TunnelAddress? {
    val parts = raw.trim().split('/', limit = 2)
    val address = parts.getOrNull(0)?.trim().orEmpty()
    val prefixLength = parts.getOrNull(1)?.toIntOrNull() ?: 32
    if (address.isBlank() || prefixLength !in 1..32) return null
    return TunnelAddress(address, prefixLength)
}

/**
 * Whether the resolver literal in a connect intent is something the tunnel can
 * actually be built around.
 *
 * Deliberately *syntactic* and deliberately not `InetAddress.getByName`: this runs
 * on the connect path, and a resolver lookup here would be exactly the DNS
 * dependency the teardown work exists to keep off the failure path. It is a
 * pre-filter, not a parser — `Builder.addDnsServer` remains the authority, and
 * the builder is wrapped in `runCatching` so anything this misses fails the
 * connect instead of crashing the service.
 *
 * The invariant that matters is the negative one: we must never establish a
 * catch-all tunnel with no resolver, because that silently hands the device's DNS
 * to the underlying network.
 */
internal fun isUsableDnsLiteral(raw: String): Boolean {
    val v = raw.trim()
    if (v.isEmpty()) return false
    // Whitespace means this is a name or a malformed list, not a literal.
    if (v.any { it.isWhitespace() }) return false
    // A hostname must never reach addDnsServer: the tunnel's resolver is a literal
    // inside the tunnel, and resolving here would leak the lookup past the tunnel.
    if (v.contains('/')) return false
    // IPv4 is dotted quad; IPv6 is hex groups separated by ':'. Anything else is
    // not an address literal at all.
    val looksV4 = v.all { it.isDigit() || it == '.' }
    val looksV6 = v.contains(':') && v.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
    return looksV4 || looksV6
}

/**
 * The outcome of taking ownership of the descriptor the engine is to be given.
 *
 * @param H the platform handle type, so this stays testable off-device.
 */
internal sealed interface DescriptorHandoff<out H> {
    /** Ownership was taken; the engine may be started with the raw descriptor. */
    data class Owned<H>(val handle: H) : DescriptorHandoff<H>

    /**
     * Ownership could not be taken. The descriptor has been closed and the
     * connect must be refused.
     */
    data object Refused : DescriptorHandoff<Nothing>
}

/**
 * Gives the engine's descriptor an owner, or refuses the connect.
 *
 * `builder.establish()` returns `pfd`, and the engine is handed a *duplicate* of
 * it. That duplicate is the second reference that keeps the tun device
 * established, so it has to be closable for teardown to work at all.
 *
 * The subtlety this exists for: once the duplicate has been produced by
 * `pfd.dup().detachFd()` it is owned by **nobody**. Not by `pfd` (a different
 * descriptor), not by the engine (which wraps it in an `OwnedFd` only *after* a
 * successful handshake, so every failed handshake leaves it untouched), and not
 * by the service unless ownership is successfully taken here.
 *
 * So a failure to take ownership is not "there is no descriptor" — it is
 * "there is a descriptor that nobody can close". Continuing past it starts the
 * engine on a descriptor that outlives every teardown path, which leaves the
 * device holding a `0.0.0.0/0` catch-all route and a resolver at `10.0.0.53`
 * with nothing behind it: the production blackhole, reached through a different
 * door than the one the previous fix closed. "Close both descriptors" only helps
 * while the second one is still reachable.
 *
 * Generic in the handle type and injected on both sides so the invariant is
 * assertable in a plain JVM test; a test asserting "an error is shown" would
 * prove nothing, because the app's error handling is correct throughout this
 * failure.
 *
 * @param adopt takes ownership without closing. Must not throw.
 * @param close releases the raw descriptor when ownership could not be taken.
 */
internal fun <H> takeDescriptorOwnership(
    fd: Int,
    adopt: (Int) -> H?,
    close: (Int) -> Unit,
): DescriptorHandoff<H> {
    val handle = runCatching { adopt(fd) }.getOrNull()
    if (handle == null) {
        // The duplicate is already detached, so nothing else will ever close it.
        runCatching { close(fd) }
        return DescriptorHandoff.Refused
    }
    return DescriptorHandoff.Owned(handle)
}

/** What happens to IPv6 while the tunnel is up (leak-verification posture). */
internal enum class Ipv6Posture {
    /** The engine carries IPv6: route ::/0 into the tunnel. */
    ROUTE_INTO_TUNNEL,

    /**
     * The engine cannot carry IPv6, so an unrouted IPv6 stack would bypass the
     * tunnel wholesale — including DNS to the link's IPv6 resolvers. Blocking
     * the family is the only leak-proof posture.
     */
    BLOCK,

    /** Leak protection is off: the user has opted out of IPv6 coverage. */
    PASS_THROUGH_UNDERLYING,
}

/** Whether the Rust engine currently forwards IPv6 packets from the tun. */
internal const val ENGINE_SUPPORTS_IPV6 = false

/**
 * Decides the IPv6 posture from what the engine can carry and what the user
 * chose. Pure so the invariant that matters — a dual-stack network can never
 * send IPv6 around the tunnel while protection is on (the default) — is unit-
 * testable without the framework.
 */
internal fun ipv6Posture(
    engineSupportsIpv6: Boolean,
    leakProtectionEnabled: Boolean,
): Ipv6Posture = when {
    engineSupportsIpv6 -> Ipv6Posture.ROUTE_INTO_TUNNEL
    leakProtectionEnabled -> Ipv6Posture.BLOCK
    else -> Ipv6Posture.PASS_THROUGH_UNDERLYING
}

/**
 * Owns the lifetime of the tun interface, which is **two descriptors, not one**.
 *
 * There is no `VpnService.stopVpn()` and `VpnService.Builder` has no teardown
 * method — the whole public surface of both classes was enumerated from
 * `android.jar` to confirm it. So descriptor ownership *is* the interface:
 * the catch-all route and the `addDnsServer` resolver at `10.0.0.53` are live
 * from the moment `establish()` returns until the last descriptor referencing
 * the tun device is closed, and only then does the platform remove the
 * interface and hand the device its DNS back.
 *
 * The two descriptors are:
 *
 *  1. `pfd` — what `establish()` returned. The service's authority.
 *  2. `engineFd` — a duplicate of it, handed to the Rust engine.
 *
 * **Closing only (1) does not tear the interface down.** That was the previous
 * fix, and it is why a failed connect still left the phone with no DNS: a tun
 * device stays established while *any* descriptor references it, so `pfd.close()`
 * left (2) open and the interface survived a `handleDisconnect()` that provably
 * ran. Verified on device — the service logged `handleDisconnect: Tearing down
 * VPN...` and `tun0` was still there 33 seconds later with `ping example.com`
 * returning `unknown host`.
 *
 * The engine takes ownership of (2) only once the handshake has *succeeded*:
 * `transport/engine.rs` calls `reality.connect().await?` **before** it spawns
 * the `tun_loop` that wraps the descriptor in an `OwnedFd`, so every failed
 * handshake returns early and nothing on the Rust side owns (2). So [release]
 * has to close it — and it must be able to do that *without* waiting for the
 * engine to return, because a connect can hang rather than fail.
 *
 * On success the engine does own (2), so [confirmTransport] relinquishes the
 * service's handle to it (`ParcelFileDescriptor.detachFd()` — gives up ownership
 * without closing) rather than closing a descriptor the engine is holding.
 *
 * State machine: PENDING → CONFIRMED → CLOSED, all transitions CAS'd, so
 * [closeInterface] runs at most once however many teardown paths arrive at once.
 *
 * Pure and injectable so the invariant can be asserted in a plain JVM test. A
 * test that checks the UI shows an error proves nothing about whether the
 * *device* was left broken; these assert on the descriptors instead.
 */
internal class TunInterfaceLease internal constructor(
    /** @param closeEngineSide true if the engine never took ownership of its descriptor. */
    private val closeInterface: (closeEngineSide: Boolean) -> Unit,
    /** Gives up ownership of the engine's descriptor *without* closing it. */
    private val relinquishEngineSide: () -> Unit,
) {

    private val state = AtomicInteger(PENDING)

    /**
     * Records that the node confirmed the transport, and returns whether the
     * interface is still up.
     *
     * False means a concurrent `onRevoke` or `ACTION_DISCONNECT` already tore the
     * interface down mid-handshake, so the engine is now running on a descriptor
     * the device does not route through. The caller must not report that as a
     * connected tunnel.
     */
    fun confirmTransport(): Boolean {
        // Only the winner of PENDING → CONFIRMED may hand the descriptor over.
        // A loser has already had it closed and must not pretend otherwise.
        if (!state.compareAndSet(PENDING, CONFIRMED)) return false
        relinquishEngineSide()
        return true
    }

    /**
     * Closes the interface so the platform takes down the catch-all route and
     * the resolver, and hands the device its DNS back. Idempotent and safe from
     * any thread.
     *
     * Which descriptors this closes is decided by the CAS itself rather than by
     * a flag read afterwards. Two terminal states, not one, because "the engine
     * took the descriptor" and "we are closing it" must be mutually exclusive by
     * construction: a separate flag is readable in between, and a teardown
     * landing in that gap would both close the descriptor the engine is about to
     * own *and* hand it over.
     */
    fun release() {
        when {
            // Never confirmed: the engine never took ownership, so this side
            // still has to close it. This is the failed-connect case, and the
            // descriptor whose leak left tun0 standing.
            //
            // `closeInterface` is a *function type* `(Boolean) -> Unit`, and Kotlin
            // forbids named arguments on those — only on declared functions. Calling
            // it with `closeEngineSide = true` is a **compile error**, not a style
            // nit: it took this whole teardown mechanism out of the build, so the
            // fix that was supposed to take `tun0` down never reached a device.
            // The argument is derived from the state, so it is passed positionally.
            state.compareAndSet(PENDING, CLOSED_OWNING_ENGINE) ->
                closeInterface(true)
            // Confirmed first: the engine owns that descriptor and closes it when
            // its loop exits. Closing it here too would be a double close
            // against an fd still registered with tokio's reactor.
            state.compareAndSet(CONFIRMED, CLOSED_ENGINE_OWNS) ->
                closeInterface(false)
        }
    }

    /** Test seam: whether the transport has been confirmed by the node. */
    internal val transportConfirmed: Boolean
        get() = state.get() == CONFIRMED

    /** Test seam: whether the interface has been torn down. */
    internal val isReleased: Boolean
        get() = state.get() == CLOSED_OWNING_ENGINE || state.get() == CLOSED_ENGINE_OWNS

    private companion object {
        const val PENDING = 0
        const val CONFIRMED = 1

        /** Torn down while the engine had not taken its descriptor; we closed both. */
        const val CLOSED_OWNING_ENGINE = 2

        /** Torn down after the engine took its descriptor; we closed only ours. */
        const val CLOSED_ENGINE_OWNS = 3
    }
}

/**
 * The capture a connect installed: the interface's lease, and the raw
 * descriptor the engine is to be given.
 *
 * @param H the platform handle type, so this stays testable off-device.
 */
internal class CaptureInstall internal constructor(
    /** The lease on the tun interface — two descriptors, not one. */
    val lease: TunInterfaceLease,
    /** The engine's own duplicate of the tun descriptor. */
    val fd: Int,
)

/**
 * One connect attempt, AS AN OBJECT — the identity the per-attempt teardowns
 * are checked against (`attemptState.get() === attempt`).
 *
 * It supersedes the five `tunLease === lease` guards those teardowns used: a
 * lease identifies only the CAPTURE (the interface half), while an attempt
 * owns the whole chain — the session generation, the capture install and the
 * proof. Between a transport's proof-timeout and a superseding CONNECT the
 * lease answers the wrong question, and one attempt's failure becomes every
 * attempt's failure (RFC-048 §4.5). The object's identity is the distinction,
 * because a plain value could be reissued by a later attempt with the same
 * content and be mistaken for this one.
 *
 * The fields are written by the coordinator's own steps as they complete
 * (the install lambda at PHASE 2, the transport lambda at PHASE 1) and read
 * by the service's teardowns, which is why they are marked volatile and not
 * final.
 */
internal class ConnectAttempt internal constructor() {
    /** The attempt's capture install, once PHASE 2 ran; null while the transport handshake is in flight. */
    @Volatile
    var install: CaptureInstall? = null

    /** The generation this attempt owns, once PHASE 1 proved the transport. */
    @Volatile
    var generation: ULong? = null
}

@AndroidEntryPoint
class MeshVpnService : VpnService() {
    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "shadowmesh_vpn_channel"

        /**
         * Longest a connect may hold the device without working DNS before the
         * interface is taken back. A REALITY handshake resolves in ~2s on a good
         * link; 15s is generous enough not to cut off a real one on a bad mobile
         * connection, and short enough that a hung engine costs the user seconds
         * rather than a phone with no resolver. See [awaitTransportOrTearDown].
         */
        const val HANDSHAKE_TIMEOUT_MS = 15_000L
    }

    /**
     * The lease on the tun descriptor for the connect currently in flight, if
     * any. Held by the service rather than the engine thread so that a
     * disconnect arriving mid-handshake — `onRevoke` from a competing VPN app,
     * the user revoking consent, `ACTION_DISCONNECT`, or `onDestroy` — can take
     * the interface down instead of waiting for a handshake that may never
     * finish. See [TunInterfaceLease].
     */
    @Volatile
    private var tunLease: TunInterfaceLease? = null

    /**
     * The CURRENT connect attempt — the identity keyed on object reference
     * (`attemptState.get() === attempt`). A per-attempt teardown that is not
     * the current attempt's must not run: see [ConnectAttempt].
     */
    private val attemptState = AtomicReference<ConnectAttempt?>(null)

    /**
     * Whether a transport connect is in flight. Two concurrent
     * `connect_transport` calls would race two transport sessions against one
     * tun slot; the second attempt is refused at the seam and fails with
     * nothing installed and nothing owned.
     */
    private val connectInFlight = AtomicBoolean(false)

    /**
     * The engine's own stats handle, used to tell a tunnel that *works* from one
     * whose handshake merely completed. Hilt provides it as a singleton, which is
     * what makes it the same engine instance the coordinator's transport step
     * drives.
     */
    @Inject
    lateinit var vpnManager: VpnManager

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // v6.9.4: Register socket protector to prevent VPN infinite loops
        registerSocketProtector(object : SocketProtector {
            override fun protect(fd: Int): Boolean {
                val success = this@MeshVpnService.protect(fd)
                if (success) {
                    ZLog.d("SHADOWMESH_DEBUG", "🛡️ Socket $fd protected from VPN")
                } else {
                    ZLog.e("SHADOWMESH_DEBUG", "⚠️ Failed to protect socket $fd")
                }
                return success
            }
        })
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            "com.shadowmesh.app.ACTION_CONNECT" -> {
                // A failed connect tears itself down from inside handleConnect via
                // handleDisconnect(), which calls stopForeground() and stopSelf().
                // Falling through would then re-arm startForeground() for a tunnel
                // that does not exist, leaving a foreground service and an
                // "ShadowMesh Active" notification for nothing — and stopSelf() would
                // be racing the startForeground() that just undid it. This is the
                // same zombie-foreground-state hazard the ACTION_DISCONNECT branch
                // below already guards against, on the path that reaches it far more
                // often, because every refused connect lands here.
                if (!handleConnect(intent)) return START_NOT_STICKY
            }
            "com.shadowmesh.app.ACTION_DISCONNECT" -> {
                // Must not re-arm the foreground state after teardown: doing so leaves a
                // zombie VpnService holding a dead tun0 that blackholes all device traffic.
                handleDisconnect()
                return START_NOT_STICKY
            }
        }

        val secureStorage = SecureStorage.getInstance(this)
        val isCamouflage = secureStorage.getBoolean(Config.KEY_CAMOUFLAGE_ENABLED, false)

        val notification = createNotification(isCamouflage)
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            ZLog.e("MeshVpnService", "Failed to start foreground service", e)
        }

        return START_NOT_STICKY
    }

    override fun onRevoke() {
        // Android calls this both when a competing VPN app claims the device's
        // single VPN slot and when the user removes ShadowMesh from the allowed
        // VPN list. Those are very different events, and silently treating both
        // as a routine disconnect is what made users report "the VPN stops by
        // itself". VpnService.prepare() returns non-null only when consent has
        // been withdrawn, which distinguishes the two.
        val stillHasConsent = android.net.VpnService.prepare(this) == null
        val reason = com.shadowmesh.app.vpn.RevokeReasonPolicy.forRevoke(stillHasConsent)
        val message = com.shadowmesh.app.vpn.RevokeReasonPolicy.message(reason)
        ZLog.w("ShadowMeshVpnService", "onRevoke: $reason${if (message.isNotBlank()) " — $message" else ""}")

        // Persisted, not just logged: the UI may not be running when the slot is
        // taken, and a reason that only reaches logcat is a reason the user
        // never sees. The ViewModel consumes and clears it on next display.
        runCatching {
            com.shadowmesh.core_vpn.SecureStorage.getInstance(applicationContext)
                .set(Config.KEY_LAST_DISCONNECT_REASON, reason.name)
        }.onFailure { ZLog.e("ShadowMeshVpnService", "Could not persist disconnect reason", it) }

        handleDisconnect()
        super.onRevoke()
    }

    override fun onDestroy() {
        ZLog.i("SHADOWMESH_DEBUG", "MeshVpnService destroyed, releasing tunnel resources")
        handleDisconnect()
        super.onDestroy()
    }

    /**
     * @return true if a tunnel was established and ownership handed to the engine;
     * false if the attempt was refused or failed, in which case this has already
     * torn itself down and the caller must not re-arm the foreground service.
     */
    private fun handleConnect(intent: Intent): Boolean {
        val config = VpnConfig(
            privateKey = intent.getStringExtra("private_key"),
            publicKey = intent.getStringExtra("public_key") ?: "",
            address = intent.getStringExtra("address") ?: "",
            endpoint = intent.getStringExtra("endpoint") ?: "",
            dns = intent.getStringExtra("dns") ?: "",
            mtu = intent.getIntExtra("mtu", 1420).toUInt(),
            trafficMode = intent.getStringExtra("traffic_mode") ?: "normal",
            realityConfig = if (intent.hasExtra("reality_server_ip")) {
                RealityConfig(
                    serverIp = intent.getStringExtra("reality_server_ip") ?: "",
                    port = intent.getIntExtra("reality_port", 443).toUInt(),
                    uuid = intent.getStringExtra("reality_uuid") ?: "",
                    publicKey = intent.getStringExtra("reality_public_key") ?: "",
                    shortId = intent.getStringExtra("reality_short_id") ?: "",
                    sniTarget = intent.getStringExtra("reality_sni_target") ?: "",
                    fingerprint = intent.getStringExtra("reality_fingerprint")
                )
            } else null,
            shadowsocksConfig = null,
            hysteriaConfig = null,
            vmessConfig = null
        )

        // v8.0: Unified Rust engine — all traffic modes (normal / fragmented /
        // reality / websocket) are handled natively by shadowmesh-core.
        val tunnelAddress = parseTunnelAddress(config.address)
        if (tunnelAddress == null) {
            ZLog.e("MeshVpnService", "Invalid tunnel address")
            handleDisconnect()
            return false
        }

        // The resolver to install is an intent extra, so its provenance is only as
        // good as the caller: `intent.getStringExtra("dns") ?: ""` yields "" when
        // the key is absent, and `Builder.addDnsServer("")` throws
        // IllegalArgumentException from inside the builder chain. That exception
        // was uncaught, so it unwound straight out of onStartCommand and killed
        // the service instead of failing the connect cleanly.
        //
        // It must not be "fixed" by dropping addDnsServer: an established tun with
        // no DNS server hands the device's queries to the *underlying* network,
        // which for this product is the leak the VPN exists to prevent. So a
        // resolver we cannot use is a connect we refuse, not one we silently
        // downgrade.
        val tunnelDns = config.dns.trim()
        if (!isUsableDnsLiteral(tunnelDns)) {
            ZLog.e("MeshVpnService", "Refusing to connect: no usable resolver literal in the config")
            handleDisconnect()
            return false
        }

        // Everything that can throw is inside runCatching, so no Builder failure
        // can escape handleConnect. A throw here would leave the service
        // foregrounded with a notification for a tunnel that was never built.
        //
        // ⚠️ **The capture is NOT configured here.** The builder carries the
        // settings that are not part of the capture — the MTU, the non-blocking
        // hint, the session name, the IPv6 posture and the split-tunnel rules
        // — and the CAPTURE itself (the address, the catch-all route and the
        // resolver) is a pure value, `TunnelCaptureSpec`, installed through the
        // one seam, `VpnServiceCaptureInstaller`.
        //
        // ⇒ **This is what makes RFC-048 §4.1's reorder mechanical.** The
        // capture used to be three calls inside this chain and a fifth
        // (`establish()`) further down, so "install the capture AFTER the
        // transport is proven" would have meant unpicking the builder. Now the
        // capture is ONE value and ONE call, and the reorder is moving one
        // block.
        val builder = runCatching {
            this.Builder()
                .setMtu(config.mtu.toInt())
                .setBlocking(false) // v6.9 Non-blocking I/O hint
                .setSession("ShadowMesh")
        }.getOrElse { e ->
            ZLog.e("MeshVpnService", "VPN builder rejected the config; not establishing anything", e)
            handleDisconnect()
            return false
        }

        // The capture, as a pure value (RFC-048 §4.3).
        //
        // ⚠️ **The resolver is the CONFIG's literal** — never a literal written
        // here. The in-tunnel resolver (`10.0.0.53`) is answerable only once
        // the tunnel actually carries traffic, which is why a capture that
        // points at it before the transport is proven is not "protected", it is
        // broken (RFC-048 §6, item 5). And the route is the catch-all
        // `0.0.0.0/0`, because the capture takes traffic away from the
        // underlying network: a capture that does not say so explicitly is a
        // capture that does not capture.
        val captureSpec = TunnelCaptureSpec(
            address = tunnelAddress.address,
            prefixLength = tunnelAddress.prefixLength,
            route = "0.0.0.0",
            routePrefixLength = 0,
            resolver = tunnelDns,
        )

        // IPv6 leak posture (command-center leak-verification mandate). The
        // builder routed only 0.0.0.0/0, so on any dual-stack network every
        // IPv6 packet — DNS included — rode the underlying network around the
        // tunnel. The `dnsLeakProtection` setting existed but was consumed by
        // nothing. Today the engine forwards IPv4 only, so "protection on"
        // (the default) must BLOCK the family outright; when the core gains
        // IPv6, ROUTE_INTO_TUNNEL takes over via `addRoute("::", 0)`.
        val leakProtectionOn = SecureStorage.getInstance(this)
            .getBoolean(Config.KEY_DNS_LEAK_PROTECTION, true)
        when (ipv6Posture(ENGINE_SUPPORTS_IPV6, leakProtectionOn)) {
            // allowFamily(AF_INET) denies every other family: IPv6 traffic
            // cannot route around the tunnel while the engine is IPv4-only.
            Ipv6Posture.BLOCK -> builder.allowFamily(OsConstants.AF_INET)
            Ipv6Posture.ROUTE_INTO_TUNNEL -> builder.addRoute("::", 0)
            Ipv6Posture.PASS_THROUGH_UNDERLYING -> { /* user opted out */ }
        }

        // Split tunnelling. Until now the picker in Settings persisted a
        // SplitTunnelConfig that nothing ever read, so every selection silently
        // did nothing: per-app routing is a property of the VpnService.Builder,
        // and the builder was only ever given a full-tunnel 0.0.0.0/0 route.
        // Applied here, before establish(), which is the only point at which
        // Android accepts per-app rules.
        applySplitTunnel(builder, config)

        // Supersede any interface still standing *before* taking the VPN slot.
        //
        // `tunLease` holds exactly one lease, and `handleConnect` used to assign
        // `tunLease = lease` unconditionally. So a second CONNECT arriving while
        // the first attempt was still inside its handshake window (the retry
        // ladder does exactly this, and so does a user tapping connect twice)
        // overwrote the field — and with it the *only* reference to the first
        // attempt's two tun descriptors. From that moment nothing in the process
        // could close them, so that first interface stayed established for the
        // life of the process, holding a catch-all route and a dead resolver at
        // 10.0.0.53. That is the mechanism behind "each attempt degrades the
        // next": attempt N's corpse is still black-holing the DNS that attempt
        // N+1's own API calls need.
        //
        // Android allows one VPN per device and establishing a new one supersedes
        // the old at the framework level, so the old descriptors have to go here
        // rather than being left to overlap with the new tunnel.
        tunLease?.let { superseded ->
            ZLog.w("MeshVpnService", "Superseding an interface still standing from an earlier attempt")
            tunLease = null
            superseded.release()
        }

        // The attempt, AS AN OBJECT — the identity the per-attempt teardowns
        // are keyed on. Publishing it here is what stops a concurrent older
        // attempt's teardowns from acting on the state a newer attempt is
        // building (RFC-048 §4.5): they compare `attemptState.get()` against
        // their own object and lose.
        val attempt = ConnectAttempt()
        attemptState.set(attempt)

        // The attempt runs through TunnelCaptureCoordinator — ONE ordered
        // unit, the same seam the JVM tests drive, so the production path and
        // the test path cannot drift apart (RFC-048 §4.3). The lambdas are
        // the contract's phases in production terms:
        //
        // | phase | production lambda |
        // | :--- | :--- |
        // | 0b supersede | release the interface still standing (the lease-level supersede) |
        // | 1 connectTransport | `uniffi.shadowmesh.connectTransport(config)` → the generation |
        // | 2 spec + installer | `installCapture(builder, spec)` → the lease + fd pair |
        // | 3 attachTun | `uniffi.shadowmesh.attachTun(handle, fd)` |
        // | 4 awaitProof | the engine-side traffic proof, confirmed on the lease |
        //
        // The coordinator's fixed order proves the transport FIRST, so for the
        // whole handshake the device's DNS is untouched — the capture exists
        // only after a proven transport (RFC-048 §4.1).
        val coordinator = TunnelCaptureCoordinator<ULong, CaptureInstall>(
            specFor = { captureSpec },
            installer = CaptureInstaller { spec ->
                installCapture(builder, spec).also { attempt.install = it }
            },
            supersede = {
                tunLease?.let { supersededLease ->
                    ZLog.w("MeshVpnService", "Superseding an interface still standing from an earlier attempt")
                    tunLease = null
                    supersededLease.release()
                }
            },
            connectTransport = {
                if (connectInFlight.compareAndSet(false, true)) {
                    try {
                        runCatching { uniffi.shadowmesh.connectTransport(config) }
                            .onFailure {
                                ZLog.e("MeshVpnService", "connectTransport failed", it)
                            }
                            .getOrNull()
                            .also { h -> attempt.generation = h }
                    } finally {
                        connectInFlight.set(false)
                    }
                } else {
                    // A second concurrent connect races two sessions for one
                    // tun slot. This attempt owns nothing — no capture, no
                    // generation — so it fails cleanly at the seam and its
                    // epilogue reports the loss.
                    ZLog.w("MeshVpnService", "connect-in-flight guard refused a second concurrent transport connect")
                    null
                }
            },
            attachTun = { handle, install ->
                runCatching { uniffi.shadowmesh.attachTun(handle, install.fd) }
                    .onFailure {
                        ZLog.e("MeshVpnService", "attachTun refused the descriptor", it)
                    }
                    .isSuccess
            },
            awaitProof = { _ ->
                val proven = attempt.install?.let { install -> awaitTransportProof(install.lease) } ?: false
                if (proven) {
                    // Record that so per-app (split-tunnelling INCLUDE) connects can
                    // be judged on a truthful signal instead of waiting for tunnelled
                    // packets that an idle per-app tunnel will never produce.
                    EngineStatus.markEstablished("engine-started mode=${config.trafficMode}")
                }
                proven
            },
            closeTransport = { handle ->
                runCatching { uniffi.shadowmesh.closeTransport(handle) }
                    .onFailure { ZLog.e("MeshVpnService", "closeTransport failed", it) }
                    .getOrElse { false }
            },
            onFailure = { install -> install.lease.release() },
        )

        // The attempt thread IS the connect: it proves the transport, then
        // installs and attaches the capture, then confirms the proof. On
        // failure the epilogue is the only place the SERVICE state is torn
        // down, and only while this attempt is still the current one — a
        // superseded attempt's failure must not tear down the tunnel that
        // replaced it (§4.5).
        val worker = Thread({
            try {
                val ok = coordinator.run()
                if (!ok && attemptState.get() === attempt) {
                    // The coordinator already ran the attempt's own teardowns
                    // (the scoped close for a session that existed; the lease
                    // release for an interface that stood). What remains here
                    // is the service state — the foreground notification and
                    // the connect flags — so the user does not see a live VPN
                    // that is not there.
                    handleDisconnect()
                }
            } catch (e: Throwable) {
                // A coordinator step can only fail through the lambdas, each
                // of which reports its own failure; this catches the
                // unexpected — and must run the same teardown-or-supersede
                // decision as a false return.
                ZLog.e("MeshVpnService", "connect attempt crashed", e)
                attempt.install?.lease?.release()
                if (attemptState.get() === attempt) handleDisconnect()
            }
        }, "shadowmesh-connect-attempt")
        worker.start()

        // CAP the blackout at the same bound as before: the interface is only
        // up from PHASE 2 onward, so the watchdog serves the window between
        // the capture and the proof. A transport handshake that hangs holds
        // no capture (no descriptor, no route, no resolver override), so the
        // device is not in the dark and there is nothing to take back.
        awaitTransportOrTearDown(attempt, worker)
        return true
    }

    /**
     * PHASE 2 — install the capture, once the transport is proven.
     *
     * This is the coordinator's PHASE 2 step (its `installer` lambda): the
     * order's own prose — prove the transport FIRST, install the capture
     * SECOND — is executed by [TunnelCaptureCoordinator.run], and this method
     * is only one ordered step of it. Before the two-phase bindings landed,
     * the FFI fused connect and attach into
     * `start_vpn_engine(i32 fd, VPNConfig config)`, which took the descriptor
     * up front and made the wrong order unavoidable; with
     * `connect_transport(config)` and `attach_tun(handle, fd)` in the
     * generated surface the knot is gone and the capture exists only after a
     * proven transport.
     *
     * @param builder the configured builder — every setting that is not part of
     *   the capture has already been applied to it.
     * @param spec the capture, as a pure value (RFC-048 §4.3).
     * @return the installed capture, or null if it could not be installed. On
     *   null the coordinator closes the attempt's own session by generation
     *   (the transport WAS proven — it owns one), and the epilogue tears the
     *   service state down if this attempt is still the current one.
     */
    private fun installCapture(builder: Builder, spec: TunnelCaptureSpec): CaptureInstall? {
        // ⚠️ **This is the ONLY call to `establish()` in the tree**, and it is
        // behind the seam (RFC-048 §4.3) — the same seam the JVM tests drive,
        // so the production path and the test path cannot drift apart. Before
        // this, the production path called `builder.establish()` directly and
        // the seam was a helper with no caller: a claim about the tree that
        // the tree did not honour.
        val pfd = VpnServiceCaptureInstaller(builder).install(spec)
        if (pfd == null) {
            // establish() returning null means consent was withdrawn or another
            // app took the slot. Nothing was installed, but the service is
            // foregrounded with a live notification, so the caller still has to
            // tear it down or the user is left with a permanently "active" VPN
            // that is not there.
            ZLog.e("MeshVpnService", "establish() refused: no VPN consent or slot taken")
            return null
        }

        // The interface is two descriptors and both have to go — see TunInterfaceLease.
        // The lease is installed first, so that every failure below is covered by it
        // rather than needing its own teardown.
        val engineSide = AtomicReference<ParcelFileDescriptor?>(null)
        val lease = TunInterfaceLease(
            closeInterface = { closeEngineSide ->
                // Engine's duplicate first: it is the reference the previous fix
                // left dangling, and the reason the interface outlived teardown.
                if (closeEngineSide) {
                    engineSide.get()?.let { handle ->
                        runCatching { handle.close() }
                            .onFailure { ZLog.e("MeshVpnService", "close(engine tun descriptor) failed", it) }
                    }
                }
                runCatching { pfd.close() }
                    .onFailure { ZLog.e("MeshVpnService", "close(tun descriptor) failed", it) }
            },
            relinquishEngineSide = {
                // detachFd(), not close(): the engine owns this descriptor now.
                engineSide.get()?.let { handle ->
                    runCatching { handle.detachFd() }
                        .onFailure { ZLog.e("MeshVpnService", "detachFd(engine tun descriptor) failed", it) }
                }
            },
        )
        tunLease = lease

        // The engine is handed its own duplicate rather than `pfd` itself, so the
        // two owners never overlap. Sharing one fd number would mean either a
        // double close — the engine's OwnedFd is still registered with tokio's
        // reactor, against an fd number the process may already have handed to
        // an unrelated file — or neither owner being able to close safely.
        val fd = try {
            pfd.dup().detachFd()
        } catch (e: Exception) {
            // Nothing downstream can run without a descriptor, and leaving the
            // interface up here would be the exact defect being fixed. The lease
            // is installed, so the caller's teardown closes `pfd` on the way out.
            ZLog.e("MeshVpnService", "Could not duplicate the tun descriptor", e)
            return null
        }

        // adoptFd() is the exact inverse of the detachFd() that produced `fd`, and
        // it is public API (API 13). It takes ownership without closing, so this
        // handle and the bare int above refer to one descriptor: the engine can
        // take it on success, and the service can still close it if the engine
        // never returns. Without this handle the failed-handshake descriptor is
        // unreachable from Kotlin — which is precisely how the previous fix left
        // tun0 standing.
        //
        // If ownership cannot be taken we must NOT continue: `fd` is already
        // detached, so nothing else owns it, and starting the engine anyway
        // hands the interface to code that returns early on a failed handshake
        // without ever closing it. See takeDescriptorOwnership.
        when (val handoff = takeDescriptorOwnership(
            fd = fd,
            adopt = { ParcelFileDescriptor.adoptFd(it) },
            // Close the orphaned duplicate by adopting it into a handle we own and
            // closing that. Os.close() takes a FileDescriptor, and the int-only
            // FileDescriptor(int) constructor is hidden API, so this is the one
            // way to release a bare fd from public API alone.
            close = { ParcelFileDescriptor.adoptFd(it).close() },
        )) {
            is DescriptorHandoff.Owned -> engineSide.set(handoff.handle)
            // takeDescriptorOwnership already closed the orphaned descriptor.
            // The lease is armed, so this takes the interface down on the way out.
            DescriptorHandoff.Refused -> {
                ZLog.e("MeshVpnService", "Refusing to connect: the engine tun descriptor has no owner")
                return null
            }
        }

        return CaptureInstall(lease, fd)
    }

    /**
     * Waits for evidence that the tunnel actually carries traffic, and only then
     * confirms the lease.
     *
     * `connect_transport` returning (the coordinator's PHASE 1) means the
     * handshake resolved and the transport's session is open. It does not mean
     * the tunnel works: a node behind a filtering middlebox can complete the
     * handshake and then relay nothing. Confirming on the return left the device
     * holding a catch-all route and a resolver at `10.0.0.53` with nothing
     * behind them until the connect ladder's separate 25s traffic probe gave up
     * — measured at ~25s of `unknown host` on a CPH2363, with no error shown to
     * the user at any point.
     *
     * Until proof arrives the lease stays PENDING, which keeps two things
     * armed: this loop's own deadline, and the handshake watchdog, which declines
     * to act only once the transport is genuinely confirmed.
     *
     * ⚠️ **A false return tears down THE INTERFACE (the lease) here — and only
     * that.** The attempt's session is closed by the coordinator's failure path
     * (the scoped close, by generation), and the service state is torn down by
     * the attempt's epilogue, only while the attempt is still the current one.
     * This body does not reach either — it is PHASE 4's leg, and its job stops
     * at what it owned.
     *
     * @return true if the transport was proven and the lease confirmed; false if
     *   it was not, in which case the interface has already been torn down and
     *   the caller must not report a working tunnel.
     */
    private fun awaitTransportProof(lease: TunInterfaceLease): Boolean {
        val perAppRouting = isPerAppRouting()

        // Per-app mode cannot produce traffic on an idle device, so do not spend
        // the budget waiting for a signal that will never arrive.
        if (TransportProof.isProof(0uL, perAppRouting)) {
            return confirmOrDiscard(lease)
        }

        val deadline = SystemClock.elapsedRealtime() + TransportProof.PROOF_TIMEOUT_MS
        var proof = false
        while (!proof) {
            // A concurrent onRevoke/ACTION_DISCONNECT already tore the interface
            // down; there is nothing left to prove and nothing to hand over.
            if (lease.isReleased) {
                ZLog.w("SHADOWMESH_DEBUG", "Interface was torn down while awaiting transport proof")
                // The attempt's session is closed by the coordinator's failure
                // path (the scoped close, by generation), and the service state
                // by the epilogue — only if this attempt is still current.
                return false
            }
            if (SystemClock.elapsedRealtime() >= deadline) break

            Thread.sleep(TransportProof.PROOF_POLL_MS)

            // A stats read can throw if the engine has already unwound; that is
            // not proof of anything either way, so keep polling until the deadline.
            val received = runCatching { vpnManager.getStats().bytesReceived }.getOrDefault(0uL)
            proof = TransportProof.isProof(received, perAppRouting)
        }

        if (!proof) {
            ZLog.e(
                "SHADOWMESH_DEBUG",
                "Handshake returned but no traffic arrived within ${TransportProof.PROOF_TIMEOUT_MS}ms; " +
                    "tearing the interface down so the device keeps its DNS"
            )
            // Both descriptors, on the timeout path too: the engine took the fd but
            // never proved it works, so this side still owns it.
            lease.release()
            // The session it is scoped to is closed by the coordinator's
            // failure path (the per-attempt close, by generation), and the
            // service state by the epilogue — only if this attempt is still
            // the current one.
            return false
        }

        return confirmOrDiscard(lease)
    }

    /**
     * Confirms the lease, or discards the tunnel if the interface went away
     * underneath us while we were proving it.
     */
    private fun confirmOrDiscard(lease: TunInterfaceLease): Boolean {
        if (lease.confirmTransport()) return true

        // onRevoke/ACTION_DISCONNECT landed while we were proving the transport and
        // already took the interface down. The engine is now running on a descriptor
        // the device no longer routes through, so this is not a working tunnel and
        // must not be reported as one.
        ZLog.w(
            "SHADOWMESH_DEBUG",
            "Interface was torn down during the handshake; discarding a tunnel the device no longer has"
        )
        // False here reaches the coordinator's proof-failure arm, which runs
        // the attempt's own scoped close; the service state belongs to the
        // epilogue, only if this attempt is still the current one.
        return false
    }

    /**
     * Whether the tunnel is restricted to a per-app list, read from the same
     * persisted store [applySplitTunnel] builds its rules from.
     *
     * The core's in-memory copy reverts to defaults on every process restart, so
     * reading it from here reported "full tunnel" while the service was really
     * applying per-app rules — and the two disagreeing is what made an idle
     * per-app tunnel look like a dead one.
     */
    private fun isPerAppRouting(): Boolean {
        val storage = SecureStorage.getInstance(applicationContext)
        if (!storage.getBoolean(Config.KEY_ST_ENABLED, false)) return false
        val mode = storage.get(Config.KEY_ST_MODE) ?: "exclude"
        if (!mode.equals("include", ignoreCase = true)) return false
        return storage.prefs
            .getStringSet(Config.KEY_ST_APPS, emptySet())
            .orEmpty()
            .any { it.isNotBlank() }
    }

    /**
     * Bounds how long the CAPTURE may hold the device without working DNS.
     *
     * A tun fd is unusable, so once the capture is established the device has
     * no DNS until the interface comes down. Normally that window is the
     * proof — about two seconds to a hard failure on a real device. But the
     * engine can also *hang*: `connect_transport` blocks in the handshake with
     * no timeout of its own, and no failure path runs, so in the PRE-FIX
     * order nothing would ever tear the interface down. In the FIXED order
     * the capture exists only after the transport is proven, so the exposure
     * starts at PHASE 2 — and a transport handshake that hangs holds NO
     * capture (no descriptor, no route, no resolver override): the device is
     * not in the dark, and there is nothing to take back.
     *
     * So: if the attempt has neither ended nor confirmed its transport within
     * [HANDSHAKE_TIMEOUT_MS] **while its interface is up**, give the interface
     * back. Set deliberately inside the ladder's own 25s per-attempt budget,
     * so this fires first and the ladder's later teardown is a no-op.
     */
    private fun awaitTransportOrTearDown(attempt: ConnectAttempt, worker: Thread) {
        Thread({
            try {
                // The attempt thread IS the connect: this join costs nothing
                // on the fast paths.
                worker.join(HANDSHAKE_TIMEOUT_MS)
                if (worker.isAlive) {
                    // The exposure is the CAPTURE's, so the boundary is the
                    // lease: no install yet means no blackout, and the hung
                    // handshake behind the seam is the device's guest, not
                    // its captor.
                    val lease = attempt.install?.lease
                    if (lease != null && !lease.transportConfirmed) {
                        ZLog.e(
                            "SHADOWMESH_DEBUG",
                            "Engine did not confirm a transport within ${HANDSHAKE_TIMEOUT_MS}ms; " +
                                "tearing the interface down so the device keeps its DNS"
                        )
                        // Scoped: a timeout on a superseded attempt must not take down
                        // the tunnel that replaced it.
                        lease.release()
                        if (attemptState.get() === attempt) handleDisconnect()
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }, "shadowmesh-handshake-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Applies the persisted per-app routing rules to [builder].
     *
     * EXCLUDE — the listed apps bypass the tunnel and keep using the underlying
     * network. INCLUDE — only the listed apps (plus this VPN app, which Android
     * always includes) are routed through it.
     *
     * Failure handling is deliberately per-package: Android throws
     * [PackageManager.NameNotFoundException] for a package that was uninstalled
     * since the list was saved, and `addDisallowedApplication` throws
     * [IllegalArgumentException] for this app's own package. Either must not
     * take down the tunnel — a stale entry costs that one app its rule, nothing
     * more.
     */
    private fun applySplitTunnel(builder: Builder, config: VpnConfig) {
        val storage = SecureStorage.getInstance(this)
        if (!storage.getBoolean(Config.KEY_ST_ENABLED, false)) return

        val mode = storage.get(Config.KEY_ST_MODE) ?: "exclude"
        val packages = storage.prefs
            .getStringSet(Config.KEY_ST_APPS, emptySet())
            .orEmpty()
            .filter { it.isNotBlank() }
        // An empty list means "no per-app restriction" in both modes: applying
        // INCLUDE with no apps would route nothing but the VPN app itself and
        // silently break all connectivity on the device.
        if (packages.isEmpty()) return

        var applied = 0
        var skipped = 0
        for (pkg in packages) {
            if (pkg == packageName) {
                // Routing the VPN provider through itself is invalid.
                skipped++
                continue
            }
            try {
                if (mode.equals("include", ignoreCase = true)) {
                    builder.addAllowedApplication(pkg)
                } else {
                    builder.addDisallowedApplication(pkg)
                }
                applied++
            } catch (e: PackageManager.NameNotFoundException) {
                skipped++
            } catch (e: IllegalArgumentException) {
                skipped++
            }
        }
        ZLog.i("SHADOWMESH_DEBUG", "Split tunnel: mode=$mode applied=$applied skipped=$skipped")
    }

    private fun handleDisconnect() {
        ZLog.i("SHADOWMESH_DEBUG", "🛑 handleDisconnect: Tearing down VPN...")
        EngineStatus.markStopped()

        // There is no VpnService.stopVpn() and no Builder teardown method, so
        // closing the tun descriptors is the teardown. This has to release *both*
        // of them — the one establish() returned and the duplicate the engine was
        // handed. A tun device stays established while any descriptor references
        // it, so closing only the first leaves the catch-all route and the
        // 10.0.0.53 resolver live and the device with no working DNS at all,
        // produced by the act of trying to protect it.
        val lease = tunLease
        tunLease = null
        lease?.release()

        try {
            stopVpnEngine()
        } catch (e: Throwable) {
            ZLog.e("SHADOWMESH_DEBUG", "Error stopping Rust engine", e)
        }

        // v7.3: Immediate notification cleanup to signal OS
        stopForeground(STOP_FOREGROUND_REMOVE)

        // Force-close the service to ensure tun0 is destroyed
        stopSelf()
    }

    private fun createNotification(isCamouflage: Boolean): Notification {
        val title = if (isCamouflage) "Notes Cloud Sync" else "ShadowMesh Active"
        val content = if (isCamouflage) "Personal diary is synchronizing..." else "Secure mesh tunnel is active."
        val icon = if (isCamouflage) R.drawable.ic_notes_monochrome else R.drawable.ic_launcher_foreground

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(icon)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "ShadowMesh VPN Service"
            val descriptionText = "Ensures secure mesh tunnel remains active in the background."
            val importance = NotificationManager.IMPORTANCE_MIN
            val channel =
                NotificationChannel(CHANNEL_ID, name, importance).apply {
                    description = descriptionText
                }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }
}
