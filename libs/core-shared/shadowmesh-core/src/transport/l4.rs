//! The L4 axis — one table, in core, of "can this transport carry a datagram?".
//!
//! Implementation Source:
//! - Specification: RFC-047 §1 (the capability axis) and RFC-044 §4's verified
//!   transport matrix. No GPL/AGPL proxy source is consulted or ingested.
//!
//! # Why this is a table and not a field
//!
//! The control plane can name nine transports
//! (`services/server/src/protocol/capability.rs:74-87`) and those names reach
//! the wire inside `ResolvedCapability.available`. RFC-047 §1.2 ruled that
//! **`Transport` does not gain an L4 axis**: it is a name enum, `Copy`,
//! `Serialize`d, matched in ten sites, and a field on it would change a
//! wire-visible type to carry a fact that belongs to the transports
//! themselves.
//!
//! So the axis is **relocated**, not abolished. It lives here, beside the
//! transport implementations in `libs/`, where the facts are, so the control
//! plane and the client read **one** table rather than two. RFC-047 §5.2
//! item 10 forbids a second UDP-capability lookup table, and this file is
//! that table.
//!
//! # What the class actually means
//!
//! **"Can a node serving this transport carry a datagram payload?"**
//!
//! It is not "does this transport speak UDP at the IP layer". VLESS and VMess
//! carry datagrams *inside* the stream — the `udp_relay_session` reads UDP
//! frames off the same TCP stream (`transport/inbound/vmess.rs:249`) — and
//! RFC-044 §4 still classes them as carrying a UDP payload. Trojan is the
//! opposite case: its UDP command is **hard-rejected**
//! (`transport/inbound/trojan.rs:133`), so a Trojan inbound can never carry
//! the product's payload however healthy its certificate is.
//!
//! The product's payload is WireGuard over UDP to a node-local port
//! (`transport/reality.rs:137-140`), so this class is the transport's
//! capability to carry it.
//!
//! # Which side of the connection this describes
//!
//! **The serving side.** The keys are the names a node's *advertised* set
//! uses, and they answer: "can a node serving this transport carry a
//! datagram payload?"
//!
//! The direction matters, and the table does not record it, because the class
//! flips with direction (RFC-047 §1.2, reason 2). The Trojan **inbound**
//! hard-rejects the UDP command, so a node serving Trojan cannot carry a
//! datagram; the Trojan **outbound** has a UDP ASSOCIATE command
//! (`transport/outbound/trojan.rs:168`), so a client dialling Trojan can. One
//! name, two answers — and only the serving side is the one RFC-040 §6.4's
//! D1 evaluates ("a node's advertised set").
//!
//! ⚠️ [`crate::engine::metadata::L4Protocol`] is a **different** type with a
//! **different** scope: it is the protocol of one connection's packets, and it
//! has `Icmp` and `Unknown` members. The two must not be merged.
//!
//! # Unknown names
//!
//! [`l4_of`] returns `None` for a name this crate does not implement. An
//! unknown transport is **never** assumed datagram-capable: assuming
//! capability widens the surface, which is the posture the control plane
//! already takes with unknown protocols ("unknown protocols are ignored rather
//! than trusted", `services/server/src/protocol/capability.rs:26-27`).
//!
//! # The test is the authority
//!
//! `tests/f90_l4_axis_test.rs` pins every name below to its verdict. That
//! test is what the control plane's `derive()` may consult, and it is what
//! stops this table and RFC-044 §4 from drifting apart.

use serde::{Deserialize, Serialize};

/// Whether a transport can carry a datagram (UDP) payload.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum L4 {
    /// Stream only (TCP). Cannot carry the product's payload.
    Stream,
    /// Datagram capable (UDP).
    Datagram,
}

impl L4 {
    /// The stable name of the class.
    ///
    /// One place, so a consumer and a log line cannot spell it two ways.
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Stream => "stream",
            Self::Datagram => "datagram",
        }
    }

    /// Whether this class can carry a datagram payload.
    pub const fn is_datagram(self) -> bool {
        matches!(self, Self::Datagram)
    }
}

/// The L4 class of a transport, by its canonical name.
///
/// The keys are the names this codebase uses **for a transport**: the control
/// plane's canonical spellings (`services/server/src/protocol/capability.rs:74-87`
/// — the same strings that appear in `ResolvedCapability.available`), the
/// client's own spellings of the same transports, and RFC-044 §4's matrix
/// names. Core cannot depend on the server, so the join is by name, and there
/// is exactly one table.
///
/// The match is **exact**. The canonical names are lowercase; a name spelled
/// differently is not a name this table knows, and `None` is the answer. That
/// includes configuration spellings that are not capability names
/// (`shadowsocks2022`, `shadowsocks-2022`): the control plane folds those
/// into `ss2022` before advertising, and the advertised name is the one this
/// table is keyed by.
///
/// `None` is also the answer for a name this crate does not implement — never
/// a default, and never an implicit `Datagram`.
///
/// The verdicts, each with the site it was read from:
///
/// | name | class | why |
/// | :--- | :--- | :--- |
/// | `wireguard` | `Datagram` | native UDP on :51820 — the payload plane itself |
/// | `reality` | `Datagram` | VLESS UDP to the node-local WireGuard port (`transport/reality.rs:137-140`) |
/// | `vless` | `Datagram` | `cmd == 2` opens a UDP relay session (`transport/inbound/vmess.rs:142-146`) |
/// | `vmess` | `Datagram` | the same dispatcher (`transport/inbound/vmess.rs:105`) |
/// | `tuic` | `Datagram` | QUIC/UDP-native (`transport/inbound/tuic.rs:135`) |
/// | `hysteria2` | `Datagram` | QUIC/UDP-native (`protocol_registry.rs:436-459`) |
/// | `hysteria` | `Datagram` | the same transport under the spelling the client's config uses |
/// | `shadowsocks` | `Datagram` | the inbound opens a UDP listener (`transport/inbound/shadowsocks.rs:158-159`) |
/// | `ss2022` | `Datagram` | the same Shadowsocks inbound, under the name the control plane advertises (`capability.rs:561`) |
/// | `trojan` | `Stream` | the UDP command is hard-rejected (`transport/inbound/trojan.rs:133`) |
/// | `websocket` | `Stream` | a byte-stream transport with no datagram path (`transport/websocket.rs`) |
/// | `socks` | `Stream` | a TCP listener only (`transport/inbound/socks.rs:91`) |
/// | `http` | `Stream` | a TCP listener only (`transport/inbound/http.rs:101`) |
///
/// ⚠️ **The local plumbing is not classified.** `tun` (the device's own
/// listener) and `direct` (the bypass route) are not transports in this axis's
/// sense — they are how a packet enters and leaves this host — so they have
/// no entry and resolve to `None`.
///
/// ⚠️ **A known gap inside one verdict.** The Shadowsocks 2022 *methods* have
/// no working datagram path in this crate: the SIP007 UDP decryptor fails
/// closed for them (`protocol/shadowsocks.rs:347-353`) and points at the
/// SIP022 receiver, which no inbound uses yet. The class above describes the
/// **transport's** capability — the Shadowsocks inbound does open a UDP
/// listener — and this gap is in one cipher path inside it. It is recorded
/// here so that nobody reads `Datagram` as "the 2022 UDP path works today".
pub const fn l4_of(transport_name: &str) -> Option<L4> {
    // Matched on **bytes**, not on `&str`: `PartialEq for str` is not yet a
    // stable const trait (E0658 on the pinned toolchain, rustc 1.98.0), so a
    // `&str` pattern in a `const fn` does not compile. Byte-string patterns
    // are allowed in a const fn, so the function stays const-evaluable.
    //
    // The byte patterns below are the same literals, one byte per ASCII
    // character — the canonical names are ASCII, so this is the same match,
    // not a prefix or a case-folded comparison. The exactness the table
    // promises is preserved: `b"trojanx"` matches nothing.
    match transport_name.as_bytes() {
        b"wireguard" | b"reality" | b"vless" | b"vmess" | b"tuic" | b"hysteria" | b"hysteria2"
        | b"shadowsocks" | b"ss2022" => Some(L4::Datagram),
        b"trojan" | b"websocket" | b"socks" | b"http" => Some(L4::Stream),
        _ => None,
    }
}
