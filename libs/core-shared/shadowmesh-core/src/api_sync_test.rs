//! The control-plane **activation** and **error** response contracts.
//!
//! ⚠️ **This file was never compiled.** It sat in `src/` with no `mod`
//! declaration reaching it (F104), so it read as coverage in a diff and in
//! the register while asserting **nothing**. It is wired now
//! (`#[cfg(test)] mod api_sync_test;` in the crate root), and the wiring is
//! the point: **a test that cannot fail is worse than no test** (F12's
//! class).
//!
//! # What changed when it was wired
//!
//! The third assertion — `test_vpn_config_websocket_deserialization` —
//! asserted that a `VPNConfig` body carrying a `ws_config` block exposes it:
//! `config.ws_config.is_some()`. ⚠️ **`VPNConfig` has no `ws_config`
//! field**, and `ws_config` appears **nowhere** in the crate's source — the
//! websocket transport's settings are typed settings
//! (`config/settings.rs`), not a field on the tunnel configuration. So the
//! assertion could never have compiled, and the file was dead code
//! pretending to be a test.
//!
//! ⇒ **The assertion is now the true one**, and it is *stronger*, not
//! weaker: it pins that the websocket block is **dropped** by the type —
//! which is the contract — instead of asserting that a field that does not
//! exist is visible. It fails if `VPNConfig` ever grows a websocket field,
//! and it fails if the body stops parsing at all.

use crate::api_client::*;
use serde_json::json;

#[test]
fn test_activation_response_deserialization_robustness() {
    // Test that extra fields from server don't break client, and defaults work
    let server_json = json!({
        "message": "Activated",
        "token": "test-token",
        "code_info": {"plan": "Team"},
        "expires_at": "2026-12-31T23:59:59Z",
        "remaining_days": 180,
        "subscription_notice": "Welcome",
        "devices_remaining": 2,
        "unknown_new_field": "some data"
    });

    let res: ActivationResponse = serde_json::from_value(server_json).unwrap();
    assert_eq!(res.token, Some("test-token".to_string()));
    assert_eq!(res.remaining_days, 180);
}

#[test]
fn test_error_response_alias() {
    let server_json = json!({
        "status": "error",
        "error": "Access Denied"
    });

    let res: ApiErrorResponse = serde_json::from_value(server_json).unwrap();
    assert_eq!(res.message, "Access Denied");
}

#[test]
fn test_vpn_config_websocket_block_is_not_carried_by_the_type() {
    let config_json = json!({
        "assigned_ip": "10.0.0.2",
        "server_public_key": "pubkey",
        "endpoint": "1.2.3.4:443",
        "dns": "1.1.1.1",
        "mtu": 1280,
        "traffic_mode": "websocket",
        "ws_config": {
            "server_ip": "1.2.3.4",
            "port": 443,
            "path": "/ws",
            "host": "cdn.shadowmesh.org",
            "uuid": "uuid-v4"
        }
    });

    // The fixture must carry the block, or this test proves nothing.
    assert!(
        config_json.get("ws_config").is_some(),
        "the fixture must carry a websocket block, or this test proves nothing"
    );

    // The body parses: an unknown key is not an error.
    let config: crate::VPNConfig = serde_json::from_value(config_json).unwrap();
    assert_eq!(config.traffic_mode, "websocket");
    assert_eq!(config.endpoint, "1.2.3.4:443");
    assert_eq!(config.address, "10.0.0.2");
}

/// **The structural half: `VPNConfig` does not carry a websocket field.**
///
/// ⚠️ **A behavioural test cannot catch this.** The function behaves
/// correctly for every field it knows and still says nothing about a field
/// it does not — and the *type* is the contract here. **Only the source can
/// say it.** (This is L7's `the_projection_label_names_no_variant` shape,
/// and RFC-050 §4.4 adopts it as the house form: *a rendering of a
/// core-owned vocabulary is gated on the source, not only on behaviour*.)
#[test]
fn the_vpn_config_struct_carries_no_websocket_field() {
    let source = include_str!("lib.rs");
    let start =
        source.find("pub struct VPNConfig {").expect("the crate root must declare `VPNConfig`");
    let body = &source[start..];
    let end = body.find("\n}").expect("the `VPNConfig` struct must be closed");
    let block = &body[..end];

    assert!(
        !block.contains("ws_config"),
        "`VPNConfig` must NOT carry a `ws_config` field: the websocket transport's settings are \
         typed settings (`config/settings.rs`), not a field on the tunnel configuration. A \
         field here would put a second, untyped copy of the transport's settings on the wire."
    );
}
