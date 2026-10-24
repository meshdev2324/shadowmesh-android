use crate::NodeRoster;
use crate::PublicNode;
use crate::ShadowMeshError;
use serde::Deserialize;
use std::sync::Arc;
use tokio::sync::RwLock;
use tracing::{error, info, warn};

/// RFC-004 channel 2: the edge-computed Cloudflare Worker.
///
/// **Single definition site.** This literal was previously written twice inside
/// `ApiClient::new` and `ApiClient::with_pins`, so the two constructors could
/// drift and did. One owner per wire invariant.
pub const DEFAULT_DISCOVERY_WORKER_URL: &str = "https://discovery.shadowmesh.workers.dev/nodes";

/// RFC-004 channel 3: the DNS-over-HTTPS TXT discovery domain.
///
/// Single definition site, for the same reason as [`DEFAULT_DISCOVERY_WORKER_URL`].
pub const DEFAULT_DISCOVERY_DOMAIN: &str = "nodes.shadowmesh.org";

/// Supported discovery channels for node list retrieval.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DiscoveryChannel {
    /// Standard API endpoint.
    PrimaryApi,
    /// Edge-computed Cloudflare Worker.
    CloudflareWorker,
    /// DNS-over-HTTPS TXT record.
    DnsOverHttps,
}

/// A resilient engine that cascades through multiple discovery channels
/// to retrieve the latest VPN node list.
pub struct ResilientDiscoveryEngine {
    api_base_url: String,
    worker_url: String,
    doh_endpoints: Vec<String>,
    discovery_domain: String,
    master_key: String,
    /// The caller's session, shared with [`crate::api_client::ApiClient`].
    ///
    /// The cascade runs its own `reqwest::Client` and therefore never went
    /// through `ApiClient::add_headers`. That meant channel 1 of this cascade
    /// asked the control plane *anonymously*, so `/api/v1/nodes` answered with
    /// the `NodeListEntry::Public` projection (`id`, `country`, `region`,
    /// `is_online`) instead of the `Full` one. `VPNNode` has no `#[serde(default)]`,
    /// so that projection is not merely incomplete, it fails to deserialize and
    /// the whole cascade returns `Err`. The resilient path could therefore never
    /// return a connectable node, for an authenticated or anonymous caller alike.
    ///
    /// `services/server/src/api/handlers/vpn.rs::project_node` is the projection
    /// decision; `session_presented` is the gate. Both are shared with the
    /// non-resilient `ApiClient::get_nodes`, so both paths now present the same
    /// session and receive the same shape.
    session: Arc<RwLock<Option<String>>>,
}

#[derive(Deserialize)]
struct DohResponse {
    #[serde(rename = "Answer")]
    answer: Option<Vec<DohAnswer>>,
}

#[derive(Deserialize)]
struct DohAnswer {
    data: String,
}

/// The manifest the **fallback channels** publish.
///
/// ⚠️ **This is not the control plane's manifest.** The discovery channels
/// (RFC-004: the Cloudflare Worker and the DoH-published TXT record) publish
/// a *node list*; they carry **no projection statement**, because there is no
/// server statement behind them — the publisher is the server, but the
/// document is a list, not an envelope.
///
/// The control plane's `GET /api/v1/manifest` body **does** carry one, and it
/// is parsed into [`crate::GlobalManifest`] — where the projection is
/// required, because absent must be an error rather than a default
/// (RFC-047 §2.6).
///
/// ⇒ Two documents, two types. The client narrows these rows itself, so the
/// projection it states downstream is the one the rows really are
/// ([`crate::NodeRoster::narrowed_locally`]).
#[derive(Deserialize)]
struct PublishedManifest {
    /// The published nodes, in the server's own shape.
    nodes: Vec<crate::VPNNode>,
}

impl ResilientDiscoveryEngine {
    /// Creates a new `ResilientDiscoveryEngine` with default fallbacks.
    ///
    /// `session` is the shared token cell owned by [`crate::api_client::ApiClient`];
    /// the same `Arc` is handed in so a token set after construction is observed
    /// here without a second plumbing path.
    pub fn new(
        api_base_url: String,
        worker_url: String,
        discovery_domain: String,
        session: Arc<RwLock<Option<String>>>,
    ) -> Self {
        Self {
            api_base_url,
            worker_url,
            doh_endpoints: vec![
                "https://cloudflare-dns.com/dns-query".to_string(),
                "https://dns.google/dns-query".to_string(),
            ],
            discovery_domain,
            master_key: "SHADOWMESH_DISCOVERY_PROD_V1".to_string(), // Placeholder for real key logic
            session,
        }
    }

    /// The fallback sources in use: `(worker URL, DoH discovery domain)`.
    #[cfg(test)]
    pub(crate) fn sources(&self) -> (&str, &str) {
        (&self.worker_url, &self.discovery_domain)
    }

    /// Attaches the caller's session to a channel-1 request.
    ///
    /// Mirrors `ApiClient::add_headers` deliberately: both paths hit
    /// `/api/v1/nodes`, and the server picks its projection from this header
    /// alone. Keeping the two in step is what stops the cascade from silently
    /// downgrading to the anonymous projection.
    async fn authorize(&self, request: reqwest::RequestBuilder) -> reqwest::RequestBuilder {
        match self.session.read().await.as_ref() {
            Some(token) => {
                request.header(reqwest::header::AUTHORIZATION, format!("Bearer {}", token))
            }
            None => request,
        }
    }

    /// Attempts to fetch nodes from all channels in order of preference.
    ///
    /// **The roster carries the projection, and the three channels do not
    /// agree about where it comes from.**
    ///
    /// * Channel 1 is the control plane. Its body is the envelope
    ///   (`{"projection": …, "nodes": […]}`) and the projection is the
    ///   server's own statement, carried through untouched (F86,
    ///   RFC-047 §2.2).
    /// * Channels 2 and 3 are **not** the control plane. They publish a
    ///   *manifest*, which this client narrows to the anonymous projection
    ///   itself (see [`NodeRoster::narrowed_locally`]). There is no server
    ///   statement behind those rows, so the honest claim is
    ///   [`crate::NodeProjection::LocalFallback`] — **"no server was asked"**,
    ///   which is **not** the same claim as
    ///   [`crate::NodeProjection::Anonymous`] ("your session is not in force")
    ///   (RFC-049 §15.3.3).
    ///
    /// A body with no `projection` at all does not deserialize: absent is an
    /// error, not a default (RFC-047 §2.6). The cascade then falls through to
    /// the next channel rather than inventing a projection.
    pub async fn fetch_nodes_resilient(&self) -> Result<NodeRoster, ShadowMeshError> {
        let client_builder = reqwest::Client::builder().timeout(std::time::Duration::from_secs(15));

        let client = client_builder.build().map_err(|e| ShadowMeshError::Other(e.to_string()))?;

        // 1. Primary API
        let api_url = format!("{}/api/v1/nodes", self.api_base_url);
        match self.authorize(client.get(&api_url)).await.send().await {
            Ok(resp) if resp.status().is_success() => {
                if let Ok(roster) = resp.json::<NodeRoster>().await {
                    info!("Discovery: Successfully retrieved nodes from Primary API.");
                    return Ok(roster);
                }
            }
            _ => warn!("Discovery: Primary API failed. Falling back to Worker..."),
        }

        // 2. Cloudflare Worker
        match self.fetch_from_worker().await {
            Ok(nodes) => {
                info!("Discovery: Successfully retrieved nodes from Cloudflare Worker.");
                return Ok(NodeRoster::narrowed_locally(nodes));
            }
            Err(e) => warn!("Discovery: Cloudflare Worker failed: {}. Falling back to DoH...", e),
        }

        // 3. DNS-over-HTTPS
        match self.fetch_from_doh().await {
            Ok(nodes) => {
                info!("Discovery: Successfully retrieved nodes from DNS-over-HTTPS.");
                return Ok(NodeRoster::narrowed_locally(nodes));
            }
            Err(e) => error!("Discovery: All channels failed. Last error (DoH): {}", e),
        }

        Err(ShadowMeshError::ConnectionFailed)
    }

    async fn fetch_from_worker(&self) -> Result<Vec<PublicNode>, ShadowMeshError> {
        let client = reqwest::Client::new();
        let request =
            client.get(&self.worker_url).header("X-Shadow-Discovery-Key", &self.master_key);
        let response = self
            .authorize(request)
            .await
            .send()
            .await
            .map_err(|e| ShadowMeshError::IoError(e.to_string()))?;

        if response.status().is_success() {
            let encrypted_payload =
                response.text().await.map_err(|e| ShadowMeshError::IoError(e.to_string()))?;

            self.decrypt_and_parse_nodes(&encrypted_payload)
        } else {
            Err(ShadowMeshError::Other("Worker returned non-success status".into()))
        }
    }

    async fn fetch_from_doh(&self) -> Result<Vec<PublicNode>, ShadowMeshError> {
        let client = reqwest::Client::new();

        for endpoint in &self.doh_endpoints {
            let url = format!("{}?name={}&type=TXT", endpoint, self.discovery_domain);
            let response = client.get(&url).header("Accept", "application/dns-json").send().await;

            if let Ok(resp) = response {
                if resp.status().is_success() {
                    let doh_resp: DohResponse =
                        resp.json().await.map_err(|e| ShadowMeshError::JsonError(e.to_string()))?;

                    if let Some(answers) = doh_resp.answer {
                        // Concatenate multiple TXT records if fragmented
                        let mut full_payload = String::new();
                        for answer in answers {
                            // DoH TXT data usually includes quotes
                            let clean_data = answer.data.trim_matches('"');
                            full_payload.push_str(clean_data);
                        }

                        if !full_payload.is_empty() {
                            return self.decrypt_and_parse_nodes(&full_payload);
                        }
                    }
                }
            }
        }

        Err(ShadowMeshError::DnsResolutionError)
    }

    fn decrypt_and_parse_nodes(&self, payload: &str) -> Result<Vec<PublicNode>, ShadowMeshError> {
        // RFC-004: Payloads are base64-encoded and encrypted.
        // For Phase 1, we assume it's base64-encoded JSON for simplicity.
        use base64::prelude::*;
        let decoded = BASE64_STANDARD
            .decode(payload)
            .map_err(|e| ShadowMeshError::Other(format!("Base64 decode failed: {}", e)))?;

        // Horizon 3 Phase 2: Now expects GlobalManifest
        let manifest: PublishedManifest = serde_json::from_slice(&decoded)
            .map_err(|e| ShadowMeshError::JsonError(e.to_string()))?;

        // Narrow the richer manifest nodes down to the anonymous projection.
        // Connect material (`endpoint`, `public_key`) is deliberately dropped:
        // it is not served anonymously, and the authenticated
        // `GET /api/v1/config/:server_id` is the path that carries it.
        //
        // Anycast VIPs are deliberately NOT synthesised into nodes here. The
        // previous code built them with `public_key: String::new()`, which
        // deserialises fine and then produces a connect attempt against `""`.
        // Failing closed means dropping them from the anonymous path instead.
        Ok(manifest
            .nodes
            .into_iter()
            .map(|n| PublicNode {
                id: n.id,
                country: n.country,
                region: n.region,
                is_online: n.is_online,
            })
            .collect())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api_client::ApiClient;
    use base64::prelude::*;
    use mockito::Server;
    use serde::Serialize;
    use serde_json::json;
    use std::sync::Arc;

    /// The **published** document's own shape — the fixture the fallback
    /// channels publish.
    ///
    /// ⚠️ **This is deliberately NOT `crate::GlobalManifest`.** The control
    /// plane's manifest is a **listing** and carries `PublicNode` rows
    /// (RFC-050 §2.2); the *published* document is the **publisher's** shape,
    /// and `PublishedManifest` above reads `Vec<crate::VPNNode>` and narrows
    /// it to the same `PublicNode` (RFC-050 §3.1 item 6, §5's R6).
    ///
    /// ⇒ The fixture publishes what the publisher publishes, and the
    /// narrowing it exercises is the real one. The JSON it produces is
    /// byte-for-byte what the previous fixture produced.
    #[derive(Serialize)]
    struct PublishedManifestFixture {
        /// Inert on this path — `PublishedManifest` reads the nodes only —
        /// but present, because the published document carries it.
        projection: crate::NodeProjection,
        nodes: Vec<crate::VPNNode>,
        anycast_vips: Vec<crate::AnycastVip>,
        version: String,
    }

    #[tokio::test]
    async fn test_discovery_cascade_fallback() {
        let mut server = Server::new_async().await;
        let url = server.url();

        // 1. Mock API fails
        let _m1 = server.mock("GET", "/api/v1/nodes").with_status(500).create_async().await;

        // 2. Mock Worker succeeds (but returns base64 encoded manifest)
        let nodes = vec![crate::VPNNode {
            id: "test".into(),
            name: "Test".into(),
            region: "US".into(),
            country: "US".into(),
            endpoint: "1.2.3.4:443".into(),
            public_key: "pub".into(),
            load: 0,
            latency: 0,
            is_sovereign: false,
            is_online: true,
            shard_id: None,
        }];
        let manifest = PublishedManifestFixture {
            // The Worker is **not** the control plane, so it has no server
            // statement to publish. The field is inert on this path
            // (`PublishedManifest` reads only the nodes), but the value in
            // the fixture must be the honest one: the client's own claim.
            projection: crate::NodeProjection::LocalFallback,
            nodes,
            anycast_vips: vec![crate::AnycastVip {
                id: 1,
                ip_address: "100.64.0.1".into(),
                label: "Anycast".into(),
                is_active: true,
            }],
            version: "v0.3.0".into(),
        };
        let manifest_json = serde_json::to_string(&manifest).unwrap();
        let encoded = BASE64_STANDARD.encode(manifest_json);

        let _m2 = server
            .mock("GET", "/worker/nodes")
            .with_status(200)
            .with_body(encoded)
            .create_async()
            .await;

        let _api_client = Arc::new(ApiClient::new(url.clone()).unwrap());
        let engine = ResilientDiscoveryEngine::new(
            url.clone(),
            format!("{}/worker/nodes", url),
            "nodes.test".into(),
            Arc::new(RwLock::new(None)),
        );

        let result = engine.fetch_nodes_resilient().await.unwrap();
        // Only the real node survives. The Anycast VIP in the manifest is
        // deliberately NOT synthesised into a node on this anonymous path:
        // it previously produced `public_key: String::new()`, which
        // deserialises fine and then drives a connect attempt against `""`.
        // Failing closed means dropping it here.
        assert_eq!(result.nodes.len(), 1);
        assert!(result.nodes.iter().any(|n| n.id == "test"));
        // The Worker is not the control plane: **no server was asked**, so
        // the client's own claim is `LocalFallback` — and it is NOT the
        // server's `Anonymous` ("your session is not in force").
        // Conflating the two is what made F86's claim unexpressible
        // (RFC-049 §15.3.3).
        assert_eq!(result.projection, crate::NodeProjection::LocalFallback);
    }

    #[tokio::test]
    async fn test_doh_fallback_parsing() {
        let mut server = Server::new_async().await;
        let url = server.url();

        // All previous channels fail
        let _m1 = server.mock("GET", "/api/v1/nodes").with_status(404).create_async().await;
        let _m2 = server.mock("GET", "/worker/nodes").with_status(404).create_async().await;

        // DoH succeeds
        let nodes = vec![crate::VPNNode {
            id: "doh".into(),
            name: "DOH".into(),
            region: "DE".into(),
            country: "DE".into(),
            endpoint: "5.6.7.8:443".into(),
            public_key: "pub".into(),
            load: 10,
            latency: 20,
            is_sovereign: false,
            is_online: true,
            shard_id: None,
        }];
        let manifest = PublishedManifestFixture {
            // The DoH TXT record is published by the fallback, not by the
            // control plane: the client's own claim, not the server's.
            projection: crate::NodeProjection::LocalFallback,
            nodes,
            anycast_vips: Vec::new(),
            version: "v1.0.0".into(),
        };
        let encoded = BASE64_STANDARD.encode(serde_json::to_string(&manifest).unwrap());

        let doh_response = json!({
            "Status": 0,
            "Answer": [{ "name": "nodes.test", "type": 16, "data": format!("\"{}\"", encoded) }]
        });

        let _m3 = server
            .mock("GET", "/dns-query?name=nodes.test&type=TXT")
            .with_status(200)
            .with_header("content-type", "application/dns-json")
            .with_body(serde_json::to_string(&doh_response).unwrap())
            .create_async()
            .await;

        let mut engine = ResilientDiscoveryEngine::new(
            url.clone(),
            format!("{}/worker/nodes", url),
            "nodes.test".into(),
            Arc::new(RwLock::new(None)),
        );
        engine.doh_endpoints = vec![format!("{}/dns-query", url)];

        let result = engine.fetch_nodes_resilient().await.unwrap();
        assert_eq!(result.nodes.len(), 1);
        assert_eq!(result.nodes[0].id, "doh");
    }

    /// Regression: the cascade must present the caller's session on channel 1.
    ///
    /// Before the fix this request went out with no `Authorization` header, so
    /// `project_node` in `services/server` answered `NodeListEntry::Public` and
    /// the whole cascade failed to deserialize. This asserts on the header the
    /// server actually keys its projection off, against the `Full` projection
    /// it returns when that header carries a session.
    #[tokio::test]
    async fn resilient_primary_presents_session_and_returns_connectable_node() {
        let mut server = Server::new_async().await;
        let url = server.url();

        let full = json!({
            "projection": "full",
            "nodes": [{
                "id": "1", "name": "SG-1", "region": "SG", "country": "SG",
                "endpoint": "10.0.0.1:51820", "public_key": "pubkey", "load": 5,
                "latency": 21, "is_sovereign": true, "is_online": true, "shard_id": null
            }]
        });

        let mock = server
            .mock("GET", "/api/v1/nodes")
            .match_header("authorization", "Bearer test-jwt")
            .with_status(200)
            .with_header("content-type", "application/json")
            .with_body(full.to_string())
            .create_async()
            .await;

        let session = Arc::new(RwLock::new(Some("test-jwt".to_string())));
        let engine = ResilientDiscoveryEngine::new(
            url.clone(),
            format!("{}/worker/nodes", url),
            "nodes.test".into(),
            Arc::clone(&session),
        );

        let result = engine.fetch_nodes_resilient().await.unwrap();

        // The header was really sent (not merely that the call succeeded).
        mock.assert_async().await;

        // The projection carries only directory fields. Connect material is
        // deliberately absent even when the server is handed a full node, so a
        // client can never build a connection from the anonymous channel.
        assert_eq!(result.nodes.len(), 1);
        assert_eq!(result.nodes[0].id, "1");
        assert!(result.nodes[0].is_online);
        // The control plane's own statement, carried through untouched (F86).
        assert_eq!(result.projection, crate::NodeProjection::Full);
    }

    /// The full production path, end to end through the FFI-facing API:
    /// `set_auth_token` must reach the cascade, because the cascade is what the
    /// Android client falls back to whenever `getNodes()` is empty or throws
    /// (`VPNManagerViewModel.refreshNodes`).
    #[tokio::test]
    async fn api_client_auth_token_reaches_the_resilient_cascade() {
        let mut server = Server::new_async().await;
        let url = server.url();

        let full = json!({
            "projection": "full",
            "nodes": [{
                "id": "1", "name": "SG-1", "region": "SG", "country": "SG",
                "endpoint": "10.0.0.1:51820", "public_key": "pubkey", "load": 5,
                "latency": 21, "is_sovereign": true, "is_online": true, "shard_id": null
            }]
        });

        let mock = server
            .mock("GET", "/api/v1/nodes")
            .match_header("authorization", "Bearer test-jwt")
            .with_status(200)
            .with_header("content-type", "application/json")
            .with_body(full.to_string())
            .create_async()
            .await;

        let client = ApiClient::new(url.clone()).unwrap();
        client.set_auth_token(Some("test-jwt".to_string()));
        let roster = client.get_nodes_async().await.unwrap();

        mock.assert_async().await;
        // The anonymous projection carries directory fields only.
        assert_eq!(roster.nodes[0].country, "SG");
        assert!(roster.nodes[0].is_online);
    }

    /// The anonymous projection cannot become a `VPNNode`, which is exactly why
    /// the header above is load-bearing rather than an optimization.
    ///
    /// This is the production **row** shape observed on
    /// `GET https://api.shadowmesh.org/api/v1/nodes` with no session:
    /// `{"id":"1","country":"SG","region":"SG","is_online":true}`.
    ///
    /// ⚠️ The **roster** body is the envelope that carries those rows
    /// (`{"projection": …, "nodes": [ … ]}`, F86 / RFC-047 §2.2) — but the
    /// row itself is unchanged, and this assertion is about the row: an
    /// anonymous row must never satisfy `VPNNode`, or the connect material
    /// could be defaulted in and dialled.
    #[test]
    fn anonymous_projection_is_not_a_connectable_node() {
        let public = r#"[{"id":"1","country":"SG","region":"SG","is_online":true}]"#;

        assert!(
            serde_json::from_str::<Vec<crate::VPNNode>>(public).is_err(),
            "if this ever starts parsing, the anonymous projection grew fields \
             and the Authorization requirement can be re-examined"
        );
    }

    /// One owner per wire invariant: both constructors must build the same
    /// fallback sources, so a URL can never be corrected in one and forgotten
    /// in the other. This is the class of drift that shipped the dead Worker
    /// literal in two places at once.
    #[test]
    fn api_client_constructors_share_one_default_source_definition() {
        let client = ApiClient::new("https://api.shadowmesh.org".to_string()).unwrap();
        assert_eq!(
            client.discovery_sources(),
            (DEFAULT_DISCOVERY_WORKER_URL, DEFAULT_DISCOVERY_DOMAIN)
        );

        // `with_pins` builds a rustls config, which needs a process-level
        // provider. nextest gives each test its own process, so installing here
        // cannot race anything else.
        let _ = rustls::crypto::ring::default_provider().install_default();

        let pinned =
            ApiClient::with_pins("https://api.shadowmesh.org".to_string(), vec![]).unwrap();
        assert_eq!(
            pinned.discovery_sources(),
            (DEFAULT_DISCOVERY_WORKER_URL, DEFAULT_DISCOVERY_DOMAIN)
        );
    }

    /// The override mechanism: a caller can point channels 2 and 3 at hosts that
    /// exist without touching channel 1 or the UDL.
    #[tokio::test]
    async fn discovery_sources_are_overridable() {
        let mut server = Server::new_async().await;
        let url = server.url();

        let manifest = PublishedManifestFixture {
            projection: crate::NodeProjection::Anonymous,
            nodes: vec![crate::VPNNode {
                id: "overridden".into(),
                name: "Overridden".into(),
                region: "SG".into(),
                country: "SG".into(),
                endpoint: "10.0.0.9:51820".into(),
                public_key: "pub".into(),
                load: 0,
                latency: 0,
                is_sovereign: false,
                is_online: true,
                shard_id: None,
            }],
            anycast_vips: vec![],
            version: "v0.3.0".into(),
        };
        let encoded = BASE64_STANDARD.encode(serde_json::to_string(&manifest).unwrap());

        // Channel 1 is dead; the caller's own worker URL answers instead of the
        // default one.
        let _dead_primary =
            server.mock("GET", "/api/v1/nodes").with_status(500).create_async().await;
        let mock = server
            .mock("GET", "/custom/worker")
            .with_status(200)
            .with_body(encoded)
            .create_async()
            .await;

        let client = ApiClient::with_discovery_sources(
            url.clone(),
            format!("{}/custom/worker", url),
            "nodes.test".into(),
        )
        .unwrap();

        let result = client.get_nodes_resilient_async().await.unwrap();
        mock.assert_async().await;
        assert_eq!(result.nodes[0].id, "overridden");
        assert!(result.nodes[0].is_online); // projection: no endpoint
    }

    /// **R6 — the published document narrows to the SAME row type the
    /// control plane's manifest now carries.**
    ///
    /// The control plane's manifest is a **listing** and carries
    /// `PublicNode` rows (RFC-050 §2.2). The **published** document is the
    /// *publisher's* shape — it is still the richer row — and the cascade
    /// narrows it.
    ///
    /// ⇒ **Both routes end at `PublicNode`**, so the cascade's private row
    /// type is no longer a **fourth vocabulary**. The control plane's half of
    /// this assertion is a *type-level* one and lives in the integration
    /// test (`tests/f103_manifest_row_contract_test.rs`); this is the
    /// published half, and it can only be asserted here, because
    /// `PublishedManifest` is private to this module.
    #[tokio::test]
    async fn the_published_manifest_narrows_to_the_same_row_type() {
        let published = PublishedManifestFixture {
            projection: crate::NodeProjection::LocalFallback,
            nodes: vec![crate::VPNNode {
                id: "published-1".into(),
                name: "Published 1".into(),
                region: "SG".into(),
                country: "SG".into(),
                endpoint: "10.0.0.1:51820".into(),
                public_key: "pubkey".into(),
                load: 5,
                latency: 21,
                is_sovereign: true,
                is_online: true,
                shard_id: None,
            }],
            anycast_vips: vec![],
            version: "v0.3.0".into(),
        };
        let encoded = BASE64_STANDARD.encode(serde_json::to_string(&published).unwrap());

        let engine = ResilientDiscoveryEngine::new(
            "http://127.0.0.1:1".into(),
            "http://127.0.0.1:1/worker/nodes".into(),
            "nodes.test".into(),
            Arc::new(RwLock::new(None)),
        );

        // **The type-level assertion:** the narrowing produces `PublicNode`
        // rows — the same type `GlobalManifest.nodes` now carries.
        let rows: Vec<crate::PublicNode> =
            engine.decrypt_and_parse_nodes(&encoded).expect("the published document must parse");

        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].id, "published-1");
        assert_eq!(rows[0].country, "SG");
        assert_eq!(rows[0].region, "SG");
        assert!(rows[0].is_online);
        // The connect material is **dropped**, not carried: the published
        // document is not a connect surface (RFC-043 R2).
    }
}
