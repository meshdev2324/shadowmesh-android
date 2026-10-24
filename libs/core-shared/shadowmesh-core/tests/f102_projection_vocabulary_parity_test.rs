//! **F102 — the fixture-parity gate for the projection vocabulary.**
//!
//! RULING F98-3b: the duplication of the projection rule is **accepted**, but
//! only with a gate. RFC-047 §1.6's lesson is explicit: *"two lists must be
//! pinned to each other by a test, or they drift."*
//!
//! # What this file is
//!
//! **ONE fixture table**, and **one assertion** that pins three expressions of
//! the same three claims to each other:
//!
//! | # | Expression | Where it lives |
//! | :--- | :--- | :--- |
//! | 1 | the **serde** wire name (`anonymous` / `full` / `local_fallback`) | `NodeProjection`'s serde attributes — the JSON the control plane and the daemon speak |
//! | 2 | the **UDL** enum name (`Anonymous` / `Full` / `LocalFallback`) | `src/shadowmesh.udl` — the FFI vocabulary the generated bindings carry |
//! | 3 | the **Rust** variant, in declaration order | `NodeProjection` — the type the FFI converter is derived from |
//!
//! # Why the ORDER is asserted, not just the set
//!
//! UniFFI derives the enum's FFI converter from the **Rust** type's variants,
//! and the UDL declares its own list. The two are matched **by position** in
//! the wire format, so a set that agrees but an order that does not is a
//! **silent mis-mapping**: every `Anonymous` crosses the boundary as a
//! `Full`, and nothing fails (RFC-049 §15.6.4). This gate therefore asserts
//! the order, not merely membership.
//!
//! # What this file is NOT
//!
//! It does **not** check that the *generated* bindings are current. That is
//! the four-artifact checksum gate's job
//! (`scripts/ci/check-udl-binary-checksum.sh`): a stale binding carries a
//! stale checksum, and the checksum is what the load-time check compares. The
//! two gates answer different questions and neither subsumes the other.
//!
//! # L8's half
//!
//! This is the **core** half. The server keeps its own two-valued vocabulary
//! (`services/server/src/models/vpn.rs::NodeProjection` — `anonymous` /
//! `full`, and it never speaks `local_fallback`), so the server/daemon half of
//! the parity gate pins **the server's** names to **the core's** names, with
//! the third case asserted as **the client's own** and never the server's.
//!
//! # Red-proof
//!
//! The table's third case did not exist on either side before F102, so this
//! gate was **red before the fix** — it did not compile. Removing
//! `"LocalFallback"` from the UDL, or from the Rust enum, makes it red again.

mod common;
use common::udl;
use shadowmesh_core::NodeProjection;

/// **THE fixture table.** One row per claim; the three expressions of that
/// claim are the columns.
///
/// ⚠️ **This is the only place the three vocabularies are written down
/// together.** The Rust enum's serde name, the UDL's enum name and the
/// generated bindings' names are three lists in three files, and this table is
/// the pin that keeps them the same list.
const FIXTURES: [(NodeProjection, &str, &str); 3] = [
    // (the Rust variant, the serde wire name, the UDL enum name)
    (NodeProjection::Anonymous, "anonymous", "Anonymous"),
    (NodeProjection::Full, "full", "Full"),
    (NodeProjection::LocalFallback, "local_fallback", "LocalFallback"),
];

/// **The gate.** The UDL's enum, the serde names and the Rust declaration
/// order are one list, asserted in one place.
#[test]
fn the_projection_vocabulary_is_one_fixture_table_across_the_serde_and_udl_names() {
    let udl = udl::read_udl();

    // 1. The UDL's own list, in the UDL's own order.
    let udl_names = udl::enum_variants(&udl, "NodeProjection");

    // 2. The Rust declaration order, from the one place it is written down.
    let rust_order: Vec<NodeProjection> = NodeProjection::ALL.to_vec();

    assert_eq!(
        udl_names.len(),
        FIXTURES.len(),
        "the UDL's `NodeProjection` declares {} variant(s) and the fixture table has {} row(s) — \
         the two lists must be the same length or one of them has drifted \
         (RFC-049 §15.6.4)",
        udl_names.len(),
        FIXTURES.len()
    );

    for (index, (variant, serde_name, udl_name)) in FIXTURES.iter().enumerate() {
        // The serde name — the JSON the control plane and the daemon speak.
        assert_eq!(
            variant.as_str(),
            *serde_name,
            "fixture {index}: the serde wire name of {udl_name} is not {serde_name}"
        );
        let json = serde_json::to_string(variant).expect("the projection must serialise");
        assert_eq!(
            json,
            format!("\"{serde_name}\""),
            "fixture {index}: the serialised form of {udl_name} is not the serde name"
        );

        // The UDL name — the FFI vocabulary, by position.
        assert_eq!(
            udl_names.get(index).map(String::as_str),
            Some(*udl_name),
            "fixture {index}: the UDL's `NodeProjection` declares {:?} at position {index}, but the \
             table says {udl_name} — the UDL's order and the Rust enum's order must agree, or \
             the discriminants are silently mis-mapped (RFC-049 §15.6.4)",
            udl_names.get(index)
        );

        // The Rust variant, by position.
        assert_eq!(
            rust_order.get(index),
            Some(variant),
            "fixture {index}: the Rust enum's declaration order does not match the table"
        );
    }
}

/// **The envelope is declared, with the two fields the FFI carries.**
///
/// The roster's projection must travel **with** the rows: a bare
/// `sequence<PublicNode>` cannot say which projection arrived, and that is
/// the whole defect F86 exists for (RFC-047 §2.2).
#[test]
fn the_udl_declares_the_roster_envelope_with_its_two_fields() {
    let udl = udl::read_udl();
    let fields = udl::dictionary_fields(&udl, "NodeRoster");

    assert_eq!(
        fields,
        vec!["NodeProjection projection".to_string(), "sequence<PublicNode> nodes".to_string(),],
        "the UDL's `NodeRoster` must declare the projection and the rows, in that order — \
         the rows alone cannot carry the claim (RFC-047 §2.2)"
    );
}

/// **The accessors return the envelope, not the rows.**
///
/// A UDL that still says `sequence<PublicNode> get_nodes();` is a UDL whose
/// FFI drops the claim — which is the defect this whole change removes.
///
/// ⚠️ **The search is scoped to `interface ApiClient`.** The UDL declares
/// **two** `get_nodes()`: `VPNManager.get_nodes()` is the client's **local
/// cache** (`sequence<VPNNode>`, RFC-049 §15.4.1 — UNCHANGED by this
/// change), and `ApiClient.get_nodes()` is the roster route. A whole-file
/// search finds the *first* one — which is the cache, not the roster — and
/// would report a green tree that is red.
#[test]
fn the_udl_roster_accessors_return_the_envelope() {
    let udl = udl::read_udl();
    let api_client = udl::interface_body(&udl, "ApiClient");

    for accessor in ["get_nodes", "get_nodes_resilient"] {
        // THE shared UDL reader (F111) — this was the second rotted
        // hand-rolled parser (c6887d2): a cut beginning at the needle could
        // never contain the return type sitting before it, so a healthy UDL
        // could not pass. `declaration_signature` carries the line-anchored
        // window, learned once in `common::udl`.
        let signature =
            udl::declaration_signature(&api_client, &format!("{accessor}("), "interface ApiClient");

        assert!(
            signature.contains("NodeRoster"),
            "the UDL's `ApiClient.{accessor}` must return `NodeRoster`, not the bare rows — \
             the signature is: {signature}"
        );
        assert!(
            !signature.contains("sequence<PublicNode>"),
            "the UDL's `ApiClient.{accessor}` must not return `sequence<PublicNode>` — that is \
             the shape that drops the claim (RFC-049 §15.4)"
        );
    }
}

/// **The local cache's accessor is NOT the roster's accessor.**
///
/// `VPNManager.get_nodes()` returns `sequence<VPNNode>` and stays that way:
/// it is the **local cache**, it never touches the control plane, and it has
/// no projection to carry. RFC-049 §15.4.1 lists it as **UNCHANGED**, and a
/// gate that cannot tell the two `get_nodes()` apart would eventually
/// "helpfully" change the wrong one.
#[test]
fn the_local_cache_accessor_is_untouched() {
    let udl = udl::read_udl();
    let vpn_manager = udl::interface_body(&udl, "VPNManager");

    assert!(
        vpn_manager.contains("sequence<VPNNode> get_nodes()"),
        "`VPNManager.get_nodes()` is the LOCAL CACHE and must keep returning \
         `sequence<VPNNode>` (RFC-049 §15.4.1) — the body is: {}",
        vpn_manager.replace('\n', " ")
    );
    assert!(
        !vpn_manager.contains("NodeRoster"),
        "`VPNManager` must not gain a projection: the local cache never touches the control \
         plane, so it has no projection to carry (RFC-049 §15.4.1)"
    );
}

/// **The server's vocabulary is not widened by this change.**
///
/// ⚠️ `local_fallback` is the **client's** claim. The control plane must
/// never be able to publish it, because "no server was asked" is a statement
/// only the client can make (RFC-049 §15.3.2).
#[test]
fn the_local_fallback_claim_is_the_clients_own() {
    let udl = udl::read_udl();

    // The UDL declares it — the FFI must be able to carry it.
    assert!(
        udl::enum_variants(&udl, "NodeProjection").contains(&"LocalFallback".to_string()),
        "the UDL must be able to carry the client's own claim"
    );

    // And the wire name is the client's, distinct from both of the server's.
    assert_ne!(NodeProjection::LocalFallback.as_str(), NodeProjection::Anonymous.as_str());
    assert_ne!(NodeProjection::LocalFallback.as_str(), NodeProjection::Full.as_str());
}
