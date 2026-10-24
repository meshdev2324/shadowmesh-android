#![no_main]
//! Fuzz target: the VLESS URI parser (`RealityConfig::from_vless_uri`).
//!
//! Invariant: the parser answers **Some-or-None** for every input. It never
//! panics, and it never returns a config that is not usable — a returned
//! credential is a promise the caller will use.
//!
//! Why this target exists (F93): `from_vless_uri` found the first `?` and the
//! first `@` independently and then sliced the host out of
//! `uri[at_idx + 1..params_start]`. When the `?` precedes the `@` that slice
//! runs backwards (`uri[15..9]` for `vless://u?x=1@h:443?pbk=a&sid=b`) — a
//! slice panic, and under `panic = "abort"` a **process abort** on a
//! UniFFI-exported surface (`parse_reality_vless_uri`, `lib.rs:112`).
//!
//! Two paths over the same bytes:
//!
//!   1. **RAW** — the bytes are decoded lossily into a `String`. A
//!      byte-oriented fuzzer mutating raw bytes is exactly the shape that
//!      finds an off-by-one in a byte-offset slice, and lossy UTF-8 keeps
//!      every mutation reachable (a multi-byte sequence that splits becomes
//!      two replacement characters, which is still a valid `String` and still
//!      exercises the same offsets).
//!   2. **STRUCTURED** — `arbitrary` draws a uuid, a host, a port and a query
//!      out of the same bytes, so the fuzzer explores the URI *grammar*
//!      instead of the alphabet. The raw path finds the offset bug; the
//!      structured path finds the shape bug.
//!
//! No credentials are carried by the corpus: the seeds are syntactically
//! shaped but meaningless.

use arbitrary::Unstructured;
use libfuzzer_sys::fuzz_target;
// `RealityConfig` is defined at the crate root; `reality.rs` imports it
// privately, so the public path is `shadowmesh_core::RealityConfig`.
use shadowmesh_core::RealityConfig;

/// The parser must answer, and a returned config must be a usable one.
fn assert_parser_answers(candidate: &str) {
    // `catch_unwind` turns a panic into a failed assertion instead of a
    // libFuzzer crash report, so the artefact stays in the corpus with the
    // reproducing bytes.
    let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        RealityConfig::from_vless_uri(candidate)
    }));

    let parsed = match outcome {
        Ok(parsed) => parsed,
        Err(_) => panic!("F93: from_vless_uri panicked on input {candidate:?}"),
    };

    let Some(config) = parsed else {
        return;
    };

    assert!(
        !config.uuid.is_empty(),
        "F93: from_vless_uri returned a config with an EMPTY uuid for {candidate:?} — a nil credential"
    );
    assert!(
        !config.public_key.is_empty(),
        "F93: from_vless_uri returned a config with an EMPTY public key for {candidate:?}"
    );
    assert!(
        !config.short_id.is_empty(),
        "F93: from_vless_uri returned a config with an EMPTY short id for {candidate:?}"
    );
    assert!(
        !config.server_ip.is_empty(),
        "F93: from_vless_uri returned a config with an EMPTY server address for {candidate:?}"
    );
    assert!(
        config.port <= 65535,
        "F93: from_vless_uri returned port {} for {candidate:?} — a port is 16 bits",
        config.port
    );
}

fuzz_target!(|data: &[u8]| {
    // 1. RAW bytes.
    assert_parser_answers(&String::from_utf8_lossy(data));

    // 2. STRUCTURED bytes. The `let ... else` chain keeps the borrows
    //    sequential: a tuple expression would need four `&mut u` at once.
    let mut u = Unstructured::new(data);
    let Ok(uuid) = u.arbitrary::<String>() else {
        return;
    };
    let Ok(host) = u.arbitrary::<String>() else {
        return;
    };
    let Ok(port) = u.arbitrary::<u16>() else {
        return;
    };
    let Ok(query) = u.arbitrary::<String>() else {
        return;
    };
    assert_parser_answers(&format!("vless://{uuid}@{host}:{port}?{query}"));
});
