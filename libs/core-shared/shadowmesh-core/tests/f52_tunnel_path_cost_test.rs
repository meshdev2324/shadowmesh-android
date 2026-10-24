//! F52: the replacement metric — a signed differential of the **same**
//! endpoint, over the tunnel minus off it.
//!
//! RFC-047 §3 ruled the old latency figure **VOID** (it measured the control
//! plane, not the tunnel) and ruled the replacement's shape: a differential,
//! on demand, **both legs in one call**, a floor of two surviving samples per
//! leg, and **`None` never `0`**.
//!
//! The type under test is [`TunnelPathCost`]; the method is
//! [`NetworkDetector::measure_tunnel_path_cost`]. Neither is on the wire:
//! `NetworkReport` gains **no** field for it, so the two figures can never be
//! read as one, and no binding has to be regenerated to adopt it.
//!
//! ## What this gate may say — and what it may NOT (RFC-047 §3.10)
//!
//! > *"The figure is a **differential of two sockets** to the same endpoint,
//! > one of which was **offered** to the platform's exclusion seam. The core
//! > proves the offering; the **exclusion** — and therefore the claim that
//! > the two legs are different paths — is certified on the device under
//! > **F52-D1** (RFC-047 §3.8.3), and is not provable in core."*
//!
//! What this file must NOT claim — in a name, an assertion or a comment:
//!
//! * ❌ *"the two legs are different paths"*;
//! * ❌ *"the tunnel was up"* (the `Connected` gate is the app's own belief —
//!   `vpn_manager.rs`);
//! * ❌ *"the protector excluded the socket"* (a `protect()` call is a
//!   **request**, and a protector may answer it with a lie);
//! * ❌ *"the figure is the tunnel's cost"* (that is D1's claim, and D1 is
//!   unobserved).

use shadowmesh_core::network::detector::{NetworkDetector, TunnelPathCost};
use shadowmesh_core::network::tunnel_path::{ProbeSocket, TunnelPathLegs, TunnelPathProbe};
use shadowmesh_core::{
    ApiClient, ConnectionStatus, NetworkReport, NetworkType, ShadowMeshError, UserSettings,
    VPNManager,
};
use std::net::SocketAddr;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;

/// A probe that answers with fixed samples, so the semantics can be proved
/// without a network.
struct FakeProbe {
    /// `(milliseconds, how many samples)` for the over-tunnel leg.
    over: Option<(u32, usize)>,
    /// `(milliseconds, how many samples)` for the off-tunnel leg.
    off: Option<(u32, usize)>,
    /// When set, the measurement fails outright.
    fail: bool,
    /// How many times the probe was asked for legs.
    calls: AtomicUsize,
}

impl FakeProbe {
    fn new(over: Option<(u32, usize)>, off: Option<(u32, usize)>) -> Arc<Self> {
        Arc::new(Self { over, off, fail: false, calls: AtomicUsize::new(0) })
    }

    fn failing() -> Arc<Self> {
        Arc::new(Self { over: None, off: None, fail: true, calls: AtomicUsize::new(0) })
    }
}

#[async_trait::async_trait]
impl TunnelPathProbe for FakeProbe {
    async fn sample_legs(&self, samples: usize) -> Result<TunnelPathLegs, ShadowMeshError> {
        self.calls.fetch_add(1, Ordering::SeqCst);
        if self.fail {
            return Err(ShadowMeshError::ConnectionFailed);
        }
        let mut legs = TunnelPathLegs::default();
        if let Some((ms, count)) = self.over {
            legs.over_tunnel_ms = vec![ms; count.min(samples)];
        }
        if let Some((ms, count)) = self.off {
            legs.off_tunnel_ms = vec![ms; count.min(samples)];
        }
        Ok(legs)
    }
}

/// A detector whose tunnel state is fixed and whose probe is supplied.
fn detector(status: ConnectionStatus, probe: Arc<dyn TunnelPathProbe>) -> NetworkDetector {
    let client = Arc::new(ApiClient::new("https://probe.invalid".to_string()).expect("client"));
    let manager = Arc::new(VPNManager::new(UserSettings::default()));
    manager.set_status(status);
    NetworkDetector::with_path_probe(client, Some(manager), probe)
}

/// The first red-proof: no tunnel, no figure.
///
/// The over-tunnel leg is meaningless with the tunnel down, and a figure
/// produced anyway would be a number nobody measured.
#[tokio::test]
async fn tunnel_path_cost_is_none_when_the_tunnel_is_down() {
    let detector =
        detector(ConnectionStatus::Disconnected, FakeProbe::new(Some((110, 5)), Some((45, 5))));

    assert!(
        detector.measure_tunnel_path_cost().await.is_none(),
        "with the tunnel down there is no over-tunnel leg, so there is no figure"
    );
}

/// The arithmetic: `over − off`, signed, and not clamped.
#[tokio::test]
async fn tunnel_path_cost_is_the_signed_difference_of_the_two_legs() {
    let detector =
        detector(ConnectionStatus::Connected, FakeProbe::new(Some((110, 5)), Some((45, 5))));

    let cost = detector.measure_tunnel_path_cost().await.expect("a figure");

    assert_eq!(cost.tunnel_path_cost_ms, 110 - 45);
    assert_eq!(cost.samples_per_leg, 5, "both legs carried five samples");
}

/// A negative differential is a **measurement**, not an error and not a zero.
///
/// Noise dominated the signal. Clamping it to zero would turn "the tunnel was
/// marginally cheaper this time" into "the tunnel is free", which is the
/// honest-zero hazard this whole metric was ruled to avoid.
#[tokio::test]
async fn a_negative_differential_is_a_measurement_and_is_not_clamped() {
    let detector =
        detector(ConnectionStatus::Connected, FakeProbe::new(Some((10, 5)), Some((90, 5))));

    let cost = detector.measure_tunnel_path_cost().await.expect("a figure");

    assert_eq!(cost.tunnel_path_cost_ms, 10 - 90);
    assert!(cost.tunnel_path_cost_ms < 0, "a negative path cost must survive the arithmetic");
}

/// The floor applies to **both** legs.
///
/// A one-sample leg against a five-sample leg is not a difference of
/// anything.
#[tokio::test]
async fn tunnel_path_cost_is_none_when_either_leg_has_fewer_than_two_samples() {
    let short_off =
        detector(ConnectionStatus::Connected, FakeProbe::new(Some((110, 5)), Some((45, 1))));
    assert!(
        short_off.measure_tunnel_path_cost().await.is_none(),
        "a one-sample off-tunnel leg against a five-sample over-tunnel leg yields no figure"
    );

    let short_over =
        detector(ConnectionStatus::Connected, FakeProbe::new(Some((110, 1)), Some((45, 5))));
    assert!(
        short_over.measure_tunnel_path_cost().await.is_none(),
        "a one-sample over-tunnel leg against a five-sample off-tunnel leg yields no figure"
    );
}

/// **The invariant**: a failed leg yields `None`, never `0`.
///
/// A zero here would be indistinguishable from "the tunnel path costs
/// nothing", and a user would read it as a measurement of a tunnel that was
/// never measured.
#[tokio::test]
async fn a_failed_leg_yields_none_and_never_zero() {
    let missing_off = detector(ConnectionStatus::Connected, FakeProbe::new(Some((110, 5)), None));
    let absent = missing_off.measure_tunnel_path_cost().await;
    assert!(absent.is_none(), "the off-tunnel leg produced no samples at all");
    assert_ne!(
        absent,
        Some(TunnelPathCost { tunnel_path_cost_ms: 0, samples_per_leg: 5 }),
        "a failed leg must never be reported as a zero path cost"
    );

    let failed = detector(ConnectionStatus::Connected, FakeProbe::failing());
    let absent = failed.measure_tunnel_path_cost().await;
    assert!(absent.is_none(), "a failed measurement yields no figure");
    assert_ne!(
        absent,
        Some(TunnelPathCost { tunnel_path_cost_ms: 0, samples_per_leg: 5 }),
        "a failed measurement must never be reported as a zero path cost"
    );
}

/// The two legs are distinguishable, and the figure is theirs.
///
/// The over-tunnel leg and the off-tunnel leg are different measurements; the
/// figure is the difference between them and nothing else.
#[tokio::test]
async fn the_two_legs_are_taken_separately_and_the_figure_is_their_difference() {
    let probe = FakeProbe::new(Some((110, 5)), Some((45, 5)));
    let detector = detector(ConnectionStatus::Connected, probe.clone());

    let cost = detector.measure_tunnel_path_cost().await.expect("a figure");

    assert_eq!(cost.tunnel_path_cost_ms, 110 - 45);
    assert_eq!(
        probe.calls.load(Ordering::SeqCst),
        1,
        "both legs are taken in one call, not one call per leg"
    );
}

/// **Row 7 — the new core-side red-proof (RFC-047 §3.10 / §3.6 row 7)**, the
/// closest thing core now has to the property the old row-4 claim used to
/// assert: a controlled skew injected into **one** leg moves the figure by
/// exactly that amount, **with the sign the arithmetic specifies**.
///
/// The arithmetic is `over − off`, so a skew added to the **off** leg moves
/// the figure by **−(the skew)**: 60 ms added to the off leg moves
/// `110 − 45 = 65` to `110 − 105 = 5`. Nothing is clamped, nothing is
/// absolute-valued, and the movement is exact — a differential that could
/// absorb a skew into a rounded mean would be describing something else.
///
/// ⚠️ This proves the ARITHMETIC hears which leg is which. It still does not
/// prove the exclusion (see the file header): the legs here are a fake
/// probe's numbers, not two real paths.
#[tokio::test]
async fn a_known_skew_on_the_off_tunnel_leg_appears_in_the_figure_with_the_opposite_sign() {
    let baseline =
        detector(ConnectionStatus::Connected, FakeProbe::new(Some((110, 5)), Some((45, 5))));
    let skewed =
        detector(ConnectionStatus::Connected, FakeProbe::new(Some((110, 5)), Some((105, 5))));

    let baseline_figure = baseline.measure_tunnel_path_cost().await.expect("a baseline figure");
    let skewed_figure = skewed.measure_tunnel_path_cost().await.expect("a skewed figure");

    assert_eq!(
        baseline_figure.tunnel_path_cost_ms,
        110 - 45,
        "the unskewed differential is `over − off`"
    );
    assert_eq!(
        skewed_figure.tunnel_path_cost_ms,
        110 - 105,
        "the off leg is SUBTRACTED by the arithmetic, so +60 ms on the off leg only \
         moves the figure by −60"
    );
    assert_eq!(
        baseline_figure.tunnel_path_cost_ms - skewed_figure.tunnel_path_cost_ms,
        60,
        "the skew's footprint is exactly the injected 60 ms"
    );
}

/// A protector that counts how often it was asked to protect a socket.
///
/// ⚠️ **It is a liar by construction** (RFC-047 §3.10): `protect` returns
/// `true` unconditionally and excludes nothing. That is what makes it
/// suitable for the *offer* test above — the test must not depend on the
/// protector's honesty — and useless for any *exclusion* claim.
struct CountingProtector {
    calls: Arc<AtomicUsize>,
}

impl shadowmesh_core::SocketProtector for CountingProtector {
    fn protect(&self, _fd: i32) -> bool {
        self.calls.fetch_add(1, Ordering::SeqCst);
        true
    }
}

/// **Row 4, amended (RFC-047 §3.10)**: the off-tunnel leg must **offer** its
/// socket to the platform's exclusion seam, and the over-tunnel leg must not.
///
/// ⚠️ **What this test is, and what it is not.** It establishes the
/// **request**: the off leg's socket is handed to the registered
/// [`SocketProtector`] and the over leg's is not — the invocation is what is
/// asserted, not merely that two numbers differ.
///
/// ⚠️ **It is not the red-proof for the whole method, and that sentence is
/// withdrawn (RFC-047 §3.6 as amended).** L3's falsification measured the
/// configuration where a protector says `true` and excludes nothing, and the
/// figure came out `Some(tunnel_path_cost_ms = 0)` — *the tunnel costs 0 ms*
/// — while the off leg traversed the tunnel. **A liar PASSES this test.**
/// That is not a defect in this test; it is what any core-side test can see:
/// the protector accepts or rejects the offer, and only the platform's
/// behavior on the ACCEPT tells you whether the socket took the off-tunnel
/// path.
///
/// ⚠️ **And this test's own protector is itself a liar by construction.**
/// `CountingProtector::protect` returns `true` unconditionally — it "protects"
/// nothing. So the green below proves only that the OFFER was made once per
/// leg in the right directions. The EXCLUSION — and therefore the claim that
/// the two legs are different paths — is certified on the device under
/// **F52-D1** (RFC-047 §3.8.3), and is not provable in core.
#[test]
fn the_off_tunnel_leg_offers_its_socket_to_the_protector_and_the_over_tunnel_leg_does_not() {
    let calls = Arc::new(AtomicUsize::new(0));
    shadowmesh_core::register_socket_protector(Box::new(CountingProtector {
        calls: calls.clone(),
    }));

    // TEST-NET-1 (RFC 5737): a documentation address. Nothing is sent to it —
    // the socket is opened and dropped.
    let addr: SocketAddr = "192.0.2.1:443".parse().expect("address");

    let off = ProbeSocket::open(addr, true).expect("the off-tunnel leg's socket");
    assert!(off.as_raw_fd() >= 0, "the off-tunnel leg must have a socket");

    let after_off = calls.load(Ordering::SeqCst);
    assert!(
        after_off > 0,
        "the off-tunnel leg must exclude its socket from the tunnel, or it measures the tunnel again"
    );

    let over = ProbeSocket::open(addr, false).expect("the over-tunnel leg's socket");
    assert!(over.as_raw_fd() >= 0, "the over-tunnel leg must have a socket");

    assert_eq!(
        calls.load(Ordering::SeqCst),
        after_off,
        "the over-tunnel leg must NOT protect its socket: a protected socket would take the \
         off-tunnel path and the two legs would measure the same thing"
    );
}

/// The control-plane figure and the tunnel-path cost are **separate
/// surfaces**.
///
/// The literal below is the pin: `NetworkReport` gains no field for the
/// tunnel-path cost, so the two figures can never be added, subtracted or
/// displayed under one label. Add a field, and this file stops compiling.
#[test]
fn the_control_plane_figure_and_the_tunnel_path_cost_are_separate_surfaces() {
    let report = NetworkReport {
        is_connected: true,
        network_type: NetworkType::WiFi,
        latency_ms: Some(25),
        jitter_ms: Some(2),
        packet_loss: Some(0.0),
        speed_test: None,
        server_report: None,
        captive_portal: None,
        dpi_detected: None,
        dpi_detected_by_server: false,
        is_protected: false,
    };

    assert_eq!(report.latency_ms, Some(25), "the control-plane figure is unchanged");
    assert!(
        report.latency_ms != Some(0),
        "the control-plane figure keeps its own meaning; it is not the path cost"
    );
}
