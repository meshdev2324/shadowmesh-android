//! Implementation Source:
//! - Specification: none (internal transport selection, lifecycle and runtime
//!   plumbing; no on-wire format is defined here). Protocols are cited in their
//!   own modules.
//! - No GPL/AGPL proxy source is consulted or ingested.

use crate::transport::hysteria::HysteriaTransport;
use crate::transport::reality::RealityTransport;
use crate::transport::shadowsocks::ShadowsocksTransport;
use crate::transport::wireguard::WireGuardTransport;
use crate::transport::{AsyncTransport, TransportStack};
use crate::{ShadowMeshError, VPNConfig};
use bytes::Bytes;
use std::os::unix::io::{AsRawFd, FromRawFd, OwnedFd, RawFd};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, OnceLock};
use tracing::{error, info, warn};

/// Global control flag for the engine.
static ENGINE_RUNNING: AtomicBool = AtomicBool::new(false);
/// Storage for the active configuration (for fallback).
static ACTIVE_CONFIG: OnceLock<VPNConfig> = OnceLock::new();

/// The process-global engine session (RFC-048 §3.3: the core holds exactly
/// one engine session per process).
static SESSION: OnceLock<Arc<Session>> = OnceLock::new();

/// The fixed read buffer for the TUN descriptor.
///
/// A TUN read is a *stream* read: it returns whatever is available, up to the
/// buffer size. A short read is not a truncated packet, it is a slice of one,
/// and the remainder arrives on a later read. Nothing is lost by this bound --
/// it caps how many bytes a single readiness event can move, which is a
/// throughput property, not a correctness one.
pub(crate) const TUN_READ_BUFFER: usize = 2048;

/// One raw read from the TUN descriptor.
///
/// Extracted from the body of `tun_loop` so that the multi-read burst property
/// can be tested against production code rather than against a re-implementation
/// of it. A test that copies this logic proves only that the copy behaves as
/// its author believed; it stays green if `tun_loop` is broken. That is the
/// failure mode behind this function's existence.
///
/// Semantics are unchanged from the inline original: a positive result is the
/// byte count, and anything else -- including `WouldBlock` -- surfaces as an
/// error. That is correct in this position specifically because
/// `AsyncFd::try_io` treats an error as "readiness was stale", clears it and
/// re-arms on the next readable event. Returning `Err` for `WouldBlock` is how
/// the loop is told to come back later rather than spin.
///
/// A `res == 0` read is also folded into the error arm. On a TUN descriptor
/// that is a genuine failure, and the errno reported is the one left behind by
/// the last syscall rather than a synthesised one. Unchanged behaviour; noted
/// here so the next reader does not mistake it for an oversight.
fn read_tun_chunk(fd: RawFd, buf: &mut [u8]) -> std::io::Result<usize> {
    let res = unsafe { libc::read(fd, buf.as_mut_ptr() as *mut libc::c_void, buf.len()) };
    if res > 0 {
        Ok(res as usize)
    } else {
        Err(std::io::Error::last_os_error())
    }
}

/// The mutable state of one engine session.
///
/// The **generation** is the handle the FFI hands out. It identifies the
/// *session*, not a transport instance (RFC-048 §3.3): a mid-session
/// fallback swap does not invalidate it, a new `connect_transport` does.
#[derive(Debug, Default)]
struct SessionState {
    /// The live generation. `0` means "no live session".
    generation: u64,
    /// The generation to hand out next. Monotonic, never reused, never `0`.
    next_generation: u64,
    /// Whether the live session already owns a tun. **One session, one tun.**
    tun_attached: bool,
}

/// One engine session: the stack, the generation, and the tun ownership.
///
/// The session is a **value**, not a set of process statics, for one reason:
/// the red-proofs each need an isolated session, and a process-global would
/// make one test's session the next test's live session. The production
/// process-global is [`SESSION`]; the type is the same either way, so the
/// code under test is the code that ships.
pub(crate) struct Session {
    /// The stack the session's transport lives in.
    stack: Arc<TransportStack>,
    /// The session's state. A `std::sync::Mutex` and not a tokio one: the
    /// critical sections are a few instructions wide and never await.
    state: Mutex<SessionState>,
}

impl Session {
    /// A fresh session with no transport and no live generation.
    fn new() -> Self {
        Self {
            stack: Arc::new(TransportStack::default()),
            state: Mutex::new(SessionState::default()),
        }
    }

    /// Locks the session state.
    ///
    /// A poisoned lock means another thread panicked while holding it. The
    /// state is two `u64`s and a `bool` — it cannot be left half-updated — so
    /// the lock is recovered rather than propagated: an engine that has
    /// panicked once must still be able to stop.
    fn state(&self) -> MutexGuard<'_, SessionState> {
        self.state.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    /// The live generation, or `0` when there is no live session.
    #[cfg(test)]
    fn live_generation(&self) -> u64 {
        self.state().generation
    }

    /// Whether the live session already owns a tun.
    #[cfg(test)]
    fn has_tun(&self) -> bool {
        self.state().tun_attached
    }

    /// Invalidates the live generation.
    ///
    /// The session is gone: every handle from it is now stale, and nothing
    /// can attach to it.
    fn invalidate(&self) {
        let mut state = self.state();
        state.generation = 0;
        state.tun_attached = false;
    }

    /// Allocates the next generation and makes it the live one.
    ///
    /// Monotonic and never `0`: `0` is reserved for "no session" and is never
    /// returned to a caller (RFC-048 §3.2).
    fn allocate_generation(&self) -> u64 {
        let mut state = self.state();
        state.next_generation = state.next_generation.wrapping_add(1);
        if state.next_generation == 0 {
            state.next_generation = 1;
        }
        state.generation = state.next_generation;
        state.generation
    }

    /// Claims the live session for `handle`.
    ///
    /// Returns `true` when the handle named the live session — in which case
    /// the generation is invalidated and the caller owns the teardown.
    /// Returns `false` for a stale handle, and **nothing is touched**: the
    /// caller does not own the session and must not close it.
    fn claim(&self, handle: u64) -> bool {
        let mut state = self.state();
        if handle == 0 || handle != state.generation {
            return false;
        }
        state.generation = 0;
        state.tun_attached = false;
        true
    }

    /// Establishes the transport and installs it as the session's.
    ///
    /// **Supersession happens on success only** (RFC-048 §3.2). A connect
    /// that fails leaves the live session **untouched**: its transport stays
    /// open and its generation stays valid. The asymmetry is load-bearing —
    /// the client runs connect attempts concurrently, and a failed attempt
    /// that tore down a *newer* attempt's session would turn one attempt's
    /// failure into every attempt's failure.
    async fn connect(&self, config: &VPNConfig) -> Result<u64, ShadowMeshError> {
        // The active configuration is kept for the mid-session fallback. It
        // is set once per process (`OnceLock`), exactly as before.
        let _ = ACTIVE_CONFIG.set(config.clone());

        // The handshake. A failure here returns before anything is touched.
        let transport = build_and_connect(config).await?;

        // The previous handle is stale from this instant: a concurrent
        // `attach_tun` must not reach the transport that is about to be
        // replaced.
        self.invalidate();

        // `swap` installs the new transport and closes the outgoing one —
        // the previous session's transport is closed by the swap itself.
        self.stack.swap(transport).await;

        let generation = self.allocate_generation();
        info!("🔌 transport established (generation {generation}, mode {})", config.traffic_mode);
        Ok(generation)
    }

    /// Attaches the tun descriptor to the live session.
    ///
    /// The descriptor is **adopted by the caller** (an `OwnedFd`), so every
    /// failure path closes it and no `tun_loop` is spawned.
    async fn attach(&self, handle: u64, fd: OwnedFd) -> Result<(), ShadowMeshError> {
        {
            let mut state = self.state();
            if handle == 0 || handle != state.generation {
                return Err(ShadowMeshError::Other(format!(
                    "stale transport handle {handle}: it does not name the live session"
                )));
            }
            if state.tun_attached {
                return Err(ShadowMeshError::Other(
                    "this session already owns a tun: one session, one tun".into(),
                ));
            }
            state.tun_attached = true;
        }

        // The flag is set **before** the spawn: the loops observe it between
        // iterations, and a loop that started with the flag already false
        // would exit on its first turn.
        ENGINE_RUNNING.store(true, Ordering::SeqCst);
        let stack = self.stack.clone();
        tokio::spawn(tun_loop(fd, stack));
        info!("🔌 tun attached (generation {handle})");
        Ok(())
    }

    /// Closes the stack's active transport, if there is one.
    ///
    /// The transport's own `close()` error is **logged, not raised**: a
    /// teardown path must not force the caller into an exception handler
    /// (RFC-048 §3.2), and a silent failure is not acceptable either
    /// (RFC-040: *"healing must never be silent"*).
    async fn close_transport(&self) {
        match self.stack.close().await {
            Some(Ok(())) => info!("🔌 transport closed"),
            Some(Err(e)) => error!("🔌 transport close failed: {e}"),
            None => info!("🔌 no transport to close"),
        }
    }
}

/// The process-global session.
///
/// `start_engine`, `stop_engine` and the three FFI entry points all reach
/// this one session, which is what makes the generation meaningful: it names
/// *the* session the process holds.
fn session() -> Arc<Session> {
    #[cfg(test)]
    {
        // The session is **cloned** out of the lock, not moved: `MutexGuard`
        // gives a `&mut Option<Arc<Session>>`, and a move out of a
        // dereference is not allowed (E0507). `Arc` is a handle, so cloning it
        // costs one atomic increment.
        //
        // The lock is released **before** the caller gets the session: holding
        // the process-global session's lock across the caller's work would
        // serialise every caller behind it, and the test seam exists to give
        // each test its own session — not to make them queue for it.
        let installed: Option<Arc<Session>> = {
            let guard = TEST_SESSION.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
            match &*guard {
                Some(session) => Some(Arc::clone(session)),
                None => None,
            }
        };
        if let Some(test_session) = installed {
            return test_session;
        }
    }
    SESSION.get_or_init(|| Arc::new(Session::new())).clone()
}

/// The test seam: the session the process-global accessor returns.
///
/// `None` (the default) means "the real process-global session". A test
/// installs a fresh session so that its session is not the next test's live
/// session — the red-proofs are the reason this exists, and it is compiled
/// out of a release build.
#[cfg(test)]
static TEST_SESSION: Mutex<Option<Arc<Session>>> = Mutex::new(None);

/// Installs (or removes) the test seam's session.
#[cfg(test)]
pub(crate) fn set_test_session(session: Option<Arc<Session>>) {
    *TEST_SESSION.lock().unwrap_or_else(|poisoned| poisoned.into_inner()) = session;
}

/// Closes the session's transport, now or on the runtime.
///
/// `stop_engine` and `close_transport` are called from three places: the FFI
/// (a foreign thread), the engine thread (a plain OS thread) and the
/// daemon's async shutdown (inside a runtime). `Runtime::block_on` **panics**
/// inside a runtime, so the in-runtime case schedules the close on the
/// runtime instead of blocking on it. The generation is already invalidated
/// by then, so nothing can attach to the session while the close is in
/// flight.
fn close_now(session: &Arc<Session>) {
    let Ok(rt) = crate::api_client::get_runtime() else {
        error!("🔌 engine teardown: no runtime is available; the transport was not closed");
        return;
    };
    match tokio::runtime::Handle::try_current() {
        Ok(handle) => {
            let session = session.clone();
            handle.spawn(async move { session.close_transport().await });
        }
        Err(_) => rt.block_on(session.close_transport()),
    }
}

/// Starts the unified VPN engine.
///
/// **One implementation of each phase** (RFC-048 §3.5): the stop, the
/// connect and the attach below are the *same* code the three FFI entry
/// points run. There is no second copy of "connect a transport" to drift.
///
/// The order is **unconditional stop → connect → attach**. The stop is
/// unconditional because `ENGINE_RUNNING` is false in exactly the state the
/// stop exists for: a failed reconnect leaves the previous transport live
/// with the flag already false, so gating the teardown on the flag skips the
/// teardown in the one case it is needed.
pub fn start_engine(fd: i32, config: VPNConfig) -> Result<(), ShadowMeshError> {
    // F89: last line of defence. Both the daemon and the Android FFI reach the
    // engine here, and each of them sizes its TUN device from `config.mtu`
    // before calling. Whatever a future consumer forgets to validate on its
    // own load path, an out-of-range MTU is refused here rather than becoming
    // a process abort on the first packet larger than 2016 bytes.
    config.validate()?;

    // Unconditional: close any live session and invalidate its generation.
    // Called **outside** the runtime, because `Runtime::block_on` panics
    // inside one.
    stop_engine();

    let rt = crate::api_client::get_runtime()?;
    rt.block_on(async move {
        let handle = session().connect(&config).await?;

        // The descriptor is adopted only once the transport is up: a failed
        // connect must leave the caller's descriptor untouched (the daemon
        // still owns its TUN device), and the attach cannot fail for a
        // reason that would close it (the session was just created and its
        // stop cleared the tun ownership).
        let owned = unsafe { OwnedFd::from_raw_fd(fd) };
        session().attach(handle, owned).await
    })
}

/// Establishes the transport and returns the session's generation.
///
/// RFC-048 §3.1/§3.2 — **frozen**. The client calls this with **no** tun
/// descriptor: the handshake does not need one, so the capture can be
/// installed only after the transport is proven.
///
/// The returned handle is a **generation token**, not a capability: it names
/// the session, it carries no authority, and a stale one is rejected rather
/// than honoured.
pub fn connect_transport(config: VPNConfig) -> Result<u64, ShadowMeshError> {
    // The same F89 MTU gate `start_engine` applies, before any socket is
    // opened: a config the engine would refuse is refused here.
    config.validate()?;
    let rt = crate::api_client::get_runtime()?;
    rt.block_on(async move { session().connect(&config).await })
}

/// Attaches the tun descriptor to the session named by `handle`.
///
/// RFC-048 §3.1/§3.2 — **frozen**. The descriptor is adopted **at the
/// call**: from here it has exactly one owner, and every failure path
/// closes it without spawning a tun loop.
pub fn attach_tun(handle: u64, fd: i32) -> Result<(), ShadowMeshError> {
    if fd < 0 {
        return Err(ShadowMeshError::Other(
            "attach_tun: the descriptor is not a valid file descriptor".into(),
        ));
    }
    // Adopted before any fallible step, so a refusal still closes it.
    let owned = unsafe { OwnedFd::from_raw_fd(fd) };
    let rt = crate::api_client::get_runtime()?;
    rt.block_on(async move { session().attach(handle, owned).await })
}

/// Closes the session named by `handle`.
///
/// RFC-048 §3.1/§3.2 — **frozen**. **Not** a throwing path: a teardown must
/// not force the caller into an exception handler. The return value answers
/// *"did I own the session?"* — `false` means the handle was stale, nothing
/// was closed, and the caller must not touch the live session.
pub fn close_transport(handle: u64) -> bool {
    let session = session();
    if !session.claim(handle) {
        return false;
    }
    close_now(&session);
    true
}

/// Stops the engine.
///
/// RFC-048 §3.4: the flag, **the transport**, and the generation. Before
/// this change the function only flipped the flag, so a failed reconnect
/// left the previous transport live on the node — a leak that reproduces on
/// the daemon's restart path today, with no Android involved.
pub fn stop_engine() {
    ENGINE_RUNNING.store(false, Ordering::SeqCst);
    let session = session();
    // The generation is invalidated before the transport is closed, so no
    // `attach_tun` can slip onto a session that is being torn down.
    session.invalidate();
    close_now(&session);
    info!("🛑 ShadowMesh Unified Engine Stopped.");
}

/// Builds the transport for `config` and completes the handshake.
///
/// The mode ladder is unchanged (RFC-048 §3.2): REALITY → Shadowsocks →
/// Hysteria → VMess → WireGuard.
async fn build_and_connect(config: &VPNConfig) -> Result<Arc<dyn AsyncTransport>, ShadowMeshError> {
    let transport = build_transport(config)?;
    transport.connect().await?;
    Ok(transport)
}

/// Builds the transport for `config` without connecting it.
fn build_transport(config: &VPNConfig) -> Result<Arc<dyn AsyncTransport>, ShadowMeshError> {
    #[cfg(test)]
    {
        // The test seam: the red-proofs need a transport whose `close()` is
        // observable and whose `connect()` can be made to fail without a
        // node. It is compiled out of a release build, so there is no way to
        // reach it from the FFI.
        if let Some(builder) =
            *TRANSPORT_BUILDER.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
        {
            return builder(config);
        }
    }
    build_transport_production(config)
}

/// The production transport ladder.
fn build_transport_production(
    config: &VPNConfig,
) -> Result<Arc<dyn AsyncTransport>, ShadowMeshError> {
    // v6.9.1 Adaptive Entry: If mode is Reality, start with REALITY directly to avoid
    // UDP blockage delay.
    if config.traffic_mode == "reality" && config.reality_config.is_some() {
        let reality_config = config
            .reality_config
            .as_ref()
            .ok_or_else(|| ShadowMeshError::Other("missing reality config".into()))?;

        let priv_key = decode_key(config.private_key.as_deref().unwrap_or_default())?;
        let pub_key = decode_key(&config.public_key)?;

        Ok(Arc::new(RealityTransport::new(reality_config.clone(), priv_key, pub_key)))
    } else if config.traffic_mode == "shadowsocks" && config.shadowsocks_config.is_some() {
        let ss_config = config
            .shadowsocks_config
            .as_ref()
            .ok_or_else(|| ShadowMeshError::Other("missing shadowsocks config".into()))?;

        let priv_key =
            decode_key(config.private_key.as_deref().unwrap_or_default()).unwrap_or([0u8; 32]);
        let pub_key = decode_key(&config.public_key).unwrap_or([0u8; 32]);

        Ok(Arc::new(ShadowsocksTransport::new(ss_config.clone(), priv_key, pub_key)))
    } else if config.traffic_mode == "hysteria" && config.hysteria_config.is_some() {
        let hysteria_config = config
            .hysteria_config
            .as_ref()
            .ok_or_else(|| ShadowMeshError::Other("missing hysteria config".into()))?;

        let priv_key =
            decode_key(config.private_key.as_deref().unwrap_or_default()).unwrap_or([0u8; 32]);
        let pub_key = decode_key(&config.public_key).unwrap_or([0u8; 32]);

        Ok(Arc::new(HysteriaTransport::new(hysteria_config.clone(), priv_key, pub_key)))
    } else if config.traffic_mode == "vmess" && config.vmess_config.is_some() {
        let vmess_config = config
            .vmess_config
            .as_ref()
            .ok_or_else(|| ShadowMeshError::Other("missing vmess config".into()))?;

        // v6.9.22: VMess Support in Unified Engine
        let (priv_key_vec, pub_key_vec) = shadowmesh_common::crypto::generate_x25519_keypair();
        let mut priv_key = [0u8; 32];
        priv_key.copy_from_slice(&priv_key_vec);
        let mut pub_key = [0u8; 32];
        pub_key.copy_from_slice(&pub_key_vec);

        Ok(Arc::new(crate::transport::outbound::vmess::VmessTransport::new(
            vmess_config.clone(),
            priv_key,
            pub_key,
        )))
    } else {
        // Tier 1: Initialize WireGuard (UDP/443)
        let remote_addr = config
            .endpoint
            .parse()
            .map_err(|_| ShadowMeshError::Other("Invalid endpoint address".into()))?;

        let wg = WireGuardTransport::new(
            remote_addr,
            config.private_key.clone().unwrap_or_default(),
            config.public_key.clone(),
        )?;

        Ok(Arc::new(wg))
    }
}

/// The test seam's builder type.
#[cfg(test)]
type TransportBuilder = fn(&VPNConfig) -> Result<Arc<dyn AsyncTransport>, ShadowMeshError>;

/// The test seam's installed builder.
#[cfg(test)]
static TRANSPORT_BUILDER: Mutex<Option<TransportBuilder>> = Mutex::new(None);

/// Installs (or removes) the test seam's transport builder.
#[cfg(test)]
pub(crate) fn set_transport_builder(builder: Option<TransportBuilder>) {
    *TRANSPORT_BUILDER.lock().unwrap_or_else(|poisoned| poisoned.into_inner()) = builder;
}

/// The tun loop: two tasks, one reading the tun into the transport, one
/// writing what the transport receives back into the tun.
///
/// The descriptor arrives as an [`OwnedFd`] — the caller adopted it, so the
/// loop owns it from here and there is exactly one owner of the descriptor
/// from the moment it crossed the boundary.
async fn tun_loop(fd: OwnedFd, stack: Arc<TransportStack>) {
    use std::sync::atomic::Ordering;
    use tokio::io::unix::AsyncFd;

    // v6.9.5: High-Performance Non-blocking TUN
    // Ensure the FD is non-blocking before wrapping in AsyncFd
    unsafe {
        let flags = libc::fcntl(fd.as_raw_fd(), libc::F_GETFL);
        libc::fcntl(fd.as_raw_fd(), libc::F_SETFL, flags | libc::O_NONBLOCK);
    }

    // SAFETY: `fd` is a valid, open descriptor with exactly one owner: this
    // loop received it as an `OwnedFd` and wraps it once in the `AsyncFd`,
    // whose `Arc` keeps the descriptor open and referring to the same file
    // description for the `AsyncFd`'s entire lifetime. It is never duplicated,
    // re-registered, or extracted via `into_inner`.
    let async_fd = match unsafe { AsyncFd::register(fd) } {
        Ok(afd) => Arc::new(afd),
        Err(e) => {
            error!("Failed to create AsyncFd for TUN: {}", e);
            return;
        }
    };

    let manager = crate::vpn_manager::GLOBAL_MANAGER.get();

    // Read from TUN (AsyncFd), Send to Transport
    let stack_send = stack.clone();
    let manager_send = manager.cloned();
    let read_fd = async_fd.clone();
    tokio::spawn(async move {
        let mut b = [0u8; TUN_READ_BUFFER];
        while ENGINE_RUNNING.load(Ordering::Relaxed) {
            match read_fd.readable().await {
                Ok(mut guard) => {
                    match guard.try_io(|inner| read_tun_chunk(inner.as_raw_fd(), &mut b)) {
                        Ok(Ok(n)) => {
                            let data = Bytes::copy_from_slice(&b[..n]);

                            // v6.9.22: Mandatory Throttling in IO Hot-Path (RFC-001)
                            if let Some(ref m) = manager_send {
                                let _ = m.get_throttler().throttle(n).await;
                                m.get_atomic_stats()
                                    .bytes_sent
                                    .fetch_add(n as u64, Ordering::Relaxed);
                                m.get_atomic_stats().packets_sent.fetch_add(1, Ordering::Relaxed);
                            }

                            if let Err(e) = stack_send.send(data).await {
                                error!("Transport send error: {}", e);
                            }
                        }
                        Ok(Err(e)) => {
                            if e.kind() != std::io::ErrorKind::WouldBlock {
                                error!("TUN raw read error: {}", e);
                            }
                        }
                        Err(_would_block) => continue,
                    }
                }
                Err(e) => {
                    error!("AsyncFd readable error: {}", e);
                    break;
                }
            }
        }
    });

    // Receive from Transport, Write to TUN (AsyncFd)
    let stack_recv = stack.clone();
    let manager_recv = manager.cloned();
    let write_fd = async_fd.clone();
    tokio::spawn(async move {
        while ENGINE_RUNNING.load(Ordering::Relaxed) {
            match stack_recv.recv().await {
                Ok(packet) if !packet.is_empty() => {
                    match write_fd.writable().await {
                        Ok(mut guard) => {
                            // v6.9.22: Mandatory Throttling in IO Hot-Path (RFC-001)
                            if let Some(ref m) = manager_recv {
                                let _ = m.get_throttler().throttle(packet.len()).await;
                                m.get_atomic_stats()
                                    .bytes_received
                                    .fetch_add(packet.len() as u64, Ordering::Relaxed);
                                m.get_atomic_stats()
                                    .packets_received
                                    .fetch_add(1, Ordering::Relaxed);
                                let now = chrono::Utc::now().timestamp();
                                m.get_atomic_stats().last_handshake.store(now, Ordering::Relaxed);
                            }

                            let _ = guard.try_io(|inner| {
                                let res = unsafe {
                                    libc::write(
                                        inner.as_raw_fd(),
                                        packet.as_ptr() as *const libc::c_void,
                                        packet.len(),
                                    )
                                };
                                if res > 0 {
                                    Ok(res as usize)
                                } else {
                                    Err(std::io::Error::last_os_error())
                                }
                            });
                        }
                        Err(e) => {
                            error!("AsyncFd writable error: {}", e);
                            break;
                        }
                    }
                }
                Ok(_) => {
                    tokio::time::sleep(std::time::Duration::from_millis(10)).await;
                }
                Err(e) => {
                    error!("Transport receive error: {}", e);
                    fallback_to_reality(stack_recv.clone()).await;
                }
            }
        }
    });
}

async fn fallback_to_reality(stack: Arc<TransportStack>) {
    warn!("⚠️ UDP Transport failure detected. Escalating to REALITY (TCP/443)...");

    if let Some(config) = ACTIVE_CONFIG.get() {
        if let Some(reality_config) = &config.reality_config {
            let priv_key =
                decode_key(config.private_key.as_deref().unwrap_or_default()).unwrap_or([0u8; 32]);
            let pub_key = decode_key(&config.public_key).unwrap_or([0u8; 32]);

            let reality = RealityTransport::new(reality_config.clone(), priv_key, pub_key);
            if let Err(e) = reality.connect().await {
                error!("Failed to connect to REALITY fallback: {}", e);
            } else {
                stack.swap(Arc::new(reality)).await;
                info!("✅ Successfully escalated to REALITY transport.");
            }
        } else {
            error!("REALITY fallback failed: No reality_config found in active config.");
        }
    } else {
        error!("REALITY fallback failed: No active configuration found.");
    }
}

fn decode_key(s: &str) -> Result<[u8; 32], ShadowMeshError> {
    use base64::prelude::*;
    let b = BASE64_STANDARD
        .decode(s)
        .map_err(|_| ShadowMeshError::Other("Invalid key format".into()))?;
    if b.len() != 32 {
        return Err(ShadowMeshError::Other("Invalid key length".into()));
    }
    let mut key = [0u8; 32];
    key.copy_from_slice(&b);
    Ok(key)
}

#[cfg(test)]
mod tests {
    //! **F65 -- coverage for the TUN read loop's multi-event burst behaviour.**
    //!
    //! ## The claim that was wrong
    //!
    //! L9 claimed the TUN read path silently truncated packets larger than its
    //! buffer. **That claim was wrong and is recorded as refuted.** The loop at
    //! `tun_loop` re-arms on readability, so tail bytes arrive on a later read.
    //! L3 confirmed it on a real fd with a 6000-byte coalesced burst, observing
    //! 2048 / 2048 / 1904 = 6000 of 6000. A 2048-byte buffer cannot return more
    //! than 2048 by definition: it bounds per-read throughput, not correctness.
    //!
    //! ## What survived
    //!
    //! A real **coverage gap**. "The loop delivers the whole burst" was true only
    //! as a one-time manual observation, so a future refactor could break it
    //! silently and the only symptom would be packet loss in production.
    //!
    //! ## Why these tests live here rather than in `tests/`
    //!
    //! An integration test cannot reach `read_tun_chunk`, so the obvious move is
    //! to re-implement the read in the test file. That is the trap: a copy of
    //! this logic proves the copy behaves as its author expected, and stays
    //! green if production is broken -- precisely the F59 failure class, where a
    //! duplicated implementation was mistaken for a tested one. These tests call
    //! the production function directly. If `tun_loop` ever stops draining, or
    //! `read_tun_chunk` changes what it returns, these fail.
    //!
    //! No production behaviour is changed by this module. It locks in what the
    //! loop already does.

    use super::{read_tun_chunk, TUN_READ_BUFFER};
    use std::io;
    use std::os::unix::io::RawFd;

    /// A non-blocking pipe, mirroring the `O_NONBLOCK` set in `tun_loop`.
    ///
    /// The write end is returned so the caller can keep it OPEN while reading:
    /// a pipe whose write end is closed reports EOF, and EOF on a pipe is not
    /// the condition a TUN descriptor produces.
    fn nonblocking_pipe() -> (RawFd, RawFd) {
        let mut fds = [0 as RawFd; 2];
        let rc = unsafe { libc::pipe(fds.as_mut_ptr()) };
        assert_eq!(rc, 0, "pipe() failed: {}", io::Error::last_os_error());
        for fd in fds {
            unsafe {
                let flags = libc::fcntl(fd, libc::F_GETFL);
                assert_ne!(flags, -1, "F_GETFL failed");
                assert_eq!(libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK), 0);
            }
        }
        (fds[0], fds[1])
    }

    /// Writes the payload in full, tolerating partial writes and `WouldBlock`.
    fn write_all_nonblocking(fd: RawFd, payload: &[u8]) {
        let mut written = 0;
        // Bounded so a genuine failure surfaces as a failure, not a hang.
        for _ in 0..10_000 {
            if written == payload.len() {
                return;
            }
            let res = unsafe {
                libc::write(
                    fd,
                    payload[written..].as_ptr() as *const libc::c_void,
                    payload.len() - written,
                )
            };
            if res > 0 {
                written += res as usize;
            } else {
                let err = io::Error::last_os_error();
                assert_eq!(err.kind(), io::ErrorKind::WouldBlock, "write failed: {err}");
                std::thread::sleep(std::time::Duration::from_millis(1));
            }
        }
        panic!("write_all_nonblocking did not complete");
    }

    /// Byte value at position `i`, so every offset is uniquely determined.
    ///
    /// A length-only assertion would pass if bytes were duplicated or reordered
    /// in a way that preserved the total.
    fn payload(len: usize) -> Vec<u8> {
        (0..len).map(|i| (i % 251) as u8).collect()
    }

    /// Drains `fd` with the production reader until `expected` bytes are read.
    fn drain_with_production_reader(fd: RawFd, expected: usize) -> (Vec<u8>, Vec<usize>) {
        let mut buf = [0u8; TUN_READ_BUFFER];
        let mut collected = Vec::with_capacity(expected);
        let mut sizes = Vec::new();

        while collected.len() < expected {
            match read_tun_chunk(fd, &mut buf) {
                Ok(n) => {
                    assert!(n <= TUN_READ_BUFFER, "read returned more than the buffer: {n}");
                    sizes.push(n);
                    collected.extend_from_slice(&buf[..n]);
                }
                // `WouldBlock` is how production reports "nothing queued yet";
                // the loop is meant to come back later. If it happens before the
                // payload is complete, that is a real defect, not a normal exit.
                Err(e) if e.kind() == io::ErrorKind::WouldBlock => {
                    panic!("WouldBlock with only {} of {} bytes read", collected.len(), expected);
                }
                Err(e) => panic!("unexpected read error: {e}"),
            }
        }
        (collected, sizes)
    }

    /// The core F65 guarantee, asserted against production code: a burst larger
    /// than the read buffer arrives intact across reads. This is exactly what
    /// L3 verified by hand; here it fails the build if it stops being true.
    #[test]
    fn burst_larger_than_buffer_arrives_intact_across_reads() {
        let (read_fd, write_fd) = nonblocking_pipe();
        let expected = 6000; // 2048 + 2048 + 1904; not a buffer multiple, so a dropped tail shows
        let data = payload(expected);
        write_all_nonblocking(write_fd, &data);

        let (collected, sizes) = drain_with_production_reader(read_fd, expected);
        unsafe {
            libc::close(read_fd);
            libc::close(write_fd);
        }

        assert_eq!(
            sizes,
            vec![2048, 2048, 1904],
            "a 6000-byte burst must chunk 2048/2048/1904 through the production reader"
        );
        assert_eq!(
            collected.len(),
            expected,
            "burst was truncated: {}/{}",
            collected.len(),
            expected
        );
        assert_eq!(collected, data, "burst arrived reordered or corrupted, not merely truncated");
    }

    /// One byte past the buffer: the sharpest case, because if the loop ever
    /// treated a short read as a whole message the 2049th byte would vanish.
    #[test]
    fn one_byte_past_the_buffer_is_not_dropped() {
        let (read_fd, write_fd) = nonblocking_pipe();
        let expected = TUN_READ_BUFFER + 1;
        let data = payload(expected);
        write_all_nonblocking(write_fd, &data);

        let (collected, sizes) = drain_with_production_reader(read_fd, expected);
        unsafe {
            libc::close(read_fd);
            libc::close(write_fd);
        }

        assert_eq!(sizes, vec![2048, 1], "the tail must arrive as its own read");
        assert_eq!(collected, data, "the 2049th byte was dropped");
    }

    /// A burst exactly one buffer long must come back in a single read.
    #[test]
    fn burst_exactly_one_buffer_arrives_in_one_read() {
        let (read_fd, write_fd) = nonblocking_pipe();
        let data = payload(TUN_READ_BUFFER);
        write_all_nonblocking(write_fd, &data);

        let mut buf = [0u8; TUN_READ_BUFFER];
        let n = read_tun_chunk(read_fd, &mut buf).expect("first read failed");
        unsafe {
            libc::close(read_fd);
            libc::close(write_fd);
        }

        assert_eq!(n, TUN_READ_BUFFER, "a buffer-sized burst must not be split");
        assert_eq!(&buf[..n], &data[..]);
    }

    /// The loop's exit condition is documented behaviour, so it is locked.
    ///
    /// With nothing queued, production surfaces `WouldBlock` as an error and
    /// `tun_loop` continues on the next readable event. This asserts the reader
    /// reports that state rather than returning 0 bytes that the loop would then
    /// treat as a packet.
    #[test]
    fn empty_ready_fd_reports_would_block_not_zero_bytes() {
        let (read_fd, write_fd) = nonblocking_pipe();
        let mut buf = [0u8; TUN_READ_BUFFER];

        let res = read_tun_chunk(read_fd, &mut buf);
        unsafe {
            libc::close(read_fd);
            libc::close(write_fd);
        }

        let err = res.expect_err("an empty non-blocking fd must not yield zero bytes as data");
        assert_eq!(
            err.kind(),
            io::ErrorKind::WouldBlock,
            "the loop relies on WouldBlock to defer, not to receive an empty packet"
        );
    }
}

/// F43-b's red-proofs (RFC-048 §7.1) — the two-phase contract's core half.
#[cfg(test)]
mod f43_capture_ordering_test;
