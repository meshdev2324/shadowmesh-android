//! **THE test tree's ONE UDL reader (F111).**
//!
//! Until F111, every contract gate re-learned the UDL's format by hand, and
//! the three parsers that did rotted **three different ways** — each while
//! the UDL they read was healthy (`c6887d2` carries two of them, `de6810a`
//! the third; the register row F111 has the names):
//!
//! 1. `udl_enum_variants` dropped **every** variant: the UDL writes its
//!    terminator as a trailing comma (`"Full",`) and the reader stripped only
//!    the closing quote, so the parity gate compared **0 rows against a
//!    healthy table**.
//! 2. the roster-envelope accessor reader cut the signature **starting at the
//!    needle** — but the return type `NodeRoster` sits *before* the needle,
//!    so the assertion could not pass against a healthy UDL.
//! 3. F94's throwing-boundary reader anchored the `[Throws]` window
//!    **mid-line** — the needle follows the return type on the declaration's
//!    own line, so the window's last "line" was the declaration's own prefix
//!    (` string `), failing with the tell
//!    *"<string>generate_reality_short_id()"* — again against a healthy UDL.
//!
//! The format is therefore learned **here, once** — the same rule the wire
//! contracts follow at the runtime layer (F59's one-definition-site class),
//! applied at the test's own layer. A reader that needs any part of this
//! format must call one of these functions; copying the pattern into a new
//! gate is exactly the rot this module exists to stop.
//!
//! `#![allow(dead_code)]` lives in the parent `mod.rs`: every test binary
//! declares `mod common;` and uses a different part of it.

/// The UDL's own path, relative to this crate's manifest directory.
fn udl_path() -> std::path::PathBuf {
    std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("src/shadowmesh.udl")
}

/// The UDL's text, **with every `//` comment stripped**.
///
/// The comments are the UDL's prose, and prose is not a declaration: without
/// this, a comment that *mentions* `enum NodeProjection {` would be found by
/// the readers below and parsed as if it were the declaration.
pub fn read_udl() -> String {
    let path = udl_path();
    let raw = std::fs::read_to_string(&path)
        .unwrap_or_else(|e| panic!("the UDL must be readable at {}: {e}", path.display()));
    raw.lines().map(|line| line.split("//").next().unwrap_or("")).collect::<Vec<_>>().join("\n")
}

/// The body of a `keyword name { … };` block — enum, dictionary or
/// interface — as the callers' window to slice.
///
/// The window itself is *not* read against the `//`-stripped text twice per
/// gate; every per-construct reader below is a thin slice of this one.
pub fn block(udl: &str, keyword: &str, name: &str) -> String {
    let needle = format!("{keyword} {name} {{");
    let start = udl.find(&needle).unwrap_or_else(|| panic!("the UDL must declare `{needle}`"))
        + needle.len();
    let body = &udl[start..];
    let end = body
        .find("};")
        .unwrap_or_else(|| panic!("the UDL's `{keyword} {name}` block must be closed with `}}`"));
    body[..end].to_string()
}

/// The names declared inside a UDL `enum <name> { … };` block, **in order**.
///
/// The reader only accepts quoted variant names, so a dictionary field or a
/// type declaration cannot be mistaken for a variant — and it tolerates the
/// **trailing comma** the UDL writes as a terminator (`"Full",`): a
/// terminator is not part of the name. The trailing-comma trim is load-
/// bearing in exactly the way rot 1 was not: without it the reader silently
/// returns an empty list and the gate passes zero rows against a healthy
/// UDL (the failure mode of a stale reader, not a real drift).
pub fn enum_variants(udl: &str, enum_name: &str) -> Vec<String> {
    block(udl, "enum", enum_name)
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty())
        .filter_map(|line| {
            let quoted = line.trim_end_matches(',').trim().strip_prefix('"')?.strip_suffix('"')?;
            if quoted.contains('"') {
                return None;
            }
            Some(quoted.to_string())
        })
        .collect()
}

/// The field declarations of a UDL `dictionary <name> { … };` block, as
/// `"<type> <name>"` strings, **in order**.
pub fn dictionary_fields(udl: &str, dict_name: &str) -> Vec<String> {
    block(udl, "dictionary", dict_name)
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty())
        .map(|line| line.trim_end_matches(';').trim().to_string())
        .collect()
}

/// The body of a UDL `interface <name> { … };` block.
pub fn interface_body(udl: &str, interface_name: &str) -> String {
    block(udl, "interface", interface_name)
}

/// The declaration the needle names, **as a whole line** — the shape rot 2
/// missed and rot 3 mis-took: the needle may begin mid-line (the return type
/// sits before it, on the same line), so the reader anchors at the start of
/// the declaration's own line before reading up, and cuts the signature at
/// the terminating `;`.
///
/// ⚠️ Scope: when searching inside a block body (an interface's accessors),
/// pass the **body**, not the whole UDL — a whole-file search finds the
/// *first* declaration of the name, and the names are not unique across
/// interfaces (`get_nodes()` exists twice, in `ApiClient` and `VPNManager`,
/// with different meanings).
pub fn declaration_signature(scope: &str, needle: &str, scope_label: &str) -> String {
    let at = scope.find(needle).unwrap_or_else(|| panic!("{scope_label} must declare `{needle}`"));
    let line_start = scope[..at].rfind('\n').map(|i| i + 1).unwrap_or(0);
    let tail = &scope[line_start..];
    let end = tail
        .find(';')
        .unwrap_or_else(|| panic!("{scope_label}'s `{needle}` declaration must end with `;`"));
    tail[..end].replace('\n', " ")
}

/// The nearest non-blank line **above the declaration's own line** — where a
/// UDL attribute (`[Throws=…]`) is written.
///
/// The anchoring is the rot-3 fix, kept with the declaration reader so the
/// two window shapes cannot drift apart: the needle may begin mid-line, and
/// a reader whose window ends on the declaration's own prefix finds the
/// return type and can never pass a healthy UDL.
pub fn attribute_line_above(udl: &str, needle: &str) -> String {
    let at = udl.find(needle).unwrap_or_else(|| panic!("the UDL must declare `{needle}`"));
    let line_start = udl[..at].rfind('\n').map(|i| i + 1).unwrap_or(0);
    udl[..line_start].lines().rev().find(|line| !line.trim().is_empty()).unwrap_or("").to_string()
}
