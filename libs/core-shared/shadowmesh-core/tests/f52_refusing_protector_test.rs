//! F52 — row 6 of the amended gate (RFC-047 §3.10): a protector that
//! **refuses** the off-tunnel leg's offer closes the seam, the leg cannot be
//! taken, and the figure is `None` — never `0`.
//!
//! This is the platform saying "no": `protect()` is called and answers
//! `false`. Android reaches this state for real (a denylisted or refused
//! uid), so it is a shipped configuration, not a hypothetical.
//!
//! ## Why this row is in its OWN file
//!
//! The same reasoning the ruling gives for row 8's file:
//! `register_socket_protector` writes a process-wide `OnceLock`
//! (`lib.rs`) and **ignores a second registration**. This file's process
//! therefore registers **only** the refusing protector, so every test here
//! observes exactly the configuration it names — under both `cargo nextest`
//! (one process per test) and `cargo test` (one process per binary, and
//! this file is one binary).
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

/// A protector that always refuses: the platform declines to exclude a
/// socket from the tunnel.
struct RefusingProtector;

impl shadowmesh_core::SocketProtector for RefusingProtector {
    fn protect(&self, _fd: i32) -> bool {
        false
    }
}

/// **Row 6 (RFC-047 §3.6 row 6 / §3.10)** — a protector that returns
/// `false` ⇒ the off leg cannot be taken ⇒ `None`, never `0`. Fail-closed at
/// the seam. (L3 measured this end-to-end: a refusing protector ⇒ `None`.)
#[test]
fn the_off_tunnel_leg_is_refused_when_the_platform_refuses_to_protect_it() {
    shadowmesh_core::register_socket_protector(Box::new(RefusingProtector));

    // TEST-NET-1 (RFC 5737): a documentation address. Nothing is sent to it —
    // the socket is opened and dropped.
    let addr: SocketAddr = "192.0.2.1:443".parse().expect("address");

    // The off leg CANNOT be taken: the offer is refused, and the refusal is
    // fail-closed (`library rule`: an unprotected socket on this leg would
    // measure the tunnel again, and the difference would be zero by
    // construction).
    let refused =
        ProbeSocket::open(addr, true).err().expect("the refusing platform must close the seam");
    let is_the_seam_refusal = matches!(refused, shadowmesh_core::ShadowMeshError::IoError(_));
    let says_refused = format!("{refused}").contains("refused");
    assert!(
        is_the_seam_refusal && says_refused,
        "a refused offer must fail closed with the seam's own refusal, not a protocol \
         or parsing error; got {refused:?}"
    );

    // The over leg is untouched by the refusal: the protector is not consulted
    // for a socket that is meant to stay inside the tunnel.
    ProbeSocket::open(addr, false).expect("the over-tunnel leg does not need the seam");

    // And the figure for such a configuration is `None`, never `0`. The
    // refused leg leaves the off leg short — short enough to break the
    // two-sample floor — and a short leg yields no figure at all.
    let absent = TunnelPathCost::from_legs(&[110, 110, 110], &[]);
    assert!(
        absent.is_none(),
        "the off leg the platform refused leaves no figure — a refusal may never be \
         read as 'the tunnel cost nothing'"
    );
    assert_ne!(
        absent,
        Some(TunnelPathCost { tunnel_path_cost_ms: 0, samples_per_leg: 3 }),
        "a refused leg must never be reported as a zero path cost"
    );

    // One surviving off-tunnel sample is still a refusal-shaped hole: the
    // floor is per leg, and one is below it.
    let short = TunnelPathCost::from_legs(&[110, 110, 110], &[45]);
    assert!(
        short.is_none(),
        "one surviving sample where the platform refused the rest is not a figure"
    );
}

/// The zero-figure negative control — the figure that would LIE here is
/// named, so the assertion's failure message shows the exact shape the
/// falsified standard produced (L3's measurement: `Some(0)` with a liar
/// protector; this row is its sibling with one that refuses).
#[test]
fn the_zero_figure_this_configuration_must_never_produce_is_hers_and_named() {
    let refusing_figure = TunnelPathCost { tunnel_path_cost_ms: 0, samples_per_leg: 2 };

    let refused = TunnelPathCost::from_legs(&[110, 110], &[]);
    assert!(
        refused.is_none() && refused != Some(refusing_figure),
        "a refusing platform produces figures by taking legs, and it cannot take the off \
         leg — so `Some(0)` is a lie this configuration must not be able to produce"
    );
}

/// The registration this file performs must be homogeneous: the second
/// helper test above runs in the SAME process (under `cargo test`, one per
/// binary) and must tolerate the OnceLock holding the refusing protector —
/// it asserts nothing about a second registration — so both rows keep their
/// one configuration. (A marker test that keeps this file to the
/// refuse-only contract, asserted by construction: this file registers
/// `RefusingProtector` and nothing else.)
#[test]
fn this_process_registers_only_a_refusing_protector() {
    let refused = ProbeSocket::open("192.0.2.1:443".parse::<SocketAddr>().expect("address"), true);
    assert!(
        refused.is_err(),
        "this file's process registered a refusing protector, so the seam must stay closed \
         — if this opens, a second registration replaced it, and the file is no longer \
         observing its own configuration"
    );
}
