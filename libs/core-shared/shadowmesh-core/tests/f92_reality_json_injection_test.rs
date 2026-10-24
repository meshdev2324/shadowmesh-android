//! F92 — `RealityConfig::to_outbound_config` must build real JSON, not
//! `format!`-interpolated JSON.
//!
//! The old implementation spliced untrusted field values straight into a
//! `format!` template. A single quote, backslash or brace in a uuid, SNI,
//! public key or short ID produced a string that is not JSON at all — and the
//! failure mode is silent, because the function returns a `String` and the
//! caller parses it later (or does not).
//!
//! The fix is `serde_json::json!`, which escapes every value. The test proves
//! two directions:
//!   1. the output parses as `serde_json::Value` for ARBITRARY untrusted
//!      strings, and the fields round-trip intact (no silent truncation, no
//!      escaped-away content);
//!   2. the fingerprint default is EXPLICIT and DOCUMENTED — F68's shape: a
//!      silent `unwrap_or("chrome")` inside a string template is a default
//!      nobody can find, audit, or change in one place.
//!
//! Property budget: 32 cases (owner directive).

use proptest::prelude::*;
// `RealityConfig` is defined at the crate root; `reality.rs` imports it
// privately, so the public path is `shadowmesh_core::RealityConfig`.
use shadowmesh_core::RealityConfig;

/// Build a config from raw (possibly hostile) field values.
fn hostile_config(
    uuid: &str,
    sni: &str,
    public_key: &str,
    short_id: &str,
    fingerprint: Option<&str>,
) -> RealityConfig {
    RealityConfig::new(
        "1.2.3.4".to_string(),
        443,
        uuid.to_string(),
        public_key.to_string(),
        short_id.to_string(),
        sni.to_string(),
        fingerprint.map(str::to_string),
    )
}

#[test]
fn outbound_config_of_hostile_strings_is_valid_json_with_intact_fields() {
    // The deterministic case: the exact characters that break a `format!`
    // JSON template.
    let uuid = "a\"b\\c{d}e\nf";
    let sni = "sni\"x\\y{z}";
    let public_key = "pbk\"k\\k";
    let short_id = "sid\\s\"s";
    let config = hostile_config(uuid, sni, public_key, short_id, Some("fp\"q"));

    let raw = config.to_outbound_config();
    let value: serde_json::Value = serde_json::from_str(&raw).unwrap_or_else(|e| {
        panic!("F92: to_outbound_config produced non-JSON for hostile fields: {e}\nraw: {raw}")
    });

    assert_eq!(value["uuid"].as_str(), Some(uuid), "F92: uuid must round-trip intact");
    assert_eq!(value["serverName"].as_str(), Some(sni), "F92: serverName must round-trip intact");
    assert_eq!(
        value["publicKey"].as_str(),
        Some(public_key),
        "F92: publicKey must round-trip intact"
    );
    assert_eq!(value["shortId"].as_str(), Some(short_id), "F92: shortId must round-trip intact");
    assert_eq!(value["fingerprint"].as_str(), Some("fp\"q"));
    assert_eq!(value["protocol"].as_str(), Some("vless"));
    assert_eq!(value["security"].as_str(), Some("reality"));
    assert_eq!(value["address"].as_str(), Some("1.2.3.4"));
    assert_eq!(value["port"].as_u64(), Some(443));
}

proptest! {
    #![proptest_config(ProptestConfig {
        cases: 32,
        .. ProptestConfig::default()
    })]

    /// ARBITRARY untrusted strings in every user-controlled field must still
    /// yield parsable JSON whose fields round-trip intact.
    #[test]
    fn outbound_config_of_arbitrary_strings_is_valid_json(
        uuid in any::<String>(),
        sni in any::<String>(),
        public_key in any::<String>(),
        short_id in any::<String>(),
    ) {
        let config = hostile_config(&uuid, &sni, &public_key, &short_id, None);
        let raw = config.to_outbound_config();

        prop_assert!(
            serde_json::from_str::<serde_json::Value>(&raw).is_ok(),
            "F92: to_outbound_config produced non-JSON\nraw: {raw}\nuuid: {uuid:?}"
        );
        let value: serde_json::Value =
            serde_json::from_str(&raw).expect("F92: output must parse as JSON");

        prop_assert_eq!(value["uuid"].as_str(), Some(uuid.as_str()));
        prop_assert_eq!(value["serverName"].as_str(), Some(sni.as_str()));
        prop_assert_eq!(value["publicKey"].as_str(), Some(public_key.as_str()));
        prop_assert_eq!(value["shortId"].as_str(), Some(short_id.as_str()));
        prop_assert_eq!(value["port"].as_u64(), Some(443));
        prop_assert_eq!(value["protocol"].as_str(), Some("vless"));
        prop_assert_eq!(value["security"].as_str(), Some("reality"));
    }
}

#[test]
fn fingerprint_default_is_explicit_and_documented() {
    // Behaviour: no fingerprint supplied -> the documented default.
    let config = hostile_config("uuid", "sni", "pbk", "sid", None);
    let raw = config.to_outbound_config();
    let value: serde_json::Value =
        serde_json::from_str(&raw).unwrap_or_else(|e| panic!("F92: non-JSON output: {e}"));
    assert_eq!(
        value["fingerprint"].as_str(),
        Some("chrome"),
        "F92: the fingerprint default must be visible in the emitted config"
    );

    // An explicit fingerprint must win over the default.
    let config = hostile_config("uuid", "sni", "pbk", "sid", Some("firefox"));
    let raw = config.to_outbound_config();
    let value: serde_json::Value =
        serde_json::from_str(&raw).unwrap_or_else(|e| panic!("F92: non-JSON output: {e}"));
    assert_eq!(value["fingerprint"].as_str(), Some("firefox"));

    // Documentation: the default must be a named, documented constant in the
    // source — not a literal buried in a `format!` template where nobody can
    // audit it (F68: a degrading default nobody can see).
    let source_path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("src/reality.rs");
    let source = std::fs::read_to_string(&source_path)
        .unwrap_or_else(|e| panic!("F92: cannot read {}: {e}", source_path.display()));
    assert!(
        source.contains("DEFAULT_FINGERPRINT"),
        "F92: the fingerprint default must be a named, documented constant \
         (DEFAULT_FINGERPRINT) in src/reality.rs, not a silent literal inside the \
         output template"
    );
    assert!(
        source.contains("\"chrome\""),
        "F92: the documented default constant must name the default value it carries"
    );
}
