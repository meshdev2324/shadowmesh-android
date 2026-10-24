//! F94 — an entropy failure must REFUSE the credential, never emit a
//! constant one.
//!
//! The claim this file exists to defend (register row F94): the old
//! `generate_short_id` answered an OS-CSPRNG failure with `0000000000000000`
//! and the comment above it called that "an explicit, detectable
//! misconfiguration". **Nothing detected it.** The REALITY server accepted
//! any non-empty short ID of at most eight bytes, the operator path never
//! saw the generator at all, and the function was UniFFI-exported with no
//! committed caller. A constant credential is not a misconfiguration that
//! is detected — it is a credential that is public.
//!
//! Four things are asserted here, and each of them is a direction:
//!
//! 1. **The failure direction** — an entropy source that fails produces NO
//!    credential at all: the generator returns `Err`. It does not clamp, it
//!    does not default, it does not zero-fill.
//! 2. **The normal direction** — the generator asks for exactly 8 bytes and
//!    hex-encodes what it is given; the real one returns 16 lowercase hex
//!    characters and a different value on every call.
//! 3. **The boundary** — the FFI adapter cannot emit the constant
//!    credential any more. **F98 changed this direction:** the UDL now
//!    declares `[Throws=ShadowMeshError]`, so the refusal crosses the FFI
//!    as an **exception** the consumer must handle, rather than as a
//!    sentinel value it must know to check. The adapter's own shape is the
//!    assertion: it returns a `Result`, so a failure cannot be mistaken for
//!    a short id.
//! 4. **The other side of the wire** — the REALITY server refuses an
//!    all-zero short ID, so the constant cannot authenticate even if it
//!    reaches a configuration by some other route.
//!
//! Implementation Source:
//! - Specifications: this repository's own `reality.rs` / `reality_server.rs`
//!   contract; the REALITY short-ID slot is 8 bytes.
//! - Security considerations: the failure path is exercised through an
//!   injected entropy source, never by weakening the real CSPRNG.

mod common;

use common::udl;
use shadowmesh_core::reality::{
    generate_short_id_with_entropy_source, try_generate_short_id, SHORT_ID_LEN,
};
use shadowmesh_core::transport::reality_server::decode_acceptable_short_id;
use shadowmesh_core::ShadowMeshError;

/// The constant credential the old generator emitted. It must be unreachable
/// from every surface that used to be able to produce it.
const CONSTANT_CREDENTIAL: &str = "0000000000000000";

/// (1) The failure direction: an entropy source that fails produces NO
/// credential. This is the assertion the old code could not have made —
/// there was no failure to assert, only a constant.
#[test]
fn entropy_failure_refuses_the_credential() {
    let err = generate_short_id_with_entropy_source(|_len| -> Option<Vec<u8>> { None })
        .expect_err("an entropy failure must NOT produce a short id");

    assert!(
        matches!(err, ShadowMeshError::KeyGenerationFailed),
        "the refusal must be reported as a key-generation failure, got: {err:?}"
    );
}

/// (1b) A source that returns the WRONG NUMBER OF bytes is a refusal too: a
/// truncated or padded credential is still a credential the caller did not
/// ask for, and the old code would have hex-encoded whatever it was given.
#[test]
fn a_source_that_returns_the_wrong_length_is_refused() {
    let err = generate_short_id_with_entropy_source(|_len| Some(vec![0u8; 4]))
        .expect_err("four bytes are not a short id");
    assert!(
        matches!(err, ShadowMeshError::KeyGenerationFailed),
        "a short byte string must be refused, got: {err:?}"
    );

    let err = generate_short_id_with_entropy_source(|_len| Some(vec![0u8; 16]))
        .expect_err("sixteen bytes are not a short id");
    assert!(
        matches!(err, ShadowMeshError::KeyGenerationFailed),
        "an over-long byte string must be refused, got: {err:?}"
    );
}

/// (2) The normal direction: exactly 8 bytes are requested, and what comes
/// back is hex-encoded verbatim.
#[test]
fn the_short_id_is_eight_fresh_bytes_hex_encoded() {
    let bytes: Vec<u8> = vec![1, 2, 3, 4, 5, 6, 7, 8];
    let mut asked: Vec<usize> = Vec::new();

    let id = generate_short_id_with_entropy_source(|len| {
        asked.push(len);
        Some(bytes.clone())
    })
    .expect("a working entropy source must produce a short id");

    assert_eq!(
        asked,
        vec![SHORT_ID_LEN],
        "the generator must ask the entropy source for exactly 8 bytes"
    );
    assert_eq!(id, "0102030405060708", "the bytes must be hex-encoded verbatim");
    assert_eq!(id.len(), 16, "8 bytes hex-encoded is 16 characters");
}

/// (2b) The same direction through the REAL source: 16 lowercase hex
/// characters, and a different value every time (a constant would be a
/// credential failure, not a formatting detail).
#[test]
fn the_real_generator_returns_fresh_bytes_hex_encoded() {
    let first = try_generate_short_id().expect("the OS CSPRNG must work");
    let second = try_generate_short_id().expect("the OS CSPRNG must work");

    assert_eq!(first.len(), 16, "8 bytes hex-encoded is 16 characters");
    assert!(
        first.chars().all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase()),
        "the short id must be lowercase hex, got: {first:?}"
    );
    assert_ne!(
        first, second,
        "two short ids must not be the same bytes — a constant is not a credential"
    );
    assert_ne!(first, CONSTANT_CREDENTIAL, "the constant credential is unreachable");
}

/// (3) The FFI boundary. **F98: the boundary is throwing now.**
///
/// `generate_reality_short_id` is bound by `shadowmesh.udl` as
/// `[Throws=ShadowMeshError] string generate_reality_short_id();`, so the
/// refusal crosses the FFI as an **exception** the consumer must handle.
///
/// ⚠️ **The adapter's shape is the assertion.** It returns a `Result`, so a
/// failure cannot be mistaken for a short id — which is exactly what the old
/// `String`-returning adapter could not say (RFC-049 §3.2, RULING F98-1).
#[test]
fn the_ffi_boundary_throws_instead_of_returning_a_sentinel() {
    // The type annotation is the point: the FFI-bound function returns a
    // `Result`, so there is no value a consumer could mistake for a
    // successful credential.
    let id: Result<String, ShadowMeshError> = shadowmesh_core::generate_reality_short_id();
    let id = id.expect("the OS CSPRNG must work");

    assert_ne!(
        id, CONSTANT_CREDENTIAL,
        "the constant credential must be unreachable from the FFI surface"
    );
    assert_eq!(id.len(), 16, "a successful call returns a short id, never a sentinel: {id:?}");
}

/// (3a) **The UDL declares the throw.** F98's red-proof, at the declaration
/// that caused it.
///
/// Before this change the UDL said `string generate_reality_short_id();` —
/// un-throwing, so the boundary was **mute** exactly where it most needed to
/// speak. The consumer received a `String` and had to know, out of band,
/// that the empty string meant refusal.
#[test]
fn the_udl_declares_the_short_id_boundary_as_throwing() {
    // THE shared UDL reader (F111) — this suite carried the third rotted
    // hand-rolled parser (de6810a): a needle that begins mid-line made the
    // `[Throws]`-window end on the declaration's own prefix (` string `), and
    // the gate blamed a HEALTHY contract with the tell
    // `stringgenerate_reality_short_id()`. The line-anchored window now lives
    // once, in `common::udl`, next to the declaration reader it serves.
    let udl = udl::read_udl();
    let attribute_line = udl::attribute_line_above(&udl, "generate_reality_short_id(");
    let declaration = udl::declaration_signature(&udl, "generate_reality_short_id(", "the UDL");
    assert!(
        attribute_line.contains("[Throws=ShadowMeshError]"),
        "the UDL's `generate_reality_short_id` must be declared `[Throws=ShadowMeshError]`, \
         and the attribute must sit on the line above it — the declaration is: {} {}",
        attribute_line.trim(),
        declaration
    );
}

/// (3b) The empty string is a REFUSAL, not a credential: the consumer that
/// parses a VLESS URI refuses it, so a refusal at the boundary propagates
/// all the way to "no connection", instead of a credential that connects.
#[test]
fn an_empty_short_id_is_refused_by_every_consumer() {
    let uri =
        "vless://uuid@1.2.3.4:443?type=tcp&security=reality&pbk=pubkey123&sid=&sni=example.com";

    assert!(
        shadowmesh_core::parse_reality_vless_uri(uri.to_string()).is_none(),
        "a URI carrying an empty short id must be refused, not accepted"
    );
}

/// (4) The other side of the wire: the REALITY server refuses an all-zero
/// short ID. This is the belt to the generator's braces — even if a zero ID
/// reaches a configuration by another route (an operator paste, an old
/// provisioning file, a subscription), it cannot authenticate.
#[test]
fn the_server_refuses_an_all_zero_short_id() {
    assert!(
        decode_acceptable_short_id(CONSTANT_CREDENTIAL).is_none(),
        "the constant credential must not be usable as a credential"
    );
    assert!(
        decode_acceptable_short_id("00").is_none(),
        "a one-byte zero short id carries no entropy either"
    );
    assert!(decode_acceptable_short_id("").is_none(), "an empty short id is not a credential");
    assert!(
        decode_acceptable_short_id("zzzz").is_none(),
        "a short id that is not hex is not a credential"
    );
    assert!(
        decode_acceptable_short_id("010203040506070809").is_none(),
        "a short id longer than the 8-byte slot is not a credential"
    );
}

/// (4b) …and a REAL short id is accepted, including a short one whose wire
/// slot is zero-padded. The padding is the wire format, not a credential:
/// the rule is about the configured bytes, never about the padded slot.
#[test]
fn the_server_accepts_a_real_short_id() {
    let full = decode_acceptable_short_id("0123456789abcdef")
        .expect("a real 8-byte short id must be accepted");
    assert_eq!(full.len(), 8);

    let short = decode_acceptable_short_id("abcd")
        .expect("a 2-byte short id is legal — the slot is zero-padded");
    assert_eq!(short, vec![0xab, 0xcd]);
}
