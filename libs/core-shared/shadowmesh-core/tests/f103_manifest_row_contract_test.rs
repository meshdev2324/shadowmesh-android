//! **F103 — the manifest route's row contract.**
//!
//! RFC-050 §5's red-proofs, named as the RFC names them: **R1**, **R2**,
//! **R3** and **R6** (and **R8**, the bindings' half).
//!
//! # The defect this file proves is gone
//!
//! `/api/v1/manifest` is a **second spelling of the fleet map**, and its
//! client type was the **connect** surface's row:
//!
//! ```text
//! services/server/src/models/vpn.rs:446-451   NodeListEntry  (13 fields OR 4)
//! libs/…/src/lib.rs:597-606                    PublicNode     (4 fields)
//! libs/…/src/lib.rs:552-581                    VPNNode        (8 mandatory)  ← the manifest used this
//! ```
//!
//! ⇒ The anonymous projection's row has **four** fields and `VPNNode`
//! demands **eight mandatory** ones ⇒ `serde` **errors** ⇒ the body is
//! rejected **wholesale** ⇒ the `"projection"` the server sent *correctly*
//! (`services/server/src/api/handlers/system.rs:89`) is **never read**.
//!
//! ⇒ **The manifest path had three outcomes where it should have had two:**
//! a withheld manifest and an unreachable control plane were **the same
//! observation** — a `Err` — which is the F86 distinction RFC-043 §3.3 named
//! (*"the client cannot tell 'the server withheld by policy' from 'your
//! session died thirty seconds ago'"*), **collapsed into an error**.
//!
//! # What is asserted here, and what is NOT
//!
//! **Asserted:** the anonymous body **parses**; the projection is **read**;
//! the full body parses into the **same** row type; *"withheld"* and *"not
//! known"* are **distinguishable**; the published document narrows to the
//! **same** row type; and the **generated bindings** declare the four-field
//! row, not the connect row.
//!
//! **Not asserted:** that the *server* serves the right rows — that is
//! **L8's** R4 (`services/server/tests/f103_manifest_row_contract_test.rs`),
//! and this file does not stand in for it. And **no ranking**: the manifest
//! is a **filter**, never a ranking (RFC-050 §2.5, RFC-043 R3) — nothing
//! here reads `latency` or `load`, because the row does not carry them.

use mockito::Server;
use serde_json::json;
use shadowmesh_core::{ApiClient, GlobalManifest, NodeProjection, PublicNode};

/// The server's ANONYMOUS row — the four-field `PublicNodeResponse` shape
/// (`services/server/src/models/vpn.rs:473-478`).
fn anonymous_row() -> serde_json::Value {
    json!({ "id": "1", "country": "SG", "region": "SG", "is_online": true })
}

/// The server's FULL row — the thirteen-field `VPNNodeResponse` shape
/// (`services/server/src/models/vpn.rs:526-540`).
///
/// Every field the anonymous projection withholds is present, so a test that
/// drops them is provably dropping them.
fn full_row() -> serde_json::Value {
    json!({
        "id": "1",
        "name": "SG-1",
        "region": "SG",
        "country": "SG",
        "latency": 21,
        "load": 5,
        "is_online": true,
        "is_sovereign": true,
        "shard_id": null,
        "public_key": "pubkey",
        "endpoint": "10.0.0.1:51820",
        "cpu": 12.5,
        "memory": 33.5
    })
}

/// **R1 — THE red-proof.** An anonymous manifest is **read**, and it states
/// the projection.
///
/// The body here is **the exact body the server serves an anonymous caller**
/// (`services/server/src/api/handlers/system.rs:71-94`): the envelope, the
/// four-field rows, the VIPs and the version.
///
/// **Before the row contract landed this was `Err(JsonError)`** — the body
/// was rejected wholesale and the envelope was never reached. The claim was
/// declaration-derived (RFC-050 §0.1) and L11 re-derived it; this is where
/// it is **observed**.
#[tokio::test]
async fn an_anonymous_manifest_is_read_and_states_the_projection() {
    let mut server = Server::new_async().await;

    let mock = server
        .mock("GET", "/api/v1/manifest")
        .with_status(200)
        .with_header("content-type", "application/json")
        .with_body(
            json!({
                "projection": "anonymous",
                "nodes": [anonymous_row()],
                "anycast_vips": [],
                "version": "0.1.0",
            })
            .to_string(),
        )
        .create_async()
        .await;

    let client = ApiClient::new(server.url()).expect("the client must build");
    let manifest = client
        .fetch_global_manifest_async()
        .await
        .expect("an anonymous manifest must PARSE — it is a legitimate answer, not a failure");

    mock.assert_async().await;

    assert_eq!(
        manifest.projection,
        NodeProjection::Anonymous,
        "the manifest must STATE the projection the server applied"
    );
    assert_eq!(manifest.nodes.len(), 1);
    assert_eq!(manifest.nodes[0].id, "1");
    assert_eq!(manifest.nodes[0].country, "SG");
    assert_eq!(manifest.nodes[0].region, "SG");
    assert!(manifest.nodes[0].is_online);
}

/// **R2 — the two projections land in ONE row type.**
///
/// The thirteen-field body parses into the **same** type the four-field body
/// parses into, and the nine extra fields are **dropped by the type, not
/// withheld by the server** — which is RFC-047 §2.4's third statement, and
/// the envelope is what says so.
#[tokio::test]
async fn a_full_manifest_is_read_and_states_the_projection() {
    let mut server = Server::new_async().await;

    let mock = server
        .mock("GET", "/api/v1/manifest")
        .with_status(200)
        .with_header("content-type", "application/json")
        .with_body(
            json!({
                "projection": "full",
                "nodes": [full_row()],
                "anycast_vips": [],
                "version": "0.1.0",
            })
            .to_string(),
        )
        .create_async()
        .await;

    let client = ApiClient::new(server.url()).expect("the client must build");
    let manifest = client.fetch_global_manifest_async().await.expect("a full manifest must parse");

    mock.assert_async().await;

    assert_eq!(manifest.projection, NodeProjection::Full);
    assert_eq!(manifest.nodes.len(), 1);
    assert_eq!(manifest.nodes[0].id, "1");
    assert_eq!(manifest.nodes[0].country, "SG");
    assert_eq!(manifest.nodes[0].region, "SG");
    assert!(manifest.nodes[0].is_online);
}

/// **R3 — THE F86 distinction, on the manifest path.**
///
/// *"The server withheld by policy"* and *"the control plane is unreachable"*
/// must be **different observations**.
///
/// **Before the row contract landed they were the SAME observation**: case 1
/// (the four-field body) was rejected wholesale and returned `Err`, exactly
/// like case 2 (a 500). ⇒ A client could not tell a **policy outcome** from
/// a **network failure** — and it reported the policy outcome as a network
/// failure.
///
/// ⇒ The difference is the **envelope's presence**, not the rows' shape.
#[tokio::test]
async fn a_withheld_manifest_and_an_unreachable_control_plane_are_different_observations() {
    // ── Case 1: the server answered, and withheld. ──────────────────────────
    let mut server = Server::new_async().await;
    let mock = server
        .mock("GET", "/api/v1/manifest")
        .with_status(200)
        .with_header("content-type", "application/json")
        .with_body(
            json!({
                "projection": "anonymous",
                "nodes": [anonymous_row()],
                "anycast_vips": [],
                "version": "0.1.0",
            })
            .to_string(),
        )
        .create_async()
        .await;

    let client = ApiClient::new(server.url()).expect("the client must build");
    let withheld = client.fetch_global_manifest_async().await;
    mock.assert_async().await;

    let withheld = withheld.expect("a withheld manifest is an ANSWER, not an error");
    assert_eq!(withheld.projection, NodeProjection::Anonymous);

    // ── Case 2: the control plane is unreachable. ───────────────────────────
    let mut server = Server::new_async().await;
    let mock = server
        .mock("GET", "/api/v1/manifest")
        .with_status(500)
        .with_header("content-type", "application/json")
        .with_body(json!({ "status": "error", "message": "boom" }).to_string())
        .create_async()
        .await;

    let client = ApiClient::new(server.url()).expect("the client must build");
    let unknown = client.fetch_global_manifest_async().await;
    mock.assert_async().await;

    // ⇒ **The two are distinguishable.** One is `Ok` with an envelope that
    // names a projection; the other is an `Err` — a different observation
    // entirely, and one that carries no claim about anybody.
    assert!(
        unknown.is_err(),
        "an unreachable control plane must be an Err — the two observations must differ"
    );
}

/// **R6 — the published document and the control plane's manifest narrow to
/// the SAME row type.**
///
/// The control plane's half is asserted **at the type level**: this
/// assignment does not compile unless the manifest's rows are `PublicNode` —
/// the same type the discovery cascade's narrowing produces
/// (`src/network/discovery.rs`, and its own unit test beside it).
///
/// ⇒ The cascade's private row type stops being a **fourth vocabulary**.
#[test]
fn the_control_plane_manifest_carries_the_same_row_type_the_published_manifest_narrows_to() {
    let manifest = GlobalManifest {
        projection: NodeProjection::Anonymous,
        nodes: vec![PublicNode {
            id: "1".into(),
            country: "SG".into(),
            region: "SG".into(),
            is_online: true,
        }],
        anycast_vips: vec![],
        version: "0.1.0".into(),
    };

    // **The type-level assertion.** `Vec<PublicNode>` is not a coercion
    // target: it compiles only because the manifest's rows ARE `PublicNode`.
    let rows: Vec<PublicNode> = manifest.nodes;
    assert_eq!(rows.len(), 1);
    assert_eq!(rows[0].id, "1");
}

/// **R8 — the GENERATED BINDINGS declare the four-field row.**
///
/// ⚠️ **The bindings are the FFI's contract**, and the FFI is where the
/// manifest's rows actually reach a client. A binding that still declared
/// the **connect** row would hand a client eight fields it must not have, or
/// read the wrong bytes — and a **record-layout change moves no checksum**
/// (`uniffi_meta::Type::Record` hashes only the type's *name*), so nothing
/// else would notice.
///
/// ⇒ This is asserted **on the file**, for both platforms, and on **both**
/// halves of each binding: the **record** (what a client sees) and the
/// **converter** (what actually moves the bytes).
///
/// ⚠️ **The first version of this assertion was red for the WRONG REASON**,
/// and that is worth recording. It searched a 2000-character window from the
/// first occurrence of the *string* `GlobalManifest` — and the first
/// occurrence is in the **namespace function list**
/// (`fun fetchGlobalManifest(): GlobalManifest`), not the record
/// declaration. ⇒ The window held neither row type, so the assertion was
/// red on a tree whose bindings were **correct**.
/// RFC-050 §10 item 6: *"a gate that is red for the wrong reason proves
/// nothing about the fix."* ⇒ The search is now anchored on the
/// **declaration**, and the **converter** is asserted separately — because
/// the record's field type and the converter's `read`/`write` are two
/// different facts, and a binding can agree with itself about one and
/// disagree about the other.
#[test]
fn the_generated_bindings_declare_the_four_field_row_and_not_the_connect_row() {
    let repo_root = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../..");
    let kotlin =
        repo_root.join("apps/android/core/vpn/src/main/java/uniffi/shadowmesh/shadowmesh.kt");
    let swift = repo_root.join("apps/ios/ShadowMesh/ShadowMesh/shadowmesh.swift");

    // ── the KOTLIN binding ──────────────────────────────────────────────────
    let kotlin = std::fs::read_to_string(&kotlin).unwrap_or_else(|e| {
        panic!("F103: the Kotlin bindings must be readable at {}: {e}", kotlin.display())
    });

    let record = block_after(&kotlin, "data class GlobalManifest (", "\n)", "the Kotlin record");
    assert!(
        record.contains("List<PublicNode>"),
        "F103: the Kotlin bindings must declare `GlobalManifest.nodes` as the FOUR-FIELD row \
         (`PublicNode`) — the manifest is a listing surface (RFC-050 §2.2). The record is: \
         {record}"
    );
    assert!(
        !record.contains("VpnNode"),
        "F103: the Kotlin bindings must NOT declare `GlobalManifest.nodes` as the CONNECT row \
         (`VpnNode`) — the connect material comes from the authenticated config route \
         (RFC-043 R2, applied by RFC-050 §2.4). The record is: {record}"
    );

    let converter = block_after(
        &kotlin,
        "object FfiConverterTypeGlobalManifest:",
        "\n}",
        "the Kotlin converter",
    );
    assert!(
        converter.contains("FfiConverterSequenceTypePublicNode"),
        "F103: the Kotlin CONVERTER must move the four-field row — this is the code that \
         actually reads and writes the bytes (RFC-047 §2.4: the extras are dropped by the \
         TYPE). The converter is: {converter}"
    );
    assert!(
        !converter.contains("FfiConverterSequenceTypeVPNNode"),
        "F103: the Kotlin CONVERTER must NOT move the connect row. The converter is: {converter}"
    );

    // ── the SWIFT binding ───────────────────────────────────────────────────
    let swift = std::fs::read_to_string(&swift).unwrap_or_else(|e| {
        panic!("F103: the Swift bindings must be readable at {}: {e}", swift.display())
    });

    let record = block_after(&swift, "public struct GlobalManifest {", "\n}", "the Swift record");
    assert!(
        record.contains("[PublicNode]"),
        "F103: the Swift bindings must declare `GlobalManifest.nodes` as the FOUR-FIELD row \
         (`[PublicNode]`) — the manifest is a listing surface (RFC-050 §2.2). The record is: \
         {record}"
    );
    assert!(
        !record.contains("[VpnNode]"),
        "F103: the Swift bindings must NOT declare `GlobalManifest.nodes` as the CONNECT row \
         (`[VpnNode]`) — the connect material comes from the authenticated config route \
         (RFC-043 R2, applied by RFC-050 §2.4). The record is: {record}"
    );

    let converter = block_after(
        &swift,
        "public struct FfiConverterTypeGlobalManifest:",
        "\n}",
        "the Swift converter",
    );
    assert!(
        converter.contains("FfiConverterSequenceTypePublicNode"),
        "F103: the Swift CONVERTER must move the four-field row — this is the code that \
         actually reads and writes the bytes. The converter is: {converter}"
    );
    assert!(
        !converter.contains("FfiConverterSequenceTypeVPNNode"),
        "F103: the Swift CONVERTER must NOT move the connect row. The converter is: {converter}"
    );
}

/// The text between `start` and the next `terminator` after it.
///
/// ⚠️ **A missing `start` is a failure, not an empty block.** A reader that
/// returns "" when the marker is absent answers a different question from
/// the one it was asked, and the answer is plausible enough to look right —
/// the same shape as the four-artifact gate's blind spot.
fn block_after(text: &str, start: &str, terminator: &str, what: &str) -> String {
    let at = text
        .find(start)
        .unwrap_or_else(|| panic!("F103: the generated bindings must declare {what} (`{start}`)"));
    let body = &text[at..];
    let end = body
        .find(terminator)
        .unwrap_or_else(|| panic!("F103: {what} must be closed with `{terminator}`"));
    body[..end].to_string()
}
