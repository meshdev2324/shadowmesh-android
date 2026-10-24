//! Implementation Source:
//! - Specification: WireGuard protocol (official whitepaper); Noise_IKpsk2
//!   handshake and packet crypto are provided by `boringtun` (MPL-2.0).
//! - ShadowMesh adds only the UDP socket plumbing and configuration parsing.
//! - No GPL/AGPL proxy source is consulted or ingested.

use super::wg_buffer::{self, ENCAPSULATION_BUFFER};
use super::{AsyncTransport, TransportType};
use crate::ShadowMeshError;
use async_trait::async_trait;
use boringtun::noise::{Tunn, TunnResult};
use bytes::Bytes;
use std::net::SocketAddr;
use std::sync::Arc;
use tokio::net::UdpSocket;
use tokio::sync::Mutex;
use tracing::{error, info};

/// Integrated WireGuard transport using boringtun.
#[derive(Clone)]
pub struct WireGuardTransport {
    remote_addr: SocketAddr,
    tunn: Arc<Mutex<Tunn>>,
    socket: Arc<Mutex<Option<Arc<UdpSocket>>>>,
}

impl std::fmt::Debug for WireGuardTransport {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("WireGuardTransport").field("remote_addr", &self.remote_addr).finish()
    }
}

impl WireGuardTransport {
    /// Creates a new `WireGuardTransport` with the specified remote address and keys.
    pub fn new(
        remote_addr: SocketAddr,
        private_key: String,
        public_key: String,
    ) -> Result<Self, ShadowMeshError> {
        use base64::engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD};
        use base64::Engine;

        let priv_key_bytes = STANDARD
            .decode(&private_key)
            .or_else(|_| URL_SAFE_NO_PAD.decode(private_key.trim_matches('=')))
            .map_err(|_| ShadowMeshError::Other("Invalid private key b64".into()))?;

        let pub_key_bytes = STANDARD
            .decode(&public_key)
            .or_else(|_| URL_SAFE_NO_PAD.decode(public_key.trim_matches('=')))
            .map_err(|_| ShadowMeshError::Other("Invalid public key b64".into()))?;

        let priv_key = x25519_dalek::StaticSecret::from(
            <[u8; 32]>::try_from(priv_key_bytes)
                .map_err(|_| ShadowMeshError::Other("Invalid private key length".into()))?,
        );
        let pub_key = x25519_dalek::PublicKey::from(
            <[u8; 32]>::try_from(pub_key_bytes)
                .map_err(|_| ShadowMeshError::Other("Invalid public key length".into()))?,
        );

        let tunn = Tunn::new(priv_key, pub_key, None, None, 0, None);

        Ok(Self {
            remote_addr,
            tunn: Arc::new(Mutex::new(tunn)),
            socket: Arc::new(Mutex::new(None)),
        })
    }
}

impl WireGuardTransport {
    /// Returns the connected socket, or `None` if `close` has run.
    ///
    /// The socket is cloned out as an `Arc` so the lock is released before any
    /// network call. `recv` blocks until a datagram arrives, so holding the
    /// lock across it would park a reader in the critical section and deadlock
    /// `close` — which is what leaks the descriptor on every reconnect.
    async fn connected_socket(&self) -> Result<Arc<UdpSocket>, ShadowMeshError> {
        self.socket
            .lock()
            .await
            .as_ref()
            .cloned()
            .ok_or_else(|| ShadowMeshError::Other("Not connected".into()))
    }
}

#[async_trait]
impl AsyncTransport for WireGuardTransport {
    fn transport_type(&self) -> TransportType {
        TransportType::WireGuard
    }

    async fn connect(&self) -> Result<(), ShadowMeshError> {
        use std::os::unix::io::AsRawFd;
        let socket = UdpSocket::bind("0.0.0.0:0")
            .await
            .map_err(|e| ShadowMeshError::IoError(e.to_string()))?;

        // v6.9.4: Protect socket to prevent infinite loop on Android
        crate::protect_socket(socket.as_raw_fd());

        socket
            .connect(self.remote_addr)
            .await
            .map_err(|e| ShadowMeshError::IoError(e.to_string()))?;
        *self.socket.lock().await = Some(Arc::new(socket));
        info!("🛡️ WireGuard Transport bound to port 443 (UDP)");
        Ok(())
    }

    async fn send(&self, data: Bytes) -> Result<(), ShadowMeshError> {
        let socket = self.connected_socket().await?;

        let mut buf = vec![0u8; ENCAPSULATION_BUFFER];
        // F89: same abort as the other transports, via `format_packet_data`.
        // This site was not in the original report.
        wg_buffer::check_encapsulable(data.len(), buf.len())?;
        let packet = {
            let mut tunn = self.tunn.lock().await;
            match tunn.encapsulate(&data, &mut buf) {
                TunnResult::WriteToNetwork(packet) => Some(packet.to_vec()),
                TunnResult::Err(e) => {
                    error!("WireGuard encapsulation error: {:?}", e);
                    None
                }
                _ => None,
            }
        };

        if let Some(packet) = packet {
            socket.send(&packet).await.map_err(|e| ShadowMeshError::IoError(e.to_string()))?;
        }

        Ok(())
    }

    async fn recv(&self) -> Result<Bytes, ShadowMeshError> {
        let socket = self.connected_socket().await?;

        let mut buf = vec![0u8; ENCAPSULATION_BUFFER];
        loop {
            let len =
                socket.recv(&mut buf).await.map_err(|e| ShadowMeshError::IoError(e.to_string()))?;
            // F89: `socket.recv` caps `len` at ENCAPSULATION_BUFFER, which
            // bounds the ciphertext below it too -- so this cannot panic today.
            // The guard states the invariant instead of relying on two
            // constants happening to be equal.
            wg_buffer::check_decapsulable(len, ENCAPSULATION_BUFFER)?;
            let reply = {
                let mut tunn = self.tunn.lock().await;
                let mut out_buf = vec![0u8; ENCAPSULATION_BUFFER];
                match tunn.decapsulate(None, &buf[..len], &mut out_buf) {
                    // A handshake or keepalive reply. Sent without holding the
                    // `tunn` lock so the send path is not blocked behind it.
                    TunnResult::WriteToNetwork(packet) => Some(packet.to_vec()),
                    TunnResult::WriteToTunnelV4(packet, _)
                    | TunnResult::WriteToTunnelV6(packet, _) => {
                        return Ok(Bytes::copy_from_slice(packet));
                    }
                    TunnResult::Err(e) => {
                        error!("WireGuard decapsulation error: {:?}", e);
                        None
                    }
                    _ => None,
                }
            };

            if let Some(reply) = reply {
                socket.send(&reply).await.map_err(|e| ShadowMeshError::IoError(e.to_string()))?;
            }
        }
    }

    async fn close(&self) -> Result<(), ShadowMeshError> {
        *self.socket.lock().await = None;
        Ok(())
    }
}
