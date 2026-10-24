//! F86 — the client half: the roster's projection must be **read**, never
//! inferred.
//!
//! RFC-047 §2.5's test 5, named exactly as the RFC names it:
//! `a_client_that_presented_a_session_and_received_the_anonymous_projection_can_observe_the_narrowing`.
//!
//! **Why this test is the one that makes the client half falsifiable.**
//! L8's server-side tests prove that the server *declares* the projection.
//! They say nothing about whether the client can *see* it: a server that
//! declares `"full"` and a client that drops nine fields would still pass
//! every server-side test, and F86 would be half-fixed — which is the failure
//! RFC-046 §1.3 is a catalogue of.
//!
//! **What is NOT claimed here.** The narrowing itself is a *server-side* fact
//! (RFC-047 §2.1) and no client-side mechanism can detect it by looking at
//! the rows: the anonymous row is a strict subset of the full row, so the two
//! are byte-compatible and always were. What the client can do — and what
//! this file proves — is **read the envelope the server sent** and expose it,
//! so that *"the server narrowed this"* becomes a statement the client can
//! actually make.

use mockito::Server;
use serde_json::json;
use shadowmesh_core::{ApiClient, NodeProjection, NodeRoster};

/// The server's FULL row — the thirteen-field `VPNNodeResponse` shape
/// (`services/server/src/models/vpn.rs::VPNNodeResponse`).
///
/// Every field that the anonymous projection withholds is present here, so a
/// test that drops them is provably dropping them.
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

/// The server's ANONYMOUS row — the four-field `PublicNodeResponse` shape.
fn anonymous_row() -> serde_json::Value {
    json!({ "id": "1", "country": "SG", "region": "SG", "is_online": true })
}

/// A client that has presented a session.
fn client_with_session(base_url: String) -> ApiClient {
    let client = ApiClient::new(base_url).expect("the client must build");
    client.set_auth_token(Some("session-token".to_string()));
    client
}

/// **THE red-proof.** A client that presented a session and received the
/// anonymous projection can observe the narrowing.
///
/// Before the fix, `ApiClient::get_nodes_async()` returned `Vec<PublicNode>`
/// and the projection simply did not exist on this side of the wire: the
/// client received a well-formed, correctly-typed, much smaller answer and no
/// signal at all. The test does not merely fail on the old code — it does not
/// compile, which is the correct shape for a missing field.
#[tokio::test]
async fn a_client_that_presented_a_session_and_received_the_anonymous_projection_can_observe_the_narrowing(
) {
    let mut server = Server::new_async().await;

    // The mock only answers a request that carries the session, and
    // `assert_async` below proves the call really happened with it.
    let mock = server
        .mock("GET", "/api/v1/nodes")
        .match_header("authorization", "Bearer session-token")
        .with_status(200)
        .with_header("content-type", "application/json")
        .with_body(json!({ "projection": "anonymous", "nodes": [anonymous_row()] }).to_string())
        .create_async()
        .await;

    let client = client_with_session(server.url());
    let roster = client.get_nodes_async().await.expect("the roster must parse");

    mock.assert_async().await;

    // **The narrowing is observable.** This assertion could not be written
    // before the fix: the field did not exist.
    assert_eq!(
        roster.projection,
        NodeProjection::Anonymous,
        "F86: a client that presented a session and received the anonymous \
         projection must be able to SEE the narrowing"
    );
    assert_eq!(roster.nodes.len(), 1);
    assert_eq!(roster.nodes[0].id, "1");
}

/// The converse, and the guard against a projection that always says
/// `anonymous`: a roster the server did **not** narrow says `full`.
#[tokio::test]
async fn a_client_that_presented_a_session_and_received_the_full_projection_knows_it_holds_a_narrowing(
) {
    let mut server = Server::new_async().await;

    let mock = server
        .mock("GET", "/api/v1/nodes")
        .match_header("authorization", "Bearer session-token")
        .with_status(200)
        .with_header("content-type", "application/json")
        .with_body(json!({ "projection": "full", "nodes": [full_row()] }).to_string())
        .create_async()
        .await;

    let client = client_with_session(server.url());
    let roster = client.get_nodes_async().await.expect("the roster must parse");

    mock.assert_async().await;

    assert_eq!(roster.projection, NodeProjection::Full);

    // The rows are still the four-field shape. The client is therefore
    // holding a **narrowing** of the server's richer answer — and the
    // envelope is what makes that visible (RFC-047 §2.4, *"I dropped
    // fields."*). The connect material is not on this surface at all.
    assert_eq!(roster.nodes.len(), 1);
    assert_eq!(roster.nodes[0].id, "1");
    assert_eq!(roster.nodes[0].country, "SG");
    assert!(roster.nodes[0].is_online);
}

/// **The projection is read from the envelope, not inferred from the rows.**
///
/// The body carries the thirteen-field rows *and* says `anonymous`. A client
/// that inferred the projection from the row shape — the shape the RFC says
/// cannot carry the fact — would answer `full` here.
#[tokio::test]
async fn the_projection_is_read_from_the_envelope_and_not_inferred_from_the_rows() {
    let mut server = Server::new_async().await;

    let mock = server
        .mock("GET", "/api/v1/nodes")
        .with_status(200)
        .with_header("content-type", "application/json")
        .with_body(json!({ "projection": "anonymous", "nodes": [full_row()] }).to_string())
        .create_async()
        .await;

    let client = ApiClient::new(server.url()).unwrap();
    let roster = client.get_nodes_async().await.expect("the roster must parse");

    mock.assert_async().await;

    assert_eq!(
        roster.projection,
        NodeProjection::Anonymous,
        "the envelope is the only carrier of the projection; the row shape is \
         not evidence of anything (RFC-047 §2.2)"
    );
}

/// **Absent is an error, not a default.** (RFC-047 §2.6)
///
/// The body below is the one the control plane used to send: a bare array of
/// rows. It carries no projection, so it does not deserialize. A defaulted
/// projection would let this client report *"the server narrowed this"* about
/// a roster the server never narrowed — the inverse of the defect, and just as
/// harmful.
#[tokio::test]
async fn a_roster_without_a_projection_is_refused_rather_than_defaulted() {
    let mut server = Server::new_async().await;

    let mock = server
        .mock("GET", "/api/v1/nodes")
        .with_status(200)
        .with_header("content-type", "application/json")
        .with_body(json!([anonymous_row()]).to_string())
        .create_async()
        .await;

    let client = ApiClient::new(server.url()).unwrap();
    let result = client.get_nodes_async().await;

    mock.assert_async().await;

    assert!(
        result.is_err(),
        "a roster body with no `projection` must be refused, not defaulted to \
         `anonymous` (RFC-047 §2.6)"
    );
}

/// The same rule, at the type: an envelope without a projection is not a
/// roster, and a roster with one is.
#[test]
fn a_roster_without_a_projection_does_not_deserialize() {
    assert!(
        serde_json::from_str::<NodeRoster>(r#"{"nodes":[]}"#).is_err(),
        "a defaulted projection is the honest-zero hazard this contract exists \
         to avoid"
    );
    assert!(serde_json::from_str::<NodeRoster>(r#"{"projection":"anonymous","nodes":[]}"#).is_ok());
    assert!(serde_json::from_str::<NodeRoster>(r#"{"projection":"full","nodes":[]}"#).is_ok());
}

/// One wire vocabulary for three crates: the control plane, the daemon and
/// the client all read `anonymous` / `full`.
#[test]
fn the_projection_wire_names_are_the_ones_the_server_speaks() {
    assert_eq!(NodeProjection::Anonymous.as_str(), "anonymous");
    assert_eq!(NodeProjection::Full.as_str(), "full");
    assert_eq!(serde_json::to_string(&NodeProjection::Anonymous).unwrap(), "\"anonymous\"");
    assert_eq!(serde_json::to_string(&NodeProjection::Full).unwrap(), "\"full\"");
}

/// The envelope round-trips: a roster the client received can be published
/// onward — which is what the daemon's IPC hop does with it (RFC-047 §2.3(c)).
#[test]
fn the_roster_round_trips_through_the_envelope() {
    let roster = NodeRoster::from_server(NodeProjection::Full, vec![]);
    let json = serde_json::to_string(&roster).unwrap();
    assert!(json.contains("\"projection\":\"full\""), "{json}");
    assert!(json.contains("\"nodes\":[]"), "{json}");
    assert_eq!(serde_json::from_str::<NodeRoster>(&json).unwrap(), roster);
}

/// **GATE-102-1.** The fallback channels are not the control plane, so **no
/// server was asked** — and the roster must say *that*, not say the server's
/// claim.
///
/// ⚠️ **This test used to be named
/// `a_locally_narrowed_roster_states_the_anonymous_projection`, and it
/// asserted `NodeProjection::Anonymous`.** It was not a nuisance to be
/// updated — it was **the proof that the vocabulary could not carry F86's
/// claim**, and it is renamed and re-asserted here, not deleted
/// (RFC-049 §15.3.3).
///
/// The reasoning it recorded was **not wrong about the rows**: the rows
/// really are the four-field anonymous shape. It was **incomplete about the
/// claim**. A claim needs a speaker, and there are two:
///
/// | Value | The claim | Who makes it |
/// | :--- | :--- | :--- |
/// | `Anonymous` | *"the server applied the anonymous projection"* | the **server** |
/// | `LocalFallback` | *"no server was asked; the client narrowed these itself"* | the **client** |
///
/// With one value for both, a mobile client that received `Anonymous` had to
/// **guess** which of the two conditions it was in — and the guess it would
/// make ("the server withheld") is wrong precisely when the device is most
/// degraded. The three-variant vocabulary keeps the old reading available
/// (`LocalFallback` **is** the four-field shape) **and** adds the speaker.
/// **Nothing is lost; the ambiguity is removed.**
#[test]
fn a_locally_narrowed_roster_states_the_local_fallback_projection() {
    let roster = NodeRoster::narrowed_locally(vec![]);
    assert_eq!(
        roster.projection,
        NodeProjection::LocalFallback,
        "no server was asked, so the client's own claim is `LocalFallback` — \
         `Anonymous` is the SERVER's claim ('your session is not in force') \
         and the two must never be conflated (RFC-049 §15.3.3)"
    );
}

/// **GATE-102-2.** The two claims are **not the same value** — asserted in
/// one place, so the vocabulary cannot collapse back into one.
///
/// This is the test that GATE-102-1 alone cannot make: a renamed assertion
/// proves the client states `LocalFallback`; it does not prove the server's
/// `Anonymous` still means something different. If a future edit made
/// `LocalFallback` an alias of `Anonymous` — or made `narrowed_locally`
/// return `Anonymous` again — **only this test would go red**.
#[test]
fn a_server_sourced_roster_and_a_locally_narrowed_roster_state_different_claims() {
    // The server's own statement: the session was not honoured.
    let from_server = NodeRoster::from_server(NodeProjection::Anonymous, vec![]);

    // The client's own statement: no server was asked.
    let from_fallback = NodeRoster::narrowed_locally(vec![]);

    assert_eq!(from_server.projection, NodeProjection::Anonymous);
    assert_eq!(from_fallback.projection, NodeProjection::LocalFallback);
    assert_ne!(
        from_server.projection, from_fallback.projection,
        "'your session is not in force' and 'the control plane is unreachable' \
         are two materially different conditions, and one value cannot say which \
         one a caller is in (RFC-049 §15.3.3)"
    );
}

// ---------------------------------------------------------------------------
// The MANIFEST path — RFC-047 §2.3(b).
//
// `/api/v1/manifest` is a **second spelling** of the same fleet map, and it
// serves the same `"projection"` the roster route's envelope serves
// (`services/server/src/api/handlers/system.rs`). The core's `GlobalManifest`
// used to **drop** it on the way in, so the manifest read could not say which
// projection it was holding — and the desktop's manifest read therefore could
// not surface a narrowing at all.
// ---------------------------------------------------------------------------

/// **THE red-proof for the manifest path.** The manifest carries the
/// projection the server applied.
///
/// Before the fix the field did not exist: the body said `"projection":
/// "full"` and the core's `GlobalManifest` dropped it, so a client could not
/// tell a full manifest from a narrowed one — the desktop's manifest read
/// could not surface a narrowing, which is what L7 named as unreachable
/// from its side.
#[tokio::test]
async fn the_manifest_carries_the_projection_the_server_applied() {
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
                "version": "0.1.0"
            })
            .to_string(),
        )
        .create_async()
        .await;

    let client = ApiClient::new(server.url()).expect("the client must build");
    let manifest = client.fetch_global_manifest_async().await.expect("the manifest must parse");

    mock.assert_async().await;

    assert_eq!(
        manifest.projection,
        NodeProjection::Full,
        "F86: the manifest must carry the projection the server applied — the \
         statement was being dropped on the way in"
    );
    assert_eq!(manifest.nodes.len(), 1);
    assert_eq!(manifest.nodes[0].id, "1");
}

/// **The projection is read from the envelope, not inferred from the rows.**
///
/// The body says `"anonymous"` while carrying the thirteen-field rows. A
/// client that inferred the projection from the row shape — the shape the
/// RFC says cannot carry the fact — would answer `full` here.
#[tokio::test]
async fn the_manifest_projection_is_read_from_the_envelope_and_not_inferred() {
    let mut server = Server::new_async().await;

    let mock = server
        .mock("GET", "/api/v1/manifest")
        .with_status(200)
        .with_header("content-type", "application/json")
        .with_body(
            json!({
                "projection": "anonymous",
                "nodes": [full_row()],
                "anycast_vips": [],
                "version": "0.1.0"
            })
            .to_string(),
        )
        .create_async()
        .await;

    let client = ApiClient::new(server.url()).expect("the client must build");
    let manifest = client.fetch_global_manifest_async().await.expect("the manifest must parse");

    mock.assert_async().await;

    assert_eq!(
        manifest.projection,
        NodeProjection::Anonymous,
        "the envelope is the only carrier of the projection; the row shape is \
         not evidence of anything (RFC-047 §2.2)"
    );
}

/// **Absent is an error, not a default.** (RFC-047 §2.6)
///
/// The body below is the one the control plane used to send on this route: a
/// manifest with no projection. A defaulted projection would let this client
/// report *"the server narrowed this"* about a manifest the server never
/// narrowed — the inverse of the defect, and just as harmful.
#[tokio::test]
async fn a_manifest_body_with_no_projection_is_refused_rather_than_defaulted() {
    let mut server = Server::new_async().await;

    let mock = server
        .mock("GET", "/api/v1/manifest")
        .with_status(200)
        .with_header("content-type", "application/json")
        .with_body(
            json!({
                "nodes": [full_row()],
                "anycast_vips": [],
                "version": "0.1.0"
            })
            .to_string(),
        )
        .create_async()
        .await;

    let client = ApiClient::new(server.url()).expect("the client must build");
    let result = client.fetch_global_manifest_async().await;

    mock.assert_async().await;

    assert!(
        result.is_err(),
        "a manifest body with no `projection` must be refused, not defaulted to \
         `anonymous` (RFC-047 §2.6)"
    );
}

// ---------------------------------------------------------------------------
// The manifest route — the second spelling of the same map.
//
// RFC-047 §2.3(a): "`/api/v1/manifest` is a second spelling of the same map
// … It must carry the same envelope, or the same narrowing stays silent on
// one of the two routes."
//
// The server sends it (`services/server/src/api/handlers/system.rs:89` — the
// body carries `"projection"`). The CLIENT half was the gap: the manifest was
// parsed into a type with nowhere to put the answer, so the server's
// statement was dropped on the way in and the desktop could not surface a
// narrowing from a manifest read at all.
// ---------------------------------------------------------------------------

/// The manifest body the control plane serves: the envelope, the rows, the
/// VIPs, the version.
///
/// The **body**'s rows are the **thirteen-field** shape on purpose.
///
/// ⚠️ **The manifest's row type is now `PublicNode`** — RFC-050 §2.2: the
/// manifest is a **listing** surface, and a listing carries the four-field
/// row. So the thirteen-field body below **parses**, and the nine extra
/// fields are **dropped by the type, not withheld by the server**.
///
/// This file used to say that it *declined* to pin the row contract, so
/// that *"a good change (widening the manifest's rows to the anonymous
/// shape) would not fail this suite"*. **That good change has landed**, so
/// the note is now history: the body stays thirteen-field, the client's row
/// is four-field, and what this file is about is still the **envelope**.
fn manifest_body(projection: &str) -> serde_json::Value {
    json!({
        "projection": projection,
        "nodes": [full_row()],
        "anycast_vips": [],
        "version": "0.1.0",
    })
}

/// **THE red-proof for the manifest half.** A manifest that says `anonymous`
/// is read as `anonymous` — the statement the server made survives the trip
/// into the client's own type.
///
/// Before the field existed, the body above parsed into a manifest that had
/// nowhere to put the answer: the client's type simply had no `projection`.
/// The test does not merely fail on the old code — it does not compile,
/// which is the correct shape for a missing field.
#[tokio::test]
async fn the_manifest_route_carries_the_projection_the_server_applied() {
    let mut server = Server::new_async().await;

    let mock = server
        .mock("GET", "/api/v1/manifest")
        .with_status(200)
        .with_header("content-type", "application/json")
        .with_body(manifest_body("anonymous").to_string())
        .create_async()
        .await;

    let client = ApiClient::new(server.url()).unwrap();
    let manifest = client.fetch_global_manifest_async().await.expect("the manifest must parse");

    mock.assert_async().await;

    assert_eq!(
        manifest.projection,
        NodeProjection::Anonymous,
        "the manifest route must carry the same envelope as the roster route, or \
         the same narrowing stays silent on one of the two routes (RFC-047 §2.3(a))"
    );
}

/// The converse, and the guard against a projection that always says
/// `anonymous`: a manifest the server did not narrow says `full`.
#[tokio::test]
async fn a_manifest_the_server_did_not_narrow_says_full() {
    let mut server = Server::new_async().await;

    let mock = server
        .mock("GET", "/api/v1/manifest")
        .with_status(200)
        .with_header("content-type", "application/json")
        .with_body(manifest_body("full").to_string())
        .create_async()
        .await;

    let client = ApiClient::new(server.url()).unwrap();
    let manifest = client.fetch_global_manifest_async().await.expect("the manifest must parse");

    mock.assert_async().await;

    assert_eq!(
        manifest.projection,
        NodeProjection::Full,
        "a manifest the server did not narrow must say so"
    );
}

/// **The projection is read from the envelope, not inferred from the rows.**
///
/// The body carries the thirteen-field rows *and* says `anonymous`. A client
/// that inferred the projection from the row shape would answer `full` here —
/// and the row shape is not evidence of anything (RFC-047 §2.2).
#[tokio::test]
async fn the_manifest_projection_is_read_from_the_envelope_and_not_inferred_from_the_rows() {
    let mut server = Server::new_async().await;

    let mock = server
        .mock("GET", "/api/v1/manifest")
        .with_status(200)
        .with_header("content-type", "application/json")
        .with_body(manifest_body("anonymous").to_string())
        .create_async()
        .await;

    let client = ApiClient::new(server.url()).unwrap();
    let manifest = client.fetch_global_manifest_async().await.expect("the manifest must parse");

    mock.assert_async().await;

    assert_eq!(
        manifest.projection,
        NodeProjection::Anonymous,
        "the envelope is the only carrier of the projection; the row shape is not \
         evidence of anything (RFC-047 §2.2)"
    );

    // And the client's rows are the **four-field** shape — the **same** in
    // both projections. ⇒ The projection **could not** have been read off
    // them: they are identical in the two bodies above, and the answers
    // differ. The only thing that differs is the envelope.
    //
    // The nine extras the body carried are **dropped by the type, not
    // withheld by the server** (RFC-047 §2.4) — and the connect material
    // is on the authenticated config route, not here (RFC-043 R2).
    assert_eq!(manifest.nodes.len(), 1);
    assert_eq!(manifest.nodes[0].id, "1");
    assert_eq!(manifest.nodes[0].country, "SG");
    assert_eq!(manifest.nodes[0].region, "SG");
    assert!(manifest.nodes[0].is_online);
}

/// **Absent is an error, not a default** — on the manifest route too.
/// (RFC-047 §2.6)
///
/// The body below is the one the control plane used to send: no projection
/// statement at all. It carries none, so it does not deserialize. A defaulted
/// projection would let this client report *"the server narrowed this"* about
/// a manifest the server never narrowed — the inverse of the defect, and just
/// as harmful.
#[tokio::test]
async fn a_manifest_without_a_projection_is_refused_rather_than_defaulted() {
    let mut server = Server::new_async().await;

    let mock = server
        .mock("GET", "/api/v1/manifest")
        .with_status(200)
        .with_header("content-type", "application/json")
        .with_body(
            json!({
                "nodes": [full_row()],
                "anycast_vips": [],
                "version": "0.1.0",
            })
            .to_string(),
        )
        .create_async()
        .await;

    let client = ApiClient::new(server.url()).unwrap();
    let result = client.fetch_global_manifest_async().await;

    mock.assert_async().await;

    assert!(
        result.is_err(),
        "a manifest body with no `projection` must be refused, not defaulted to \
         `anonymous` (RFC-047 §2.6)"
    );
}

/// **One map, one envelope, one vocabulary.**
///
/// The roster route and the manifest route are two spellings of the same
/// fleet map, so they must speak the same wire names for the same fact — and
/// a client that reads one of them must be reading the other's envelope too.
#[tokio::test]
async fn the_roster_route_and_the_manifest_route_speak_one_projection_vocabulary() {
    let mut server = Server::new_async().await;

    let roster_mock = server
        .mock("GET", "/api/v1/nodes")
        .with_status(200)
        .with_header("content-type", "application/json")
        .with_body(json!({ "projection": "anonymous", "nodes": [anonymous_row()] }).to_string())
        .create_async()
        .await;

    let manifest_mock = server
        .mock("GET", "/api/v1/manifest")
        .with_status(200)
        .with_header("content-type", "application/json")
        .with_body(manifest_body("anonymous").to_string())
        .create_async()
        .await;

    let client = ApiClient::new(server.url()).unwrap();
    let roster = client.get_nodes_async().await.expect("the roster must parse");
    let manifest = client.fetch_global_manifest_async().await.expect("the manifest must parse");

    roster_mock.assert_async().await;
    manifest_mock.assert_async().await;

    assert_eq!(
        roster.projection, manifest.projection,
        "one map, two routes, one envelope: a client must not have to learn two \
         vocabularies for the same fact (RFC-047 §2.3(a))"
    );
    assert_eq!(roster.projection, NodeProjection::Anonymous);
}
