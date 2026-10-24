//! F90: the L4 axis — one table, in core.
//!
//! This test is the authority the control plane's `derive()` may consult.
//! RFC-047 §1.6 named it, and §1.4(a) named the table it pins:
//! `shadowmesh_core::transport::l4::l4_of`.
//!
//! ## Why the names are pinned as literals
//!
//! The control plane's transport enum lives in `services/server/**`, which
//! core cannot depend on, so the join between the two is **by name**. Two
//! lists that must agree drift unless a test pins them to each other — the
//! F59 lesson, and the reason RFC-047 §1.6 warns that this test "must not
//! import `services/server`". The names below are therefore written out as
//! string literals: a change to `Transport::as_str()` in the server shows up
//! here as a **failing string comparison**, not as a silent divergence.
//!
//! ## What the class means
//!
//! "Can a node serving this transport carry a datagram payload?" — not "does
//! this transport speak UDP at the IP layer". VLESS and VMess carry datagrams
//! inside the stream, and RFC-044 §4 still classes them as carrying a UDP
//! payload; Trojan hard-rejects the UDP command, so it cannot.

use shadowmesh_core::transport::l4::{l4_of, L4};

/// The canonical names the control plane puts on the wire.
///
/// Spelled out as literals from `services/server/src/protocol/capability.rs:74-87`
/// on purpose — see the module header.
const CONTROL_PLANE_NAMES: [&str; 9] = [
    "wireguard",
    "vless",
    "reality",
    "ss2022",
    "trojan",
    "hysteria2",
    "tuic",
    "vmess",
    "websocket",
];

/// RFC-044 §4's matrix, as the ruling recorded it.
const RFC044_DATAGRAM: [&str; 6] = ["reality", "vless", "vmess", "tuic", "hysteria2", "wireguard"];
const RFC044_STREAM: [&str; 1] = ["trojan"];

/// THE red-proof for the axis: every canonical transport name has exactly one
/// entry, and the entries equal RFC-044 §4's matrix.
///
/// Before the table existed there was no answer at all to "can this transport
/// carry a datagram?", which is the hole F90 opened.
#[test]
fn the_l4_table_is_closed_and_agrees_with_rfc044_section_4() {
    // 1. Closed over the control plane's names: every one of them is
    //    classified, and nothing is classified twice.
    for name in CONTROL_PLANE_NAMES {
        assert!(
            l4_of(name).is_some(),
            "the control plane advertises '{name}' and the table has no entry for it; \
             an unclassified name is treated as not datagram-capable, so the \
             advertised set would silently under-report"
        );
    }

    // 2. The verdicts themselves, exactly as RFC-047 §1.6 recorded them.
    for name in RFC044_DATAGRAM {
        assert_eq!(
            l4_of(name),
            Some(L4::Datagram),
            "'{name}' carries a datagram payload (RFC-044 §4) and the table must say so"
        );
    }
    for name in RFC044_STREAM {
        assert_eq!(
            l4_of(name),
            Some(L4::Stream),
            "'{name}' is stream-only (RFC-044 §4) and the table must say so"
        );
    }
}

/// The rest of the table, pinned so that a future edit has to be deliberate.
///
/// The control plane's names are not the whole table: RFC-044 §4 also names
/// the inbound-only transports core implements (`socks`, `http`,
/// `shadowsocks`) and the client spells one transport two ways
/// (`hysteria` / `hysteria2`).
#[test]
fn the_table_covers_the_inbound_only_transports_and_both_spellings() {
    for name in ["socks", "http"] {
        assert_eq!(l4_of(name), Some(L4::Stream), "'{name}' is a TCP listener only");
    }
    for name in ["shadowsocks", "hysteria"] {
        assert_eq!(l4_of(name), Some(L4::Datagram), "'{name}' carries a datagram payload");
    }
    // `websocket` is a byte-stream transport: it has no datagram path at all.
    assert_eq!(l4_of("websocket"), Some(L4::Stream));
}

/// An unknown name is `None` — never a default, and never an implicit
/// `Datagram`.
///
/// RFC-047 §5.2 item 11: assuming capability **widens** the surface, which is
/// the failure this table exists to prevent. This is the same posture the
/// control plane takes with an unknown protocol ("unknown protocols are
/// ignored rather than trusted").
#[test]
fn an_unknown_transport_is_never_assumed_datagram_capable() {
    for name in [
        "",
        "gopher",
        "openvpn",
        "Trojan",          // the match is exact
        "trojan ",         // and whitespace is not stripped
        "shadowsocks2022", // a config spelling, not an advertised name
        "shadowsocks-2022",
    ] {
        assert_eq!(
            l4_of(name),
            None,
            "'{name}' is not a name this table knows; it must not be classified, \
             and above all not assumed datagram-capable"
        );
    }
}

/// The class and the wire name cannot drift apart.
#[test]
fn the_class_names_are_stable() {
    assert_eq!(L4::Stream.as_str(), "stream");
    assert_eq!(L4::Datagram.as_str(), "datagram");
    assert!(L4::Datagram.is_datagram());
    assert!(!L4::Stream.is_datagram());
}

/// The table and the reason that names the capability fault must agree.
///
/// `Unavailable::CannotCarryUdpPayload` exists because a Trojan inbound is
/// stream-only. If the table ever stopped saying so, the reason and the
/// table would be telling two different stories about the same fact.
#[test]
fn the_table_and_the_availability_reason_agree_about_trojan() {
    use shadowmesh_core::transport::inbound::trojan_availability::{
        evaluate, Availability, TrojanInputs, Unavailable,
    };

    assert_eq!(
        l4_of("trojan"),
        Some(L4::Stream),
        "Trojan hard-rejects the UDP command, so it is stream-only"
    );

    let availability = evaluate(
        TrojanInputs {
            domain: Some("vpn.example.com"),
            cert_path: Some("/cert.pem"),
            key_path: Some("/key.pem"),
            payload_path: false,
        },
        |_| true,
    );
    assert_eq!(
        availability,
        Availability::NotAvailable(Unavailable::CannotCarryUdpPayload),
        "the reason that names the capability fault must still be reachable"
    );
}
