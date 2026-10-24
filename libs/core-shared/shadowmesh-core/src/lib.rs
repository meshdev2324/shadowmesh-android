//! ShadowMesh Client Core
//!
//! A high-performance, security-focused VPN library written in Rust.
//! It serves as the cross-platform engine for both Mobile (Android/iOS) and Desktop clients.
//!
//! ### Full-Stack Control Plane Mapping (`server-rust`)
//! - [`ApiClient::activate_device`](crate::api_client::ApiClient::activate_device) -> `POST /api/v1/auth/activate`
//! - [`ApiClient::send_heartbeat`](crate::api_client::ApiClient::send_heartbeat) -> `POST /api/v1/session/heartbeat`
//! - [`ApiClient::fetch_nodes`](crate::api_client::ApiClient::fetch_nodes) -> `GET /api/v1/nodes`
//! - [`ApiClient::report_network`](crate::api_client::ApiClient::report_network) -> `POST /api/v1/telemetry/report`

#![allow(clippy::empty_line_after_doc_comments)]
#![allow(clippy::double_must_use)]
// uniffi-generated FFI scaffolding returns large Result enums by design.
#![allow(clippy::result_large_err)]
#![allow(missing_docs)]
#![forbid(unsafe_op_in_unsafe_fn)]

use std::sync::{Arc, OnceLock};
use zeroize::Zeroize;

// --- Global Allocator Configuration ---
#[cfg(feature = "mimalloc")]
#[global_allocator]
static GLOBAL: mimalloc::MiMalloc = mimalloc::MiMalloc;
// ---------------------------------------

/// Anti-tampering and signature verification components.
pub mod anti_tamper;
/// Control plane API and proto definitions.
pub mod api;
/// API client for interacting with the ShadowMesh control plane.
pub mod api_client;
/// User and system configuration management.
pub mod config;
/// Desktop-specific orchestration logic.
pub mod desktop_controller;
/// DNS resolution and routing.
pub mod dns;
/// Engine orchestration and lifecycle.
pub mod engine;
/// Packet fragmentation and DPI evasion logic.
pub mod fragment;
/// Sovereign Identity primitives and Enclave integration.
pub mod identity;
/// System-level kill-switch management.
pub mod kill_switch;
/// Networking utilities and detector.
pub mod network;
/// Secure in-memory node caching.
pub mod node_cache;
/// OS-specific optimizations and abstractions.
pub mod platform;
/// Proof-of-Work (PoW) solver for adaptive friction.
pub mod pow;
/// Internal protocol definitions.
pub mod protocol;
/// Typed protocol registry (RFC-012 G4) — the single protocol table the
/// engine validates and dispatches through.
pub mod protocol_registry;
/// REALITY protocol and cryptographic pairing routines.
pub mod reality;
/// Smart routing engine.
pub mod router;
/// Secure forensic-resistant logging.
pub mod security_logger;
/// Smart routing and node selection algorithm.
pub mod shadow_router;
/// **The short-id rule, in ONE fixture table.** (RFC-049 §4.4, RULING
/// F98-3b.)
///
/// The rule — *"a matchable short id is 1..=8 bytes of hex carrying at
/// least one non-zero byte"* — is expressed in three places, and the
/// duplication is protected by **one table** rather than by a convention.
/// This module holds that table, and it is `pub` so the **control plane's**
/// copy (`services/server`) can assert against the same list: each copy's
/// test passing against itself proves nothing about the other two.
pub mod short_id_rule;
/// Network speed testing infrastructure.
pub mod speed_test;
/// Traffic mode and preference management.
pub mod torrent_policy;
pub mod traffic_modes;
/// Network transport implementations.
pub mod transport;
/// Sovereignty Vault for resilient failover.
pub mod vault;
/// Core VPN lifecycle and state management.
pub mod vpn_manager;

pub use crate::anti_tamper::{AntiTamperChecker, AntiTamperConfig};
pub use crate::api_client::{
    ActivationChallenge, ActivationRequest, ActivationResponse, ApiClient, ApiErrorResponse,
    HealthStatus, HeartbeatRequest, HeartbeatResponse, IdentityInfo, SecurityManifest,
    ServerNetworkReport,
};
pub use crate::config::UserSettings;
pub use crate::desktop_controller::DesktopController;
pub use crate::identity::{AttestationReport, EnclaveProvider, SovereignIdentity};
pub use crate::kill_switch::{KillSwitchEvent, KillSwitchManager, KillSwitchState};
pub use crate::network::detector::{NetworkDetector, NetworkReport, NetworkType, TunnelPathCost};
pub use crate::network::leak_guard::{LeakGuard, SecurityEnforcer};
pub use crate::network::throttler::BandwidthThrottler;
pub use crate::network::tunnel_path::{TunnelPathLegs, TunnelPathProbe};
pub use crate::node_cache::NodeCache;
pub use crate::security_logger::{
    scrub_pii, SecurityEvent, SecurityEventLogger, SecurityEventType,
};
pub use crate::speed_test::{SpeedTest, SpeedTestResult};
pub use crate::traffic_modes::{TrafficAnalytics, TrafficPreferences};
pub use crate::transport::l4::{l4_of, L4};
pub use crate::transport::QuantumResistanceLevel;
pub use crate::vpn_manager::{
    get_persistent_device_id, ConnectionStatus, ProtocolStats, ServicePlan, SplitTunnelConfig,
    SplitTunnelMode, TrafficMode, TrafficModePreference, VPNManager, VmessConfig,
};
use serde::{Deserialize, Serialize};

pub use crate::reality::{
    compute_dh_public_key, compute_dh_shared_secret, derive_session_token, generate_dh_private_key,
    generate_short_id as generate_reality_short_id,
};

// ── The crate root's own unit tests (F104) ─────────────────────────────────
//
// ⚠️ **These three files were NEVER COMPILED.** They sat in `src/` with **no**
// `mod` declaration reaching them, and `Cargo.toml` declares no `[[test]]`
// target for them — so they read as coverage in a diff and in the register
// while asserting **nothing**. **A test that cannot fail is worse than no
// test** (F12's class).
//
// ⇒ They are wired here. **What the wiring FOUND is recorded in the files
// themselves and in the commit that did it** — and one of them was RED on
// the day it was first compiled, which is the whole point of wiring a test
// that has never run.
#[cfg(test)]
mod api_sync_test;
/// F110 — the core half of the raw-server-string closure (L10 §7.2/M-2).
/// The classifier-level tests live inside `api_client` (they drive the
/// private raise site); the constructor-level closure lives here.
#[cfg(test)]
mod f110_error_class_closure_test;
#[cfg(test)]
mod lib_test;
#[cfg(test)]
mod subscription_test;

/// Parse a VLESS+REALITY URI string into a `RealityConfig`.
pub fn parse_reality_vless_uri(uri: String) -> Option<RealityConfig> {
    RealityConfig::from_vless_uri(&uri)
}

/// (ring::SystemRandom — the audited CSPRNG already in the tree).
///
/// Single source of truth for every salt / IV / ephemeral-key fill in the
/// core so no production path silently uses a non-CSPRNG generator.
/// Returns `None` only if the OS entropy source itself fails (the caller
/// decides; crypto must never fall back to weaker randomness).
#[must_use]
pub fn secure_random_bytes(len: usize) -> Option<Vec<u8>> {
    use ring::rand::{SecureRandom, SystemRandom};
    let mut buf = vec![0u8; len];
    SystemRandom::new().fill(&mut buf).ok().map(|()| buf)
}

/// Normalizes an activation code by removing dashes and converting to uppercase.
/// Normalises an activation code **for the wire** — the format, not the
/// credential.
///
/// ⚠️ **The credential is the SERVER's.** This removes the grouping
/// characters and upper-cases; it does **not** strip the `UVPN` brand prefix,
/// does **not** validate the 25-character shape, and does **not** reject a
/// code the server would reject. The server owns all of that
/// (`services/server/src/api/handlers/auth.rs::normalize_activation_code`),
/// and it owns it **carefully** — the prefix is stripped from the front
/// only, because a generated code may legitimately *begin* `UVPN` (~1 in
/// 923 521) and a global replace would map two distinct credentials onto
/// one.
///
/// ⚠️ **There are THREE normalisers in this tree, with TWO contracts:**
///
/// | # | Where | Contract |
/// | :--- | :--- | :--- |
/// | 1 | the **server** (`services/server/…/auth.rs`) | the full one: trim, strip the prefix **from the front only**, strip the grouping, and require exactly 25 alphanumerics |
/// | 2 | **this** function (the core's FFI export) | the **format** one: strip the grouping, upper-case |
/// | 3 | the **Android**'s own (`CoreUtils.normalizeActivationCode`, `apps/android/core/vpn/…/CoreUtils.kt:67`) | the **format** one — the same shape as this one, and the one the pairing screen actually calls |
///
/// ⇒ The core and the Android agree, and the server is stricter — and the
/// server's strictness is a **floor**, not a conflict: a code the server
/// accepts is accepted whatever the client did to it.
///
/// ⚠️ **A third, never-compiled test in this crate asserted the SERVER's
/// contract here** (`src/lib_test.rs::test_normalize_activation_code` — see
/// F104). Wiring it surfaced the disagreement. **The product was not
/// changed**: making the core agree with the server would have made it
/// **diverge from the Android's** shipped normaliser, which is the drift
/// this house forbids. The finding is recorded, not silently resolved.
pub fn normalize_activation_code(raw: String) -> Option<String> {
    let normalized = raw.replace('-', "").to_uppercase();
    if normalized.is_empty() {
        None
    } else {
        Some(normalized)
    }
}

/// Returns the default user settings.
pub fn get_default_user_settings() -> UserSettings {
    UserSettings::default()
}

/// Creates a new security logger.
pub fn create_security_logger(
    device_id: String,
    app_version: String,
    storage_dir: String,
) -> Result<Arc<SecurityEventLogger>, ShadowMeshError> {
    SecurityEventLogger::new(device_id, app_version, storage_dir).map(Arc::new)
}

/// Creates a new VPN manager.
pub fn create_vpn_manager(settings: UserSettings) -> Result<Arc<VPNManager>, ShadowMeshError> {
    let manager = Arc::new(VPNManager::new(settings));
    let _ = crate::vpn_manager::GLOBAL_MANAGER.set(manager.clone());
    Ok(manager)
}

/// Creates a new API client.
pub fn create_api_client(base_url: String) -> Result<Arc<ApiClient>, ShadowMeshError> {
    Ok(Arc::new(ApiClient::new(base_url)?))
}

/// Creates a new API client with certificate pins.
pub fn create_api_client_with_pins(
    base_url: String,
    pinned_hashes: Vec<String>,
) -> Result<Arc<ApiClient>, ShadowMeshError> {
    Ok(Arc::new(ApiClient::with_pins(base_url, pinned_hashes)?))
}

/// Creates a new node cache.
pub fn create_node_cache(
    max_size: u32,
    ttl_seconds: u32,
) -> Result<Arc<NodeCache>, ShadowMeshError> {
    Ok(Arc::new(NodeCache::new(max_size as usize, ttl_seconds as u64)))
}

/// Creates a new kill switch manager.
pub fn create_kill_switch_manager() -> Result<Arc<KillSwitchManager>, ShadowMeshError> {
    Ok(Arc::new(KillSwitchManager::new()))
}

/// Creates a new speed test.
pub fn create_speed_test(api_client: Arc<ApiClient>) -> Arc<SpeedTest> {
    Arc::new(SpeedTest::new(api_client))
}

/// Creates a new network detector.
pub fn create_network_detector(
    api_client: Arc<ApiClient>,
    manager: Option<Arc<VPNManager>>,
) -> Arc<NetworkDetector> {
    Arc::new(NetworkDetector::new(api_client, manager))
}

/// Creates a new traffic analytics instance.
pub fn create_traffic_analytics(
) -> Result<Arc<crate::traffic_modes::TrafficAnalytics>, ShadowMeshError> {
    Ok(Arc::new(crate::traffic_modes::TrafficAnalytics::default()))
}

/// Creates default traffic preferences.
pub fn create_traffic_preferences() -> TrafficPreferences {
    TrafficPreferences {
        data_limit_mb: None,
        prioritize_wifi: true,
        restrict_background_data: false,
        mode_preference: TrafficModePreference::Auto,
    }
}

/// Placeholder for decrypting QR pairing payload.
pub fn decrypt_qr_pairing_payload(ciphertext: Vec<u8>, pin: String) -> Vec<u8> {
    crate::reality::decrypt_qr_payload(&ciphertext, &pin)
}

/// Placeholder for encrypting QR pairing payload.
pub fn encrypt_qr_pairing_payload(plaintext: Vec<u8>, pin: String) -> Vec<u8> {
    crate::reality::encrypt_qr_payload(&plaintext, &pin)
}

/// Selects the best VPN node.
pub fn shadow_route_best_node(nodes: Vec<VPNNode>) -> Option<VPNNode> {
    crate::shadow_router::shadow_route_best_node(&nodes).cloned()
}

/// Scores a node based on the Shadow-Routing algorithm.
pub fn score_node(
    node: &VPNNode,
    frag_success_ratio: f64,
    reputation_score: f64,
    transit_diversity: f64,
) -> crate::shadow_router::NodeScore {
    crate::shadow_router::score_node(node, frag_success_ratio, reputation_score, transit_diversity)
}

/// Returns the preferred traffic mode for a region.
pub fn preferred_traffic_mode_for_region(region_code: String) -> String {
    format!("{:?}", crate::shadow_router::preferred_mode_for_region(&region_code))
}

/// Returns the MTU for Quantum Tunneling.
pub fn get_quantum_mtu() -> u32 {
    576
}

/// Returns the TCP MSS for Quantum Tunneling.
pub fn get_quantum_tcp_mss() -> u32 {
    536
}

/// Setup internal logging for Android.
pub fn setup_logging() {
    #[cfg(target_os = "android")]
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("SHADOWMESH_RUST"),
    );

    // v6.9.5: Explicitly install Rustls CryptoProvider to prevent ambiguous selection crash
    #[cfg(feature = "ring")]
    let _ = rustls::crypto::ring::default_provider().install_default();
    #[cfg(all(not(feature = "ring"), feature = "aws-lc-rs"))]
    let _ = rustls::crypto::aws_lc_rs::default_provider().install_default();
}

/// Callback interface to protect sockets from VPN interception on Android.
pub trait SocketProtector: Send + Sync {
    /// Protects the given socket FD.
    fn protect(&self, fd: i32) -> bool;
}

static SOCKET_PROTECTOR: OnceLock<Box<dyn SocketProtector>> = OnceLock::new();

/// Registers a socket protector callback from the platform layer.
pub fn register_socket_protector(protector: Box<dyn SocketProtector>) {
    let _ = SOCKET_PROTECTOR.set(protector);
}

/// Internal helper to protect a socket FD.
pub(crate) fn protect_socket(fd: i32) -> bool {
    if let Some(protector) = SOCKET_PROTECTOR.get() {
        protector.protect(fd)
    } else {
        false
    }
}

/// Starts the unified VPN engine with a TUN file descriptor.
///
/// ⚠️ **DEPRECATED (RFC-048 §3.6) — retained, not removed.** The two-phase
/// path ([`connect_transport`] → [`attach_tun`]) replaces it; the removal
/// condition is *"no FFI consumer remains **and** the tracked `.so` has been
/// rebuilt without the symbol"* — removing a UDL entry does not remove the
/// symbol from a shipped `.so`, so the removal is blocked on F46, not on this
/// deprecation.
pub fn start_vpn_engine(fd: i32, config: VPNConfig) -> Result<(), ShadowMeshError> {
    setup_logging();
    crate::transport::engine::start_engine(fd, config)
}

/// Stops the unified VPN engine.
///
/// Since RFC-048 §3.4 this also **closes the active transport** and
/// invalidates the live generation — before, it only flipped a flag, and a
/// failed reconnect left the previous transport live on the node.
pub fn stop_vpn_engine() {
    crate::transport::engine::stop_engine();
}

/// Establishes the transport and returns the session's generation.
///
/// RFC-048 §3.1 — **FROZEN 2026-10-20**. The two-phase establishment: this
/// call proves the transport with **no** tun descriptor, so the client
/// installs its capture only after it returns. [`attach_tun`] then hands the
/// descriptor to the already-connected transport.
///
/// The returned handle is a **generation token**: it names the session, it
/// carries no authority on its own, and a stale one is rejected rather than
/// honoured.
pub fn connect_transport(config: VPNConfig) -> Result<u64, ShadowMeshError> {
    setup_logging();
    crate::transport::engine::connect_transport(config)
}

/// Attaches the tun descriptor to the session named by `handle`.
///
/// RFC-048 §3.1 — **FROZEN 2026-10-20**. A stale handle is refused, a
/// session that already owns a tun is refused, and in both cases the
/// descriptor is **closed** without a tun loop being spawned.
///
/// ⚠️ The `i32` descriptor is the **deliberate** deviation RFC-048 §3.6
/// carries: the `EngineDescriptor` conversion must cover `attach_tun` and
/// `start_vpn_engine` **together**, in one lockstep, under RFC-042 OQ-1.
pub fn attach_tun(handle: u64, fd: i32) -> Result<(), ShadowMeshError> {
    setup_logging();
    crate::transport::engine::attach_tun(handle, fd)
}

/// Closes the session named by `handle`.
///
/// RFC-048 §3.1 — **FROZEN 2026-10-20**. **Not** a throwing path: a teardown
/// must not force the caller into an exception handler. The return value
/// answers *"did I own the session?"* — `false` means the handle was stale,
/// nothing was closed, and the caller must not touch the live session.
pub fn close_transport(handle: u64) -> bool {
    crate::transport::engine::close_transport(handle)
}

/// Returns a mocked attestation report for testing (RFC-002 Phase 1).
pub fn get_mock_attestation_report(
    nonce: String,
) -> Result<crate::identity::AttestationReport, ShadowMeshError> {
    use crate::identity::sovereign::EnclaveProvider;
    let provider = Arc::new(crate::identity::sovereign::MockEnclaveProvider::default());
    provider.generate_identity_key()?;
    provider.produce_attestation(&nonce)
}

/// Static storage for the active REALITY proxy instance.
static REALITY_PROXY: OnceLock<Arc<crate::transport::reality::RealityTransport>> = OnceLock::new();

/// Starts the local REALITY proxy bridge.
pub fn start_reality_proxy(config: RealityConfig, _local_port: u16) -> Result<(), ShadowMeshError> {
    // SOP 04: Composes WireGuard over VLESS/REALITY
    // For standalone proxy, we use ephemeral WG keys
    let (priv_key_vec, pub_key_vec) = shadowmesh_common::crypto::generate_x25519_keypair();
    let mut priv_key = [0u8; 32];
    priv_key.copy_from_slice(&priv_key_vec);
    let mut pub_key = [0u8; 32];
    pub_key.copy_from_slice(&pub_key_vec);

    let proxy =
        Arc::new(crate::transport::reality::RealityTransport::new(config, priv_key, pub_key));

    // Stop any existing one
    stop_reality_proxy();

    crate::api_client::get_runtime()?.block_on(async {
        use crate::transport::AsyncTransport;
        proxy.connect().await
    })?;

    let _ = REALITY_PROXY.set(proxy);
    Ok(())
}

/// Stops any running REALITY proxy.
pub fn stop_reality_proxy() {
    if let Some(proxy) = REALITY_PROXY.get() {
        if let Ok(rt) = crate::api_client::get_runtime() {
            rt.block_on(async {
                use crate::transport::AsyncTransport;
                let _ = proxy.close().await;
            });
        }
    }
}

uniffi::include_scaffolding!("shadowmesh");

/// Core error types for the ShadowMesh library.
/// Automatically scrubs PII in Display implementation to prevent leaks in logs.
///
/// ## F110 — the error surface is a **closed set**, and the control plane's
/// sentences do not ride it
///
/// This enum is the core half of L10's raw-server-string rule
/// (`docs/SECURITY_SCAN_2026-10-21-L10-roster-disclosure-ruling.md` §7.2 /
/// M-2): a server string may be **inspected** to classify a failure, but it
/// may never be **carried** into a surfaced message — *"classify on the
/// TYPE, never the STRING"*. L7 closed the client half with the desktop's
/// closed-set classes (`apps/desktop-native/src/connection_failure.rs`);
/// the closure HERE is the source half:
///
/// * **The class set is the variant list below.** It is what the desktop's
///   classes classify *on*, and it does not drift: there is no fourth
///   spelling of a class's name anywhere in the tree.
/// * **The ONE wording table is [`ShadowMeshError::as_str`].** A class's
///   word is spelled exactly once, and `Display` never interpolates a
///   control-plane payload.
/// * **Raise sites for control-plane-derived failures go through
///   [`ShadowMeshError::sealed`]** (see `api_client::map_status_error` and
///   `desktop_controller`), where the payload becomes the class's own
///   wording — never the server's sentence.
///
/// The **PoW nonce** is the one deliberate carriage of server-authored
/// data: `AdaptiveFrictionRequired` carries the challenge because the
/// solver consumes it (the daemon is its only reader), and `as_str()`
/// spells the class without it — a nonce is protocol material, not prose.
///
/// The four **local-diagnostic** classes (`IoError`, `JsonError`, `Other`,
/// `Common`) keep their payload in `Display`, because their payloads are
/// the OS's, the JSON parser's or the core's own text by construction — no
/// raise site hands them a control-plane sentence (the closure tests drive
/// the real raise site with a hostile body to keep that true).
#[derive(Debug, thiserror::Error, Zeroize)]
pub enum ShadowMeshError {
    /// Adaptive friction (PoW) is required by the server.
    ///
    /// ⚠️ **The payload is the PoW challenge nonce — protocol material, not
    /// prose (F110).** The daemon extracts it from this error to solve the
    /// challenge; no other consumer reads it, and it is **never rendered**:
    /// `as_str()` spells the class wording without the nonce, so a hostile
    /// control plane cannot write a sentence into this channel either.
    AdaptiveFrictionRequired(String),
    /// Failed to generate cryptographic keys.
    KeyGenerationFailed,
    /// PoW solver timed out.
    PowTimeout,
    /// Connection initiation failed.
    ConnectionFailed,
    /// Authentication failure.
    Unauthorized(String),
    /// Access to the resource is forbidden.
    Forbidden(String),
    /// The current session has been frozen by an administrator.
    SessionFrozen,
    /// The requested resource was not found.
    NotFound(String),
    /// The server is currently overloaded.
    TooManyRequests(String),
    /// The specified node was not found.
    NodeNotFound,
    /// A system IO error occurred.
    IoError(String),
    /// Failed to parse or serialize JSON.
    JsonError(String),
    /// An invalid duration was provided.
    InvalidDuration,
    /// An internal server error occurred.
    ServerError(String),
    /// Kernel-level socket tuning failed.
    SocketTuningFailed,
    /// DNS resolution failed.
    DnsResolutionError,
    /// Header magic bytes mismatch (RFC-compliance check).
    HeaderMagicMismatch,
    /// The protocol version is not supported by this implementation.
    UnsupportedProtocolVersion,
    /// Invalid address type encountered in protocol frame.
    InvalidAddressType,
    /// AEAD authentication tag verification failed.
    AuthTagVerificationFailed,
    /// Invalid state transition in protocol state machine.
    InvalidStateTransition,
    /// An unspecified error occurred.
    Other(String),
    /// Error from the shared common crate.
    Common(String),
}

impl std::fmt::Display for ShadowMeshError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // F110: the surfaced message is spelled from the ONE wording table
        // (`as_str`). A control-plane class never renders its payload — the
        // payload's text is not the core's language to carry. The four
        // LOCAL-diagnostic classes keep their payload, because their payload
        // is by construction the OS's, the JSON parser's or the core's own
        // text (see the enumeration on the enum).
        let msg = match self {
            ShadowMeshError::IoError(s) => format!("{}: {}", self.as_str(), s),
            ShadowMeshError::JsonError(s) => format!("{}: {}", self.as_str(), s),
            ShadowMeshError::Other(s) => format!("{}: {}", self.as_str(), s),
            ShadowMeshError::Common(s) => format!("{}: {}", self.as_str(), s),
            other => other.as_str().to_string(),
        };
        // SOP 01 §3: scrub_pii mandatory for all forensic-resistant logging.
        write!(f, "{}", scrub_pii(&msg))
    }
}

impl ShadowMeshError {
    /// F110 — the **canonical wording table**: one definition site for
    /// every class's surfaced message, exactly as [`Self::Display`] renders
    /// it. The parity this names (for L7's desktop classes,
    /// `apps/desktop-native/src/connection_failure.rs`): **the core ships
    /// the classes** — these words are the core's own language for its own
    /// class — **and the desktop feeds its surfaces from a closed set keyed
    /// on the class's discriminant**. Nobody reformats a core wording into
    /// a new vocabulary, and the discriminants do not drift.
    pub fn as_str(&self) -> &'static str {
        match self {
            ShadowMeshError::AdaptiveFrictionRequired(_) => "Adaptive friction (PoW) required",
            ShadowMeshError::KeyGenerationFailed => "Key generation failed",
            ShadowMeshError::PowTimeout => "PoW solving timed out",
            ShadowMeshError::ConnectionFailed => "Connection failed",
            ShadowMeshError::Unauthorized(_) => "Authentication failed",
            ShadowMeshError::Forbidden(_) => "Forbidden",
            ShadowMeshError::SessionFrozen => "Session frozen by admin",
            ShadowMeshError::NotFound(_) => "Resource not found",
            ShadowMeshError::TooManyRequests(_) => "Server overloaded",
            ShadowMeshError::NodeNotFound => "Node not found",
            ShadowMeshError::IoError(_) => "IO error",
            ShadowMeshError::JsonError(_) => "JSON error",
            ShadowMeshError::InvalidDuration => "Invalid duration",
            ShadowMeshError::ServerError(_) => "Internal server error",
            ShadowMeshError::SocketTuningFailed => "Socket tuning failed",
            ShadowMeshError::DnsResolutionError => "DNS resolution error",
            ShadowMeshError::HeaderMagicMismatch => "Header magic mismatch",
            ShadowMeshError::UnsupportedProtocolVersion => "Unsupported protocol version",
            ShadowMeshError::InvalidAddressType => "Invalid address type",
            ShadowMeshError::AuthTagVerificationFailed => "AEAD auth tag verification failed",
            ShadowMeshError::InvalidStateTransition => "Invalid state transition",
            ShadowMeshError::Other(_) => "Other error",
            ShadowMeshError::Common(_) => "Common error",
        }
    }

    /// F110 — the **sealed raise** for a control-plane-derived failure.
    ///
    /// `map_status_error` (the ONE place a control-plane status becomes a
    /// raised error) and the desktop activation path raise exclusively
    /// through this constructor. The argument variant is classified FIRST —
    /// on its **type** — and the payload the sealed error carries is the
    /// class's own wording from [`Self::as_str`]: **the control plane's
    /// sentence is never carried**, so nothing downstream (the daemon's
    /// `VpnResponse.message`, an exception class, a log line, an export)
    /// can receive it through this surface.
    ///
    /// The exception is [`ShadowMeshError::AdaptiveFrictionRequired`]: the
    /// PoW nonce is protocol material the solver needs, and — being a nonce
    /// and not prose — it is carried intact and never rendered (its docs).
    pub(crate) fn sealed(kind: ShadowMeshError) -> ShadowMeshError {
        use ShadowMeshError as E;
        match kind {
            E::AdaptiveFrictionRequired(nonce) => E::AdaptiveFrictionRequired(nonce),
            E::Unauthorized(_) => {
                E::Unauthorized(E::Unauthorized(String::new()).as_str().to_string())
            }
            E::Forbidden(_) => E::Forbidden(E::Forbidden(String::new()).as_str().to_string()),
            E::NotFound(_) => E::NotFound(E::NotFound(String::new()).as_str().to_string()),
            E::TooManyRequests(_) => {
                E::TooManyRequests(E::TooManyRequests(String::new()).as_str().to_string())
            }
            E::ServerError(_) => E::ServerError(E::ServerError(String::new()).as_str().to_string()),
            // Everything else is either payloadless, or one of the four
            // LOCAL-diagnostic classes whose payload is core-authored by
            // construction — there is nothing to seal.
            other => other,
        }
    }
}

impl From<shadowmesh_common::CommonError> for ShadowMeshError {
    fn from(err: shadowmesh_common::CommonError) -> Self {
        ShadowMeshError::Common(err.to_string())
    }
}

impl From<std::io::Error> for ShadowMeshError {
    fn from(err: std::io::Error) -> Self {
        ShadowMeshError::IoError(err.to_string())
    }
}

impl From<serde_json::Error> for ShadowMeshError {
    fn from(err: serde_json::Error) -> Self {
        ShadowMeshError::JsonError(err.to_string())
    }
}

impl From<reqwest::Error> for ShadowMeshError {
    fn from(err: reqwest::Error) -> Self {
        ShadowMeshError::Other(err.to_string())
    }
}

/// Represents a VPN node in the ShadowMesh network.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, Zeroize)]
pub struct VPNNode {
    /// Unique identifier for the node.
    pub id: String,
    /// Display name of the node.
    pub name: String,
    /// Region where the node is located.
    pub region: String,
    /// ISO country code.
    pub country: String,
    /// Network endpoint (IP:Port).
    pub endpoint: String,
    /// Node's WireGuard public key.
    pub public_key: String,
    /// Current server load (0-100).
    pub load: u32,
    /// Measured latency in milliseconds.
    #[zeroize(skip)]
    pub latency: u32,
    /// Whether the node has verified hardware attestation (RFC-002).
    #[serde(default)]
    #[zeroize(skip)]
    pub is_sovereign: bool,
    /// Whether the node is currently reachable.
    #[serde(default)]
    #[zeroize(skip)]
    pub is_online: bool,
    /// The ID of the shard this node is anchored to (Horizon 4).
    #[serde(default)]
    pub shard_id: Option<String>,
}

/// The anonymous node projection served by `GET /api/v1/nodes`.
///
/// This mirrors the server's deliberate `PublicNodeResponse`
/// (`services/server/src/models/vpn.rs:473`). The server withholds `endpoint`
/// and `public_key` on purpose: for a censorship-circumvention product an
/// unauthenticated roster of live node IPs is an enumeration target, and the
/// same superset was already removed from `/api/v1/manifest`
/// (`routes.rs:611-625`) and `/api/v1/config/nodes` (`routes.rs:627-640`).
/// Connect material comes from the authenticated `GET /api/v1/config/:server_id`.
///
/// Deliberately a distinct type from [`VPNNode`] rather than a set of serde
/// defaults. Defaults would deserialize cleanly and yield empty `endpoint`
/// and `public_key`, and the client would then attempt a connect against `""`.
#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize, Zeroize)]
pub struct PublicNode {
    /// Unique identifier for the node.
    pub id: String,
    /// ISO country code.
    pub country: String,
    /// Region the node is located in.
    pub region: String,
    /// Whether the node is currently reachable.
    pub is_online: bool,
}

/// Which projection of the fleet a roster carries.
///
/// F86 / RFC-047 §2.2: the projection the server applied is a **fact only the
/// server knows**. The two projections are byte-compatible at the row level —
/// the anonymous row is a strict subset of the full row — so a client cannot
/// infer which one it received, and it never could. A client that presented a
/// session and received the anonymous roster therefore cannot tell *"the
/// server withheld by policy"* from *"your session died thirty seconds ago"*.
///
/// The signal travels in the **body**, as an envelope around the roster:
/// `{"projection": "anonymous" | "full", "nodes": [ … ]}`. Not an error (the
/// anonymous roster is a legitimate answer, not a failure), not a field on
/// every row (that repeats one claim N times, and puts a server claim inside a
/// payload whose contract is "absence, not zero"), and not a header (headers
/// are droppable by proxies and invisible to `curl | jq`).
///
/// This type lives here, in core, because **three** crates speak it: the
/// control plane (`services/server`), the daemon (`apps/daemon`), and the
/// client (`libs/…/src/api_client.rs`). One definition, one wire name —
/// `anonymous` / `full` — and no drift between them.
///
/// There is deliberately **no** "unknown" variant and **no**
/// `#[serde(default)]`: the server always knows which projection it applied,
/// and a defaulted projection would let a client report "the server narrowed
/// this" about a roster the server never narrowed. Absent must be an error,
/// not a default (RFC-047 §2.6).
///
/// ⚠️ **THE THIRD VARIANT IS A CLAIM, NOT A THIRD SHAPE.**
/// [`NodeProjection::LocalFallback`] exists because a claim needs a **speaker**,
/// and there are two speakers:
///
/// | Value | The claim it makes | Who makes it |
/// | :--- | :--- | :--- |
/// | `Anonymous` | *"The server applied the anonymous projection."* | **the server** |
/// | `Full` | *"The server applied the full projection; the nine extra fields were dropped by the type, not withheld."* | **the server** |
/// | `LocalFallback` | *"No server was asked. The client narrowed these rows itself."* | **the client** |
///
/// Before this variant existed, the core stated `Anonymous` for a roster **no
/// server ever described** — the RFC-004 fallback channels (the Cloudflare
/// Worker and the DoH-published TXT record) publish a *manifest*, which this
/// client narrows to the anonymous projection itself. So the server's "I
/// withheld by policy" and the client's "the control plane was unreachable"
/// were **one value**, and RFC-047 §2.4's first statement — *"The server
/// narrowed this"* — was **not expressible on the resilient path**. A mobile
/// client that received `Anonymous` would have to *guess* which of the two
/// conditions it was in, and the guess it would make ("the server withheld")
/// is wrong precisely when the device is most degraded (RFC-049 §15.3.3).
///
/// ⚠️ **The server's vocabulary stays two-valued.** `services/server`'s own
/// `NodeProjection` does **not** gain this variant: the server never narrows
/// locally and never speaks `local_fallback`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum NodeProjection {
    /// The anonymous four-field projection.
    Anonymous,
    /// The authenticated thirteen-field projection.
    Full,
    /// **No server was asked.** The client narrowed these rows itself, from a
    /// fallback channel that carries no server statement.
    ///
    /// This is **not** the same claim as [`NodeProjection::Anonymous`]: one is
    /// "your session is not in force", the other is "the control plane is
    /// unreachable".
    LocalFallback,
}

impl NodeProjection {
    /// The wire name. One place, so the three crates cannot drift.
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Anonymous => "anonymous",
            Self::Full => "full",
            Self::LocalFallback => "local_fallback",
        }
    }

    /// Every variant, **in declaration order**.
    ///
    /// ⚠️ **The order is load-bearing at the FFI boundary.** UniFFI's
    /// scaffolding derives the enum's FFI converter from the **Rust** type's
    /// variants, and the UDL declares its own list; the two must agree. A
    /// mismatch does not fail the build — it **silently mis-maps the
    /// discriminants**, which is the worst failure mode there is
    /// (RFC-049 §15.6.4).
    ///
    /// ⇒ This constant is the **one** place the Rust order is written down,
    /// and the fixture-parity gate
    /// (`tests/f102_projection_vocabulary_parity_test.rs`) pins it to the
    /// UDL's declared order. A reordering of the enum that forgets this
    /// constant makes that gate **red**; a reordering that updates it but not
    /// the UDL makes it red too.
    pub const ALL: [NodeProjection; 3] = [Self::Anonymous, Self::Full, Self::LocalFallback];
}

/// A node roster, and the projection the server applied to it.
///
/// The wire shape is `{"projection": "anonymous" | "full" | "local_fallback",
/// "nodes": [ … ]}` — the envelope RFC-047 §2.2 ruled for, and the one the
/// control plane already serves (`services/server/src/models/vpn.rs::NodeRoster`).
/// The third value is **never** produced by the server; it is the client's
/// own claim that no server was asked (see
/// [`NodeProjection::LocalFallback`]).
///
/// **Why the roster is a type and not a bare array.** The rows cannot say
/// which projection arrived: the anonymous row is a strict subset of the full
/// row, so a client that received the rows alone cannot tell *"the server
/// withheld by policy"* from *"your session died thirty seconds ago"*
/// (RFC-047 §2.1). That is F86, and it is a **silent** narrowing — the client
/// received a well-formed, correctly-typed, much smaller answer and no signal
/// at all. The envelope is the signal, and it travels in the body because a
/// body is what a client parses and what an operator sees in `curl … | jq`.
///
/// **This type lives in core, in one place, because three crates speak it:**
/// the control plane (`services/server`), the daemon
/// (`apps/daemon/src/types.rs`) and the client (`libs/…/src/api_client.rs`).
/// One definition, one wire name, no drift.
///
/// ⚠️ **No `#[serde(default)]` and no `#[serde(deny_unknown_fields)]`** — both
/// are ruled out (RFC-047 §2.6). A defaulted projection would let a client
/// report *"the server narrowed this"* about a roster the server never
/// narrowed; a `deny_unknown_fields` roster would reject every authenticated
/// response, because the full row is a strict superset of the anonymous one.
/// **Absent must be an error, not a default** — so a roster body that carries
/// no `projection` does not deserialize at all.
#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct NodeRoster {
    /// The server's own statement of what it did.
    pub projection: NodeProjection,
    /// The rows, in the anonymous four-field shape.
    ///
    /// `PublicNode`, **not** `VPNNode`: the connect material (`endpoint`,
    /// `public_key`) is never on this surface (RFC-043 R2, and L10's refusal
    /// to widen `/api/v1/manifest` and `/api/v1/config/nodes`). The envelope
    /// states which projection arrived; it does not widen anything.
    ///
    /// ⚠️ **The rows are always the four-field shape, whatever the projection
    /// says.** A `Full` roster therefore tells the client that it is holding a
    /// **narrowing** of the server's richer answer: the nine extra fields were
    /// dropped by the type, not withheld by the server, and the envelope is
    /// what makes that visible (RFC-047 §2.4, *"I dropped fields."*).
    pub nodes: Vec<PublicNode>,
}

impl NodeRoster {
    /// A roster the **server** described.
    ///
    /// The projection is the server's own statement and is carried through
    /// untouched. This is the only constructor on the server's path.
    pub fn from_server(projection: NodeProjection, nodes: Vec<PublicNode>) -> Self {
        Self { projection, nodes }
    }

    /// A roster the **client** narrowed itself.
    ///
    /// The RFC-004 fallback channels — the Cloudflare Worker and the
    /// DoH-published TXT record — do not serve the control plane's envelope:
    /// they publish a *manifest*, which this client then narrows to the
    /// anonymous projection by dropping the connect material
    /// (`network/discovery.rs::decrypt_and_parse_nodes`). There is no server
    /// statement behind those rows, so the honest claim is
    /// [`NodeProjection::LocalFallback`] — **"no server was asked"**.
    ///
    /// ⚠️ **This is not `Anonymous`, and the difference is the whole point.**
    /// `Anonymous` is the **server's** claim ("your session is not in force");
    /// `LocalFallback` is the **client's** ("the control plane was
    /// unreachable; these are fallback nodes"). Before this split the core
    /// stated the server's claim for a condition the server was never asked
    /// about, which made RFC-047 §2.4's *"the server narrowed this"*
    /// unexpressible on the resilient path (RFC-049 §15.3.3).
    ///
    /// The old reading is not lost: `LocalFallback` **is** the four-field
    /// shape, and the rows still say so. What is added is the **speaker**.
    pub fn narrowed_locally(nodes: Vec<PublicNode>) -> Self {
        Self { projection: NodeProjection::LocalFallback, nodes }
    }
}

/// Represents an Anycast Virtual IP for global mesh entry.
#[derive(Debug, Clone, Serialize, Deserialize, Zeroize)]
pub struct AnycastVip {
    /// Unique identifier for the VIP.
    #[zeroize(skip)]
    pub id: i64,
    /// The global anycast IP address.
    pub ip_address: String,
    /// A human-readable label for the VIP.
    pub label: String,
    /// Whether the VIP is currently active in BGP.
    #[zeroize(skip)]
    pub is_active: bool,
}

/// A comprehensive manifest of the ShadowMesh ecosystem.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GlobalManifest {
    /// Which projection the server applied to this manifest.
    ///
    /// **The server's own statement, carried through untouched.** The
    /// manifest route (`GET /api/v1/manifest`) is a *second spelling* of the
    /// fleet map, and it serves the same `"projection"` the roster route's
    /// envelope serves (`services/server/src/api/handlers/system.rs`) — one
    /// map, two answers, and the narrowing must not be silent on either.
    ///
    /// Without this field the manifest read **dropped** the statement on the
    /// way in, and a client could not tell *"the server withheld by policy"*
    /// from *"your session died thirty seconds ago"*.
    ///
    /// ⚠️ **No `#[serde(default)]`.** Absent is an error, not a default
    /// (RFC-047 §2.6): a defaulted projection would let a client report
    /// *"the server narrowed this"* about a manifest the server never
    /// narrowed.
    pub projection: NodeProjection,
    /// The rows. **The same row type the roster route uses.**
    ///
    /// ⚠️ **This was `Vec<VPNNode>`** — and `VPNNode` carries **eight
    /// mandatory fields** (`lib.rs:552-581`). The anonymous projection's row
    /// has **four** (`services/server/src/models/vpn.rs:473-478`), so an
    /// anonymous manifest **did not deserialize at all**: the body was
    /// rejected wholesale, and the `"projection"` the server sent correctly
    /// (`services/server/src/api/handlers/system.rs:89`) was **never read**
    /// (RFC-050 §1.3).
    ///
    /// ⇒ **"Withheld by policy" and "not known" were the same observation** —
    /// both an `Err` — which is the F86 distinction RFC-043 §3.3 named.
    ///
    /// `PublicNode` holds **both** projections: the four anonymous fields are
    /// a subset of the thirteen, and `PublicNode` has no
    /// `#[serde(deny_unknown_fields)]` (`lib.rs:597-606`). So the `Full`
    /// projection's nine extra fields are **dropped by the type, not withheld
    /// by the server** — which is RFC-047 §2.4's third statement, and the
    /// envelope says so.
    ///
    /// ⚠️ **The connect material is NOT here and must not be synthesised
    /// into it.** `endpoint` and `public_key` come from the authenticated
    /// `GET /api/v1/config/:server_id` (RFC-043 R2, applied by RFC-050
    /// §2.4). A `VPNNode` built from a `PublicNode` is a connect row with an
    /// empty key — the exact defect the Anycast synthesis was removed for.
    pub nodes: Vec<PublicNode>,
    /// List of active Anycast VIPs.
    pub anycast_vips: Vec<AnycastVip>,
    /// Manifest schema version.
    pub version: String,
}

/// Final WireGuard configuration for the tunnel.
#[derive(Clone, serde::Serialize, serde::Deserialize, Zeroize)]
pub struct VPNConfig {
    /// Local private key (optional if managed externally).
    pub private_key: Option<String>,
    /// Server's public key.
    #[serde(rename = "server_public_key")]
    pub public_key: String,
    /// Local IP address assigned to the tunnel.
    #[serde(rename = "assigned_ip")]
    pub address: String,
    /// Server's endpoint address.
    pub endpoint: String,
    /// DNS server addresses.
    pub dns: String,
    /// Maximum Transmission Unit (MTU).
    #[zeroize(skip)]
    pub mtu: u32,
    /// Current traffic mode (e.g., "normal", "fragmented").
    pub traffic_mode: String,
    /// REALITY protocol configuration (if applicable).
    pub reality_config: Option<RealityConfig>,
    /// Shadowsocks protocol configuration (if applicable).
    pub shadowsocks_config: Option<ShadowsocksConfig>,
    /// Hysteria protocol configuration (if applicable).
    pub hysteria_config: Option<HysteriaConfig>,
    /// VMess protocol configuration (if applicable).
    pub vmess_config: Option<VmessConfig>,
}

impl VPNConfig {
    /// Resolves and range-checks the configured MTU.
    ///
    /// F89: `mtu` reaches the TUN device (`apps/daemon/src/native_engine.rs:67`)
    /// and therefore the size of the packets the kernel hands the transport,
    /// while the WireGuard encapsulation buffer is a fixed 2048 bytes. An
    /// operator- or control-plane-set MTU above 2016 makes an ordinary large
    /// packet overrun that buffer, and boringtun **panics** rather than
    /// erroring — which `panic = "abort"` turns into a process abort.
    ///
    /// This **rejects** an out-of-range value. It never clamps: a clamp would
    /// make the symptom disappear while silently running an MTU nobody asked
    /// for (F68), leaving the value in effect different from the one
    /// configured. Call it at config load, before any packet is sent.
    ///
    /// `mtu == 0` is the "unset" sentinel the daemon already honours
    /// (`native_engine.rs:64`) and resolves to [`DEFAULT_MTU`].
    pub fn effective_mtu(&self) -> Result<u32, ShadowMeshError> {
        transport::wg_buffer::resolve_mtu(self.mtu)
    }

    /// Validates every field that can make the engine abort or misbehave.
    ///
    /// Currently the MTU is the only such field; the name is deliberate so
    /// that the next one is added here rather than at a call site.
    pub fn validate(&self) -> Result<(), ShadowMeshError> {
        self.effective_mtu()?;
        Ok(())
    }

    /// Canonical minimal configuration shared by every test fixture in the
    /// workspace (daemon integration tests, core tests). Tests construct
    /// through this instead of hand-written struct literals, so adding a
    /// protocol-config field to `VPNConfig` never breaks consumer suites
    /// again (the recurring "missing fields" fixture-rot class).
    ///
    /// All protocol sub-configs are `None` — individual tests override the
    /// fields they care about.
    #[must_use]
    pub fn test_fixture() -> Self {
        Self {
            private_key: None,
            public_key: String::new(),
            address: String::new(),
            endpoint: String::new(),
            dns: "1.1.1.1".to_string(),
            mtu: 1420,
            traffic_mode: String::new(),
            reality_config: None,
            shadowsocks_config: None,
            hysteria_config: None,
            vmess_config: None,
        }
    }
}

/// Default MTU when `VPNConfig::mtu` is left unset.
pub use transport::wg_buffer::DEFAULT_MTU;

/// Shadowsocks-specific configuration for the tunnel.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, Zeroize)]
pub struct ShadowsocksConfig {
    pub server: String,
    pub port: u32,
    pub method: String,
    pub password: String,
}

/// Shadowsocks-2022 specific configuration for the tunnel.
///
/// `password` is the base64-encoded pre-shared key. It is never logged, never
/// included in `Debug`, and never echoed into a transport error message.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, Zeroize)]
#[zeroize(drop)]
pub struct Shadowsocks2022Config {
    pub server: String,
    pub port: u32,
    /// Wire name, e.g. `2022-blake3-aes-256-gcm`.
    pub method: String,
    /// Base64-encoded pre-shared key.
    pub password: String,
}

/// Hysteria-specific configuration for the tunnel.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, Zeroize)]
pub struct HysteriaConfig {
    pub server: String,
    pub port: u32,
    pub auth_password: String,
    pub obfuscation_key: Option<String>,
    pub up_mbps: u64,
    pub down_mbps: u64,
    pub sni: Option<String>,
}

/// Configuration for the VLESS+REALITY protocol extension.
#[derive(Clone, serde::Serialize, serde::Deserialize, Zeroize)]
pub struct RealityConfig {
    /// IP address of the REALITY gateway.
    pub server_ip: String,
    /// Port of the REALITY gateway.
    #[zeroize(skip)]
    pub port: u32,
    /// UUID for VLESS authentication.
    pub uuid: String,
    /// REALITY public key.
    pub public_key: String,
    /// REALITY short ID.
    pub short_id: String,
    /// SNI target used for masquerading.
    pub sni_target: String,
    /// Browser fingerprint to emulate (optional).
    pub fingerprint: Option<String>,
}

/// Configuration for the VLESS+REALITY protocol extension (Server-side).
#[derive(Clone, serde::Serialize, serde::Deserialize, Zeroize)]
pub struct RealityServerConfig {
    /// Private key for REALITY (Hex-encoded X25519).
    pub private_key: String,
    /// List of allowed short IDs.
    pub short_ids: Vec<String>,
    /// SNI target to proxy to when validation fails (primary decoy).
    pub sni_target: String,
    /// Allowed ClientHello ServerNames (RFC-024). Empty = no SNI gate
    /// (legacy single-decoy behavior). Public masquerade hostnames only.
    #[serde(default)]
    #[zeroize(skip)]
    pub server_names: Vec<String>,
}

impl std::fmt::Debug for RealityServerConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RealityServerConfig")
            .field("private_key", &"[REDACTED]")
            .field("short_ids", &self.short_ids)
            .field("sni_target", &self.sni_target)
            .field("server_names", &self.server_names)
            .finish()
    }
}

impl std::fmt::Debug for VPNConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("VPNConfig")
            .field("private_key", &self.private_key.as_ref().map(|_| "[REDACTED]"))
            .field("public_key", &self.public_key)
            .field("address", &self.address)
            .field("endpoint", &self.endpoint)
            .field("dns", &self.dns)
            .field("mtu", &self.mtu)
            .field("traffic_mode", &self.traffic_mode)
            .field("reality_config", &self.reality_config)
            .finish()
    }
}

impl std::fmt::Debug for RealityConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RealityConfig")
            .field("server_ip", &self.server_ip)
            .field("port", &self.port)
            .field("uuid", &"[REDACTED]")
            .field("public_key", &self.public_key)
            .field("short_id", &self.short_id)
            .field("sni_target", &self.sni_target)
            .field("fingerprint", &self.fingerprint)
            .finish()
    }
}

/// Tunnel connection statistics.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, Zeroize, Default)]
pub struct ConnectionStats {
    /// Total bytes received.
    #[zeroize(skip)]
    pub bytes_received: u64,
    /// Total bytes sent.
    #[zeroize(skip)]
    pub bytes_sent: u64,
    /// Total packets received.
    #[zeroize(skip)]
    pub packets_received: u64,
    /// Total packets sent.
    #[zeroize(skip)]
    pub packets_sent: u64,
    /// Timestamp of the last successful handshake.
    #[zeroize(skip)]
    pub last_handshake: i64,
    /// Timestamp when the connection was established.
    #[zeroize(skip)]
    pub connected_since: i64,
}

/// A Proof-of-Work challenge issued by the server.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct PoWChallenge {
    /// The raw challenge string.
    pub challenge: String,
    /// The required number of leading zero bits.
    pub difficulty: u32,
}

/// A solved Proof-of-Work solution.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct PoWSolution {
    /// The original challenge string.
    pub challenge: String,
    /// The solution string found by the solver.
    pub solution: String,
}

/// Solves a Proof-of-Work challenge using the optimized bit-check algorithm.
/// Returns a solution that the server can verify to grant access to protected endpoints.
pub fn solve_pow(challenge: PoWChallenge) -> Result<PoWSolution, ShadowMeshError> {
    let (challenge_out, solution) =
        crate::pow::solve_pow(challenge.challenge.clone(), challenge.difficulty)?;
    Ok(PoWSolution { challenge: challenge_out, solution })
}

/// Generates a new X25519 keypair for WireGuard tunnels.
/// Returns a vector where \[0\] is the base64 PrivateKey and \[1\] is the base64 PublicKey.
pub fn generate_wireguard_keys() -> Result<Vec<String>, ShadowMeshError> {
    use base64::prelude::*;
    use x25519_dalek::{PublicKey, StaticSecret};

    // CSPRNG (OS entropy): tunnel private keys are long-lived credentials —
    // a weak generator here compromises every tunnel built from it.
    let seed: [u8; 32] = secure_random_bytes(32)
        .and_then(|v| <[u8; 32]>::try_from(v).ok())
        .ok_or_else(|| ShadowMeshError::Other("OS entropy source failed".into()))?;
    let secret = StaticSecret::from(seed);
    let public = PublicKey::from(&secret);

    let private_key_b64 = BASE64_STANDARD.encode(secret.to_bytes());
    let public_key_b64 = BASE64_STANDARD.encode(public.as_bytes());

    Ok(vec![private_key_b64, public_key_b64])
}

/// Validates if a string is a correctly formatted base64 X25519 public key.
pub fn validate_wireguard_key(key_b64: String) -> bool {
    use base64::prelude::*;
    if let Ok(decoded) = BASE64_STANDARD.decode(key_b64) {
        return decoded.len() == 32;
    }
    false
}

/// Parses a raw WireGuard configuration file and extracts the peer and interface metadata.
/// Includes support for custom ShadowMesh 'TrafficMode' metadata in comments.
pub fn parse_wireguard_config(config_str: String) -> Result<VPNConfig, ShadowMeshError> {
    // This is for local config parsing (e.g. from file)
    // Needs to match internal VPNConfig fields if we want to reuse it
    let mut private_key = String::new();
    let mut public_key = String::new();
    let mut address = String::new();
    let mut endpoint = String::new();
    let mut dns = String::new();
    let mut mtu = 1420;
    let mut traffic_mode = "normal".to_string();

    for line in config_str.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            if line.contains("TrafficMode") {
                if let Some(mode) = line.split(':').nth(1) {
                    traffic_mode = mode.trim().to_string();
                }
            }
            continue;
        }

        if let Some((key, value)) = line.split_once('=') {
            let key = key.trim().to_lowercase();
            let value = value.trim();
            match key.as_str() {
                "privatekey" => private_key = value.to_string(),
                "publickey" => public_key = value.to_string(),
                "address" => address = value.to_string(),
                "endpoint" => endpoint = value.to_string(),
                "dns" => dns = value.to_string(),
                "mtu" => mtu = value.parse().unwrap_or(1420),
                _ => {}
            }
        }
    }

    let config = VPNConfig {
        private_key: Some(private_key),
        public_key,
        address,
        endpoint,
        dns,
        mtu,
        traffic_mode,
        reality_config: None,
        shadowsocks_config: None,
        hysteria_config: None,
        vmess_config: None,
    };
    // F89: refuse an out-of-range MTU here, at parse time, rather than letting
    // it size the TUN device and abort the process on the first large packet.
    config.validate()?;
    Ok(config)
}
