//! Implementation Source:
//! - Specification: VLESS (public protocol specification) for UDP framing,
//!   WireGuard (official whitepaper) for the inner tunnel, and the REALITY
//!   dialect for authentication (see `reality_tls`).
//! - No GPL/AGPL proxy source is consulted or ingested.

use super::reality_tls::RealityTlsStream;
/// Upper sanity bound for one VLESS-UDP framed WG packet.
///
/// F89: this used to be an independent `4096`, which admitted frames 2032
/// bytes larger than the `ip_buf` they were written into — and
/// `receive_packet_data` panics on exactly that overflow. It is now derived
/// from the buffer it protects, in `super::wg_buffer`.
use super::wg_buffer::{self, ENCAPSULATION_BUFFER, MAX_PACKET};
/// The `[len][pkt]` framing codec — the single owner of the length prefix
/// (RFC-046 §3.2). This transport does not re-derive it.
use super::wg_frame::{self, FrameReassembler};
use super::{AsyncTransport, TransportType};
use crate::{RealityConfig, ShadowMeshError};
use async_trait::async_trait;
use boringtun::noise::{Tunn, TunnResult};
use bytes::{BufMut, Bytes};
use socket2::{Domain, Socket, Type};
use std::os::unix::io::AsRawFd;
use std::sync::Arc;
use tokio::net::TcpStream;
use tokio::sync::Mutex;
use tracing::{error, info, warn};

/// Established REALITY tunnel: TLS stream + VLESS UDP frame reassembly buffer.
struct Tunnel {
    tls: RealityTlsStream,
    /// Decrypted app-data bytes not yet consumed as a complete [len][pkt] frame.
    frames: FrameReassembler,
}

/// REALITY-based transport implementation for high-censorship regions.
/// SOP 04 §2: Composes WireGuard (boringtun) over flow-less VLESS UDP,
/// carried by a REALITY-authenticated TLS 1.3 session (see `reality_tls`).
pub struct RealityTransport {
    config: RealityConfig,
    tunnel: Arc<Mutex<Option<Tunnel>>>,
    tunn: Arc<Mutex<Tunn>>,
}

impl std::fmt::Debug for RealityTransport {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RealityTransport").field("config", &self.config).finish()
    }
}

impl RealityTransport {
    /// Creates a new `RealityTransport` instance.
    ///
    /// `static_private`/`server_static_public` are the client's WireGuard
    /// static key and the peer (server) WireGuard public key for boringtun.
    pub fn new(
        config: RealityConfig,
        static_private: [u8; 32],
        server_static_public: [u8; 32],
    ) -> Self {
        let tunn =
            Tunn::new(static_private.into(), server_static_public.into(), None, None, 0, None);

        Self { config, tunnel: Arc::new(Mutex::new(None)), tunn: Arc::new(Mutex::new(tunn)) }
    }

    /// Establishes the REALITY TLS session + VLESS UDP channel if needed.
    async fn get_or_connect(
        &self,
    ) -> Result<tokio::sync::MutexGuard<'_, Option<Tunnel>>, ShadowMeshError> {
        let mut guard = self.tunnel.lock().await;
        if guard.is_none() {
            let server_addr = format!("{}:{}", self.config.server_ip, self.config.port);
            let addr: std::net::SocketAddr = server_addr
                .parse()
                .map_err(|_| ShadowMeshError::Other("Invalid server address".into()))?;

            // v6.9.5: Advanced Socket Protection (Pre-Handshake)
            let socket = Socket::new(Domain::for_address(addr), Type::STREAM, None)
                .map_err(|e| ShadowMeshError::IoError(format!("Socket creation failed: {}", e)))?;

            // Critical: Protect the raw socket FD BEFORE connecting.
            //
            // This used to log an error and continue. If `protect()` fails
            // the socket is not excluded from the VPN, so the REALITY
            // connection would route through the tunnel's own tun0 and either
            // loop infinitely or fail outright. That is a fail-open on a
            // failure mode where the correct behaviour is to fail closed and
            // let the user retry, so it now aborts instead of continuing.
            if !crate::protect_socket(socket.as_raw_fd()) {
                return Err(ShadowMeshError::IoError(format!(
                    "failed to protect socket {} from VPN",
                    socket.as_raw_fd()
                )));
            }

            socket.set_nonblocking(true).map_err(|e| {
                ShadowMeshError::IoError(format!("Failed to set non-blocking: {}", e))
            })?;

            // v6.9.5: Proper non-blocking connect handling
            match socket.connect(&addr.into()) {
                Ok(_) => {}
                Err(e) if e.raw_os_error() == Some(libc::EINPROGRESS) => {
                    // This is expected for non-blocking sockets
                }
                Err(e) => {
                    return Err(ShadowMeshError::IoError(format!("TCP connect failed: {}", e)));
                }
            }

            // Hand over to tokio for the async connection
            let std_stream: std::net::TcpStream = socket.into();
            let tcp = TcpStream::from_std(std_stream)
                .map_err(|e| ShadowMeshError::IoError(format!("TCP conversion failed: {}", e)))?;

            // Wait for the socket to be writable (connection established)
            tcp.writable()
                .await
                .map_err(|e| ShadowMeshError::IoError(format!("TCP connect timeout: {}", e)))?;

            // REALITY-authenticated TLS 1.3 (ClientHello session_id auth).
            let mut tls = RealityTlsStream::connect(
                tcp,
                &self.config.public_key,
                &self.config.short_id,
                &self.config.sni_target,
            )
            .await?;

            // v6.9.14: Flow-less VLESS UDP Handshake
            // Format: [Version 0][UUID 16b][Addon Len 0][Cmd 2 (UDP)][Port 2b][AddrType 1b][Addr 4b]
            let mut header = Vec::with_capacity(24);
            header.push(0x00); // Version 0
            let uuid =
                uuid::Uuid::parse_str(&self.config.uuid).unwrap_or_else(|_| uuid::Uuid::nil());
            header.extend_from_slice(uuid.as_bytes());
            header.push(0x00); // Addon length 0 (empty flow: required, Vision+UDP is rejected by Xray)
            header.push(0x02); // Command: UDP
            header.put_u16(51820); // Target Port (node-local WireGuard)
            header.push(0x01); // IPv4
            header.extend_from_slice(&[127, 0, 0, 1]); // Target Address: 127.0.0.1

            tls.write_app(&header).await.map_err(|e| {
                error!("⚠️ VLESS request send failed: {}", e);
                e
            })?;

            // Response header [Version 1b][AddonLen 1b] arrives as app data.
            let mut tunnel =
                Tunnel { tls, frames: FrameReassembler::with_capacity(ENCAPSULATION_BUFFER) };
            fill_frame_bytes(&mut tunnel, wg_frame::FRAME_HEADER_LEN).await?;
            let resp = tunnel.frames.take_bytes(wg_frame::FRAME_HEADER_LEN).ok_or_else(|| {
                ShadowMeshError::Other("REALITY: no VLESS response header".into())
            })?;
            if resp[0] != 0x00 {
                return Err(ShadowMeshError::Other(format!(
                    "VLESS: unexpected response version {:#04x} (not a REALITY VLESS endpoint)",
                    resp[0]
                )));
            }
            if resp[1] > 0 {
                fill_frame_bytes(&mut tunnel, wg_frame::FRAME_HEADER_LEN + resp[1] as usize)
                    .await?;
                let _addon = tunnel.frames.take_bytes(resp[1] as usize);
            }

            info!("✅ REALITY tunnel established (VLESS UDP → 127.0.0.1:51820)");
            *guard = Some(tunnel);
        }
        Ok(guard)
    }

    /// Reads the next complete VLESS UDP frame and decapsulates it through
    /// boringtun. Returns the inner IP packet, or empty if the frame was a
    /// WG control packet (handshake/cookie) that was answered inline.
    async fn read_packet(&self) -> Result<Bytes, ShadowMeshError> {
        let mut guard = self.get_or_connect().await?;
        let tunnel = match guard.as_mut() {
            Some(t) => t,
            None => return Ok(Bytes::new()),
        };

        fill_frame_bytes(tunnel, wg_frame::FRAME_HEADER_LEN).await?;
        let len = tunnel
            .frames
            .head_len()
            .ok_or_else(|| ShadowMeshError::Other("REALITY: no frame header".into()))?
            as usize;
        if len > MAX_PACKET {
            error!("⚠️ RealityTransport: Packet too large ({} bytes). Stream out of sync.", len);
            *guard = None;
            return Err(ShadowMeshError::Other(format!("Packet too large: {len}")));
        }
        if len == 0 {
            tunnel.frames.take_header();
            return Ok(Bytes::new());
        }
        fill_frame_bytes(tunnel, wg_frame::FRAME_HEADER_LEN + len).await?;
        let payload = tunnel
            .frames
            .take_frame(len)
            .ok_or_else(|| ShadowMeshError::Other("REALITY: incomplete frame".into()))?;

        // F89: `receive_packet_data` panics when the buffer is smaller than the
        // datagram's ciphertext, and it checks that before authenticating, so
        // the peer reaches the panic from any established session. The
        // MAX_PACKET frame bound above is derived from ENCAPSULATION_BUFFER and
        // therefore already implies this; the guard is the second owner that
        // keeps the two from drifting apart again.
        let mut ip_buf = vec![0u8; ENCAPSULATION_BUFFER];
        wg_buffer::check_decapsulable(payload.len(), ip_buf.len())?;
        let mut tunn = self.tunn.lock().await;
        match tunn.decapsulate(None, &payload, &mut ip_buf) {
            TunnResult::WriteToTunnelV4(packet, _) | TunnResult::WriteToTunnelV6(packet, _) => {
                return Ok(Bytes::copy_from_slice(packet));
            }
            TunnResult::WriteToNetwork(packet) => {
                // WireGuard control reply (e.g. re-handshake under load): send inline.
                let frame = wg_frame::encode_frame(packet)?;
                tunnel.tls.write_app(&frame).await?;
            }
            _ => {}
        }
        Ok(Bytes::new())
    }
}

/// Reads from the TLS stream until at least `n` bytes are buffered for
/// framing. Cancel-safe: `read_app` keeps partial TCP reads buffered, and the
/// returned payload is appended to the reassembly buffer immediately after
/// each await.
///
/// ⚠️ **The read is this transport's; the framing is not.** The `[len][pkt]`
/// codec lives in [`super::wg_frame`], and this loop only says *where the
/// bytes come from*.
async fn fill_frame_bytes(tunnel: &mut Tunnel, n: usize) -> Result<(), ShadowMeshError> {
    while tunnel.frames.buffered() < n {
        match tunnel.tls.read_app().await? {
            Some(data) => tunnel.frames.push(&data),
            None => return Err(ShadowMeshError::Other("REALITY: tunnel closed by server".into())),
        }
    }
    Ok(())
}

#[async_trait]
impl AsyncTransport for RealityTransport {
    fn transport_type(&self) -> TransportType {
        TransportType::Reality
    }

    async fn connect(&self) -> Result<(), ShadowMeshError> {
        let _ = self.get_or_connect().await?;
        Ok(())
    }

    async fn send(&self, data: Bytes) -> Result<(), ShadowMeshError> {
        let mut guard = self.get_or_connect().await?;

        let mut buf = vec![0u8; ENCAPSULATION_BUFFER];
        // F89: `format_packet_data` panics (and `panic = "abort"` makes that a
        // process abort) when the packet plus its 32-byte WireGuard overhead
        // does not fit. `data` came from the TUN device, whose MTU is the
        // operator/control-plane `config.mtu`, so this is reachable from
        // configuration. Refuse rather than clamp.
        wg_buffer::check_encapsulable(data.len(), buf.len())?;
        let wg_packet = {
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

        if let Some(packet) = wg_packet {
            let tunnel = guard.as_mut().ok_or_else(|| {
                ShadowMeshError::Other("REALITY: tunnel disappeared during send".into())
            })?;
            // v6.9.14: Standard VLESS UDP Framing [u16 BE length][payload]
            let frame = wg_frame::encode_frame(&packet)?;

            if let Err(e) = tunnel.tls.write_app(&frame).await {
                *guard = None;
                return Err(ShadowMeshError::IoError(e.to_string()));
            }
        }
        Ok(())
    }

    async fn recv(&self) -> Result<Bytes, ShadowMeshError> {
        let mut interval = tokio::time::interval(std::time::Duration::from_secs(1));

        loop {
            tokio::select! {
                _ = interval.tick() => {
                    // SOP 04 §2: Trigger Tunn::update_timers() every second.
                    // Scope the tunn lock so it is released before acquiring
                    // the tunnel lock (avoids lock-order inversion with send).
                    let wg_packet = {
                        let mut tunn = self.tunn.lock().await;
                        let mut buf = vec![0u8; ENCAPSULATION_BUFFER];
                        match tunn.update_timers(&mut buf) {
                            TunnResult::WriteToNetwork(packet) => Some(packet.to_vec()),
                            _ => None,
                        }
                    };
                    if let Some(packet) = wg_packet {
                        let mut guard = self.get_or_connect().await?;
                        if let Some(tunnel) = guard.as_mut() {
                            let frame = wg_frame::encode_frame(&packet)?;
                            let _ = tunnel.tls.write_app(&frame).await;
                        }
                    }
                }
                res = self.read_packet() => {
                    match res {
                        Ok(packet) => {
                            if !packet.is_empty() {
                                return Ok(packet);
                            }
                        }
                        Err(e) => {
                            warn!("REALITY recv error: {} — resetting tunnel", e);
                            let mut guard = self.tunnel.lock().await;
                            *guard = None;
                            return Err(e);
                        }
                    }
                }
            }
        }
    }

    async fn close(&self) -> Result<(), ShadowMeshError> {
        let mut guard = self.tunnel.lock().await;
        if let Some(mut tunnel) = guard.take() {
            tunnel.tls.close().await;
        }
        Ok(())
    }
}
