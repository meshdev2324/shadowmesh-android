use crate::api_client::{ApiClient, ServerNetworkReport};
use crate::network::tunnel_path::{HttpTunnelPathProbe, TunnelPathProbe};
use crate::speed_test::{SpeedTest, SpeedTestResult};
use crate::ShadowMeshError;
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use std::time::Instant;

/// Represents the type of network connection currently active.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum NetworkType {
    /// The network type could not be determined.
    Unknown,
    /// Connected via Wi-Fi.
    WiFi,
    /// Connected via a cellular network (e.g., LTE, 5G).
    Cellular,
    /// Connected via a wired Ethernet connection.
    Ethernet,
}

/// A comprehensive report on the current network conditions.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NetworkReport {
    /// Whether the device has internet connectivity to the ShadowMesh API.
    pub is_connected: bool,
    /// The type of network connection.
    pub network_type: NetworkType,
    /// Average round-trip latency in milliseconds.
    ///
    /// `None` when fewer than [`MIN_LATENCY_SAMPLES`] probes of five succeeded.
    /// A mean over fewer samples than that is not a latency figure, and
    /// publishing one would make a degrading link look better rather than
    /// worse, because fewer surviving probes means less averaging. Read this
    /// together with `packet_loss`, which is denominated over all five probes
    /// and is always populated when the link is up at all.
    pub latency_ms: Option<u32>,
    /// Jitter (variation in latency) in milliseconds.
    pub jitter_ms: Option<u32>,
    /// Percentage of packet loss (0.0 to 1.0).
    pub packet_loss: Option<f32>,
    /// Detailed speed test results, if performed.
    pub speed_test: Option<SpeedTestResult>,
    /// Detailed report from the server's perspective.
    pub server_report: Option<ServerNetworkReport>,
    /// Captive portal result: `None` = not checked, `Some(0)` = checked and
    /// clear, `Some(1)` = checked and a portal was found.
    ///
    /// This was a `bool` initialised to `false` and never assigned again, so
    /// the UI rendered "no captive portal" for a check that had never run. A
    /// nullable integer distinguishes the two states; on a censorship tool the
    /// difference between "clear" and "unknown" is the whole point.
    pub captive_portal: Option<u8>,
    /// Local DPI result, same encoding as [NetworkReport::captive_portal].
    /// Core performs no local DPI analysis, so this stays `None`.
    pub dpi_detected: Option<u8>,
    /// Whether [NetworkReport::dpi_detected] reflects the control plane's
    /// heuristic rather than analysis on this device. Reported separately so
    /// the UI can attribute the finding honestly.
    pub dpi_detected_by_server: bool,
    /// Whether the VPN connection is verified "Protected" (Observed IP != Baseline IP).
    pub is_protected: bool,
}

/// The latency statistics derived from one probe run.
///
/// A unit of measurement on its own is not a measurement: a mean over one
/// sample is that sample, reported with the same confidence as a mean over
/// five. Carrying the option rather than a bare number lets the caller say
/// "not enough samples" instead of saying something it cannot support.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct LatencySummary {
    /// Mean round-trip time, or `None` when too few probes succeeded.
    ///
    /// `None` here means "not measured to a standard this report can defend",
    /// not "zero", and not "the scan never ran" -- that is
    /// `NetworkReport::is_connected`. Callers must render the absence rather
    /// than substitute a placeholder: a placeholder sitting next to a
    /// non-zero `packet_loss` is the exact confusion this floor removes.
    avg_ms: Option<u32>,
    /// Mean absolute difference between consecutive probes, or `None` when
    /// there is no consecutive pair to differ.
    jitter_ms: Option<u32>,
}

/// The fewest surviving probes that may be published as a latency figure.
///
/// Two, not one. Below two there is no mean at all -- only a single
/// observation, which is indistinguishable from one lucky probe on a link that
/// is otherwise dropping four packets in five. Two also matches the
/// pre-existing jitter rule, which already needed a consecutive pair, so the
/// two statistics now agree about what counts as enough data instead of
/// disagreeing in the one case where a user is most likely to be misled.
///
/// This is a floor on *reporting*, not on probing: all five probes still run
/// and `packet_loss` still reports the true loss rate over all five. Refusing
/// the mean costs the user one number they could not have trusted, and keeps
/// the one number that is correctly denominated.
const MIN_LATENCY_SAMPLES: usize = 2;

/// Round trips taken on **each** leg of the tunnel-path differential.
///
/// Five, the same budget the control-plane figure spends (`detect()` takes
/// five probes), so the two figures are comparable in *method*: both are
/// means over the survivors of five attempts, and both refuse to publish a
/// mean over fewer than [`MIN_LATENCY_SAMPLES`] survivors.
///
/// The floor is what makes the comparison meaningful: a difference between a
/// one-sample leg and a five-sample leg is not a difference of anything.
const TUNNEL_PATH_SAMPLES: usize = 5;

/// The marginal cost of the tunnel path.
///
/// Two round-trip times to the **same** endpoint, taken with the same method:
/// one routed through the tunnel, one over the underlying network. The
/// difference is the cost of the tunnel path, in milliseconds.
///
/// **Signed.** A negative value is a real measurement — noise dominated the
/// signal — and is not clamped to zero. Zero is not "free" and is not
/// "unmeasured": unmeasured is `None`.
///
/// **This is NOT a tunnel RTT.** The difference includes the node→edge leg,
/// which is the node's uplink, not the tunnel. The name says "path cost" for
/// that reason; naming it `tunnel_rtt_ms` would claim an evidence the
/// measurement does not carry (RFC-043 R4).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TunnelPathCost {
    /// Milliseconds. Signed.
    pub tunnel_path_cost_ms: i32,
    /// Round trips behind each leg's mean.
    ///
    /// The **smaller** of the two legs' surviving sample counts: the number of
    /// round trips that stands behind *both* means. At least
    /// [`MIN_LATENCY_SAMPLES`] on both legs, or there is no figure at all.
    pub samples_per_leg: usize,
}

impl TunnelPathCost {
    /// The signed differential of the two legs' means.
    ///
    /// `None` when either leg has fewer than [`MIN_LATENCY_SAMPLES`]
    /// surviving samples. The floor and the mean are the detector's own — the
    /// same ones the control-plane figure uses — so there is one owner for
    /// "how many samples is enough" and one owner for "what a mean is".
    pub fn from_legs(over_tunnel_ms: &[u32], off_tunnel_ms: &[u32]) -> Option<Self> {
        let over = NetworkDetector::summarise_latency(over_tunnel_ms).avg_ms?;
        let off = NetworkDetector::summarise_latency(off_tunnel_ms).avg_ms?;

        // Widened before it is narrowed: the two means are unsigned
        // milliseconds, and their difference does not fit in an `i32` only if
        // one of them is absurd. `None` rather than a wrapped number.
        let delta = i64::from(over) - i64::from(off);

        Some(Self {
            tunnel_path_cost_ms: i32::try_from(delta).ok()?,
            samples_per_leg: over_tunnel_ms.len().min(off_tunnel_ms.len()),
        })
    }
}

/// A detector for analyzing network connectivity and quality.
pub struct NetworkDetector {
    api_client: Arc<ApiClient>,
    speed_tester: Arc<SpeedTest>,
    vpn_manager: Option<Arc<crate::vpn_manager::VPNManager>>,
    /// How the tunnel-path differential is measured.
    ///
    /// A trait object because it is the seam the measurement is taken through:
    /// the semantics (the floor, the signed difference, `None` rather than a
    /// fabricated zero) must be provable without a network, and a platform
    /// with its own transport must be able to supply its own probe.
    path_probe: Arc<dyn TunnelPathProbe>,
}

impl NetworkDetector {
    /// Creates a new `NetworkDetector` using the provided API client.
    pub fn new(
        api_client: Arc<ApiClient>,
        manager: Option<Arc<crate::vpn_manager::VPNManager>>,
    ) -> Self {
        let speed_tester = Arc::new(SpeedTest::new(api_client.clone()));
        let path_probe = Arc::new(HttpTunnelPathProbe::new(api_client.base_url()));
        Self { api_client, speed_tester, vpn_manager: manager, path_probe }
    }

    /// Creates a detector whose tunnel-path probe is supplied.
    ///
    /// The probe is the seam the differential is measured through, so a test —
    /// or a platform with its own transport — can supply one that does not
    /// need a network.
    pub fn with_path_probe(
        api_client: Arc<ApiClient>,
        manager: Option<Arc<crate::vpn_manager::VPNManager>>,
        path_probe: Arc<dyn TunnelPathProbe>,
    ) -> Self {
        let speed_tester = Arc::new(SpeedTest::new(api_client.clone()));
        Self { api_client, speed_tester, vpn_manager: manager, path_probe }
    }

    /// Performs a network detection scan.
    ///
    /// If `run_speed_test` is true, a full download/upload speed test will be performed
    /// if connectivity is confirmed.
    pub fn detect(&self, run_speed_test: bool) -> Result<NetworkReport, ShadowMeshError> {
        let mut report = Self::unmeasured_report();

        // 1. Connectivity & Latency Check
        //
        // F66: this loop looks like a duplicate of `SpeedTest::measure_latency`
        // in speed_test.rs, and it is deliberately not merged into it. That one
        // aborts on a failed probe; this one tolerates failures and reports the
        // loss separately. See the note on `measure_latency_async` before
        // touching either.
        let iterations = 5;
        let mut latencies = Vec::with_capacity(iterations);
        let mut success_count = 0;

        for _ in 0..iterations {
            let start = Instant::now();
            if self.api_client.speedtest_ping().is_ok() {
                latencies.push(start.elapsed().as_millis() as u32);
                success_count += 1;
            }
        }

        if success_count > 0 {
            report.is_connected = true;
            report.packet_loss = Some((iterations - success_count) as f32 / iterations as f32);
            let summary = Self::summarise_latency(&latencies);
            report.latency_ms = summary.avg_ms;
            report.jitter_ms = summary.jitter_ms;

            // 1.1 Fetch Server Report
            if let Ok(server_report) = self.api_client.detect_network() {
                // The control plane's heuristic, recorded as such. Reporting
                // it as `dpi_detected` implied this device had analysed
                // traffic; it had not.
                report.dpi_detected_by_server = server_report.potential_dpi;
                if server_report.potential_dpi {
                    report.dpi_detected = Some(1);
                }

                // v11.1 Verification Flow: Verify Protected Status
                if let Some(ref manager) = self.vpn_manager {
                    if let Some(baseline) = manager.get_baseline_ip() {
                        if baseline != server_report.client_ip
                            && manager.get_status()
                                == crate::vpn_manager::ConnectionStatus::Connected
                        {
                            report.is_protected = true;
                        }
                    }
                }

                report.server_report = Some(server_report);

                // v5.0 Proactive Telemetry: If DPI detected, log it immediately
                if report.dpi_detected_by_server {
                    let _ = self.api_client.log_security_event(
                        serde_json::json!({
                            "event_type": "DpiDetected",
                            "details": "Network analysis identified characteristic DPI patterns",
                            "success": false
                        })
                        .to_string(),
                    );
                }
            }
        }

        // 2. Speed Test (Optional)
        if run_speed_test && report.is_connected {
            if let Ok(result) = self.speed_tester.run_full_test() {
                report.speed_test = Some(result);
            }
        }

        // 3. Captive portal and 4. local DPI analysis are deliberately NOT
        //    implemented here, and are left at NotChecked.
        //
        //    Both were previously reported as "clear" without ever having been
        //    measured. Detecting a portal requires fetching a known plaintext
        //    HTTP resource and inspecting the response for a redirect, which is
        //    a platform-network operation; local DPI analysis requires packet
        //    inspection this core does not have. Reporting a result we did not
        //    produce is worse than reporting nothing.

        Ok(report)
    }

    /// Measure the marginal cost of the tunnel path.
    ///
    /// Two round-trip times to the **same** endpoint, taken with the same
    /// method: one through the tunnel, one over the underlying network. The
    /// reported quantity is the **signed difference** — the cost of the
    /// tunnel path, in milliseconds.
    ///
    /// Returns `None` when:
    ///
    /// * the tunnel is down (the over-tunnel leg is meaningless without it),
    /// * the underlying network cannot reach the endpoint (the off-tunnel leg
    ///   is meaningless without it),
    /// * the platform cannot exclude a socket from the tunnel, so the
    ///   off-tunnel leg cannot be taken at all, or
    /// * either leg has fewer than [`MIN_LATENCY_SAMPLES`] surviving samples.
    ///
    /// **`None`, never `0`.** Zero is a measurement — the two paths cost the
    /// same — and "unmeasured" is `None`. A failed leg that reported `0` would
    /// be indistinguishable from a tunnel that costs nothing.
    ///
    /// This is **not** the control-plane latency figure
    /// ([`NetworkReport::latency_ms`]), and the two must never be added,
    /// subtracted or displayed under one label. The figure above measures the
    /// control-plane edge; this one measures the tunnel path, and it includes
    /// the node's uplink — which is why it is called a *path cost* and not a
    /// tunnel RTT.
    ///
    /// It is measured **on demand**, never inside [`NetworkDetector::detect`]:
    /// `detect()` runs on the network-change path and must not take two extra
    /// round trips.
    pub async fn measure_tunnel_path_cost(&self) -> Option<TunnelPathCost> {
        // The tunnel must be up. Without a manager the state is unknown, and
        // an unknown state is not an up state.
        let manager = self.vpn_manager.as_ref()?;
        if manager.get_status() != crate::vpn_manager::ConnectionStatus::Connected {
            return None;
        }

        // Both legs, back to back, in this one call.
        let legs = self.path_probe.sample_legs(TUNNEL_PATH_SAMPLES).await.ok()?;

        TunnelPathCost::from_legs(&legs.over_tunnel_ms, &legs.off_tunnel_ms)
    }

    /// A report in which nothing has been measured yet.
    ///
    /// Extracted so the "unknown" starting state is directly testable. The
    /// diagnostic fields that no code path actually populates start as `None`
    /// rather than `false`: a false would be indistinguishable from a clean
    /// result, and the client would render an all-clear for checks that never
    /// ran.
    fn unmeasured_report() -> NetworkReport {
        NetworkReport {
            is_connected: false,
            network_type: NetworkType::Unknown,
            latency_ms: None,
            jitter_ms: None,
            packet_loss: None,
            speed_test: None,
            server_report: None,
            captive_portal: None,
            dpi_detected: None,
            dpi_detected_by_server: false,
            is_protected: false,
        }
    }

    /// Aggregate the successful probe samples into the reportable statistics.
    ///
    /// Every successful probe pushes exactly one sample, so `latencies.len()`
    /// *is* the success count. Dividing by the slice length rather than by a
    /// separately tracked counter keeps one owner for the denominator.
    ///
    /// That single owner is exactly why the floor below has to live here rather
    /// than in the caller. `packet_loss` is computed over `iterations` and is
    /// unaffected by how many probes survived; the mean is computed over the
    /// survivors. Left alone, the two describe one measurement over different
    /// denominators, and the gap between them widens as the link degrades.
    fn summarise_latency(latencies: &[u32]) -> LatencySummary {
        if latencies.len() < MIN_LATENCY_SAMPLES {
            return LatencySummary { avg_ms: None, jitter_ms: None };
        }
        LatencySummary {
            avg_ms: Some(latencies.iter().sum::<u32>() / latencies.len() as u32),
            jitter_ms: Some(Self::calculate_jitter(latencies)),
        }
    }

    fn calculate_jitter(latencies: &[u32]) -> u32 {
        if latencies.len() < 2 {
            return 0;
        }
        let mut jitter = 0;
        for i in 0..latencies.len() - 1 {
            jitter += latencies[i].abs_diff(latencies[i + 1]);
        }
        jitter / (latencies.len() - 1) as u32
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unmeasured_diagnostics_are_none_not_false() {
        // Regression lock. These fields were `bool` initialised to false and
        // never assigned, so the client rendered "no captive portal" and "no
        // DPI" for checks that had never run -- a permanent false all-clear on
        // a censorship tool. If this test fails, a boolean has crept back in.
        let report = NetworkDetector::unmeasured_report();

        assert_eq!(
            report.captive_portal, None,
            "captive portal must be unknown until a probe actually runs"
        );
        assert_eq!(
            report.dpi_detected, None,
            "local DPI analysis is not implemented; it must not claim to be clear"
        );
        assert!(!report.dpi_detected_by_server, "no server heuristic has been consulted yet");
    }

    #[test]
    fn summarise_refactor_reproduces_the_previous_numbers() {
        // Characterisation lock for the extraction of `summarise_latency`. This
        // pins the *pre-fix* behaviour so the refactor cannot have silently
        // changed what `detect()` reports while moving code.
        //
        // The five-sample case still reproduces the pre-extraction numbers
        // exactly (240 / 5 = 48, jitter 40 / 4 = 10), so the extraction itself
        // is proven inert. The single-sample case has since changed under F68:
        // it was Some(40) and is now None. This test records both facts -- the
        // arithmetic did not move, the reporting floor was added deliberately.
        let full = NetworkDetector::summarise_latency(&[40, 50, 60, 50, 40]);
        assert_eq!(full.avg_ms, Some(48)); // 240 / 5, as `sum / success_count` gave
        assert_eq!(full.jitter_ms, Some(10)); // |40-50|+|50-60|+|60-50|+|50-40| = 40 / 4

        let single = NetworkDetector::summarise_latency(&[40]);
        assert_eq!(
            single.avg_ms, None,
            "F68: one sample is no longer published as a mean. Before the floor this was Some(40) -- \
             a single probe reported at full value beside packet_loss = 0.8"
        );
        assert_eq!(single.jitter_ms, None);

        assert_eq!(
            NetworkDetector::summarise_latency(&[]),
            LatencySummary { avg_ms: None, jitter_ms: None }
        );
    }

    #[test]
    fn one_success_of_five_must_not_report_a_latency() {
        // F68 red-proof. `detect()` pushes only successful samples into
        // `latencies` and divided by `success_count`, so a single surviving
        // sample was reported at full value beside `packet_loss = 0.8`. A user
        // saw a normal-looking latency next to 80% loss, and the number carried
        // no sign it had survived on one sample.
        //
        // The direction of that error is the point: as the tunnel degrades,
        // fewer samples survive to be averaged, so the metric looks *better*.
        // Same class as F57's inverted integrity check -- a figure that grows
        // more convincing as it fails.
        let one_of_five = NetworkDetector::summarise_latency(&[40]);

        assert_eq!(
            one_of_five.avg_ms, None,
            "one success of five reported {} ms at full value; a mean over one sample is not a latency measurement",
            one_of_five.avg_ms.unwrap_or_default()
        );
        assert_eq!(one_of_five.jitter_ms, None);

        // The full-sample case must be unaffected: this is a floor, not a
        // change of arithmetic.
        let all_five = NetworkDetector::summarise_latency(&[40, 50, 60, 50, 40]);
        assert_eq!(all_five.avg_ms, Some(48));
        assert_eq!(all_five.jitter_ms, Some(10));

        // And the boundary: two samples is the floor, so it still reports.
        assert_eq!(NetworkDetector::summarise_latency(&[40, 50]).avg_ms, Some(45));
    }

    #[test]
    fn test_calculate_jitter() {
        let latencies = vec![10, 12, 10, 12];
        // differences: |10-12|=2, |12-10|=2, |10-12|=2. Sum=6. Count=3. Avg=2.
        assert_eq!(NetworkDetector::calculate_jitter(&latencies), 2);

        let latencies = vec![10, 10, 10];
        assert_eq!(NetworkDetector::calculate_jitter(&latencies), 0);

        let latencies = vec![10];
        assert_eq!(NetworkDetector::calculate_jitter(&latencies), 0);
    }
}
