//! Single owner of the VLESS-style `[len][pkt]` UDP framing codec.
//!
//! Implementation Source:
//! - Specification: RFC-046 §3.2 — the one approved modularization that
//!   ordered this extraction (quoted below) — and the public VLESS
//!   specification for the `[len][pkt]` frame format itself. No GPL/AGPL
//!   proxy source is consulted or ingested.
//!
//! # Why this module exists
//!
//! Three transports speak the **same** frame format over **three different
//! carriers**, and each of them carried its **own copy** of the codec:
//!
//! | Transport | Carrier | The copy that was there |
//! | :--- | :--- | :--- |
//! | `transport/reality.rs` | a REALITY TLS 1.3 app-data stream | `put_u16(len)` + `frame_buf` |
//! | `transport/shadowsocks.rs` | a Shadowsocks AEAD TCP stream | `put_u16(len)` + `frame_buf` |
//! | `transport/hysteria.rs` | a Hysteria 2 QUIC stream | `put_u16(len)` + `frame_buf` |
//!
//! That is **six** write sites for the length prefix and **three** read sites
//! for it — and the prefix is a **wire invariant**, so six write sites is six
//! chances to spell it differently. RFC-046 §3.2 named this as the **one**
//! approved modularization in the transport layer:
//!
//! > *"extract the **WG packet framing + reassembly byte-codec** (the
//! > `[len][pkt]` prefix, `frame_buf` fill/drain) into one module beside
//! > `wg_buffer`, consumed by both transports"*
//!
//! ⇒ **The prefix is written here, once, and read here, once.** A transport
//! that re-derives the framing by hand has reintroduced the duplication.
//!
//! # What is NOT here
//!
//! **The transports keep their own read loop.** Where the bytes come from —
//! a TLS app-data read, a TCP read, a QUIC read — is **I/O**, and it is
//! different in all three. What is *not* different is the framing, and that
//! is what this module owns.
//!
//! # The ceiling
//!
//! The largest frame a transport may accept is **not** decided here: it is
//! [`crate::transport::wg_buffer::MAX_PACKET`], which is derived from the
//! encapsulation buffer the frame is about to be handed to (F89). A second
//! ceiling here would be a second place for the two to drift — and F89's
//! whole finding was that they had.
//!
//! # The spec
//!
//! VLESS's UDP framing, as documented by the public VLESS specification:
//! a **big-endian `u16` length** followed by that many payload bytes. The
//! payload of a WireGuard-over-VLESS frame is one WireGuard data packet
//! (boringtun, MIT) — this module never interprets it.

use crate::ShadowMeshError;
use bytes::{Buf, Bytes, BytesMut};

/// The size of a frame's length prefix: a **big-endian `u16`**.
pub const FRAME_HEADER_LEN: usize = 2;

/// Encodes one `[len][pkt]` frame.
///
/// The length prefix is a `u16`, so a payload of more than 65535 bytes
/// **cannot be framed**. This **refuses** that payload rather than
/// truncating its length: a truncated prefix is a frame the peer will
/// mis-parse, and a silent truncation is the same defect class as the
/// silently-clamped MTU F89 refused.
///
/// The transports never reach that state — [`crate::transport::wg_buffer`]
/// bounds the packet to the encapsulation buffer first — so the refusal is a
/// floor, not a path.
pub fn encode_frame(payload: &[u8]) -> Result<Bytes, ShadowMeshError> {
    let len = u16::try_from(payload.len()).map_err(|_| {
        ShadowMeshError::Other(format!(
            "a frame payload of {} bytes does not fit the u16 length prefix",
            payload.len()
        ))
    })?;
    let mut frame = BytesMut::with_capacity(FRAME_HEADER_LEN + payload.len());
    frame.extend_from_slice(&len.to_be_bytes());
    frame.extend_from_slice(payload);
    Ok(frame.freeze())
}

/// The reassembly buffer for `[len][pkt]` frames.
///
/// It owns the bytes that have arrived but are not yet a whole frame. The
/// transports **fill** it (`push`) and **drain** it (`take_frame`,
/// `take_header`, `take_bytes`); nothing else may index into it.
#[derive(Debug)]
pub struct FrameReassembler {
    buf: BytesMut,
}

impl FrameReassembler {
    /// A buffer with room for one maximum-size frame.
    ///
    /// The capacity is a **hint**: the buffer grows if a frame is larger, and
    /// it is never a limit. `wg_buffer::ENCAPSULATION_BUFFER` is the natural
    /// value — one maximum frame plus its prefix.
    pub fn with_capacity(capacity: usize) -> Self {
        Self { buf: BytesMut::with_capacity(capacity) }
    }

    /// How many bytes are buffered and not yet consumed as a whole frame.
    pub fn buffered(&self) -> usize {
        self.buf.len()
    }

    /// Appends bytes read from the wire.
    pub fn push(&mut self, data: &[u8]) {
        self.buf.extend_from_slice(data);
    }

    /// The length prefix of the frame at the head of the buffer.
    ///
    /// `None` when fewer than [`FRAME_HEADER_LEN`] bytes are buffered — the
    /// caller has not read enough yet.
    pub fn head_len(&self) -> Option<u16> {
        if self.buf.len() < FRAME_HEADER_LEN {
            return None;
        }
        Some(u16::from_be_bytes([self.buf[0], self.buf[1]]))
    }

    /// Consumes the first `n` buffered bytes.
    ///
    /// Returns `None` — and consumes nothing — when fewer than `n` bytes are
    /// buffered. This is the **raw** drain, for a header that is not a
    /// `[len][pkt]` frame (the VLESS response header is `[version][addon
    /// length]`).
    pub fn take_bytes(&mut self, n: usize) -> Option<Bytes> {
        if self.buf.len() < n {
            return None;
        }
        Some(self.buf.split_to(n).freeze())
    }

    /// Consumes the length prefix alone — a frame of **zero** length.
    ///
    /// Returns `false`, and consumes nothing, when the prefix is not
    /// buffered.
    pub fn take_header(&mut self) -> bool {
        if self.buf.len() < FRAME_HEADER_LEN {
            return false;
        }
        self.buf.advance(FRAME_HEADER_LEN);
        true
    }

    /// Consumes one whole frame of `len` payload bytes and returns the
    /// payload.
    ///
    /// Returns `None` — and consumes nothing — when the frame is not fully
    /// buffered. The caller reads more bytes and tries again.
    pub fn take_frame(&mut self, len: usize) -> Option<Bytes> {
        if self.buf.len() < FRAME_HEADER_LEN + len {
            return None;
        }
        let mut frame = self.buf.split_to(FRAME_HEADER_LEN + len);
        frame.advance(FRAME_HEADER_LEN);
        Some(frame.freeze())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// **The prefix is a big-endian `u16`, byte for byte.**
    ///
    /// This is the wire invariant the module exists to own, and it is
    /// asserted against the bytes rather than against the encoder — a test
    /// that round-trips the encoder proves only that the encoder agrees with
    /// itself.
    #[test]
    fn the_prefix_is_a_big_endian_u16() {
        let frame = encode_frame(&[0xAA, 0xBB]).expect("a two-byte payload frames");
        assert_eq!(frame.len(), FRAME_HEADER_LEN + 2);
        assert_eq!(&frame[..FRAME_HEADER_LEN], &[0x00, 0x02], "the prefix is big-endian u16");
        assert_eq!(&frame[FRAME_HEADER_LEN..], &[0xAA, 0xBB]);
    }

    /// **A payload that does not fit the prefix is refused, not truncated.**
    ///
    /// A truncated length is a frame the peer mis-parses, and a silent
    /// truncation is the defect class F89 refused to accept.
    #[test]
    fn a_payload_too_long_for_the_prefix_is_refused() {
        let payload = vec![0u8; 70_000];
        let err = encode_frame(&payload).expect_err("a 70000-byte payload cannot be framed");
        assert!(
            err.to_string().contains("70000"),
            "the refusal must name the offending size: {err}"
        );
    }

    /// **The round trip, including a zero-length frame.**
    #[test]
    fn a_frame_round_trips_through_the_reassembler() {
        for payload in [vec![], vec![0x01], vec![0xAB; 2048]] {
            let frame = encode_frame(&payload).expect("the payload frames");
            let mut buffer = FrameReassembler::with_capacity(0);
            buffer.push(&frame);
            let len = buffer.head_len().expect("the prefix is buffered");
            assert_eq!(len as usize, payload.len());
            let taken = buffer.take_frame(len as usize).expect("the frame is whole");
            assert_eq!(&taken[..], &payload[..]);
            assert_eq!(buffer.buffered(), 0, "the frame is fully consumed");
        }
    }

    /// **A frame split across three reads is reassembled.**
    ///
    /// This is the case the transports actually hit: a TCP read returns
    /// whatever the kernel had, and the length prefix and the payload can
    /// arrive in different reads.
    #[test]
    fn a_frame_split_across_reads_is_reassembled() {
        let frame = encode_frame(&[0x11; 100]).expect("the payload frames");
        let mut buffer = FrameReassembler::with_capacity(0);

        // The first read holds only the first byte of the prefix.
        buffer.push(&frame[..1]);
        assert_eq!(buffer.head_len(), None, "one byte is not a prefix");
        assert!(buffer.take_frame(100).is_none(), "the frame is not whole yet");

        // The second read completes the prefix but not the payload.
        buffer.push(&frame[1..FRAME_HEADER_LEN + 10]);
        assert_eq!(buffer.head_len(), Some(100));
        assert!(buffer.take_frame(100).is_none(), "the payload is still short");

        // The third read completes it.
        buffer.push(&frame[FRAME_HEADER_LEN + 10..]);
        let taken = buffer.take_frame(100).expect("the frame is whole");
        assert_eq!(&taken[..], &[0x11; 100]);
        assert_eq!(buffer.buffered(), 0);
    }

    /// **A burst of frames in one read is drained one frame at a time.**
    ///
    /// The F65-style burst: a single read can carry several frames, and the
    /// reassembler must not lose or reorder them.
    #[test]
    fn a_burst_of_frames_in_one_read_is_drained_in_order() {
        let payloads: Vec<Vec<u8>> = (0..8u8).map(|i| vec![i; 16 + i as usize]).collect();
        let mut wire = Vec::new();
        for payload in &payloads {
            wire.extend_from_slice(&encode_frame(payload).expect("the payload frames"));
        }

        let mut buffer = FrameReassembler::with_capacity(0);
        buffer.push(&wire);
        for payload in &payloads {
            let len = buffer.head_len().expect("the prefix is buffered");
            assert_eq!(len as usize, payload.len());
            let taken = buffer.take_frame(len as usize).expect("the frame is whole");
            assert_eq!(&taken[..], &payload[..], "the frames must come back in order");
        }
        assert_eq!(buffer.buffered(), 0, "the burst is fully consumed");
    }

    /// **The drain refuses to over-read.**
    ///
    /// A `take_frame` for a frame that is not buffered must consume
    /// **nothing**: a partial drain would corrupt the next frame.
    #[test]
    fn a_drain_that_cannot_be_satisfied_consumes_nothing() {
        let frame = encode_frame(&[0x22; 32]).expect("the payload frames");
        let mut buffer = FrameReassembler::with_capacity(0);
        buffer.push(&frame[..FRAME_HEADER_LEN + 4]);

        assert!(buffer.take_frame(32).is_none(), "the frame is not whole");
        assert_eq!(buffer.buffered(), FRAME_HEADER_LEN + 4, "a refused drain must consume nothing");
        assert!(buffer.take_bytes(64).is_none(), "there are not that many bytes");
        assert_eq!(buffer.buffered(), FRAME_HEADER_LEN + 4);
    }
}
