//! F52 — row 8 of the amended gate (RFC-047 §3.10), in its OWN file:
//! a process which registered **no protector at all** must make the
//! off-tunnel leg untakable, and the figure `None` — never `0`.
//!
//! ## 🔴 The process hazard, named (RFC-047 §3.10)
//!
//! `register_socket_protector` writes a process-wide `OnceLock` and **ignores
//! a second registration** — so a row that must observe "no protector" can
//! only observe it **in a process where nothing registered one**.
//! `cargo nextest` runs one process per test (safe). `cargo test` runs one
//! process for the whole test binary (order-dependent ⇒ flaky) — **this file
//! is its own binary, and it registers nothing**, so the row observes its
//! named configuration under both harnesses. ⚠️ **Do not move this test into
//! a file whose other tests call `register_socket_protector`** — under
//! `cargo test` that is exactly the ordering hazard the ruling named.
//!
//! ## What this file must NOT claim (RFC-047 §3.10)
//!
//! * ❌ *"the two legs are different paths"* — certified on-device, under
//!   F52-D1 (RFC-047 §3.8.3), not here;
//! * ❌ *"the tunnel was up"* — the `Connected` gate is the app's own belief;
//! * ❌ *"the figure is the tunnel's cost"* — D1's claim, and D1 is
//!   unobserved.

use shadowmesh_core::network::detector::TunnelPathCost;
use shadowmesh_core::network::tunnel_path::ProbeSocket;
use std::net::SocketAddr;

/// **Row 8 (RFC-047 §3.6 row 8 / §3.10) — no protector at all:** the
/// off-tunnel leg cannot be taken. The seam is closed not by a refusal but
/// by the ABSENCE of anyone to make the offer to, and the fail-closed rule
/// is the same: an unprotected socket on this leg would measure the tunnel
/// again, and a difference computed from two measurements of one path would
/// be zero by construction. This row is the Linux/desktop/CI case — the
/// reason the metric is unavailable on two of three platforms.
#[test]
fn the_off_tunnel_leg_is_refused_when_no_protector_is_registered() {
    // TEST-NET-1 (RFC 5737): a documentation address. Nothing is sent to it —
    // the socket is opened and dropped.
    let addr: SocketAddr = "192.0.2.1:443".parse().expect("address");

    let refused = ProbeSocket::open(addr, true).err().expect("no protector must close the seam");
    let says_refused = format!("{refused}").contains("refused");
    assert!(
        says_refused,
        "the closed seam is the OFF-TUNNEL leg's failure to place its socket with the \
         platform's exclusion seam, not a parse or a protocol error; got {refused:?}"
    );

    // The over leg needs no seam, and stays open: only the OFF-TUNNEL leg is
    // refused by the absence.
    ProbeSocket::open(addr, false).expect("the over-tunnel leg does not need the seam");
}

/// And the figure for that process is `None`, never `0`.
///
/// The zero that would lie here: `Some(tunnel_path_cost_ms = 0)` reads as
/// "the tunnel cost nothing", which is the exact reading a *fabricated*
/// figure produces when the measurement behind it never happened.
#[test]
fn a_figure_from_a_protectorless_process_is_none_never_zero() {
    let absent = TunnelPathCost::from_legs(&[110, 110, 110], &[]);
    assert!(
        absent.is_none(),
        "a process with no protector cannot take the off-tunnel leg, so it has no figure \
         — the absence must remain `None`, never a fabricated zero"
    );
    assert_ne!(
        absent,
        Some(TunnelPathCost { tunnel_path_cost_ms: 0, samples_per_leg: 3 }),
        "a protectorless process must never be reported as a zero path cost"
    );

    // The over leg ALONE is not a figure either: the floor is per leg, and a
    // differential with one leg missing is not "half a figure".
    let over_only = TunnelPathCost::from_legs(&[110, 110, 110, 110], &[]);
    assert!(over_only.is_none(), "the over leg alone is not a figure");
}
