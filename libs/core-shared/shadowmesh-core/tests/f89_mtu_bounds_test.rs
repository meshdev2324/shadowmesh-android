//! F89 -- operator-set MTU with no upper bound aborts the daemon; and a peer
//! can abort it with one frame.
//!
//! # What was wrong
//!
//! `VPNConfig::mtu` had no upper bound and is written straight to the TUN
//! device, so it sets the largest packet the kernel will hand the transport.
//! Every transport encapsulates into a fixed `vec![0u8; 2048]`, and
//! boringtun 0.7.1 **panics** rather than erroring when that buffer is too
//! small:
//!
//! * `noise/session.rs:197-198` `format_packet_data` -- `dst.len() < src.len() + 32`
//! * `noise/session.rs:242-245` `receive_packet_data` -- `dst.len() < ct_len`
//!
//! With `panic = "abort"` in the release profile, either is a process abort.
//!
//! Two independent routes reached them:
//!
//! 1. **Send.** `mtu > 2016` (`native_engine.rs:67` sizes the TUN device).
//!    Self-inflicted *and* control-plane reachable -- `get_config_async`
//!    deserializes `mtu` straight out of `GET /api/v1/config/:server_id`.
//! 2. **Receive.** `ct_len` is `datagram.len() - 16`, taken from the wire.
//!    The transports bounded the frame with an independent `MAX_PACKET = 4096`
//!    -- 2032 bytes *larger* than the 2048-byte `ip_buf` it was protecting.
//!    Hysteria had no bound at all. `receive_packet_data` length-checks
//!    before authenticating and before the receiver-index check, so any peer
//!    holding a session reaches the abort with a single frame.
//!
//! # What these tests hold
//!
//! The constants are pinned against the **real** vendor code, not against a
//! restatement of it, so a boringtun bump that changes the overhead fails
//! here instead of silently reopening the abort.

use boringtun::noise::{Tunn, TunnResult};
use shadowmesh_core::transport::wg_buffer::{
    self, ENCAPSULATION_BUFFER, MAX_MTU, MAX_PACKET, MAX_WG_DATAGRAM, MAX_WG_PAYLOAD, MIN_MTU,
    WG_RECV_HEADER_SZ,
};
use shadowmesh_core::VPNConfig;
use x25519_dalek::{PublicKey, StaticSecret};

// ---------------------------------------------------------------- helpers

fn linked_pair() -> (Tunn, Tunn) {
    let sk_a = StaticSecret::from([7u8; 32]);
    let sk_b = StaticSecret::from([9u8; 32]);
    let pk_a = PublicKey::from(&sk_a);
    let pk_b = PublicKey::from(&sk_b);
    let mut a = Tunn::new(sk_a, pk_b, None, None, 0x1111_1111, None);
    let mut b = Tunn::new(sk_b, pk_a, None, None, 0x2222_2221, None);

    let mut buf = vec![0u8; ENCAPSULATION_BUFFER];
    let init = match a.format_handshake_initiation(&mut buf, false) {
        TunnResult::WriteToNetwork(p) => p.to_vec(),
        o => panic!("handshake init: {o:?}"),
    };
    let mut buf = vec![0u8; ENCAPSULATION_BUFFER];
    let resp = match b.decapsulate(None, &init, &mut buf) {
        TunnResult::WriteToNetwork(p) => p.to_vec(),
        o => panic!("handshake resp: {o:?}"),
    };
    let mut buf = vec![0u8; ENCAPSULATION_BUFFER];
    let ka = match a.decapsulate(None, &resp, &mut buf) {
        TunnResult::WriteToNetwork(p) => p.to_vec(),
        o => panic!("handshake done: {o:?}"),
    };
    let mut buf = vec![0u8; ENCAPSULATION_BUFFER];
    match b.decapsulate(None, &ka, &mut buf) {
        TunnResult::Done => {}
        o => panic!("keepalive: {o:?}"),
    }
    (a, b)
}

/// The send-side call exactly as `transport/reality.rs` makes it.
fn encapsulate(packet_len: usize) -> Result<usize, String> {
    let outcome = std::panic::catch_unwind(|| {
        let (mut a, _b) = linked_pair();
        let payload = vec![0x41u8; packet_len];
        let mut buf = vec![0u8; ENCAPSULATION_BUFFER];
        match a.encapsulate(&payload, &mut buf) {
            TunnResult::WriteToNetwork(p) => Ok(p.len()),
            TunnResult::Err(e) => Err(format!("{e:?}")),
            _ => Err("unexpected variant".to_string()),
        }
    });
    match outcome {
        Ok(Ok(n)) => Ok(n),
        Ok(Err(e)) => Err(e),
        Err(_) => Err("PANIC".to_string()),
    }
}

/// The receive-side call exactly as `transport/reality.rs` makes it, fed a
/// peer-chosen datagram length.
fn decapsulate(datagram_len: usize) -> Result<String, String> {
    let outcome = std::panic::catch_unwind(|| {
        let (mut a, mut b) = linked_pair();
        let seed = vec![0x41u8; 64];
        let mut sbuf = vec![0u8; ENCAPSULATION_BUFFER];
        let mut pkt = match b.encapsulate(&seed, &mut sbuf) {
            TunnResult::WriteToNetwork(p) => p.to_vec(),
            o => panic!("seed data packet: {o:?}"),
        };
        // Inflate the ciphertext region. `receive_packet_data` measures
        // length before authenticating, so no valid AEAD is needed to reach
        // the abort -- which is exactly why this is remotely reachable.
        pkt.resize(datagram_len, 0);
        let mut ip_buf = vec![0u8; ENCAPSULATION_BUFFER];
        match a.decapsulate(None, &pkt, &mut ip_buf) {
            TunnResult::WriteToTunnelV4(p, _) | TunnResult::WriteToTunnelV6(p, _) => {
                Ok(format!("inner={}", p.len()))
            }
            TunnResult::Err(e) => Ok(format!("Err({e:?})")),
            TunnResult::Done => Ok("Done".to_string()),
            _ => Ok("other".to_string()),
        }
    });
    match outcome {
        Ok(Ok(s)) => Ok(s),
        Ok(Err(e)) => Err(e),
        Err(_) => Err("PANIC".to_string()),
    }
}

fn config_with_mtu(mtu: u32) -> VPNConfig {
    VPNConfig { mtu, ..VPNConfig::test_fixture() }
}

// ------------------------------------------------- the boundary, pinned

/// The last size `format_packet_data` accepts, and the first it does not.
///
/// This is the whole bug in two assertions. 2016 is the largest safe MTU;
/// 2017 -- the size L11's report described as "the currently-safe case" -- is
/// the first that aborts the process.
#[test]
fn send_boundary_is_exactly_2016() {
    assert_eq!(MAX_WG_PAYLOAD, 2016);
    assert_eq!(ENCAPSULATION_BUFFER, 2048);
    assert_eq!(MAX_WG_PAYLOAD + wg_buffer::WG_DATA_OVERHEAD, ENCAPSULATION_BUFFER);

    // The currently-reachable default still works, with its 596-byte margin.
    assert_eq!(encapsulate(1420).as_ref().ok().copied(), Some(1452));
    // The largest legal MTU round-trips through the real vendor code.
    assert_eq!(encapsulate(MAX_WG_PAYLOAD).as_ref().ok().copied(), Some(2048));
    // One byte more is the abort, proven rather than asserted.
    assert_eq!(encapsulate(MAX_WG_PAYLOAD + 1), Err("PANIC".to_string()));
    assert_eq!(encapsulate(ENCAPSULATION_BUFFER), Err("PANIC".to_string()));
}

/// The receive-side boundary, against the real vendor code.
#[test]
fn receive_boundary_is_exactly_2064() {
    assert_eq!(MAX_WG_DATAGRAM, 2064);
    assert_eq!(MAX_WG_DATAGRAM - wg_buffer::WG_RECV_HEADER_SZ, ENCAPSULATION_BUFFER);

    // At the limit the length check passes and the packet fails AEAD (our
    // padding is not a real ciphertext). The point is that it did NOT abort,
    // which is why this is Ok rather than Err("PANIC").
    let at_limit = decapsulate(MAX_WG_DATAGRAM);
    assert!(at_limit.is_ok(), "the last safe datagram must not abort: {at_limit:?}");
    assert_eq!(decapsulate(MAX_WG_DATAGRAM + 1), Err("PANIC".to_string()));
    // 4096 is what the old independent `MAX_PACKET` admitted.
    assert_eq!(decapsulate(4096), Err("PANIC".to_string()));
}

// ------------------------------------------------------- the fix, both ways

/// DIRECTION 1 -- the guard rejects a too-large MTU.
#[test]
fn rejects_out_of_range_mtu() {
    for bad in [MAX_MTU + 1, 9000, 65535, u32::MAX] {
        let err = config_with_mtu(bad).validate().unwrap_err();
        let msg = err.to_string();
        assert!(msg.contains(&bad.to_string()), "error {msg:?} must name the rejected value");
        // Rejection, not substitution: the configured value is still the bad
        // one and the config is not silently usable.
        assert!(config_with_mtu(bad).effective_mtu().is_err());
    }
}

/// DIRECTION 2 -- the guard accepts every value this product actually ships.
#[test]
fn accepts_valid_mtu() {
    // get_quantum_mtu, the api_sync fixture, the default, and the maximum.
    for good in [MIN_MTU, 1280, 1420, MAX_MTU] {
        assert!(config_with_mtu(good).validate().is_ok());
        assert_eq!(config_with_mtu(good).effective_mtu().unwrap(), good);
    }
    // The "unset" sentinel the daemon honours still resolves.
    assert_eq!(config_with_mtu(0).effective_mtu().unwrap(), 1420);
}

/// The guards must refuse rather than clamp -- the F68 shape.
///
/// A clamp here would run an MTU the operator never asked for while making the
/// abort disappear. So an out-of-range value must produce an error that names
/// the value, and must never come back as a different, working number.
#[test]
fn refuses_rather_than_clamps() {
    let err = config_with_mtu(9000).effective_mtu().unwrap_err();
    assert!(err.to_string().contains("9000"));
    assert!(err.to_string().contains(&MAX_MTU.to_string()));
    // Below range too.
    assert!(config_with_mtu(1).validate().is_err());
}

/// `parse_wireguard_config` is a load path: an operator-edited file must be
/// rejected at parse time, not at first packet.
#[test]
fn wireguard_config_file_mtu_is_validated_at_parse() {
    let key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    let ok = format!(
        "[Interface]\nPrivateKey = {key}\nAddress = 10.0.0.2/24\nMTU = 1420\n\
         [Peer]\nPublicKey = {key}\nEndpoint = 1.2.3.4:51820\n"
    );
    assert_eq!(shadowmesh_core::parse_wireguard_config(ok).unwrap().mtu, 1420);

    let bad = format!(
        "[Interface]\nPrivateKey = {key}\nAddress = 10.0.0.2/24\nMTU = 9000\n\
         [Peer]\nPublicKey = {key}\nEndpoint = 1.2.3.4:51820\n"
    );
    let err = shadowmesh_core::parse_wireguard_config(bad).unwrap_err();
    assert!(err.to_string().contains("9000"));
}

/// The remote route: a peer-chosen frame is refused by the guard, not handed
/// to a function that aborts.
#[test]
fn oversized_peer_frame_is_refused_not_handed_to_boringtun() {
    assert!(wg_buffer::check_decapsulable(MAX_WG_DATAGRAM, ENCAPSULATION_BUFFER).is_ok());
    assert!(wg_buffer::check_decapsulable(MAX_WG_DATAGRAM + 1, ENCAPSULATION_BUFFER).is_err());
    assert!(wg_buffer::check_decapsulable(u16::MAX as usize, ENCAPSULATION_BUFFER).is_err());
}

/// The old `MAX_PACKET` constant was wrong about the buffer it protected.
/// Pin that it can never be set past it again.
#[test]
fn max_packet_cannot_outgrow_its_buffer() {
    // 4096 was the old independent receive bound and was wrong about the buffer
    // it protected: it admitted frames 2032 bytes larger than the 2048-byte
    // ENCAPSULATION_BUFFER (F89). The constants that replaced it must be pinned
    // to each other and to the buffer, or the abort path reopens on any future
    // retune. Each comparison is between two independent public constants, so
    // the assertions are load-bearing, not tautologies.
    assert_eq!(
        MAX_PACKET, MAX_WG_DATAGRAM,
        "the framed ceiling must be the wire datagram ceiling, byte for byte"
    );
    assert_eq!(
        MAX_PACKET - WG_RECV_HEADER_SZ,
        ENCAPSULATION_BUFFER,
        "the largest datagram the receiver admits must exactly fill the receive buffer"
    );
    assert!(
        wg_buffer::check_decapsulable(MAX_PACKET, ENCAPSULATION_BUFFER).is_ok(),
        "a wire-ceiling datagram must be decapsulable into the receive buffer"
    );
}

/// Sweep the whole MTU range against the real vendor boundary: the guard's
/// verdict must agree with boringtun's, at every size.
#[test]
fn guard_agrees_with_vendor_at_every_size() {
    for n in 1..=ENCAPSULATION_BUFFER {
        let vendor_ok = encapsulate(n).is_ok();
        let guard_ok = wg_buffer::check_encapsulable(n, ENCAPSULATION_BUFFER).is_ok();
        assert_eq!(
            vendor_ok, guard_ok,
            "guard and vendor disagree at {n} bytes: guard={guard_ok} vendor={vendor_ok}"
        );
    }
}
