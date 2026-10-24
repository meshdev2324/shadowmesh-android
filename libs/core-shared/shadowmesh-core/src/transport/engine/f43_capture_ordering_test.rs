//! F43-b — the red-proofs for the frozen core contract (RFC-048 §7.1).
//!
//! # What these tests are
//!
//! Eleven tests, one per row of RFC-048 §7.1. They are the **core half** of
//! F43: the ordering defect itself (F43-a) lives in `apps/android/**` and is
//! L7's, but the mechanism it depends on (F43-b) lives here — and F43-b
//! **reproduces today, with no Android involved**: `start_engine` succeeds, a
//! second `start_engine` fails at the connect, and the previous transport is
//! still the stack's active one, live on the node.
//!
//! # Why they live inside the crate
//!
//! Two reasons, both about honesty rather than convenience:
//!
//! * **The transport has to be observable.** Tests 3, 5, 6, 7, 8 and 9 need a
//!   transport whose `close()` can be counted and whose `connect()` can be
//!   made to fail **without a node**. The crate has the pattern already
//!   (`tests/transport_stack_lock_discipline.rs`'s `BlockingTransport`); what
//!   it does not have is a way to *install* one. The seam that does it
//!   (`set_transport_builder`) is `#[cfg(test)]`-only, so it does not exist
//!   in a release build and cannot be reached from the FFI.
//! * **The session has to be isolable.** The core holds exactly one session
//!   per process (RFC-048 §3.3), so a process-global would make one test's
//!   session the next test's live session. The session is therefore a
//!   **value** ([`Session`]), and the process-global is only how production
//!   reaches it — so the code under test is the code that ships.
//!
//! # Why they are plain `#[test]`s, not `#[tokio::test]`s
//!
//! The FFI entry points call `Runtime::block_on`, and `block_on` **panics**
//! inside a runtime. A `#[tokio::test]` runs its body inside one, so the
//! entry points could not be called from it at all. A plain test thread is
//! outside a runtime — which is exactly where the FFI calls from.
//!
//! ⚠️ **The two tests that read the process-global `ENGINE_RUNNING` flag
//! (`a_failed_connect_leaves_no_tun_attached`) therefore need the house
//! runner's process-per-test isolation.** `cargo nextest` gives every test
//! its own process; plain `cargo test` does not, and F13 forbids it anyway.

use super::{set_test_session, set_transport_builder, Session, ENGINE_RUNNING};
use crate::transport::{AsyncTransport, TransportType};
use crate::{ShadowMeshError, VPNConfig};
use async_trait::async_trait;
use bytes::Bytes;
use std::future::Future;
use std::os::unix::io::RawFd;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

// ---------------------------------------------------------------------------
// The spy transport.
//
// A transport whose `close()` is observable and whose `connect()` can be made
// to fail. The ids let a test assert *which* transport was closed — "the
// superseded one", not merely "one of them".
// ---------------------------------------------------------------------------

/// The id of the next spy transport built.
static NEXT_SPY_ID: AtomicUsize = AtomicUsize::new(0);
/// The ids of the spy transports whose `close()` was called.
static CLOSED_SPY_IDS: Mutex<Vec<usize>> = Mutex::new(Vec::new());
/// Whether the next spy transport refuses to connect.
static SPY_REFUSES_CONNECT: AtomicBool = AtomicBool::new(false);

/// A transport that records its own teardown.
#[derive(Debug)]
struct SpyTransport {
    /// This transport's id.
    id: usize,
    /// Whether the handshake fails.
    refuses_connect: bool,
}

impl SpyTransport {
    fn new() -> Self {
        let id = NEXT_SPY_ID.fetch_add(1, Ordering::SeqCst);
        Self { id, refuses_connect: SPY_REFUSES_CONNECT.load(Ordering::SeqCst) }
    }
}

#[async_trait]
impl AsyncTransport for SpyTransport {
    fn transport_type(&self) -> TransportType {
        TransportType::WireGuard
    }

    async fn connect(&self) -> Result<(), ShadowMeshError> {
        if self.refuses_connect {
            return Err(ShadowMeshError::ConnectionFailed);
        }
        Ok(())
    }

    async fn send(&self, _data: Bytes) -> Result<(), ShadowMeshError> {
        Ok(())
    }

    async fn recv(&self) -> Result<Bytes, ShadowMeshError> {
        Ok(Bytes::new())
    }

    async fn close(&self) -> Result<(), ShadowMeshError> {
        CLOSED_SPY_IDS.lock().unwrap_or_else(|poisoned| poisoned.into_inner()).push(self.id);
        Ok(())
    }
}

/// The test seam's builder: every transport the engine builds is a spy.
fn spy_builder(_config: &VPNConfig) -> Result<Arc<dyn AsyncTransport>, ShadowMeshError> {
    Ok(Arc::new(SpyTransport::new()))
}

/// The id of the spy transport that was built last.
fn last_spy_id() -> usize {
    NEXT_SPY_ID.load(Ordering::SeqCst).saturating_sub(1)
}

/// The ids of the spy transports that were closed.
fn closed_spy_ids() -> Vec<usize> {
    CLOSED_SPY_IDS.lock().unwrap_or_else(|poisoned| poisoned.into_inner()).clone()
}

// ---------------------------------------------------------------------------
// The harness.
// ---------------------------------------------------------------------------

/// A session with the seams installed and the spy state reset.
fn fresh_session() -> Arc<Session> {
    CLOSED_SPY_IDS.lock().unwrap_or_else(|poisoned| poisoned.into_inner()).clear();
    NEXT_SPY_ID.store(0, Ordering::SeqCst);
    SPY_REFUSES_CONNECT.store(false, Ordering::SeqCst);
    set_transport_builder(Some(spy_builder));
    let session = Arc::new(Session::new());
    set_test_session(Some(session.clone()));
    session
}

/// A config the engine accepts (the F89 MTU gate passes).
fn test_config() -> VPNConfig {
    VPNConfig::test_fixture()
}

/// Runs an async observation on the core's runtime.
///
/// The tests are plain `#[test]`s, so this thread is **outside** a runtime —
/// which is the situation the FFI is in, and the only situation in which
/// `Runtime::block_on` may be called.
fn block_on<F: Future>(fut: F) -> F::Output {
    crate::api_client::get_runtime().expect("the core runtime must exist").block_on(fut)
}

/// Whether the session's stack is carrying a transport.
///
/// Observed through the stack's own API: with no active transport, `send`
/// answers *"No active transport"*; with one, it reaches the transport.
fn stack_has_transport(session: &Arc<Session>) -> bool {
    block_on(async { session.stack.send(Bytes::from_static(b"probe")).await.is_ok() })
}

/// A readable-but-idle descriptor: the read end of a pipe whose write end
/// stays open, so the tun loop parks instead of spinning on a permanent EOF.
///
/// The write end is returned so the caller can keep it **open** for the life
/// of the test.
fn idle_fd() -> (RawFd, RawFd) {
    let mut fds = [0 as libc::c_int; 2];
    let rc = unsafe { libc::pipe(fds.as_mut_ptr()) };
    assert_eq!(rc, 0, "pipe() failed: {}", std::io::Error::last_os_error());
    (fds[0], fds[1])
}

/// Whether the descriptor is still open.
fn is_open(fd: RawFd) -> bool {
    unsafe { libc::fcntl(fd, libc::F_GETFD) != -1 }
}

// ---------------------------------------------------------------------------
// The red-proofs — RFC-048 §7.1, one per row.
// ---------------------------------------------------------------------------

/// **1.** A `connect_transport` that fails returns `Err`; **no** generation
/// is allocated; with no prior session, the stack has **no** active
/// transport. Asserted on the stack's state, not on a log line.
#[test]
fn a_failed_connect_leaves_no_session() {
    let session = fresh_session();
    SPY_REFUSES_CONNECT.store(true, Ordering::SeqCst);

    let result = super::connect_transport(test_config());

    assert!(
        result.is_err(),
        "a handshake that fails must return Err, not a handle the caller would trust"
    );
    assert_eq!(
        session.live_generation(),
        0,
        "a failed connect must not allocate a generation (RFC-048 §3.2)"
    );
    assert!(
        !stack_has_transport(&session),
        "a failed connect must leave the stack with no active transport"
    );
}

/// **2.** With a **prior live session**, a failed `connect_transport` leaves
/// that session's transport **open** and its generation **valid** —
/// `attach_tun(priorHandle, fd)` still succeeds. §3.2's asymmetry.
///
/// ⚠️ **This is the test that keeps the concurrency honest.** It is the
/// core-side twin of §4.5's client-side rule, and it is what stops *"one
/// attempt's failure kills every attempt"* from being reintroduced inside
/// the core.
#[test]
fn a_failed_connect_leaves_a_live_session_untouched() {
    let session = fresh_session();
    let first = super::connect_transport(test_config()).expect("the first connect must succeed");
    let first_spy = last_spy_id();

    // The second attempt fails at the handshake.
    SPY_REFUSES_CONNECT.store(true, Ordering::SeqCst);
    let second = super::connect_transport(test_config());
    assert!(second.is_err(), "the second connect must fail at the handshake");

    // The live session is untouched: its transport is still open ...
    assert!(
        !closed_spy_ids().contains(&first_spy),
        "a failed connect must not close the live session's transport"
    );
    // ... and its generation is still valid.
    assert_eq!(
        session.live_generation(),
        first,
        "the live session's generation must stay valid across a failed attempt"
    );

    // Which is observable: the handle still attaches.
    let (fd, _write) = idle_fd();
    assert!(
        super::attach_tun(first, fd).is_ok(),
        "the live session's handle must still attach after another attempt failed"
    );
}

/// **3.** A successful `connect_transport` closes the previous session's
/// transport and invalidates its generation; `attach_tun(previousHandle, fd)`
/// is then refused.
#[test]
fn a_successful_connect_supersedes_the_previous_session() {
    let session = fresh_session();
    let first = super::connect_transport(test_config()).expect("the first connect must succeed");
    let first_spy = last_spy_id();

    let second = super::connect_transport(test_config()).expect("the second connect must succeed");
    assert_ne!(first, second, "each successful connect allocates a new generation");

    assert!(
        closed_spy_ids().contains(&first_spy),
        "a successful connect must close the superseded session's transport"
    );

    let (fd, _write) = idle_fd();
    let refused = super::attach_tun(first, fd);
    assert!(refused.is_err(), "a superseded handle must be refused");
    assert!(!is_open(fd), "a refused attach must close the descriptor it was handed");
    assert_eq!(session.live_generation(), second, "the new generation must be the live one");
}

/// **4.** After a failed `connect_transport`, there is no attached tun and
/// `ENGINE_RUNNING` is `false`. **This is the core-side half of F43's
/// ordering gate.**
#[test]
fn a_failed_connect_leaves_no_tun_attached() {
    let session = fresh_session();
    SPY_REFUSES_CONNECT.store(true, Ordering::SeqCst);

    assert!(super::connect_transport(test_config()).is_err());

    assert!(!session.has_tun(), "no tun may be attached after a failed connect");
    assert!(
        !ENGINE_RUNNING.load(Ordering::SeqCst),
        "ENGINE_RUNNING must be false after a failed connect: the engine never started"
    );
}

/// **5.** After a second `connect_transport`, `attach_tun(first_handle, fd)`
/// returns `Err`; the descriptor is **closed**; **no** `tun_loop` is spawned.
#[test]
fn attach_tun_with_a_stale_handle_is_refused() {
    let session = fresh_session();
    let first = super::connect_transport(test_config()).expect("the first connect must succeed");
    let _second = super::connect_transport(test_config()).expect("the second connect must succeed");

    let (fd, _write) = idle_fd();
    let refused = super::attach_tun(first, fd);

    assert!(refused.is_err(), "a stale handle must be refused");
    assert!(!is_open(fd), "a refused attach must close the descriptor it was handed");
    assert!(!session.has_tun(), "a refused attach must not spawn a tun loop");
}

/// **6.** The second `attach_tun` on a live handle returns `Err` and closes
/// the descriptor. **One session, one tun.**
#[test]
fn attach_tun_twice_on_one_session_is_refused() {
    let session = fresh_session();
    let handle = super::connect_transport(test_config()).expect("the connect must succeed");

    let (fd, _write) = idle_fd();
    assert!(super::attach_tun(handle, fd).is_ok(), "the first attach must succeed");
    assert!(session.has_tun(), "the first attach must own the session's tun");

    let (second_fd, _second_write) = idle_fd();
    let refused = super::attach_tun(handle, second_fd);

    assert!(refused.is_err(), "one session accepts one tun");
    assert!(!is_open(second_fd), "a refused attach must close the descriptor it was handed");
}

/// **7.** After `connect_transport` + `stop_engine`, the transport's
/// `close()` **was called**. Asserted with the close-counting spy.
///
/// ⚠️ **This is F43's second half.** Before the change, `stop_engine` only
/// flipped a flag: the transport stayed live on the node.
#[test]
fn stop_engine_closes_the_active_transport() {
    let session = fresh_session();
    super::connect_transport(test_config()).expect("the connect must succeed");
    let spy = last_spy_id();

    super::stop_engine();

    assert!(
        closed_spy_ids().contains(&spy),
        "stop_engine must close the active transport, not only flip a flag"
    );
    assert_eq!(
        session.live_generation(),
        0,
        "stop_engine must invalidate the live generation (RFC-048 §3.4)"
    );
}

/// **8.** A stale handle returns `false`; the live session's transport is
/// **still open**.
#[test]
fn close_transport_with_a_stale_handle_closes_nothing() {
    let session = fresh_session();
    let handle = super::connect_transport(test_config()).expect("the connect must succeed");
    let spy = last_spy_id();

    let closed = super::close_transport(handle.wrapping_add(1));

    assert!(!closed, "a stale handle must answer false: the caller did not own the session");
    assert!(
        !closed_spy_ids().contains(&spy),
        "a stale close must close nothing — the live session belongs to somebody else"
    );
    assert_eq!(session.live_generation(), handle, "the live session must be untouched");
}

/// **9.** Returns `true`; the transport is closed; a later `attach_tun` with
/// that handle is refused.
#[test]
fn close_transport_closes_the_transport_and_invalidates_the_generation() {
    let session = fresh_session();
    let handle = super::connect_transport(test_config()).expect("the connect must succeed");
    let spy = last_spy_id();

    assert!(super::close_transport(handle), "the owner must be told that it owned the session");

    assert!(closed_spy_ids().contains(&spy), "close_transport must close the session's transport");
    assert_eq!(session.live_generation(), 0, "close_transport must invalidate the generation");

    let (fd, _write) = idle_fd();
    assert!(super::attach_tun(handle, fd).is_err(), "a closed session's handle must be refused");
    assert!(!is_open(fd), "a refused attach must close the descriptor it was handed");
}

/// **10.** **The F43-b red-proof, and it reproduces on today's tree:**
/// `start_engine` succeeds, a second `start_engine` fails at the connect, and
/// the stack has **no** active transport. Before the change the previous
/// transport was still there — live on the node — with `ENGINE_RUNNING`
/// already false.
///
/// ⚠️ **This is the leak the daemon's restart path hits today**, with no
/// Android and no two-phase design in existence.
#[test]
fn a_failed_reconnect_leaves_no_transport_live() {
    let session = fresh_session();
    let (fd, _write) = idle_fd();
    super::start_engine(fd, test_config()).expect("the first start must succeed");
    let first_spy = last_spy_id();

    // The second start fails at the handshake.
    SPY_REFUSES_CONNECT.store(true, Ordering::SeqCst);
    let (second_fd, _second_write) = idle_fd();
    assert!(
        super::start_engine(second_fd, test_config()).is_err(),
        "the reconnect must fail at the handshake"
    );

    assert!(
        closed_spy_ids().contains(&first_spy),
        "the previous transport must not stay live on the node after a failed reconnect"
    );
    assert!(
        !stack_has_transport(&session),
        "no transport may remain active after a failed reconnect"
    );
}

/// **11.** The World A path is intact: `start_engine` connects, attaches,
/// spawns the tun loop, and the daemon's call site
/// (`apps/daemon/src/native_engine.rs:96` → `start_engine(fd, config)`)
/// keeps working.
#[test]
fn start_engine_still_composes_the_two_phases() {
    let session = fresh_session();
    let (fd, _write) = idle_fd();

    super::start_engine(fd, test_config()).expect("start_engine must succeed");

    assert!(session.live_generation() > 0, "the composed connect must allocate a generation");
    assert!(session.has_tun(), "the composed attach must own the session's tun");
    assert!(
        ENGINE_RUNNING.load(Ordering::SeqCst),
        "the composed path must leave the engine running"
    );
    assert!(stack_has_transport(&session), "the composed connect must leave a live transport");
}
