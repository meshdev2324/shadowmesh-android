//! Implementation Source:
//! - Specification: Shadowsocks AEAD (SIP007) for the encrypted stream and
//!   address framing; WireGuard (official whitepaper) for the encapsulated
//!   tunnel.
//! - Crypto: `boringtun` and `ring` (MIT/ISC) primitives only.
//! - No GPL/AGPL proxy source is consulted or ingested.

use super::wg_buffer::{self, ENCAPSULATION_BUFFER, MAX_PACKET as MAX_PACKET_BUF};
/// The `[len][pkt]` framing codec — the single owner of the length prefix
/// (RFC-046 §3.2). This transport does not re-derive it.
use super::wg_frame::{self, FrameReassembler};
use super::{AsyncTransport, TransportType};
use crate::protocol::shadowsocks::{ShadowsocksMethod, ShadowsocksStream};
use crate::{ShadowMeshError, ShadowsocksConfig};
use async_trait::async_trait;
use boringtun::noise::{Tunn, TunnResult};
use bytes::Bytes;
use socket2::{Domain, Socket, Type};
use std::os::unix::io::AsRawFd;
use std::str::FromStr;
use std::sync::Arc;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::Mutex;
use tracing::{error, info};

/// Upper sanity bound for one WG packet.
///
/// F89: this used to be an independent `4096`, which admitted frames 2032
/// bytes larger than the `ip_buf` they were written into. It is now derived
/// from the buffer it protects, in `super::wg_buffer`.
const MAX_PACKET: usize = MAX_PACKET_BUF;

/// Established Shadowsocks tunnel.
struct Tunnel {
    stream: ShadowsocksStream<TcpStream>,
    frames: FrameReassembler,
}

/// Shadowsocks-based transport implementation (WireGuard-over-SS-TCP).
pub struct ShadowsocksTransport {
    config: ShadowsocksConfig,
    tunnel: Arc<Mutex<Option<Tunnel>>>,
    tunn: Arc<Mutex<Tunn>>,
}

impl std::fmt::Debug for ShadowsocksTransport {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ShadowsocksTransport").field("server", &self.config.server).finish()
    }
}

impl ShadowsocksTransport {
    pub fn new(
        config: ShadowsocksConfig,
        static_private: [u8; 32],
        server_static_public: [u8; 32],
    ) -> Self {
        let tunn =
            Tunn::new(static_private.into(), server_static_public.into(), None, None, 0, None);

        Self { config, tunnel: Arc::new(Mutex::new(None)), tunn: Arc::new(Mutex::new(tunn)) }
    }

    async fn get_or_connect(
        &self,
    ) -> Result<tokio::sync::MutexGuard<'_, Option<Tunnel>>, ShadowMeshError> {
        let mut guard = self.tunnel.lock().await;
        if guard.is_none() {
            let server_addr = format!("{}:{}", self.config.server, self.config.port);
            let addr: std::net::SocketAddr = server_addr
                .parse()
                .map_err(|_| ShadowMeshError::Other("Invalid server address".into()))?;

            let socket = Socket::new(Domain::for_address(addr), Type::STREAM, None)
                .map_err(|e| ShadowMeshError::IoError(format!("Socket creation failed: {}", e)))?;

            if !crate::protect_socket(socket.as_raw_fd()) {
                error!("⚠️ FAILED TO PROTECT SOCKET FD: {}", socket.as_raw_fd());
            }

            socket.set_nonblocking(true).map_err(|e| {
                ShadowMeshError::IoError(format!("Failed to set non-blocking: {}", e))
            })?;

            match socket.connect(&addr.into()) {
                Ok(_) => {}
                Err(e) if e.raw_os_error() == Some(libc::EINPROGRESS) => {}
                Err(e) => {
                    return Err(ShadowMeshError::IoError(format!("TCP connect failed: {}", e)))
                }
            }

            let std_stream: std::net::TcpStream = socket.into();
            let tcp = TcpStream::from_std(std_stream)
                .map_err(|e| ShadowMeshError::IoError(format!("TCP conversion failed: {}", e)))?;

            tcp.writable()
                .await
                .map_err(|e| ShadowMeshError::IoError(format!("TCP connect timeout: {}", e)))?;

            let method = ShadowsocksMethod::from_str(&self.config.method)
                .map_err(|e| ShadowMeshError::Other(e.to_string()))?;

            let mut ss_stream = ShadowsocksStream::new(tcp, method, &self.config.password);

            // Send target address: 127.0.0.1:51820 (internal WG)
            let mut addr_buf = vec![0u8; 7];
            addr_buf[0] = 1; // IPv4
            addr_buf[1..5].copy_from_slice(&[127, 0, 0, 1]);
            addr_buf[5..7].copy_from_slice(&51820u16.to_be_bytes());

            ss_stream
                .write_all(&addr_buf)
                .await
                .map_err(|e| ShadowMeshError::IoError(e.to_string()))?;

            info!("✅ Shadowsocks tunnel established (WG-over-TCP)");
            *guard = Some(Tunnel {
                stream: ss_stream,
                frames: FrameReassembler::with_capacity(ENCAPSULATION_BUFFER),
            });
        }
        Ok(guard)
    }

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
            .ok_or_else(|| ShadowMeshError::Other("Shadowsocks: no frame header".into()))?
            as usize;
        if len > MAX_PACKET {
            *guard = None;
            return Err(ShadowMeshError::Other(format!("Packet too large: {len}")));
        }

        fill_frame_bytes(tunnel, wg_frame::FRAME_HEADER_LEN + len).await?;
        let payload = tunnel
            .frames
            .take_frame(len)
            .ok_or_else(|| ShadowMeshError::Other("Shadowsocks: incomplete frame".into()))?;

        let mut tunn = self.tunn.lock().await;
        let mut ip_buf = vec![0u8; ENCAPSULATION_BUFFER];
        // F89: `receive_packet_data` panics when the buffer is smaller than the
        // datagram's ciphertext, before authenticating. Refuse the frame
        // rather than hand the process to a panic.
        wg_buffer::check_decapsulable(payload.len(), ip_buf.len())?;
        match tunn.decapsulate(None, &payload, &mut ip_buf) {
            TunnResult::WriteToTunnelV4(packet, _) | TunnResult::WriteToTunnelV6(packet, _) => {
                return Ok(Bytes::copy_from_slice(packet));
            }
            TunnResult::WriteToNetwork(packet) => {
                let frame = wg_frame::encode_frame(packet)?;
                tunnel
                    .stream
                    .write_all(&frame)
                    .await
                    .map_err(|e| ShadowMeshError::IoError(e.to_string()))?;
            }
            _ => {}
        }
        Ok(Bytes::new())
    }
}

/// Reads from the Shadowsocks stream until at least `n` bytes are buffered
/// for framing.
///
/// ⚠️ **The read is this transport's; the framing is not.** The `[len][pkt]`
/// codec lives in [`super::wg_frame`], and this loop only says *where the
/// bytes come from*.
async fn fill_frame_bytes(tunnel: &mut Tunnel, n: usize) -> Result<(), ShadowMeshError> {
    while tunnel.frames.buffered() < n {
        let mut buf = [0u8; ENCAPSULATION_BUFFER];
        let read_n = tunnel
            .stream
            .read(&mut buf)
            .await
            .map_err(|e| ShadowMeshError::IoError(e.to_string()))?;
        if read_n == 0 {
            return Err(ShadowMeshError::Other("Shadowsocks: tunnel closed by server".into()));
        }
        tunnel.frames.push(&buf[..read_n]);
    }
    Ok(())
}

#[async_trait]
impl AsyncTransport for ShadowsocksTransport {
    fn transport_type(&self) -> TransportType {
        TransportType::Shadowsocks
    }

    async fn connect(&self) -> Result<(), ShadowMeshError> {
        let _ = self.get_or_connect().await?;
        Ok(())
    }

    async fn send(&self, data: Bytes) -> Result<(), ShadowMeshError> {
        let mut guard = self.get_or_connect().await?;
        let mut buf = vec![0u8; ENCAPSULATION_BUFFER];
        // F89: `format_packet_data` panics when the packet plus its 32-byte
        // WireGuard overhead does not fit, and `panic = "abort"` turns that
        // into a process abort. `data` came from a TUN device sized by the
        // unbounded `config.mtu`. Refuse, never clamp.
        wg_buffer::check_encapsulable(data.len(), buf.len())?;
        let wg_packet = {
            let mut tunn = self.tunn.lock().await;
            match tunn.encapsulate(&data, &mut buf) {
                TunnResult::WriteToNetwork(packet) => Some(packet.to_vec()),
                _ => None,
            }
        };

        if let Some(packet) = wg_packet {
            let tunnel = guard.as_mut().ok_or_else(|| {
                ShadowMeshError::Other("Shadowsocks tunnel disappeared".to_string())
            })?;
            let frame = wg_frame::encode_frame(&packet)?;
            tunnel
                .stream
                .write_all(&frame)
                .await
                .map_err(|e| ShadowMeshError::IoError(e.to_string()))?;
        }
        Ok(())
    }

    async fn recv(&self) -> Result<Bytes, ShadowMeshError> {
        let mut interval = tokio::time::interval(std::time::Duration::from_secs(1));
        loop {
            tokio::select! {
                _ = interval.tick() => {
                    let mut buf = vec![0u8; ENCAPSULATION_BUFFER];
                    let wg_packet = {
                        let mut tunn = self.tunn.lock().await;
                        match tunn.update_timers(&mut buf) {
                            TunnResult::WriteToNetwork(packet) => Some(packet.to_vec()),
                            _ => None,
                        }
                    };
                    if let Some(packet) = wg_packet {
                        let mut guard = self.get_or_connect().await?;
                        if let Some(tunnel) = guard.as_mut() {
                            let frame = wg_frame::encode_frame(&packet)?;
                            let _ = tunnel.stream.write_all(&frame).await;
                        }
                    }
                }
                res = self.read_packet() => {
                    match res {
                        Ok(packet) if !packet.is_empty() => return Ok(packet),
                        Ok(_) => continue,
                        Err(e) => {
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
            let _ = tunnel.stream.shutdown().await;
        }
        Ok(())
    }
}
