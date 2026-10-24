package com.shadowmesh.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's descriptor must have an owner, or the connect must be refused.
 *
 * ## The gap this file is for
 *
 * `builder.establish()` returns `pfd`, and the engine is handed a *duplicate* of
 * it (`pfd.dup().detachFd()`). That duplicate is a second reference to the tun
 * device, and a tun device stays established while any descriptor references it —
 * so the interface only comes down when the last one closes. The service takes
 * ownership of the duplicate with `ParcelFileDescriptor.adoptFd(fd)` so that
 * teardown can close it even when the engine never returns, which is exactly the
 * failed-handshake case: `transport/engine.rs` returns from
 * `reality.connect().await?` *before* it wraps the descriptor in an `OwnedFd`, so
 * nothing on the Rust side closes it.
 *
 * The defect: that adoption failure was swallowed to `null` and **execution
 * continued**. A null handle does not mean "there is no duplicate" — the
 * duplicate already exists, it has been detached from the `ParcelFileDescriptor`
 * that created it, and it is then owned by nobody at all:
 *
 *  - not by `pfd`, which is a different descriptor;
 *  - not by the new handle, which is null;
 *  - not by the engine, which constructs an `OwnedFd` only after a *successful*
 *    handshake, so every failed handshake leaves it untouched.
 *
 * Starting the engine in that state hands the interface to code that cannot close
 * it. Every teardown path then closes `pfd` while the duplicate survives, so
 * `tun0` stays up holding a `0.0.0.0/0` catch-all route and a resolver at
 * `10.0.0.53` with nothing behind it — the production blackhole, reached through
 * a different door than the one the previous fix closed. "Close both
 * descriptors" is only a fix while the second one is still reachable.
 *
 * ## Why this asserts on ownership, not on UI
 *
 * The user-visible symptom of this defect is a phone that silently stops
 * resolving, and the app's error reporting is *correct* while it happens. A test
 * asserting "an error is shown" would pass with the interface still blackholed.
 * What is observable is whether the descriptor had an owner, so that is what is
 * asserted — and it is asserted against the production function
 * [takeDescriptorOwnership], not against a re-implementation of it.
 */
class EngineDescriptorOwnershipTest {

    /**
     * Records what actually happened to the descriptor, using a stand-in for
     * `ParcelFileDescriptor` so this runs on the JVM with no device or Robolectric.
     */
    private class Recorder {
        val adopted = mutableListOf<Int>()
        val closed = mutableListOf<Int>()
        var engineStartedWith: Int? = null
            private set

        /** Mirrors the call site: only start the engine once ownership is held. */
        fun connect(fd: Int, adoptSucceeds: Boolean): Boolean =
            when (val handoff = takeDescriptorOwnership(
                fd = fd,
                adopt = { f ->
                    if (adoptSucceeds) {
                        adopted += f
                        "handle-$f" // stands in for the ParcelFileDescriptor
                    } else {
                        // ParcelFileDescriptor.adoptFd returning/raising failure.
                        null
                    }
                },
                close = { closed += it },
            )) {
                is DescriptorHandoff.Owned -> {
                    engineStartedWith = fd
                    true
                }
                DescriptorHandoff.Refused -> false
            }
    }

    // --- The regression ---------------------------------------------------------

    @Test
    fun `the engine is never started with a descriptor nobody owns`() {
        val r = Recorder()

        // False == refused, and refusal is the safe outcome.
        assertFalse(
            "the connect proceeded although the descriptor had no owner",
            r.connect(fd = 41, adoptSucceeds = false),
        )

        assertEquals(
            "the engine was started with an unowned descriptor, so tun0 outlives every teardown",
            null,
            r.engineStartedWith,
        )
        assertTrue("the orphaned descriptor was not closed", r.closed.isNotEmpty())
    }

    @Test
    fun `a descriptor that cannot be owned is closed rather than leaked`() {
        val r = Recorder()

        r.connect(fd = 41, adoptSucceeds = false)

        assertEquals(
            "the detached duplicate was never closed, so it still references tun0 and the " +
                "device keeps a resolver it cannot reach",
            listOf(41),
            r.closed,
        )
    }

    @Test
    fun `ownership failure closes the descriptor exactly once`() {
        val r = Recorder()

        r.connect(fd = 41, adoptSucceeds = false)

        // Closing it twice is a double close against an fd number the process may
        // already have handed to an unrelated file.
        assertEquals("the orphaned descriptor was closed more than once", 1, r.closed.size)
    }

    @Test
    fun `a successful adoption hands the descriptor to the engine and closes nothing`() {
        val r = Recorder()

        assertTrue("a connect that could own its descriptor was refused", r.connect(fd = 41, adoptSucceeds = true))

        assertEquals(41, r.engineStartedWith)
        assertEquals(listOf(41), r.adopted)
        assertEquals(
            "a descriptor the engine owns was closed out from under it, which is a double " +
                "close against an fd still registered with tokio's reactor",
            emptyList<Int>(),
            r.closed,
        )
    }

    /**
     * `adopt` throwing is the same failure as returning null — the platform call
     * can raise rather than return — and must not be allowed to escape. An
     * exception here would unwind out of `handleConnect` past the lease's
     * teardown and leave the interface up with nothing closed at all.
     */
    @Test
    fun `adoption that throws is treated as a refusal, not as an escape`() {
        val closed = mutableListOf<Int>()

        val handoff = takeDescriptorOwnership<Int>(
            fd = 41,
            adopt = { throw IllegalStateException("adoptFd failed") },
            close = { closed += it },
        )

        assertEquals(DescriptorHandoff.Refused, handoff)
        assertEquals("the orphaned descriptor leaked when adoption threw", listOf(41), closed)
    }

    /**
     * The refusal is only safe because the lease is armed *before* ownership is
     * taken, so the caller's `handleDisconnect()` closes `pfd` on the way out.
     * This pins that ordering claim: a refused connect must still have closed
     * something, or the device is left exactly as broken as before with less
     * evidence of why.
     */
    @Test
    fun `a refused connect still leaves nothing owned`() {
        val r = Recorder()

        r.connect(fd = 41, adoptSucceeds = false)

        assertEquals(emptyList<Int>(), r.adopted)
        assertEquals(1, r.closed.size)
    }
}