//! Implementation Source:
//! - Specification: none (module registry and TransportStack orchestration; no
//!   wire format).
//! - No GPL/AGPL proxy source is consulted or ingested.

use crate::ShadowMeshError;
use async_trait::async_trait;
use bytes::Bytes;
use std::fmt::Debug;

/// WebSocket-based transport for CDN fallback.
/// Unified VPN engine orchestration.
pub mod engine;
/// Hysteria 2 transport.
pub mod hysteria;
/// The L4 axis: one table of "can this transport carry a datagram?" (F90).
pub mod l4;
/// Post-Quantum Cryptography (PQC) integration.
pub mod quantum;
/// Hysteria-inspired high-performance QUIC transport implementation.
pub mod quic;
/// Forensic-resistant stealth transport.
pub mod reality;
/// REALITY TLS 1.3 server endpoint (RFC-015).
pub mod reality_server;
/// REALITY TLS 1.3 Client authentication.
pub mod reality_tls;
/// Shadowsocks-based transport.
pub mod shadowsocks;
pub mod shadowsocks2022;
pub mod shadowsocks2022_transport;
pub mod tls_trust;
/// Low-level transport traits and abstractions.
pub mod traits;
pub mod utls_profile;
pub mod websocket;
/// Single owner of the WireGuard encapsulation-buffer invariant (F89).
pub mod wg_buffer;
/// Single owner of the `[len][pkt]` UDP framing codec (RFC-046 §3.2).
pub mod wg_frame;
/// Integrated WireGuard transport.
pub mod wireguard;

pub mod inbound;
pub mod outbound;

pub use traits::{AsyncIoStream, InboundListener, OutboundDialer};

use std::sync::Arc;
use tokio::sync::RwLock;

/// Manages an active transport and provides failover capabilities.
///
/// # Lock discipline
///
/// The active transport is held as an `Arc` so that a *handle* to it can be
/// cloned out from under the lock and the lock released **before** any call into
/// the transport. Every transport's `send` and `recv` block on the network, so
/// awaiting them while holding the guard would park a reader inside the
/// critical section for the whole life of the tunnel and deadlock every later
/// `swap()` behind the write lock — permanently, because the receive loop only
/// observes `ENGINE_RUNNING` between iterations and a task parked inside
/// `recv()` never gets there to see it.
///
/// Holding an `Arc` handle past the lock is safe against teardown: the
/// transport is kept alive for as long as a call is using it, and the old
/// transport's `close()` is invoked after the lock is released.
#[derive(Debug, Default)]
pub struct TransportStack {
    active_transport: Arc<RwLock<Option<Arc<dyn AsyncTransport>>>>,
}

impl TransportStack {
    /// Hot-swaps the current transport with a new one.
    ///
    /// The new transport is installed under the write lock, and the old one is
    /// closed only after that lock has been released — closing can itself
    /// block, and must never do so while holding it.
    pub async fn swap(&self, new_transport: Arc<dyn AsyncTransport>) {
        let old = {
            let mut guard = self.active_transport.write().await;
            guard.replace(new_transport)
        };
        if let Some(old) = old {
            let _ = old.close().await;
        }
    }

    /// Closes the active transport and leaves the stack with none.
    ///
    /// The same lock discipline as [`Self::swap`]: the `Arc` is taken out
    /// under the write lock, the lock is **released**, and the transport is
    /// closed **outside** it — a transport's `close()` can block on the
    /// network, and holding the write lock across it would wedge every later
    /// `swap()`.
    ///
    /// Returns the transport's own close result, or `None` when the stack
    /// held no transport to close. **The error is returned, not swallowed:**
    /// the caller decides what a failed close means (RFC-048 §3.2 — the FFI's
    /// `close_transport` logs it, because a teardown path must not raise).
    pub async fn close(&self) -> Option<Result<(), ShadowMeshError>> {
        let old = {
            let mut guard = self.active_transport.write().await;
            guard.take()
        };
        match old {
            Some(transport) => Some(transport.close().await),
            None => None,
        }
    }

    /// Returns a handle to the active transport, if there is one.
    ///
    /// The lock is released before this returns, so the caller can await
    /// against the transport without blocking a concurrent `swap`.
    async fn active(&self) -> Option<Arc<dyn AsyncTransport>> {
        let guard = self.active_transport.read().await;
        guard.clone()
    }

    /// Sends data via the active transport.
    pub async fn send(&self, data: Bytes) -> Result<(), ShadowMeshError> {
        match self.active().await {
            Some(t) => t.send(data).await,
            None => Err(ShadowMeshError::Other("No active transport".into())),
        }
    }

    /// Receives data via the active transport.
    pub async fn recv(&self) -> Result<Bytes, ShadowMeshError> {
        match self.active().await {
            Some(t) => t.recv().await,
            None => Err(ShadowMeshError::Other("No active transport".into())),
        }
    }
}

/// Represents the possible transport protocols for the ShadowMesh tunnel.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TransportType {
    /// Standard WireGuard UDP transport.
    WireGuard,
    /// REALITY forensic-resistant transport.
    Reality,
    /// Shadowsocks AEAD transport.
    Shadowsocks,
    /// Hysteria 2 high-performance transport.
    Hysteria,
    /// Hysteria-inspired high-performance QUIC transport.
    Quic,
    /// Last-resort fallback via WebSocket over CDN.
    WebSocket,
}

pub use crate::config::QuantumResistanceLevel;

/// A common interface for asynchronous network transports.
///
/// All transports must implement this trait to be utilized by the `TransportStack`.
#[async_trait]
pub trait AsyncTransport: Send + Sync + Debug {
    /// Returns the type of this transport.
    fn transport_type(&self) -> TransportType;

    /// Attempts to establish the transport connection to the remote endpoint.
    async fn connect(&self) -> Result<(), ShadowMeshError>;

    /// Sends a packet via the transport.
    async fn send(&self, data: Bytes) -> Result<(), ShadowMeshError>;

    /// Receives a packet from the transport.
    async fn recv(&self) -> Result<Bytes, ShadowMeshError>;

    /// Gracefully closes the transport connection.
    async fn close(&self) -> Result<(), ShadowMeshError>;
}
