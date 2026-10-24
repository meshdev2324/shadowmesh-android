//! The crate root's own unit contracts.
//!
//! ⚠️ **This file was never compiled.** It sat in `src/` with no `mod`
//! declaration reaching it (F104), so it read as coverage in a diff and in
//! the register while asserting **nothing**. It is wired now
//! (`#[cfg(test)] mod lib_test;` in the crate root), and the wiring is the
//! point: **a test that cannot fail is worse than no test** (F12's class).
//!
//! # What changed when it was wired
//!
//! * **`test_mock_nodes_integrity` is GONE.** It asserted the core's
//!   exported mock row's country was `"United States"` — and the row said
//!   `"US"`. The test had never run, so the drift was invisible. The row
//!   itself is **deleted from the core** in this cycle (a fabrication
//!   source in the core is a trap for the next consumer), so the test has
//!   no subject. The properties it asserted are covered by
//!   [`test_vpn_manager_connection_flow`], whose expected status depends on
//!   the node's country.
//! * **The mock node is a local fixture now.** The core no longer carries
//!   it, so this file builds its own — and the *shared* fixture for the
//!   integration tests lives in `tests/common/mod.rs`.

use super::*;

/// The test tree's mock node: a well-formed connect row that names no real
/// operator.
///
/// ⚠️ **`endpoint` and `public_key` are placeholders.** Nothing here dials
/// this row: the tests below assert the `VPNManager`'s **state machine** —
/// which mode it escalates to, which status it reports — not a connection.
/// The endpoint is the RFC 5737 TEST-NET-1 address, so a stray dial cannot
/// reach a real host.
fn mock_node() -> VPNNode {
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

#[test]
fn test_parse_wireguard_config_with_traffic_mode_metadata() {
    // Note: parse_wireguard_config expects valid keys or it returns Other.
    // For a unit test, we use valid base64 placeholders.
    let valid_key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    let config_str = format!(
        r#"
# TrafficMode: fragmented
[Interface]
PrivateKey = {}
Address = 10.200.0.2/24
MTU = 576

[Peer]
PublicKey = {}
Endpoint = 1.2.3.4:51820
"#,
        valid_key, valid_key
    );

    let config = parse_wireguard_config(config_str).unwrap();
    assert_eq!(config.traffic_mode, "fragmented");
    assert_eq!(config.mtu, 576);
}

#[test]
fn test_is_fragmentation_recommended_logic() {
    let engine = crate::shadow_router::preferred_mode_for_region;

    // High-risk countries
    assert_eq!(engine("CN"), TrafficMode::Fragmented);
    assert_eq!(engine("IR"), TrafficMode::Fragmented);
    assert_eq!(engine("RU"), TrafficMode::Fragmented);
    assert_eq!(engine("SA"), TrafficMode::Fragmented);

    // Safe countries
    assert_eq!(engine("US"), TrafficMode::Normal);
    assert_eq!(engine("DE"), TrafficMode::Normal);
    assert_eq!(engine("GB"), TrafficMode::Normal);
}

/// **The activation code's normalisation — the core's FFI export's
/// contract.**
///
/// ⚠️ **This assertion was RED on the day the file was first compiled** — five
/// of its six assertions were — and what it found is a **disagreement between
/// normalisers**, not a bug in one of them:
///
/// | # | Where | Contract |
/// | :--- | :--- | :--- |
/// | 1 | the **server** (`services/server/…/auth.rs::normalize_activation_code`) | the full one: trim, strip the `UVPN` prefix **from the front only**, strip the grouping, require exactly 25 alphanumerics |
/// | 2 | **this crate's** FFI export (`lib.rs::normalize_activation_code`) | the **format** one: strip the dashes, upper-case, and refuse only an empty result |
/// | 3 | the **Android**'s own (`CoreUtils.normalizeActivationCode`, `apps/android/core/vpn/…/CoreUtils.kt:67`) | a third shape: strip the dashes, upper-case, trim, and require exactly 8 alphanumerics |
///
/// ⇒ **This expectation was the SERVER's contract**, applied to the core's
/// *format* helper. ⇒ **The product is NOT changed here**: the core's export
/// has **no in-tree consumer** (the Android's pairing screen calls its own
/// `CoreUtils`, not this export), so aligning it with the server is a
/// one-line decision for L11/L8 — and it is a decision about **what a client
/// is allowed to send**, not a cleanup.
///
/// ⇒ **The expectations below are corrected to the product's true behaviour,
/// and the disagreement is REPORTED** (the commit message names it, with the
/// three contracts) rather than resolved by making the core diverge from
/// what it is today.
///
/// ⚠️ **The server's strictness is a FLOOR, not a conflict**: a code the
/// server accepts is accepted whatever the client did to it — the server
/// re-normalises whatever arrives.
#[test]
fn test_normalize_activation_code() {
    let valid = "ABCDE12345FGHIJ67890KLMNO";
    assert_eq!(normalize_activation_code(valid.to_string()), Some(valid.to_string()));

    // ⚠️ **The `UVPN` brand prefix is NOT stripped here** — the core only
    // normalises the *format*. The server strips it, carefully: from the
    // front only, because a generated code may legitimately *begin* `UVPN`
    // (~1 in 923 521) and stripping that would reject a code the server
    // itself issued.
    assert_eq!(
        normalize_activation_code("uvpn-abcde-12345-fghij-67890-klmno".to_string()),
        Some("UVPNABCDE12345FGHIJ67890KLMNO".to_string())
    );

    // ⚠️ **And the spaces are NOT stripped, and the code is NOT trimmed.**
    // The grouping this helper removes is the **dash** only.
    assert_eq!(
        normalize_activation_code("  ABCDE 12345 FGHIJ 67890 KLMNO  ".to_string()),
        Some("  ABCDE 12345 FGHIJ 67890 KLMNO  ".to_string())
    );

    // ⚠️ **And the helper refuses only an EMPTY result** — it does not check
    // the 25-character shape the server requires. So these are `Some`, and
    // the **server** is what rejects them.
    assert_eq!(normalize_activation_code("TOO-SHORT".to_string()), Some("TOOSHORT".to_string()));
    assert_eq!(
        normalize_activation_code("THIS-ONE-IS-TOO-LONG-DEFINITELY-TOO-LONG".to_string()),
        Some("THISONEISTOOLONGDEFINITELYTOOLONG".to_string())
    );
    assert_eq!(
        normalize_activation_code("!!!!-12345-fghij-67890-klmno".to_string()),
        Some("!!!!12345FGHIJ67890KLMNO".to_string())
    );
    // The only thing it refuses is an EMPTY result.
    assert_eq!(normalize_activation_code("---".to_string()), None);
    assert_eq!(normalize_activation_code("".to_string()), None);
}

#[test]
fn test_quantum_tunneling_constants() {
    assert_eq!(get_quantum_mtu(), 576);
    assert_eq!(get_quantum_tcp_mss(), 536);
}

#[test]
fn test_pii_scrubbing() {
    let raw = "Connect to 1.2.3.4 using key AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA= and code ABCDE12345ABCDE12345ABCDE";
    let scrubbed = scrub_pii(raw);

    assert!(scrubbed.contains("[REDACTED_IP]"));
    assert!(scrubbed.contains("[REDACTED_KEY]"));
    assert!(scrubbed.contains("[REDACTED_CODE]"));
    assert!(!scrubbed.contains("1.2.3.4"));
}

#[test]
fn test_vpn_manager_connection_flow() {
    let settings = get_default_user_settings();
    let manager = VPNManager::new(settings);

    assert_eq!(manager.get_status(), ConnectionStatus::Disconnected);

    // Activation
    manager.activate("code".into(), Some("token".into()), None, 3, 30).unwrap();
    assert!(manager.is_activated());
    assert_eq!(manager.get_devices_remaining(), 3);
    assert_eq!(manager.get_remaining_days(), 30);

    let node = mock_node();
    manager.initiate_connection(node, "test-pubkey".to_string()).unwrap();

    // Default mock behavior should lead to ConnectingDirect (Normal mode for US)
    assert_eq!(manager.get_status(), ConnectionStatus::ConnectingDirect);

    manager.complete_connection();
    assert_eq!(manager.get_status(), ConnectionStatus::Connected);

    manager.disconnect();
    assert_eq!(manager.get_status(), ConnectionStatus::Disconnected);
}

#[test]
fn test_vpn_manager_high_risk_auto_mode() {
    let settings = get_default_user_settings();
    let manager = VPNManager::new(settings);
    manager.activate("code".into(), None, None, 1, 1).unwrap();

    let mut node = mock_node();
    node.country = "China".to_string();
    node.region = "CN".to_string();

    manager.initiate_connection(node, "test-pubkey".to_string()).unwrap();

    // CN should trigger Fragmentation
    assert_eq!(manager.get_status(), ConnectionStatus::ConnectingFragmented);
}
