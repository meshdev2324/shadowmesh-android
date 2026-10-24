use crate::{RealityConfig, ShadowMeshError};

/// The browser fingerprint REALITY presents when the caller names none.
///
/// F68/F92: a default that lives inside a string template is a default
/// nobody can find, audit, or change in one place. It is named, documented
/// and asserted by the test suite, and it is applied in exactly one place —
/// `RealityConfig::to_outbound_config`.
pub const DEFAULT_FINGERPRINT: &str = "chrome";

impl RealityConfig {
    /// Creates a new `RealityConfig` with the specified parameters.
    pub fn new(
        server_ip: String,
        port: u32,
        uuid: String,
        public_key: String,
        short_id: String,
        sni_target: String,
        fingerprint: Option<String>,
    ) -> Self {
        Self { server_ip, port, uuid, public_key, short_id, sni_target, fingerprint }
    }

    /// Parse a VLESS+REALITY URI string into a `RealityConfig`.
    ///
    /// Expected format:
    /// `vless://<uuid>@<host>:<port>?type=tcp&security=reality&pbk=<pub_key>&sid=<short_id>&fp=<fingerprint>&sni=<server_name>`
    pub fn from_vless_uri(uri: &str) -> Option<Self> {
        if !uri.starts_with("vless://") {
            return None;
        }

        let at_idx = uri.find('@')?;
        let params_start = uri.find('?')?;

        // The grammar has an ORDER: the authority (`user@host`) precedes the
        // query. A `?` before the `@` means the two are not in that order,
        // and the host slice `uri[at_idx + 1..params_start]` would run
        // BACKWARDS — `vless://u?x=1@h:443?pbk=a&sid=b` yields `uri[15..9]`,
        // a slice panic, and under `panic = "abort"` a PROCESS ABORT on a
        // UniFFI-exported surface (`parse_reality_vless_uri`, `lib.rs:112`).
        //
        // A panic is not a rejection. The honest answer to a URI that is not
        // a VLESS URI is `None`.
        if params_start <= at_idx + 1 {
            return None;
        }

        let uuid = uri[8..at_idx].to_string();

        // An empty uuid is a nil credential downstream
        // (`Uuid::parse_str("").unwrap_or_else(Uuid::nil)`). A config that
        // carries one is a promise the caller cannot keep, so it is refused
        // here rather than returned and failed later.
        if uuid.is_empty() {
            return None;
        }

        let host_port_part = &uri[at_idx + 1..params_start];
        let (server_ip, port_str) = host_port_part.split_once(':')?;
        // A port is 16 bits. `port` is a `u32` in the struct because that is
        // the shape the transport dials with, but a value above 65535 is not
        // a port — it is a refusal.
        let port = u32::from(port_str.parse::<u16>().ok()?);
        if server_ip.is_empty() {
            return None;
        }
        let server_ip = server_ip.to_string();

        let query = &uri[params_start + 1..];

        let mut sni_target = String::from("security.debian.org");
        let mut public_key = String::new();
        let mut short_id = String::new();
        let mut fingerprint = Some(String::from(DEFAULT_FINGERPRINT));

        for param in query.split('&') {
            if let Some((key, value)) = param.split_once('=') {
                match key {
                    "sni" => sni_target = value.to_string(),
                    "pbk" => public_key = value.to_string(),
                    "sid" => short_id = value.to_string(),
                    "fp" => fingerprint = Some(value.to_string()),
                    _ => {}
                }
            }
        }

        if public_key.is_empty() || short_id.is_empty() {
            return None;
        }

        Some(Self { server_ip, port, uuid, public_key, short_id, sni_target, fingerprint })
    }

    /// Build the VLESS+REALITY outbound config fragment as a JSON string.
    ///
    /// F92: every value here is untrusted — a uuid, an SNI, a public key and
    /// a short ID arrive from a subscription feed or a pasted URI. The
    /// fragment is therefore BUILT by `serde_json`, never interpolated into a
    /// string template: one quote, backslash or brace used to produce a
    /// string that was not JSON at all, and the failure was silent because
    /// the caller parses it later (or does not).
    ///
    /// The field set is unchanged, so the consumers of this fragment are
    /// unaffected; only the way it is assembled changed.
    pub fn to_outbound_config(&self) -> String {
        // One place decides the default. F68: a default nobody can find is a
        // default nobody can audit or change.
        let fingerprint = self.fingerprint.as_deref().unwrap_or(DEFAULT_FINGERPRINT);
        serde_json::json!({
            "protocol": "vless",
            "address": self.server_ip,
            "port": self.port,
            "uuid": self.uuid,
            "security": "reality",
            "serverName": self.sni_target,
            "publicKey": self.public_key,
            "shortId": self.short_id,
            "fingerprint": fingerprint,
        })
        .to_string()
    }
}

/// The number of bytes in a REALITY short ID.
///
/// The short ID is credential material: it is one half of what the REALITY
/// server checks after the AEAD session-id open, so it is 8 bytes wide
/// because that is the size of the wire slot it is compared in.
pub const SHORT_ID_LEN: usize = 8;

/// Generate a cryptographically random 8-byte hex Short ID for the REALITY
/// protocol.
///
/// F94: this function has no fallback, and the fallback it used to have was
/// the defect. The old implementation answered an OS-CSPRNG failure with
/// `0000000000000000` and called that "an explicit, detectable
/// misconfiguration". **Nothing detected it.** The REALITY server accepted
/// any non-empty short ID of at most eight bytes, so the constant was not a
/// misconfiguration at all — it was a credential, one that everybody knows,
/// and the comment four lines above it named that exact risk.
///
/// So the failure is a FAILURE, and the function says so in its **type**:
/// it returns [`Result`], and no short ID exists on the error arm — no
/// clamping, no default, no zero-fill.
///
/// ⚠️ **F98: this is now the function the UDL binds, and the boundary is
/// throwing.** `shadowmesh.udl` declares
/// `[Throws=ShadowMeshError] string generate_reality_short_id();`, so the
/// refusal crosses the FFI as an **exception** the consumer must handle,
/// rather than as a sentinel value it must know to check. The old adapter
/// returned `String` and refused with the **empty string** — and a sentinel
/// that is a legal value in the type is not a refusal, it is a value that
/// happens to be forbidden by a rule the consumer must know (RFC-049 §6.2
/// item 14).
pub fn generate_short_id() -> Result<String, ShadowMeshError> {
    generate_short_id_with_entropy_source(crate::secure_random_bytes)
}

/// The Rust-side name for [`generate_short_id`].
///
/// ⚠️ **It exists because a caller names it**, not because a second shape is
/// wanted. The server's mirror test calls it by this name
/// (`services/server/tests/f94_short_id_mirror_test.rs:268`), and that tree
/// is not this commit's to change. **Same shape, same body, one
/// implementation** — [`generate_short_id`] delegates here, so there is no
/// second generator and no second shape to drift.
///
/// The two-shape defect F94 was filed against — a `String`-returning adapter
/// over a fallible generator — is **gone**: there is no `String`-returning
/// function left in this module.
pub fn try_generate_short_id() -> Result<String, ShadowMeshError> {
    generate_short_id()
}

/// The injection seam: [`generate_short_id`] with the entropy source
/// supplied by the caller.
///
/// It exists so the FAILURE PATH IS TESTABLE. The OS CSPRNG cannot be made
/// to fail on demand, and a failure path nobody has ever executed is a
/// failure path nobody has ever seen — which is exactly how the constant
/// credential survived in the first place.
///
/// This is `pub` because an integration test under `tests/` compiles the
/// library without `cfg(test)` and therefore cannot see a `#[cfg(test)]`
/// item. It is not named in the UDL, so the FFI surface is unchanged; the
/// only thing this widens is the crate's own Rust API, by one function that
/// takes an entropy source as an argument.
///
/// `source` is asked for exactly [`SHORT_ID_LEN`] bytes and must return
/// `None` if the entropy source failed. A source that returns the wrong
/// number of bytes is a REFUSAL, not a short ID: a truncated or padded
/// credential is still a credential the caller did not ask for.
///
/// ⚠️ **And an ALL-ZERO source is a refusal too** (RFC-049 §4.4, RULING
/// F98-3b). The short-id rule is *"1..=8 bytes of hex carrying at least one
/// non-zero byte"*, and the emitter is one of the rule's **three
/// expressions** — the node's accept rule
/// (`transport::reality_server::decode_acceptable_short_id`) and the control
/// plane's policy (`services/server`'s `is_matchable_short_id`) are the other
/// two. Before this check the emitter expressed only the **length** bound:
/// an entropy source that returned eight zero bytes produced
/// `Ok("0000000000000000")` — **the constant credential the whole rule
/// exists to refuse**, handed out by the one component that must never
/// produce it. The node would have refused the id (correctly) and the emitter
/// would have handed it out (wrongly), and the handshake would have failed
/// with no diagnosis on either side.
///
/// The probability of a real CSPRNG returning that is 2⁻⁶⁴, so this was
/// never observed in the field — but **the injection seam exists precisely
/// so that the failure path is testable**, and a rule that one of its three
/// expressions does not enforce is a rule the three do not share.
///
/// ⇒ The one table that pins the three expressions together is
/// [`crate::short_id_rule::SHORT_ID_FIXTURES`], and the assertion that this
/// arm is the emitter's expression of it is
/// `tests/f94_short_id_fixture_parity_test.rs`.
pub fn generate_short_id_with_entropy_source<F>(mut source: F) -> Result<String, ShadowMeshError>
where
    F: FnMut(usize) -> Option<Vec<u8>>,
{
    let bytes = source(SHORT_ID_LEN).ok_or(ShadowMeshError::KeyGenerationFailed)?;
    if bytes.len() != SHORT_ID_LEN {
        return Err(ShadowMeshError::KeyGenerationFailed);
    }
    // The all-zero case is a REFUSAL, not a short id: it is not matchable,
    // and the emitter must never hand out a value the node will refuse.
    if bytes.iter().all(|byte| *byte == 0) {
        return Err(ShadowMeshError::KeyGenerationFailed);
    }
    Ok(hex::encode(bytes))
}

/// Derives a session token from a shared Diffie-Hellman secret using SHA-256.
///
/// Matches the protocol spec: `SessionToken = SHA256(Hex64(S))`.
pub fn derive_session_token(dh_shared_secret_hex: String) -> String {
    shadowmesh_common::crypto::derive_session_token(&dh_shared_secret_hex)
}

/// Encrypts a QR pairing payload with a visual stream cipher derived from a PIN.
///
/// Optimized to avoid string allocations and reuse hasher state.
pub fn encrypt_qr_payload(plaintext: &[u8], pin: &str) -> Vec<u8> {
    shadowmesh_common::crypto::encrypt_qr_payload(plaintext, pin)
}

/// Decrypts a QR pairing payload (symmetric — XOR is its own inverse).
pub fn decrypt_qr_payload(ciphertext: &[u8], pin: &str) -> Vec<u8> {
    shadowmesh_common::crypto::decrypt_qr_payload(ciphertext, pin)
}

/// Generates a random 2048-bit Diffie-Hellman private key as a hex string.
/// Uses RFC 3526 Group 14 parameters.
#[allow(deprecated)]
pub fn generate_dh_private_key() -> String {
    shadowmesh_common::crypto::generate_dh_private_key()
}

/// Computes the Diffie-Hellman public key from a hex-encoded private key.
pub fn compute_dh_public_key(private_key_hex: String) -> String {
    shadowmesh_common::crypto::compute_dh_public_key(&private_key_hex)
}

/// Computes the shared secret from a local private key and a remote public key.
pub fn compute_dh_shared_secret(private_key_hex: String, other_public_key_hex: String) -> String {
    shadowmesh_common::crypto::compute_dh_shared_secret(&private_key_hex, &other_public_key_hex)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_vless_uri_parsing() {
        let uri = "vless://uuid@1.2.3.4:443?type=tcp&security=reality&pbk=pubkey123&sid=abcd1234&fp=chrome&sni=security.debian.org";
        let config = RealityConfig::from_vless_uri(uri).expect("Should parse valid VLESS URI");
        assert_eq!(config.server_ip, "1.2.3.4");
        assert_eq!(config.port, 443);
        assert_eq!(config.uuid, "uuid");
        assert_eq!(config.sni_target, "security.debian.org");
        // `from_vless_uri` takes the public key as an opaque string; it does not
        // decode it. The hex decode happens on the AEAD handshake path.
        assert_eq!(config.public_key, "pubkey123");
        assert_eq!(config.short_id, "abcd1234");
        assert_eq!(config.fingerprint, Some("chrome".to_string()));
    }
}
