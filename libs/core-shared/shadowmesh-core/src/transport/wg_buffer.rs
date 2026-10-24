//! Single owner of the WireGuard encapsulation-buffer invariant (F89).
//!
//! Implementation Source:
//! - Specification: the WireGuard Protocol whitepaper (Jason A. Donenfeld) for
//!   the transport-data packet shape these constants bound, and boringtun
//!   0.7.1's public, BSD-licensed `Tunn` API as the implementation surface the
//!   buffer invariants wrap — the vendor panics at `noise/session.rs:197-198`
//!   and `noise/session.rs:242-245` (tabled below) are what every caller is
//!   kept clear of. The consolidation itself is the F89 register row's ruling
//!   of one owner for the encapsulation-buffer invariant.
//!   No GPL/AGPL proxy source is consulted or ingested.
//!
//! # Why this module exists
//!
//! boringtun 0.7.1 does not return an error when the caller's destination
//! buffer is too small. It **panics**, in two places:
//!
//! | vendor site | guard | reached by |
//! |---|---|---|
//! | `noise/session.rs:197-198` `format_packet_data` | `dst.len() < src.len() + 32` | `Tunn::encapsulate` (send) |
//! | `noise/session.rs:242-245` `receive_packet_data` | `dst.len() < ct_len` | `Tunn::decapsulate` (receive) |
//!
//! The upstream comment calls this "a very incorrect use of the library".
//! It is not: this crate has always called it correctly, because every
//! transport allocates one fixed `vec![0u8; 2048]`. The *input* was assumed
//! to be small. With `panic = "abort"` in the release profile
//! (workspace `Cargo.toml`), either panic is a **process abort**, not a
//! recoverable error.
//!
//! That assumption held only because nothing bounded the two sizes against
//! each other:
//!
//! * **Send.** `VPNConfig::mtu` has no upper bound and is written straight to
//!   the TUN device (`apps/daemon/src/native_engine.rs:67`). The kernel then
//!   hands the engine packets up to that MTU, while the TUN read buffer caps
//!   a single read at `TUN_READ_BUFFER` (2048, `transport/engine.rs:35`).
//!   So `mtu > 2016` makes an ordinary large packet a process abort.
//! * **Receive.** `ct_len` is `datagram.len() - 16`, taken from the wire
//!   (`noise/mod.rs:159`). The transports framed a length prefix and then
//!   bounded it by an independent `MAX_PACKET = 4096` constant — which is
//!   **2032 bytes larger than the buffer it was protecting**. A peer with a
//!   completed session needed only one frame in `(2064, 4096]` to abort.
//!
//! # The rule
//!
//! Every call into `Tunn::encapsulate` / `Tunn::decapsulate` /
//! `Tunn::update_timers` in this crate must size its buffer from
//! [`ENCAPSULATION_BUFFER`] and must run the matching `check_*` guard on the
//! length it is about to hand over. The constants below are the only place
//! they are defined; a transport that re-derives 2048 or 4096 by hand has
//! reintroduced the bug.
//!
//! # Why reject rather than clamp
//!
//! A guard that silently clamps an operator's MTU to 2016 makes the symptom
//! disappear while running a configuration nobody asked for (F68). These
//! guards **refuse**: they return an error naming the offending value, so the
//! operator or control plane is told the truth and the value in effect is
//! always the value that was configured.

use crate::ShadowMeshError;

/// `boringtun::noise::mod::DATA_OVERHEAD_SZ` — the 16-byte data-message
/// header plus the 16-byte Poly1305 tag `format_packet_data` accounts for.
///
/// Mirror of `noise/mod.rs:85`; changing one without the other reintroduces
/// a silent 16-byte discrepancy.
pub const WG_DATA_OVERHEAD: usize = 32;

/// `boringtun::noise::mod::Packet::parse_incoming_packet` consumes this many
/// bytes of header before `receive_packet_data` sees the ciphertext, so
/// `ct_len == datagram.len() - WG_RECV_HEADER_SZ`.
pub const WG_RECV_HEADER_SZ: usize = 16;

/// The fixed buffer every transport allocates for encapsulate/decapsulate.
pub const ENCAPSULATION_BUFFER: usize = 2048;

/// Largest IP packet that can be encapsulated into [`ENCAPSULATION_BUFFER`].
/// `MAX + WG_DATA_OVERHEAD == ENCAPSULATION_BUFFER` — `2016`, not `2017`.
/// 2017 is the first size that panics.
pub const MAX_WG_PAYLOAD: usize = ENCAPSULATION_BUFFER - WG_DATA_OVERHEAD;

/// Largest wire datagram that can be decapsulated into [`ENCAPSULATION_BUFFER`].
/// `MAX - WG_RECV_HEADER_SZ == ENCAPSULATION_BUFFER` — `2064`, not `2065`.
pub const MAX_WG_DATAGRAM: usize = ENCAPSULATION_BUFFER + WG_RECV_HEADER_SZ;

/// Largest framed length a VLESS-style transport may accept from the wire.
///
/// This replaces the old independent `MAX_PACKET = 4096`. That constant was
/// not wrong about framing, only about which buffer it protected: it admitted
/// frames 2032 bytes larger than `ip_buf`, which is exactly the receive-side
/// abort. The value is now derived from the buffer, so the two cannot drift.
pub const MAX_PACKET: usize = MAX_WG_DATAGRAM;

/// MTU substituted for [`UNSET_MTU`]. Matches the value the daemon already
/// treats as the default (`apps/daemon/src/native_engine.rs:64`).
pub const DEFAULT_MTU: u32 = 1420;

/// Sentinel meaning "not configured". Already in use by the daemon; it is not
/// a legal MTU, so it is resolved here rather than rejected.
pub const UNSET_MTU: u32 = 0;

/// Smallest MTU this product itself defines (`get_quantum_mtu`, lib.rs:264).
/// Below this the Quantum tunnel profile cannot be represented.
pub const MIN_MTU: u32 = 576;

/// Largest MTU a TUN device may be given before the 2048-byte encapsulation
/// buffer can no longer hold the packet plus its WireGuard overhead.
pub const MAX_MTU: u32 = MAX_WG_PAYLOAD as u32;

/// Resolves and range-checks a configured MTU.
///
/// Returns the effective MTU. `mtu == UNSET_MTU` resolves to
/// [`DEFAULT_MTU`]; every other out-of-range value is **rejected**, never
/// adjusted. Called at config load, before any packet is sent.
pub fn resolve_mtu(mtu: u32) -> Result<u32, ShadowMeshError> {
    let mtu = if mtu == UNSET_MTU { DEFAULT_MTU } else { mtu };
    if !(MIN_MTU..=MAX_MTU).contains(&mtu) {
        return Err(ShadowMeshError::Other(format!(
            "mtu {mtu} is outside the supported range {MIN_MTU}..={MAX_MTU}; \
             the WireGuard encapsulation buffer is {ENCAPSULATION_BUFFER} bytes \
             and cannot hold a {MAX_MTU}-byte packet plus {WG_DATA_OVERHEAD} bytes of overhead"
        )));
    }
    Ok(mtu)
}

/// Guards `Tunn::encapsulate` / `Tunn::update_timers`.
///
/// `format_packet_data` panics when `buffer_len < packet_len + WG_DATA_OVERHEAD`.
#[inline]
pub fn check_encapsulable(packet_len: usize, buffer_len: usize) -> Result<(), ShadowMeshError> {
    if packet_len + WG_DATA_OVERHEAD > buffer_len {
        return Err(ShadowMeshError::Other(format!(
            "packet of {packet_len} bytes exceeds the {buffer_len}-byte encapsulation buffer \
             (limit {} bytes); refusing to hand it to WireGuard, which aborts the process",
            buffer_len.saturating_sub(WG_DATA_OVERHEAD)
        )));
    }
    Ok(())
}

/// Guards `Tunn::decapsulate`.
///
/// `receive_packet_data` panics when `buffer_len < ct_len`, and `ct_len` is
/// `datagram_len - WG_RECV_HEADER_SZ`, taken from the wire. It checks this
/// *before* the receiver-index check and before AEAD authentication, so an
/// oversized datagram reaches the panic from any peer holding a session.
#[inline]
pub fn check_decapsulable(datagram_len: usize, buffer_len: usize) -> Result<(), ShadowMeshError> {
    if datagram_len.saturating_sub(WG_RECV_HEADER_SZ) > buffer_len {
        return Err(ShadowMeshError::Other(format!(
            "peer datagram of {datagram_len} bytes exceeds the {buffer_len}-byte decapsulation \
             buffer; refusing to hand it to WireGuard, which aborts the process"
        )));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The constants must agree with boringtun. If the vendor ever changes
    /// DATA_OVERHEAD_SZ these drift and the guards stop guarding.
    #[test]
    fn max_payload_is_exactly_one_byte_below_the_first_panicking_size() {
        // format_packet_data panics when buffer < len + 32. The derived
        // payload must exactly fill the buffer minus the vendor overhead, or
        // the encapsulation guard's boundary has drifted from the vendor's.
        assert_eq!(
            MAX_WG_PAYLOAD + WG_DATA_OVERHEAD,
            ENCAPSULATION_BUFFER,
            "payload + vendor overhead must equal the encapsulation buffer, byte for byte"
        );
        // 2016 is the last safe size; 2017 is the first that panics.
        assert!(check_encapsulable(MAX_WG_PAYLOAD, ENCAPSULATION_BUFFER).is_ok());
        assert!(check_encapsulable(MAX_WG_PAYLOAD + 1, ENCAPSULATION_BUFFER).is_err());
    }

    #[test]
    fn max_datagram_is_exactly_one_byte_below_the_first_panicking_size() {
        // receive_packet_data panics when buffer < datagram - 16. Same pin
        // as above, on the receive side.
        assert_eq!(
            MAX_WG_DATAGRAM - WG_RECV_HEADER_SZ,
            ENCAPSULATION_BUFFER,
            "wire datagram minus the receive header must equal the encapsulation buffer"
        );
        assert!(check_decapsulable(MAX_WG_DATAGRAM, ENCAPSULATION_BUFFER).is_ok());
        assert!(check_decapsulable(MAX_WG_DATAGRAM + 1, ENCAPSULATION_BUFFER).is_err());
    }

    /// The old constant admitted frames 2032 bytes past the buffer. Pin that
    /// the derived one cannot.
    #[test]
    fn max_packet_never_exceeds_the_buffer_it_protects() {
        assert_eq!(
            MAX_PACKET, MAX_WG_DATAGRAM,
            "the framed ceiling must remain byte-identical to the wire datagram ceiling"
        );
    }

    #[test]
    fn mtu_accepts_the_values_this_product_actually_uses() {
        assert_eq!(resolve_mtu(0).unwrap(), DEFAULT_MTU);
        assert_eq!(resolve_mtu(576).unwrap(), 576); // get_quantum_mtu
        assert_eq!(resolve_mtu(1280).unwrap(), 1280);
        assert_eq!(resolve_mtu(1420).unwrap(), 1420); // the default
        assert_eq!(resolve_mtu(MAX_MTU).unwrap(), MAX_MTU);
    }

    #[test]
    fn mtu_rejects_out_of_range_and_never_silently_clamps() {
        for bad in [1u32, 575, MAX_MTU + 1, 9000, 65535, u32::MAX] {
            let err = resolve_mtu(bad).unwrap_err();
            let msg = err.to_string();
            // The rejected value must be reported, not quietly replaced.
            assert!(msg.contains(&bad.to_string()), "message {msg:?} omits {bad}");
        }
    }
}
