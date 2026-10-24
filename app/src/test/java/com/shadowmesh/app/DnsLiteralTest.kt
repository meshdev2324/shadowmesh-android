package com.shadowmesh.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the resolver literal guard on the connect path.
 *
 * The resolver arrives as an intent extra, so it is attacker-shaped input as far
 * as the client is concerned: `getStringExtra("dns") ?: ""` yields `""` when the
 * key is absent, and `Builder.addDnsServer("")` throws. That throw was uncaught
 * and unwound out of `onStartCommand`.
 *
 * The property worth pinning is the negative one. A tunnel with a catch-all route
 * and **no** resolver is worse than no tunnel: the device's DNS goes to the
 * underlying network, which is exactly the leak this product exists to prevent. So
 * an unusable resolver must refuse the connect, never silently downgrade it.
 */
class DnsLiteralTest {

    @Test
    fun `accepts the link-local resolver the tunnel actually uses`() {
        // 10.0.0.53 is the address the service documents as the in-tunnel resolver.
        assertTrue(isUsableDnsLiteral("10.0.0.53"))
    }

    @Test
    fun `accepts a public literal and an ipv6 literal`() {
        assertTrue(isUsableDnsLiteral("1.1.1.1"))
        assertTrue(isUsableDnsLiteral("fd00::53"))
        assertTrue(isUsableDnsLiteral("fd00:0:0:0:0:0:0:53"))
    }

    @Test
    fun `tolerates surrounding whitespace from a hand-built intent`() {
        assertTrue(isUsableDnsLiteral("  10.0.0.53\n"))
    }

    @Test
    fun `rejects an absent extra, which arrives as the empty string`() {
        // The exact production failure: getStringExtra("dns") ?: "".
        assertFalse("a blank resolver must not establish a catch-all tunnel", isUsableDnsLiteral(""))
        assertFalse(isUsableDnsLiteral("   "))
    }

    @Test
    fun `rejects a hostname, because resolving it here would leak past the tunnel`() {
        // The tunnel's resolver is a literal *inside* the tunnel. Handing
        // addDnsServer a name would make the platform resolve it outside.
        assertFalse(isUsableDnsLiteral("dns.google"))
        assertFalse(isUsableDnsLiteral("localhost"))
    }

    @Test
    fun `rejects comma separated resolvers, which is not a single literal`() {
        assertFalse(isUsableDnsLiteral("10.0.0.53,1.1.1.1"))
    }

    @Test
    fun `rejects a cidrsuffixed value`() {
        assertFalse(isUsableDnsLiteral("10.0.0.53/32"))
    }

    @Test
    fun `rejects free text`() {
        assertFalse(isUsableDnsLiteral("no resolver configured"))
        assertFalse(isUsableDnsLiteral("-"))
    }
}