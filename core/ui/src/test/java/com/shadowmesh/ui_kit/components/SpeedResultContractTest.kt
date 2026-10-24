package com.shadowmesh.ui_kit.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The speed-test row's contract, asserted where CI actually runs it.
 *
 * ## Why this file exists
 *
 * The speed-test section used to render `SpeedResultItem("Latency", "…ms")` next
 * to Download and Upload. The figure behind it is `SpeedTestResult.latency_ms`
 * (`speed_test.rs:22`), produced by `measure_latency_async`
 * (`speed_test.rs:67-75`) as a **mean of 5 calls to `speedtest_ping`** — and
 * `speedtest_ping_async` (`api_client.rs:1367-1377`) is
 * `GET {base_url}/api/v1/speedtest/ping`, a full HTTP round-trip to the
 * **control-plane edge** timed with `Instant::now()`.
 *
 * Labelled "Latency" on a connected screen, that reads as tunnel RTT. It is not
 * tunnel RTT, and the core exposes no tunnel-RTT field to correct it *to*. It
 * was removed rather than relabelled; the reasoning is at the call site.
 *
 * ## Why a source-level test and not a rendered one
 *
 * `core/ui`'s instrumented source set is **compiled but never run** by CI — the
 * gate is `compileDebugAndroidTestKotlin`, deliberately, because running needs
 * an emulator. A rendered assertion here would be evidence that exists only when
 * someone remembers to run it on a device, which is precisely the gap this file
 * closes. Same trade `OrbContractTest` makes, for the same reason.
 *
 * The distinction is not blurred: this proves the row does not *build* a latency
 * string, not that a device renders one absent.
 *
 * ## The comment trap, which is the whole reason for [codeOf]
 *
 * The fix is documented at the call site, and that documentation necessarily
 * contains the words `latency_ms`, `"Latency"` and `.latencyMs`. A naive
 * substring search fails on its own explanation — the same failure
 * `OrbContractTest` recorded for `MS MESH`. So assertions run against
 * [codeOf], which drops comment-only lines first.
 */
class SpeedResultContractTest {

    private val source: String by lazy {
        val f = File("src/main/java/com/shadowmesh/ui_kit/components/ShadowMeshComponents.kt")
        assertTrue("component source not found at ${f.absolutePath}", f.isFile)
        f.readText()
    }

    /**
     * The body of a top-level `fun`, bounded to its declaration.
     *
     * Bounded for the reason `OrbContractTest` documents: an unbounded
     * `substring(start)` runs to end of file, so an assertion about one
     * composable silently measures every composable after it and can fail for
     * reasons that have nothing to do with the subject.
     */
    private fun bodyOf(declaration: String): String {
        val start = source.indexOf("fun $declaration(")
        assertTrue("$declaration not found", start >= 0)
        val next = source.indexOf("\nfun ", start + 1)
        return source.substring(start, if (next < 0) source.length else next)
    }

    /**
     * [text] with comment-only lines removed.
     *
     * Deliberately conservative: it drops lines whose first non-whitespace
     * characters open a comment (line-comment, block-continuation star, or
     * block-open) and keeps everything else, including trailing comments. That
     * is enough for this file's style and it cannot silently strip code, which
     * a cleverer parser might.
     *
     * Note for whoever edits this KDoc: Kotlin block comments nest, so a literal
     * block-open token written in prose opens a nested comment that then has to
     * be closed. Say "block-open" instead. That is not a style note; it is what
     * a real compile error in this file looked like.
     */
    private fun codeOf(text: String): String =
        text.lineSequence()
            .filterNot { line ->
                val t = line.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")

    private val speedTestSectionCode: String by lazy { codeOf(bodyOf("SpeedTestSection")) }

    @Test
    fun `the speed test row binds no latency property`() {
        // A property access, not the word "latency": the field is a Kotlin
        // `Double` on `SpeedTestResult` and could be read as `r.latencyMs`,
        // `it.latencyMs`, or `x.run { latencyMs }`.
        assertFalse(
            "SpeedTestSection reads a latency field; that field is the mean of 5 " +
                "control-plane HTTP round-trips, not tunnel RTT",
            Regex("""\.\s*latencyMs\b""").containsMatchIn(speedTestSectionCode),
        )
    }

    @Test
    fun `the speed test row renders no bare Latency label`() {
        assertFalse(
            "a bare \"Latency\" label implies mesh RTT, which nothing here measures",
            speedTestSectionCode.contains("""SpeedResultItem("Latency""""),
        )
        // The one code-level spelling of a RTT claim in this composable.
        assertFalse(
            "the subtitle must not tell the user this measures latency; the " +
                "measurement is throughput against the control plane",
            speedTestSectionCode.contains("Measure latency"),
        )
    }

    @Test
    fun `the speed test row still reports throughput`() {
        // Removal must not become gutting. Download and upload are control-plane
        // measurements too, but throughput over the path in use is actionable in
        // a way a round-trip time is not, so they stay.
        assertTrue(
            "Download must survive the latency removal",
            speedTestSectionCode.contains("""SpeedResultItem("Download""""),
        )
        assertTrue(
            "Upload must survive the latency removal",
            speedTestSectionCode.contains("""SpeedResultItem("Upload""""),
        )
    }

    @Test
    fun `no speed-test result item is labelled with a bare noun`() {
        // Guards the shape of the fix: every remaining label is a throughput
        // noun. If someone adds a third figure, this forces it to be named.
        val labels = Regex("""SpeedResultItem\("([^"]+)"""")
            .findAll(speedTestSectionCode)
            .map { it.groupValues[1] }
            .toList()

        assertTrue("expected the two throughput figures, found $labels", labels.size >= 2)
        for (label in labels) {
            assertTrue(
                "speed-test label \"$label\" claims a round-trip quality the core " +
                    "cannot measure for the tunnel",
                label !in setOf("Latency", "Ping", "RTT", "Response"),
            )
        }
    }
}