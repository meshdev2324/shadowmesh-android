package com.shadowmesh.app.vpn

import com.shadowmesh.ui_kit.components.LeakCheckSummary
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection

/**
 * In-tunnel leak verification (docs/LEAK-VERIFICATION.md, command-center
 * mandate). Run only while the tunnel is up: every probe then necessarily
 * traverses the tun, so an answer reveals what the tunnel really leaks.
 *
 * Two probes, both borrowed from established forensic practice:
 *  1. Egress attribution — an HTTPS IP-echo (ifconfig.me) must return the
 *     selected node's endpoint host. A carrier address means the tunnel is
 *     not carrying the device's traffic.
 *  2. IPv6 liveness — a direct TCP connect to an IPv6-only resolver name.
 *     With leak protection on (the default), IPv6 is family-blocked
 *     ([MeshVpnService.ipv6Posture]); a successful connect is a leak.
 *
 * The egress address is returned for display only and must never be logged
 * (ZPII): logs see the verdict, screens see the address.
 */
class LeakCheck(
    private val fetch: (String) -> String? = ::httpGetBody,
    // Nullable with an after-init default: Kotlin cannot bind an instance
    // member reference inside a constructor default expression.
    private val ipv6Probe: (() -> Boolean?)? = null,
    private val connectTimeoutMs: Int = 6_000,
) {
    private val effectiveIpv6Probe: () -> Boolean? =
        ipv6Probe ?: { ipv6Connectivity() }

    fun run(nodeEndpointHost: String): LeakCheckSummary {
        // Display-only and bounded regardless of what the fetcher returns.
        val egressIp = fetch(ECHO_URL)?.take(MaxDisplayLength)
        val egressMatchesNode: Boolean? = when {
            egressIp == null -> null
            isLiteralIp(nodeEndpointHost) -> egressIp == nodeEndpointHost
            // A domain endpoint cannot be compared textually; matching would
            // need a DNS lookup that itself must go through the tunnel. Mark
            // inconclusive rather than guess.
            else -> null
        }

        return LeakCheckSummary(
            egressIp = egressIp,
            egressMatchesNode = egressMatchesNode,
            ipv6Leaked = effectiveIpv6Probe(),
        )
    }

    /**
     * True when a direct IPv6 connection succeeds — i.e. IPv6 has real
     * connectivity outside the tunnel. Null when the probe itself could not
     * run (no AAAA resolution, transport error).
     */
    private fun ipv6Connectivity(): Boolean? {
        val address = try {
            InetAddress.getByName(IPV6_PROBE_HOST)
        } catch (_: UnknownHostException) {
            return null
        }
        if (!address.address.any { it != 0.toByte() } || address.address.size != 16) {
            // Resolved to something that is not a global IPv6 literal (e.g.
            // an IPv4 fallback) — the probe cannot say anything about IPv6.
            return null
        }
        return try {
            Socket().use { socket ->
                socket.bind(null)
                socket.connect(InetSocketAddress(address, 443), connectTimeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isLiteralIp(host: String): Boolean = try {
        // getByName on a literal returns it unchanged and never hits DNS.
        val a = InetAddress.getByName(host)
        a.hostAddress == host
    } catch (_: Exception) {
        false
    }

    companion object {
        private const val ECHO_URL = "https://ifconfig.me/ip"
        private const val IPV6_PROBE_HOST = "ipv6.google.com"
        private const val MaxDisplayLength = 64

        private fun httpGetBody(url: String): String? = try {
            val connection = URL(url).openConnection() as HttpsURLConnection
            connection.connectTimeout = 6_000
            connection.readTimeout = 6_000
            connection.setRequestProperty("User-Agent", "ShadowMesh-LeakCheck/1")
            connection.inputStream.use { it.bufferedReader().readText().trim() }
        } catch (_: Exception) {
            null
        }
    }
}
