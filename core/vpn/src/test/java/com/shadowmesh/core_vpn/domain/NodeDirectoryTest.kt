package com.shadowmesh.core_vpn.domain

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.shadowmesh.NodeProjection
import uniffi.shadowmesh.NodeRoster
import uniffi.shadowmesh.ShadowMeshException
import uniffi.shadowmesh.TrafficMode
import uniffi.shadowmesh.TrafficModePreference
import uniffi.shadowmesh.VpnConfig

/**
 * Covers the discovery seam and, above all, its failure classification.
 *
 * The regression this exists for: discovery caught every exception, logged it,
 * and returned `emptyList()`. A malformed payload, a refused session and a mesh
 * with no nodes then all reached the screen as the same empty list, so the user
 * saw zero servers and no way to find out why.
 *
 * The headline test is [a serde missing-field failure is reported, not swallowed]:
 * it feeds the classifier the exact message this bug produced and asserts it
 * comes back as a failure rather than as an empty list.
 */
class NodeDirectoryTest {

    private val entry = MeshNodeEntry(
        id = "n1",
        country = "SG",
        region = "Singapore",
        isOnline = true,
    )

    // ---- classification -------------------------------------------------

    @Test
    fun `a serde missing-field failure is reported, not swallowed`() {
        // This is the message the anonymous projection produced against a
        // VPNNode that also required name/endpoint/public_key/load/latency.
        val failure = NodeLoadFailureClassifier.classify(
            ShadowMeshException.JsonException("missing field `name`")
        )
        assertEquals(NodeLoadFailure.MALFORMED_PAYLOAD, failure)

        val error = NodeLoadError.of(failure)
        assertEquals(NodeLoadFailure.MALFORMED_PAYLOAD, error.failure)
        // Rendered text, not the raw server message: the raw string can carry
        // anything the control plane chose to say.
        assertEquals("The mesh directory came back in an unexpected format.", error.message)
        assertTrue("a contract mismatch is worth one retry", error.canRetry)
    }

    @Test
    fun `a missing-field failure is recognised through a wrapper cause`() {
        // FFI wraps: the typed variant is the shell, the real message is one or
        // two levels down. Classifying only the shell would report every
        // contract mismatch as a transport fault, and the user would be told to
        // check their wifi.
        val wrapped = ShadowMeshException.Other("core failure")
            .let { RuntimeException("ffi boundary", it) }
        val failure = NodeLoadFailureClassifier.classify(
            RuntimeException("deserialization failed", wrapped)
        )
        assertEquals(NodeLoadFailure.MALFORMED_PAYLOAD, failure)
    }

    @Test
    fun `a refused session is distinguished from a network fault`() {
        assertEquals(
            NodeLoadFailure.NOT_AUTHENTICATED,
            NodeLoadFailureClassifier.classify(ShadowMeshException.Unauthorized("401")),
        )
        assertEquals(
            NodeLoadFailure.NOT_AUTHENTICATED,
            NodeLoadFailureClassifier.classify(ShadowMeshException.Forbidden("403")),
        )

        val notAuthed = NodeLoadError.of(NodeLoadFailure.NOT_AUTHENTICATED)
        assertEquals("Activate your device to see the mesh.", notAuthed.message)
        // Retrying the identical unauthenticated request cannot succeed, so the
        // affordance is withheld; the action is to activate, not to try again.
        assertFalse("an unauthenticated load must not offer a retry", notAuthed.canRetry)
    }

    @Test
    fun `a transport fault offers a retry`() {
        val failure = NodeLoadFailureClassifier.classify(java.io.IOException("connection reset"))
        assertEquals(NodeLoadFailure.TRANSPORT, failure)
        assertTrue(NodeLoadError.of(failure).canRetry)
    }

    @Test
    fun `an unrecognised failure is still a failure`() {
        // Total by construction: nothing may classify to null and vanish.
        val failure = NodeLoadFailureClassifier.classify(IllegalStateException("who knows"))
        assertEquals(NodeLoadFailure.UNKNOWN, failure)
        assertTrue(NodeLoadError.of(failure).canRetry)
    }

    @Test
    fun `every failure has a message and a retry decision`() {
        NodeLoadFailure.entries.forEach { f ->
            val e = NodeLoadError.of(f)
            assertTrue("$f has no message", e.message.isNotBlank())
            assertEquals(f, e.failure)
        }
    }

    // ---- the seam -------------------------------------------------------

    @Test
    fun `a thrown discovery becomes a classified failure, never an empty list`() {
        val client = mockk<uniffi.shadowmesh.ApiClient>()
        every { client.getNodes() } throws
            ShadowMeshException.JsonException("missing field `name`")

        val result = NodeDirectorySource.loadDirectory(client)

        assertTrue("a failure must not be reported as Loaded", result is NodeDirectoryResult.Failed)
        val failed = result as NodeDirectoryResult.Failed
        assertEquals(NodeLoadFailure.MALFORMED_PAYLOAD, failed.failure)
        assertTrue(failed.detail.contains("missing field"))
    }

    @Test
    fun `an empty successful directory is Loaded and empty, not Failed`() {
        // The distinction the old code could not express: the mesh having no
        // nodes is an answer, not an error, and the screen says so.
        val client = mockk<uniffi.shadowmesh.ApiClient>()
        every { client.getNodes() } returns NodeRoster(NodeProjection.ANONYMOUS, emptyList())

        val result = NodeDirectorySource.loadDirectory(client)

        assertTrue(result is NodeDirectoryResult.Loaded)
        assertTrue((result as NodeDirectoryResult.Loaded).entries.isEmpty())
    }

    @Test
    fun `a directory row carries only what the server published`() {
        val client = mockk<uniffi.shadowmesh.ApiClient>()
        every { client.getNodes() } returns NodeRoster(NodeProjection.ANONYMOUS, listOf(
            uniffi.shadowmesh.PublicNode(
                id = "n1",
                country = "SG",
                region = "Singapore",
                isOnline = true,
            )
        ))

        val loaded = NodeDirectorySource.loadDirectory(client) as NodeDirectoryResult.Loaded
        val mapped = loaded.entries.single()

        assertEquals("n1", mapped.id)
        assertEquals("SG", mapped.country)
        assertEquals("Singapore", mapped.region)
        assertTrue(mapped.isOnline)
        // Capability is NOT carried over from the node: the directory is the
        // unverified stage, so REALITY support starts unknown regardless of what
        // the core happened to know.
        assertEquals(RealitySupport.UNKNOWN, mapped.realitySupport)
        // The label comes from published geography, not from the node's own name
        // field, which the anonymous projection does not include.
        assertEquals("Singapore", mapped.displayName)
    }

    @Test
    fun `displayName falls back without ever rendering blank`() {
        assertEquals("Singapore", entry.copy(region = "Singapore").displayName)
        assertEquals("SG", entry.copy(region = "", country = "SG").displayName)
        assertEquals("n1", entry.copy(region = "", country = "").displayName)
    }

    // ---- stage 2 --------------------------------------------------------

    @Test
    fun `a REALITY-mode response is the only evidence of REALITY capability`() {
        val withReality = config(endpoint = "10.0.0.1:443", reality = realityConfig())

        // Asked for REALITY, and got it: positive evidence.
        assertEquals(
            RealitySupport.SUPPORTED,
            NodeDetailSource.detailFrom(
                entry,
                withReality,
                NodeDetailSource.REALITY_PROBE_MODE,
            ).realitySupport,
        )

        // Asked for REALITY and answered 200 without the block: a real negative.
        assertEquals(
            RealitySupport.UNSUPPORTED,
            NodeDetailSource.detailFrom(
                entry,
                config(endpoint = "10.0.0.1:51820", reality = null),
                NodeDetailSource.REALITY_PROBE_MODE,
            ).realitySupport,
        )
    }

    @Test
    fun `a non-REALITY response never narrows the transport ladder`() {
        // THE REGRESSION THIS FILE EXISTS TO PREVENT.
        //
        // `mode=normal` (and a null mode, which the control plane reads as its
        // own "normal" default) returns no `reality_config` whether or not the
        // node serves REALITY. Reading that absence as "unsupported" marked the
        // entry UNSUPPORTED, `TrafficModeResolver` filtered REALITY out of the
        // ladder, and the client dialled WireGuard over UDP/443 against a node
        // speaking VLESS/REALITY on the same port. Confirmed on device: the
        // attempt ladder ran FRAGMENTED -> NORMAL, an order only reachable with
        // REALITY already removed.
        for (askedFor in listOf(null, "normal", "fragmented", "websocket")) {
            assertEquals(
                "mode=$askedFor must not establish or refute REALITY capability",
                RealitySupport.UNKNOWN,
                NodeDetailSource.detailFrom(
                    entry,
                    config(endpoint = "10.0.0.1:51820", reality = null),
                    askedFor,
                ).realitySupport,
            )
        }
    }

    @Test
    fun `REALITY stays in the ladder while capability is unknown`() {
        // UNKNOWN must keep every transport selectable, or the fix above would
        // trade one wrong transport for another.
        val unknown = entry.copy(realitySupport = RealitySupport.UNKNOWN)
        assertTrue(TrafficModeResolver.supports(unknown, TrafficMode.REALITY))
        assertEquals(
            TrafficMode.REALITY,
            TrafficModeResolver.select(unknown, TrafficModePreference.AUTO, attempt = 0),
        )
    }

    @Test
    fun `an established negative is the only thing that removes REALITY`() {
        val unsupported = entry.copy(realitySupport = RealitySupport.UNSUPPORTED)
        assertFalse(TrafficModeResolver.supports(unsupported, TrafficMode.REALITY))
        assertEquals(
            TrafficMode.NORMAL,
            TrafficModeResolver.select(unsupported, TrafficModePreference.AUTO, attempt = 0),
        )
    }

    @Test
    fun `the connectable endpoint and key come from the authenticated config`() {
        // The load-bearing property of the whole two-stage split: nothing the
        // tunnel dials comes off the directory entry.
        val detail = NodeDetailSource.detailFrom(entry, config(endpoint = "10.0.0.1:51820"))
        assertEquals("10.0.0.1:51820", detail.endpoint)
        assertEquals("server-key", detail.publicKey)
        assertEquals("n1", detail.id)
    }

    // ---- the bridge the core still forces -------------------------------

    @Test
    fun `the node handed to the core takes its endpoint and key from stage two`() {
        val detail = NodeDetailSource.detailFrom(entry, config(endpoint = "10.0.0.1:51820"))
        val node = NodeAssembler.forRust(entry, detail)

        assertEquals("n1", node.id)
        assertEquals("10.0.0.1:51820", node.endpoint)
        assertEquals("server-key", node.publicKey)
        // `region` is the one field the core's initiate_connection actually
        // reads; it is published by the directory, so it is carried verbatim.
        assertEquals("Singapore", node.region)
        assertEquals("SG", node.country)
        assertTrue(node.isOnline)
    }

    @Test
    fun `the core node never invents an attestation it was not told about`() {
        // `isSovereign` is an RFC-002 hardware-attestation claim. The directory
        // does not publish it, so it must not be asserted.
        val detail = NodeDetailSource.detailFrom(entry, config())
        assertFalse(NodeAssembler.forRust(entry, detail).isSovereign)
    }

    private fun realityConfig() = uniffi.shadowmesh.RealityConfig(
        serverIp = "10.0.0.1",
        port = 443u,
        uuid = "uuid",
        publicKey = "reality-key",
        shortId = "",
        sniTarget = "example.com",
        fingerprint = "chrome",
    )

    private fun config(endpoint: String = "10.0.0.1:51820", reality: uniffi.shadowmesh.RealityConfig? = null) =
        VpnConfig(
            privateKey = null,
            publicKey = "server-key",
            address = "10.0.0.1/32",
            endpoint = endpoint,
            dns = "8.8.8.8",
            mtu = 1420u,
            trafficMode = "normal",
            realityConfig = reality,
            shadowsocksConfig = null,
            hysteriaConfig = null,
            vmessConfig = null,
        )
}