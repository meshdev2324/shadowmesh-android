package com.shadowmesh.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kill-switch-grade tests for the VPN interface lifetime.
 *
 * The defect these exist for was never visible in the UI. Every failed connect
 * showed the user a perfectly reasonable error message while the *device* was
 * left holding a live VPN interface: a `0.0.0.0/0` catch-all route and a
 * resolver at `10.0.0.53` with nothing behind it. So every resolver on the box
 * was pinned into a dead tunnel — no DNS at all — produced by the act of trying
 * to protect it, and it took the retry ladder's own API calls down with it.
 *
 * Therefore the assertion here is deliberately *not* "the connect reported an
 * error". It is: after a failed connect, is the interface down — which means
 * **both** descriptors closed, since a tun device stays established while any
 * one of them is open. `interfaceCloses` and `engineSideCloses` stand in for
 * that.
 */
class TunInterfaceLeaseTest {

    /**
     * Stands in for the two real descriptors. `relinquished` is `detachFd()` —
     * giving ownership to the engine *without* closing — which is what must
     * happen on success so the engine's own `OwnedFd` is not closed underneath it.
     */
    private class Harness {
        val interfaceCloses = AtomicInteger(0)
        val engineSideCloses = AtomicInteger(0)
        val engineSideRelinquished = AtomicInteger(0)

        fun lease() = TunInterfaceLease(
            closeInterface = { closeEngineSide ->
                interfaceCloses.incrementAndGet()
                if (closeEngineSide) engineSideCloses.incrementAndGet()
            },
            relinquishEngineSide = { engineSideRelinquished.incrementAndGet() },
        )
    }

    // --- The regression this whole file is for ---------------------------------

    /**
     * The previous fix retained `pfd` but handed the engine `pfd.dup()`. A failed
     * handshake left the duplicate unowned (Rust returns before `tun_loop` wraps
     * it), so closing `pfd` closed one of two references and the interface
     * survived — confirmed on device, where `handleDisconnect` demonstrably ran
     * and `tun0` was still up 33s later with DNS dead.
     *
     * Both descriptors have to go.
     */
    @Test
    fun `failed handshake closes the engine descriptor as well as the interface`() {
        val h = Harness()
        val lease = h.lease()

        // startVpnEngine threw before the transport was confirmed, so the engine
        // never adopted its descriptor and the service still owns it.
        lease.release()

        assertEquals("interface descriptor left open", 1, h.interfaceCloses.get())
        assertEquals(
            "engine descriptor left open, so tun0 survives teardown and the device keeps no DNS",
            1,
            h.engineSideCloses.get(),
        )
        assertEquals(0, h.engineSideRelinquished.get())
        assertTrue(lease.isReleased)
    }

    @Test
    fun `a confirmed transport relinquishes the engine descriptor instead of closing it`() {
        val h = Harness()
        val lease = h.lease()

        assertTrue(lease.confirmTransport())
        assertTrue(lease.transportConfirmed)

        // The engine owns it now and closes it when tun_loop exits. Closing it
        // here as well would be a double close against an fd still registered
        // with tokio's reactor — an fd number the process may already have
        // handed to an unrelated file.
        assertEquals("engine descriptor was closed out from under the engine", 0, h.engineSideCloses.get())
        assertEquals(1, h.engineSideRelinquished.get())
    }

    @Test
    fun `user disconnect after confirmation still closes the interface and not the engine descriptor`() {
        val h = Harness()
        val lease = h.lease()

        lease.confirmTransport()
        lease.release()

        assertEquals("a disconnected tunnel was left established", 1, h.interfaceCloses.get())
        assertEquals(0, h.engineSideCloses.get())
    }

    // --- The rest of the lifetime contract -------------------------------------

    @Test
    fun `release is idempotent so a repeated teardown cannot double close`() {
        val h = Harness()
        val lease = h.lease()

        // engine thread catch -> lease.release(), then handleDisconnect() reaches
        // the same lease through the service field, then onDestroy -> again.
        lease.release()
        lease.release()
        lease.release()

        assertEquals("a repeated teardown closed the interface more than once", 1, h.interfaceCloses.get())
        assertEquals(1, h.engineSideCloses.get())
    }

    @Test
    fun `release after confirmation still closes the interface exactly once`() {
        val h = Harness()
        val lease = h.lease()

        lease.confirmTransport()
        lease.release()
        lease.release()

        assertEquals(1, h.interfaceCloses.get())
    }

    @Test
    fun `a disconnect landing mid-handshake is not reported as a connected tunnel`() {
        val h = Harness()
        val lease = h.lease()

        // onRevoke / ACTION_DISCONNECT while startVpnEngine is still handshaking.
        lease.release()

        // The handshake then completes against a descriptor already released.
        // The service must treat that as a failure, not announce a working
        // tunnel the device does not have.
        assertFalse(
            "reported as connected although the interface was torn down mid-handshake",
            lease.confirmTransport(),
        )
        assertFalse(lease.transportConfirmed)
        assertEquals(1, h.interfaceCloses.get())
        // Losing the CAS must not hand a descriptor the engine may already own
        // over to it.
        assertEquals("relinquished a descriptor that had already been closed", 0, h.engineSideRelinquished.get())
    }

    @Test
    fun `racing teardown and handshake close the interface exactly once`() {
        // The engine thread confirms on handshake success; the service thread
        // releases on disconnect. Both run against the same lease, and closing
        // the same ParcelFileDescriptor twice would throw or, worse, act on a
        // descriptor number the platform has since reassigned.
        repeat(500) {
            val h = Harness()
            val lease = h.lease()
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)
            val pool = Executors.newFixedThreadPool(2)

            pool.submit {
                start.await()
                lease.confirmTransport()
                done.countDown()
            }
            pool.submit {
                start.await()
                lease.release()
                done.countDown()
            }

            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))
            pool.shutdown()

            // The teardown always wins this race — release() must run whatever
            // order the two threads land in, because a confirmed transport does
            // not excuse an open interface.
            assertEquals("teardown lost the race and left the interface up", 1, h.interfaceCloses.get())

            // Exactly one of the two may have taken the engine descriptor, and a
            // descriptor is never both relinquished and closed.
            assertTrue(
                "engine descriptor was neither relinquished nor closed",
                h.engineSideRelinquished.get() + h.engineSideCloses.get() >= 1,
            )
            assertTrue(
                "engine descriptor was both handed over and closed",
                h.engineSideRelinquished.get() == 0 || h.engineSideCloses.get() == 0,
            )
        }
    }
}