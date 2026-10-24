//! F91 — the module map's drift guard.
//!
//! **Why a guard and not a correction.** `design/MODULE_MAP.md` and
//! `design/ARCHITECTURE.md` were corrected once and were never checked again;
//! they went stale within days, and when the next reader found the stale
//! names they could not tell which of them were true. RFC-046 §6.2.7's gate
//! ("L9 verifying each named module before it is written down") is a **one-time
//! human check**, and one-time checks decay. This file is the durable answer:
//! a check that fails when the map and the tree disagree, in **either**
//! direction.
//!
//! **The two rules this guard obeys, both from RFC-047 §4.7:**
//!
//! 1. **It reads the repository, not a fixture.** The documents are read
//!    relative to `CARGO_MANIFEST_DIR`, so the guard cannot pass against a
//!    stale copy of itself. A guard that proves its own copy stays green when
//!    production breaks — that is the F59 class, and it is the failure this
//!    file exists to prevent.
//! 2. **It has no hand-maintained exception list.** Every exception is a
//!    second place for the map to go stale. The resolution rules below are
//!    mechanical, and a path that resolves nowhere is a failure whoever wrote
//!    it.
//!
//! **The four tests, named as RFC-047 §4.7 names them:**
//!
//! | Test | Direction |
//! | :--- | :--- |
//! | `every_path_named_in_the_module_map_exists` | the map → the tree (**the red-proof**) |
//! | `every_module_directory_in_the_tree_is_named_in_the_module_map` | the tree → the map |
//! | `every_top_level_production_module_is_named_in_the_module_map` | the tree → the map, files |
//! | `the_architecture_module_boundaries_name_no_phantom_module` | the **second** document |
//!
//! The fourth test is the one that pins `ARCHITECTURE.md`, because that is the
//! document that was left broken when the first one was fixed: the same five
//! phantom module names appeared in both.

use std::path::{Path, PathBuf};

// ── where the documents and the tree live ───────────────────────────────────

fn crate_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
}

fn src_root() -> PathBuf {
    crate_root().join("src")
}

/// The repository root. The crate lives at `<repo>/libs/core-shared/<crate>`,
/// so three levels up is the repository. Used only so that a path naming
/// something **outside** this crate (`apps/daemon/src/native_engine.rs`) is
/// recognised as existing rather than reported as a phantom.
fn repo_root() -> PathBuf {
    crate_root().join("../../..")
}

fn module_map_path() -> PathBuf {
    crate_root().join("design").join("MODULE_MAP.md")
}

fn architecture_path() -> PathBuf {
    crate_root().join("design").join("ARCHITECTURE.md")
}

fn read(path: &Path) -> String {
    std::fs::read_to_string(path)
        .unwrap_or_else(|e| panic!("F91: cannot read {}: {e}", path.display()))
}

// ── markdown, minimally ─────────────────────────────────────────────────────

/// One markdown table: its header cells and its body rows.
struct Table {
    header: Vec<String>,
    rows: Vec<Vec<String>>,
}

fn split_row(line: &str) -> Vec<String> {
    line.trim()
        .trim_start_matches('|')
        .trim_end_matches('|')
        .split('|')
        .map(|c| c.trim().to_string())
        .collect()
}

fn is_separator_row(line: &str) -> bool {
    let cells = split_row(line);
    !cells.is_empty()
        && cells.iter().all(|c| {
            !c.is_empty() && c.chars().all(|ch| ch == '-' || ch == ':' || ch.is_whitespace())
        })
}

/// Every markdown table in the document.
fn tables(markdown: &str) -> Vec<Table> {
    let lines: Vec<&str> = markdown.lines().collect();
    let mut out: Vec<Table> = Vec::new();
    let mut i = 0usize;
    while i + 1 < lines.len() {
        if lines[i].trim_start().starts_with('|') && is_separator_row(lines[i + 1]) {
            let header = split_row(lines[i]);
            let mut rows: Vec<Vec<String>> = Vec::new();
            let mut j = i + 2;
            while j < lines.len() && lines[j].trim_start().starts_with('|') {
                rows.push(split_row(lines[j]));
                j += 1;
            }
            out.push(Table { header, rows });
            i = j;
        } else {
            i += 1;
        }
    }
    out
}

/// The inline code spans in a cell — `` `like this` ``.
fn code_spans(cell: &str) -> Vec<String> {
    let mut spans: Vec<String> = Vec::new();
    let mut current: Option<String> = None;
    for ch in cell.chars() {
        if ch == '`' {
            match current.take() {
                None => current = Some(String::new()),
                Some(span) => spans.push(span),
            }
        } else if let Some(buf) = current.as_mut() {
            buf.push(ch);
        }
    }
    spans
}

/// Every inline code span in the document, outside fenced code blocks.
///
/// Fenced blocks are skipped because their contents are *illustrations* — a
/// mermaid diagram or a shell transcript — and are not claims about the
/// tree.
fn document_code_spans(markdown: &str) -> Vec<String> {
    let mut spans: Vec<String> = Vec::new();
    let mut in_fence = false;
    for line in markdown.lines() {
        if line.trim_start().starts_with("```") {
            in_fence = !in_fence;
            continue;
        }
        if in_fence {
            continue;
        }
        spans.extend(code_spans(line));
    }
    spans
}

/// The text of the `## Module Boundaries` section, up to the next `##`
/// heading. A `###` subsection stays inside it — the map's own "Two
/// programs, not one" note lives there.
fn module_boundaries_section(markdown: &str) -> String {
    let mut out = String::new();
    let mut inside = false;
    for line in markdown.lines() {
        let trimmed = line.trim_start();
        if trimmed.starts_with("## ") {
            if inside {
                break;
            }
            let heading = trimmed.trim_start_matches('#').trim();
            if heading.eq_ignore_ascii_case("Module Boundaries") {
                inside = true;
            }
            continue;
        }
        if inside {
            out.push_str(line);
            out.push('\n');
        }
    }
    assert!(inside, "F91: ARCHITECTURE.md has no '## Module Boundaries' section");
    out
}

// ── resolving a named path ──────────────────────────────────────────────────

/// A trailing `/` is not part of a path.
fn normalize(token: &str) -> String {
    token.trim().trim_end_matches('/').to_string()
}

/// Does the token look like a path at all?
///
/// A bare word (`foundation`, `api_client`) is a **name**, not a path, and a
/// name is checked by the column rules below rather than by resolution. A
/// token with a `/` in it, or one that ends in `.rs`, is a path.
fn is_path_like(token: &str) -> bool {
    token.contains('/') || token.ends_with(".rs")
}

fn exists_at(base: &Path, token: &str) -> bool {
    let normalized = normalize(token);
    if normalized.is_empty() {
        return false;
    }
    base.join(&normalized).exists()
}

/// The strict rule: the path must be **inside this crate's `src/`**.
fn resolves_under_src(token: &str) -> bool {
    exists_at(&src_root(), token)
}

/// The permissive rule, used for prose that may legitimately name a path
/// outside the crate (the daemon's engine wiring, a sibling crate). A path
/// that resolves nowhere at all is a phantom whoever named it.
fn resolves_somewhere(token: &str) -> bool {
    resolves_under_src(token) || exists_at(&crate_root(), token) || exists_at(&repo_root(), token)
}

// ── walking the tree ───────────────────────────────────────────────────────

/// Every directory under `src/`, as a `/`-separated path relative to `src/`.
fn directories_under(root: &Path) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    let mut stack: Vec<PathBuf> = vec![root.to_path_buf()];
    while let Some(dir) = stack.pop() {
        let Ok(entries) = std::fs::read_dir(&dir) else {
            continue;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            if !path.is_dir() {
                continue;
            }
            let relative = path
                .strip_prefix(root)
                .map(|p| p.to_string_lossy().replace('\\', "/"))
                .unwrap_or_default();
            if !relative.is_empty() {
                out.push(relative);
            }
            stack.push(path);
        }
    }
    out.sort();
    out
}

/// The immediate subdirectories of `src/` — the crate's top-level modules.
fn top_level_directories_under_src() -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    let Ok(entries) = std::fs::read_dir(src_root()) else {
        return out;
    };
    for entry in entries.flatten() {
        if entry.path().is_dir() {
            out.push(entry.file_name().to_string_lossy().to_string());
        }
    }
    out.sort();
    out
}

// ── the four tests ──────────────────────────────────────────────────────────

/// **THE red-proof.** Every path the module map names must exist.
///
/// Two rules, both mechanical:
///
/// * **Rule A — the tables.** A column headed `Module` or `Path` names a
///   module or a directory **of this crate**, so it must resolve under
///   `src/`. This is the strict rule, and it is the one that was red against
///   the map as it stood: five of its ten rows named paths that do not
///   exist — `foundation`, `routing`, `inbound`, `outbound`, `session`.
/// * **Rule B — the prose.** Every path-shaped token anywhere in the
///   document must resolve *somewhere*: under `src/`, at the crate root, or
///   in the repository. A prose path that names a file which has been moved
///   or renamed is drift, and the guard is the only thing that notices.
#[test]
fn every_path_named_in_the_module_map_exists() {
    let path = module_map_path();
    let markdown = read(&path);

    let mut problems: Vec<String> = Vec::new();

    for table in tables(&markdown) {
        for (index, cell_header) in table.header.iter().enumerate() {
            let header = cell_header.trim().to_lowercase();
            if header != "module" && header != "path" {
                continue;
            }
            for row in &table.rows {
                let Some(cell) = row.get(index) else {
                    continue;
                };
                for token in code_spans(cell) {
                    if !resolves_under_src(&token) {
                        problems.push(format!(
                            "the `{header}` column names `{token}`, which does not exist under src/"
                        ));
                    }
                }
            }
        }
    }

    for token in document_code_spans(&markdown) {
        if !is_path_like(&token) {
            continue;
        }
        if !resolves_somewhere(&token) {
            problems.push(format!("`{token}` does not exist"));
        }
    }

    assert!(
        problems.is_empty(),
        "F91: the module map names paths that do not exist:\n{}",
        problems.join("\n")
    );
}

/// The other direction: no module directory may escape the map.
///
/// A map that only fails one way is a map that can still under-report, and
/// the omission is what a reader suffers from — they look for a module the
/// map never mentions and cannot tell whether it does not exist or was
/// forgotten.
#[test]
fn every_module_directory_in_the_tree_is_named_in_the_module_map() {
    let path = module_map_path();
    let markdown = read(&path);
    let tokens: Vec<String> = document_code_spans(&markdown).iter().map(|t| normalize(t)).collect();

    let mut missing: Vec<String> = Vec::new();
    for directory in directories_under(&src_root()) {
        let named = tokens
            .iter()
            .any(|token| *token == directory || token.starts_with(&format!("{directory}/")));
        if !named {
            missing.push(directory);
        }
    }

    assert!(missing.is_empty(), "F91: the module map does not name these directories: {missing:?}");
}

/// The tree → the map, for files: every top-level production module is named.
///
/// A top-level module is named by its **bare file name** (`api_client.rs`),
/// not by a path that ends in the same name — `transport/reality.rs` and the
/// crate root's `reality.rs` are two different modules, and the map has to
/// say which is which.
#[test]
fn every_top_level_production_module_is_named_in_the_module_map() {
    let path = module_map_path();
    let markdown = read(&path);
    let spans = document_code_spans(&markdown);

    let mut missing: Vec<String> = Vec::new();
    let Ok(entries) = std::fs::read_dir(src_root()) else {
        panic!("F91: src/ must be readable");
    };
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        if !name.ends_with(".rs") || name.ends_with("_test.rs") {
            continue;
        }
        if !spans.contains(&name) {
            missing.push(name);
        }
    }
    missing.sort();

    assert!(
        missing.is_empty(),
        "F91: the module map does not name these top-level modules: {missing:?}"
    );
}

/// Every `.rs` file under `src/`, minus the ones that are not modules of the
/// crate:
///
/// * `mod.rs` — a directory's **root**, named by the directory's own row.
/// * `*_test.rs` — a unit-test file, named by its own section (§4) when it
///   is not compiled, and by nothing when it is.
///
/// `main.rs` is **not** exempt: it is the crate's binary root, and the map
/// names it.
fn production_files_under(root: &Path) -> Vec<PathBuf> {
    let mut out = Vec::new();
    let mut stack = vec![root.to_path_buf()];
    while let Some(dir) = stack.pop() {
        let Ok(entries) = std::fs::read_dir(&dir) else {
            continue;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                stack.push(path);
                continue;
            }
            let name = path.file_name().unwrap_or_default().to_string_lossy().to_string();
            if !name.ends_with(".rs") || name == "mod.rs" || name.ends_with("_test.rs") {
                continue;
            }
            out.push(path);
        }
    }
    out.sort();
    out
}

/// **The tree → the map, for the files INSIDE a module directory.**
///
/// The two rules above cover the crate root's files and the module
/// **directories**. ⚠️ **Neither covers a file that lives inside a
/// directory** — and that is where the transports, the engine and the
/// protocol all live.
///
/// ⇒ A new module in a named directory could be added, and the map would
/// say nothing about it: the reader looks for it in the map and cannot tell
/// whether it does not exist or was forgotten. That is the same class of
/// drift F91 exists to end, one level down.
///
/// **This is the rule that names `transport/wg_frame.rs`** — the RFC-046
/// §3.2 framing codec. Before it, the map's `transport` row named
/// `transport/wg_buffer.rs` and not the codec beside it.
#[test]
fn every_module_file_in_the_tree_is_named_in_the_module_map() {
    let path = module_map_path();
    let markdown = read(&path);
    let spans: Vec<String> = document_code_spans(&markdown).iter().map(|s| normalize(s)).collect();

    let mut missing: Vec<String> = Vec::new();
    for file in production_files_under(&src_root()) {
        let relative =
            file.strip_prefix(src_root()).unwrap_or(&file).to_string_lossy().replace('\\', "/");
        let named =
            spans.iter().any(|span| *span == relative || span.ends_with(&format!("/{relative}")));
        if !named {
            missing.push(relative);
        }
    }
    missing.sort();

    assert!(missing.is_empty(), "F91: the module map does not name these files: {missing:?}");
}

/// **The second document.** `ARCHITECTURE.md`'s Module Boundaries section
/// names no path that does not exist, and names every top-level module
/// directory the crate has.
///
/// This is the check that pins the document which was left broken when the
/// first one was fixed: the same five phantom module names appeared in both,
/// so fixing only the map would have left the architecture still describing a
/// crate that does not exist.
#[test]
fn the_architecture_module_boundaries_name_no_phantom_module() {
    let path = architecture_path();
    let markdown = read(&path);
    let section = module_boundaries_section(&markdown);
    let spans = document_code_spans(&section);

    let mut phantom: Vec<String> = Vec::new();
    for token in &spans {
        if !is_path_like(token) {
            continue;
        }
        if !resolves_somewhere(token) {
            phantom.push(token.clone());
        }
    }
    assert!(
        phantom.is_empty(),
        "F91: ARCHITECTURE.md's Module Boundaries names paths that do not exist: {phantom:?}"
    );

    let mut unnamed: Vec<String> = Vec::new();
    for directory in top_level_directories_under_src() {
        if !spans.iter().any(|token| normalize(token) == directory) {
            unnamed.push(directory);
        }
    }
    assert!(
        unnamed.is_empty(),
        "F91: ARCHITECTURE.md's Module Boundaries does not name: {unnamed:?}"
    );
}
