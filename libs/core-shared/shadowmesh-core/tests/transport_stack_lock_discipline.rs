//! Red-first regression tests for `TransportStack`'s lock discipline.
//!
//! # The defect
//!
//! `TransportStack::recv` acquired the stack's **read** guard and then awaited
//! `transport.recv()` *while still holding it*. Every real transport blocks
//! indefinitely in that call (`WireGuardTransport::recv` parks in
//! `socket.recv().await`; `RealityTransport::recv` parks in `read_app().await`).
//!
//! That parks a reader inside the critical section for the entire life of the
//! tunnel, which is normally harmless — until anything needs the **write** lock.
//! `TransportStack::swap` does, and it is called on the engine's start path for
//! every transport (`engine.rs`, and again from `fallback_to_reality`).
//!
//! Once a receive task is parked, `swap()` can never be granted the write lock
//! again, so **every subsequent `start_engine` blocks forever**. The engine then
//! never reaches its "started" log, `tun_loop` is never spawned, and no counter
//! is ever incremented — the tunnel reports no traffic and no error.
//!
//! `stop_engine` does not help. It flips `ENGINE_RUNNING` to false, but that
//! flag is only observed *between* iterations of the receive loop. A task parked
//! inside `recv()` never returns to test it, so it holds the read guard until
//! the process dies. The wedge is permanent.
//!
//! On the CPH2363 this presented as `flowing=false engineEstablished=false` in
//! NORMAL, FRAGMENTED and REALITY alike: the transports all reached their own
//! "connected" log and then all stalled on the one shared `swap()` call.
//!
//! The fix is to never hold a stack guard across an await that can block on the
//! network: clone the `Arc` handle out from under the lock, release the lock,
//! and only then call into the transport.

use async_trait::async_trait;
use bytes::Bytes;
use shadowmesh_core::transport::{AsyncTransport, TransportStack, TransportType};
use shadowmesh_core::ShadowMeshError;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::Notify;

/// A transport whose `recv` blocks until released, exactly like a real one
/// blocked in `socket.recv().await`.
#[derive(Debug)]
struct BlockingTransport {
    /// Signals that `recv` has been entered and the stack guard is held.
    entered: Arc<Notify>,
    /// Lets the test unblock `recv` at the end of the test.
    release: Arc<Notify>,
    kind: TransportType,
}

impl BlockingTransport {
    fn new(kind: TransportType) -> (Self, Arc<Notify>, Arc<Notify>) {
        let entered = Arc::new(Notify::new());
        let release = Arc::new(Notify::new());
        (Self { entered: entered.clone(), release: release.clone(), kind }, entered, release)
    }
}

#[async_trait]
impl AsyncTransport for BlockingTransport {
    fn transport_type(&self) -> TransportType {
        self.kind
    }

    async fn connect(&self) -> Result<(), ShadowMeshError> {
        Ok(())
    }

    async fn send(&self, _data: Bytes) -> Result<(), ShadowMeshError> {
        Ok(())
    }

    async fn recv(&self) -> Result<Bytes, ShadowMeshError> {
        self.entered.notify_waiters();
        // Never returns on its own: this is what a parked tunnel reader does.
        self.release.notified().await;
        Ok(Bytes::new())
    }

    async fn close(&self) -> Result<(), ShadowMeshError> {
        Ok(())
    }
}

/// The fatal one: a parked reader must not be able to wedge `swap` forever.
///
/// Before the fix this timed out, because `swap` needs the write lock that the
/// parked reader is holding across its blocking `recv`.
#[tokio::test]
async fn swap_completes_while_a_receive_is_parked() {
    let stack = Arc::new(TransportStack::default());
    let (blocking, entered, _release) = BlockingTransport::new(TransportType::WireGuard);

    stack.swap(Arc::new(blocking)).await;

    let recv_stack = stack.clone();
    let reader = tokio::spawn(async move { recv_stack.recv().await });

    // Wait until the reader is genuinely parked inside the transport.
    tokio::time::timeout(Duration::from_secs(5), entered.notified())
        .await
        .expect("reader never entered recv()");

    let (_next, _e, _r) = BlockingTransport::new(TransportType::Reality);

    // This is the call the engine's start path makes. It must not block.
    let swapped = tokio::time::timeout(
        Duration::from_secs(5),
        stack.swap(Arc::new(BlockingTransport::new(TransportType::Reality).0)),
    )
    .await;

    assert!(
        swapped.is_ok(),
        "TransportStack::swap() blocked forever behind a parked recv() reader: \
         the engine could never restart its transport"
    );

    // The reader is still parked; release it so the test does not leak a task.
    _release.notify_waiters();
    let _ = reader.await;
}

/// The regression that matches the device failure: start, stop, start again.
///
/// `start_engine` is called once per connect attempt against the same process-
/// wide stack, so the second `swap` is the one that used to hang forever. This
/// asserts the second transport actually becomes the active one.
#[tokio::test]
async fn engine_can_restart_after_a_transport_was_parked() {
    let stack = Arc::new(TransportStack::default());

    let (first, entered, release) = BlockingTransport::new(TransportType::WireGuard);
    stack.swap(Arc::new(first)).await;

    let recv_stack = stack.clone();
    let reader = tokio::spawn(async move { recv_stack.recv().await });

    tokio::time::timeout(Duration::from_secs(5), entered.notified())
        .await
        .expect("reader never entered recv()");

    // `stop_engine` flips ENGINE_RUNNING false. The reader is parked and cannot
    // observe it — so the stack itself must still be usable.
    let second = BlockingTransport::new(TransportType::Reality).0;

    tokio::time::timeout(Duration::from_secs(5), stack.swap(Arc::new(second)))
        .await
        .expect("restart after stop blocked forever behind the previous reader");

    // The new transport must be the one the stack serves.
    let served = tokio::time::timeout(Duration::from_secs(5), stack.send(Bytes::from_static(b"x")))
        .await
        .expect("stack unusable after restart");

    assert!(served.is_ok(), "stack rejected traffic after restart");

    release.notify_waiters();
    let _ = reader.await;
}

/// `send` held the read guard across `transport.send().await` for the same
/// reason. It must release the lock before touching the transport, so a slow
/// transport cannot stall a concurrent `swap`.
#[tokio::test]
async fn swap_completes_while_a_send_is_in_flight() {
    let stack = Arc::new(TransportStack::default());

    #[derive(Debug)]
    struct SlowSend {
        entered: Arc<Notify>,
        release: Arc<Notify>,
    }

    #[async_trait]
    impl AsyncTransport for SlowSend {
        fn transport_type(&self) -> TransportType {
            TransportType::WireGuard
        }
        async fn connect(&self) -> Result<(), ShadowMeshError> {
            Ok(())
        }
        async fn send(&self, _data: Bytes) -> Result<(), ShadowMeshError> {
            self.entered.notify_waiters();
            self.release.notified().await;
            Ok(())
        }
        async fn recv(&self) -> Result<Bytes, ShadowMeshError> {
            Ok(Bytes::new())
        }
        async fn close(&self) -> Result<(), ShadowMeshError> {
            Ok(())
        }
    }

    let entered = Arc::new(Notify::new());
    let release = Arc::new(Notify::new());
    stack.swap(Arc::new(SlowSend { entered: entered.clone(), release: release.clone() })).await;

    let send_stack = stack.clone();
    let sender = tokio::spawn(async move { send_stack.send(Bytes::from_static(b"payload")).await });

    tokio::time::timeout(Duration::from_secs(5), entered.notified())
        .await
        .expect("send never entered the transport");

    let swapped = tokio::time::timeout(
        Duration::from_secs(5),
        stack.swap(Arc::new(BlockingTransport::new(TransportType::Reality).0)),
    )
    .await;

    assert!(swapped.is_ok(), "TransportStack::swap() blocked behind an in-flight send()");

    release.notify_waiters();
    let _ = sender.await;
}
