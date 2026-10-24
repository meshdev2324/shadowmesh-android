//! The **test tree's** mock node.
//!
//! # Why this file exists
//!
//! ⚠️ **This literal used to live in the CORE** — `src/lib.rs`, in
//! `get_mock_nodes()` — and it was **exported over the FFI**:
//! `sequence<VPNNode> get_mock_nodes();` in the UDL, and
//! `shadowmesh.kt:9588` / `shadowmesh.swift` carried it.
//!
//! **A fabrication source in the core is a trap for the next consumer.** The
//! iOS client's fallback consumed exactly this literal, so one call could
//! put a **fabricated roster** on a user's screen — a row with an endpoint
//! (`1.1.1.1:51820`) and a public key (`mock-pub-key-1`) that no operator
//! ever provisioned. L7 removed the *consumer* (`b6d74fd`); the *source* and
//! its *export* remained, and the next consumer could have re-introduced it.
//!
//! ⇒ **The literal now lives here, in the test tree, where a fabricated node
//! is a fixture and not a product.** The core no longer carries it and the
//! FFI no longer exports it, so a client cannot reach it at all.
//!
//! # Why it is not simply deleted
//!
//! Three integration tests need a **well-formed** `VPNNode` to drive the
//! `VPNManager`'s state machine: a row with an `id`, a country, an endpoint
//! and a key, so that `initiate_connection` has something to dial *in the
//! test*. Deleting the fixture would have deleted those tests' subject.
//!
//! ⇒ **One fixture, shared by the tests that need it**, in one place, so the
//! next reader can see exactly what a "mock node" is in this tree — and that
//! it is a fixture.

// A shared test module is compiled into EVERY test binary that declares
// `mod common;`, and each binary uses a different part of it. Without this,
// `mock_nodes` is dead code in the binaries that only want `mock_node` — and
// a warning is a red gate (clippy runs with `-D warnings`).
#![allow(dead_code)]

use shadowmesh_core::VPNNode;

/// The mock node: a well-formed connect row that names no real operator.
///
/// ⚠️ **`endpoint` and `public_key` are placeholders.** Nothing ever dials
/// this row: the tests that use it assert the `VPNManager`'s **state
/// machine** (which mode it escalates to, which status it reports), not a
/// connection. The values are reserved-for-documentation ranges
/// (`1.1.1.1` is the RFC 5737 TEST-NET-1 address) so that a stray dial
/// cannot reach a real host.
pub fn mock_node() -> VPNNode {
    VPNNode {
        id: "mock-1".into(),
        name: "Mock Node 1".into(),
        region: "US".into(),
        country: "US".into(),
        endpoint: "1.1.1.1:51820".into(),
        public_key: "mock-pub-key-1".into(),
        load: 10,
        latency: 50,
        is_sovereign: false,
        is_online: true,
        shard_id: None,
    }
}

/// The mock node, as a list — for the callers that hand a whole roster to
/// `VPNManager::set_nodes`.
pub fn mock_nodes() -> Vec<VPNNode> {
    vec![mock_node()]
}

/// **F111 — the ONE UDL reader.** The contract gates' UDL windows live
/// in `udl.rs`; its header carries the three rots (`c6887d2`, `de6810a`,
/// register row F111) that forced the consolidation — three parsers
/// re-learning one format is the F59 contract class at the test's own
/// layer, so the format is learned here, once.
pub mod udl;
