//! F95 conformance sentinel — the AES-CTR handshake pair is deleted, and CTR
//! may not return beneath the live AEAD path.
//!
//! Disposition (L10, verified at the sites): the live path is AEAD. The client
//! seals the 16-byte session_id with `Aes256GcmSha384` inside the TLS
//! ClientHello record (`transport/reality_tls.rs`, `seal_in_place_separate_tag`),
//! the server opens it with GCM and compares the short ID in constant time
//! (`transport/reality_server.rs`, `open_in_place` + `ct_eq`). The AES-CTR pair
//! in `src/reality.rs` had no caller anywhere in the repository: it was dead
//! code sitting one layer above a live, working AEAD path.
//!
//! F35 precedent: capability loss is recorded as a REMOVAL — no stub, no
//! interop matrix, no "kept for compatibility" comment.
//!
//! Mechanism (two independent gates):
//!   1. Dependency gate — the `ctr` crate is removed from the
//!      `shadowmesh-core` manifest, so a CTR handshake cannot be reintroduced
//!      by accident (compile-level impossibility).
//!   2. Source sentinel — the deleted symbols must not reappear in the core
//!      source tree.
//!
//! NOTE: `CARGO_MANIFEST_DIR` is the `shadowmesh-core` package root, so the
//! paths below resolve against this crate regardless of the caller's cwd.

use std::path::{Path, PathBuf};

fn core_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).to_path_buf()
}

/// Every `.rs` file under `src/`, sorted for deterministic failure output.
fn core_sources() -> Vec<PathBuf> {
    fn walk(dir: &Path, out: &mut Vec<PathBuf>) {
        let Ok(entries) = std::fs::read_dir(dir) else {
            return;
        };
        let mut entries: Vec<_> = entries.filter_map(Result::ok).collect();
        entries.sort_by_key(std::fs::DirEntry::path);
        for entry in entries {
            let path = entry.path();
            if path.is_dir() {
                walk(&path, out);
            } else if path.extension().is_some_and(|ext| ext == "rs") {
                out.push(path);
            }
        }
    }
    let mut out = Vec::new();
    walk(&core_root().join("src"), &mut out);
    assert!(!out.is_empty(), "F95: no core sources found — the sentinel is broken");
    out
}

#[test]
fn manifest_has_no_ctr_dependency() {
    let manifest_path = core_root().join("Cargo.toml");
    let manifest = std::fs::read_to_string(&manifest_path)
        .unwrap_or_else(|e| panic!("F95: cannot read {}: {e}", manifest_path.display()));

    for line in manifest.lines() {
        let trimmed = line.trim();
        // A dependency entry for `ctr` — `ctr = { workspace = true }`,
        // `ctr = "0.9"`, `ctr = { version = ... }`.
        let is_ctr_entry = trimmed.starts_with("ctr ")
            || trimmed.starts_with("ctr=")
            || trimmed.starts_with("\"ctr\"");
        assert!(
            !is_ctr_entry,
            "F95: the `ctr` crate is a shadowmesh-core dependency again: {trimmed:?}. \
             The AES-CTR handshake pair was deleted (removal, recorded, no stub); \
             re-adding the dependency would make the dead crypto path reachable \
             again. The live REALITY handshake is AEAD (Aes256GcmSha384)."
        );
    }
}

#[test]
fn reality_source_has_no_ctr_handshake_symbols() {
    let reality_path = core_root().join("src/reality.rs");
    let reality = std::fs::read_to_string(&reality_path)
        .unwrap_or_else(|e| panic!("F95: cannot read {}: {e}", reality_path.display()));

    for symbol in [
        "encrypt_handshake",
        "decrypt_handshake",
        "Ctr128BE",
        "StreamCipher",
        "RealityHandshakePayload",
        "REALITY_VERSION",
    ] {
        assert!(
            !reality.contains(symbol),
            "F95: `{symbol}` is back in src/reality.rs. The AES-CTR handshake pair \
             was removed because nothing called it and the live path is AEAD \
             (client seals the session_id with Aes256GcmSha384 inside the TLS \
             ClientHello record; server opens it with GCM and compares the short \
             ID in constant time). Do not reintroduce CTR below the AEAD pair."
        );
    }
}

#[test]
fn no_core_source_uses_a_ctr_stream_cipher() {
    // The wider gate: not one CTR cipher may return anywhere in the core tree,
    // not just in the file the pair used to live in.
    for path in core_sources() {
        let source = std::fs::read_to_string(&path)
            .unwrap_or_else(|e| panic!("F95: cannot read {}: {e}", path.display()));
        for symbol in ["Ctr128BE", "Ctr128", "StreamCipher"] {
            assert!(
                !source.contains(symbol),
                "F95: `{symbol}` reappeared in {} — CTR must not return beneath \
                 the AEAD path.",
                path.strip_prefix(core_root()).unwrap_or(&path).display()
            );
        }
    }
}
