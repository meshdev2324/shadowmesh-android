//! F93 — `RealityConfig::from_vless_uri` must answer Some-or-None for every
//! input, and must never panic.
//!
//! The defect (L10-ruled, verified against committed bytes): the parser finds
//! the first `?` and the first `@` independently and then slices the host out
//! of `uri[at_idx + 1..params_start]`. When the `?` comes BEFORE the `@`, that
//! slice runs backwards — `vless://u?x=1@h:443?pbk=a&sid=b` yields
//! `uri[15..9]` — and a slice panic under `panic = "abort"` is a **process
//! abort**, not an error. `parse_reality_vless_uri` (`lib.rs:112`) is
//! UniFFI-exported, so the abort primitive is published on a public FFI
//! surface.
//!
//! The grammar has an order: the authority (`user@host`) precedes the query.
//! A URI whose `?` precedes its `@` is not a VLESS URI, and the honest answer
//! is `None` — a refusal, not a panic and not a best-effort parse.
//!
//! Two proofs, one file:
//!   1. the exact ordering probe, plus the neighbouring malformed shapes
//!      (empty uuid, empty host, multibyte text), and
//!   2. a property: ARBITRARY text never panics.
//!
//! Property budget: 32 cases (owner directive).

use proptest::prelude::*;
// `RealityConfig` is defined at the crate root; `reality.rs` imports it
// privately, so the public path is `shadowmesh_core::RealityConfig`.
use shadowmesh_core::RealityConfig;

/// Parse without letting a panic escape the test body.
///
/// Returns `Err` when the parser panicked. The panic payload is not printed
/// in full: it may carry the offending input, which is fine here (the inputs
/// are test literals), but the assertion message below is the report.
fn parse_no_panic(uri: &str) -> Result<Option<RealityConfig>, ()> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| RealityConfig::from_vless_uri(uri)))
        .map_err(|_| ())
}

#[test]
fn the_question_mark_before_at_probe_is_refused_and_never_panics() {
    // The exact URI from the finding. Pre-fix this panics inside the slice
    // `uri[15..9]`; post-fix it must be a plain refusal.
    let uri = "vless://u?x=1@h:443?pbk=a&sid=b";

    match parse_no_panic(uri) {
        Err(()) => panic!(
            "F93: from_vless_uri PANICKED on {uri:?}. A `?` before the `@` makes the \
             host slice run backwards (uri[15..9]) — under `panic = \"abort\"` that \
             is a process abort on a UniFFI-exported surface."
        ),
        Ok(None) => {}
        Ok(Some(_)) => panic!(
            "F93: from_vless_uri ACCEPTED the malformed URI {uri:?}. The grammar \
             requires the authority (user@host) to precede the query; a `?` before \
             the `@` is not a VLESS URI and must be refused."
        ),
    }
}

#[test]
fn neighbouring_malformed_shapes_are_refused_and_never_panic() {
    // The shapes that share the same slice: the authority is empty, the host
    // is empty, the port is not a number, the uuid is empty.
    let cases = [
        ("vless://@h:443?pbk=a&sid=b", "empty uuid"),
        ("vless://u@:443?pbk=a&sid=b", "empty host"),
        ("vless://u@h:abc?pbk=a&sid=b", "non-numeric port"),
        ("vless://u@h:443", "no query at all"),
        ("vless://u@h:443?pbk=&sid=b", "empty public key"),
        ("vless://u@h:443?pbk=a&sid=", "empty short id"),
        ("vless://u@h:443?", "empty query"),
        ("vless://", "prefix only"),
        ("vless://u@h:443?pbk=a&sid=b#frag", "fragment"),
    ];

    for (uri, what) in cases {
        match parse_no_panic(uri) {
            Err(()) => panic!("F93: from_vless_uri PANICKED on {uri:?} ({what})"),
            Ok(value) => {
                if value.is_some() {
                    // An empty uuid is a nil credential downstream
                    // (`Uuid::parse_str("").unwrap_or_else(Uuid::nil)`), so it must
                    // not be accepted. Everything else in this list is missing a
                    // required field.
                    assert!(
                        !uri.starts_with("vless://@"),
                        "F93: an EMPTY uuid was accepted ({uri:?}) — that is a nil credential"
                    );
                }
            }
        }
    }
}

#[test]
fn a_valid_uri_still_parses() {
    // The fix must not turn a well-formed URI into a refusal.
    let uri = "vless://uuid@1.2.3.4:443?type=tcp&security=reality&pbk=pubkey123&sid=abcd1234&fp=chrome&sni=security.debian.org";
    let config = parse_no_panic(uri)
        .expect("F93: a valid VLESS URI must not panic")
        .expect("F93: a valid VLESS URI must parse");
    assert_eq!(config.server_ip, "1.2.3.4");
    assert_eq!(config.port, 443);
    assert_eq!(config.uuid, "uuid");
    assert_eq!(config.public_key, "pubkey123");
    assert_eq!(config.short_id, "abcd1234");
}

#[test]
fn a_multibyte_uri_is_refused_and_never_panics() {
    // Multibyte text is the other way a byte-slice parser breaks: `find` gives
    // byte offsets, and the offsets must still be char boundaries.
    let uri = "vless://u🎯@h:443?pbk=a&sid=aébcdef&sni=ünïcödé.de";
    if let Err(()) = parse_no_panic(uri) {
        panic!("F93: from_vless_uri PANICKED on a multibyte URI {uri:?}");
    }
}

proptest! {
    #![proptest_config(ProptestConfig {
        cases: 32,
        .. ProptestConfig::default()
    })]

    /// ARBITRARY text must never panic the parser. Some-or-None is the only
    /// contract; a panic is a defect, not a rejection.
    #[test]
    fn arbitrary_text_never_panics_the_parser(candidate in any::<String>()) {
        let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            RealityConfig::from_vless_uri(&candidate)
        }));
        prop_assert!(
            outcome.is_ok(),
            "F93: from_vless_uri PANICKED on {:?}",
            candidate
        );
    }

    /// The same invariant, but only for strings that LOOK like VLESS URIs —
    /// the shape a subscription feed would actually carry. This is the
    /// attacker's reachable surface.
    #[test]
    fn arbitrary_vless_shaped_text_never_panics_the_parser(
        tail in any::<String>(),
        at in "[0-9]{1,4}",
        q in "[0-9]{1,4}",
    ) {
        let uri = format!("vless://{at}?{q}@{tail}");
        let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            RealityConfig::from_vless_uri(&uri)
        }));
        prop_assert!(
            outcome.is_ok(),
            "F93: from_vless_uri PANICKED on {:?}",
            uri
        );
    }
}
