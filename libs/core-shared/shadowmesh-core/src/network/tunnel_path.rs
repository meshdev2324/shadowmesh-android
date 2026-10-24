//! The tunnel-path differential — two legs to the **same** endpoint, one
//! through the tunnel and one over the underlying network.
//!
//! Implementation Source:
//! - Specification: RFC-047 §3 (F52's replacement metric). No GPL/AGPL proxy
//!   source is consulted or ingested.
//!
//! # What is measured
//!
//! The **signed difference**, in milliseconds, between two round-trip times to
//! the **same** endpoint, taken with the **same** method:
//! `leg_over_tunnel − leg_off_tunnel`. It is the marginal cost of routing
//! through the tunnel.
//!
//! # Why a differential and not an in-tunnel probe
//!
//! An in-tunnel RTT would need something inside the tunnel that answers, and
//! there is none: the node's tunnel-internal subnet carries no echo service.
//! An **external** UDP echo service is not available to this product — a
//! periodic beacon to a third party discloses the user's tunnel state to a
//! party that did not ask for it, which on a censorship-circumvention tool is
//! a disclosure failure, not a metrics inconvenience (RFC-047 §3.2).
//!
//! The differential needs no new protocol, no new node service and no third
//! party.
//!
//! # The two legs
//!
//! * **Over the tunnel** — the socket is left alone, so it routes through the
//!   TUN: device → TUN → transport → node → the node's uplink → the edge.
//! * **Off the tunnel** — the socket is **protected**, that is excluded from
//!   the VPN by the platform's [`crate::SocketProtector`], so it goes device →
//!   underlying network → the edge.
//!
//! Both legs hit `{base_url}/api/v1/speedtest/ping`, the same URL the
//! control-plane latency figure uses, so the two legs differ **only** in the
//! path and never in the target. The address is resolved **once** per
//! measurement and both legs use it, so a warm resolver cannot make the
//! second leg look cheaper than the first.
//!
//! ⚠️ **This is NOT a tunnel RTT.** The difference includes the node→edge leg,
//! which is the node's uplink, not the tunnel. That is exactly why the name
//! says *path cost*: a name claiming more than its evidence carries is
//! RFC-043 R4's defect.
//!
//! # A platform without a protector
//!
//! On a platform with no [`crate::SocketProtector`], the off-tunnel leg cannot
//! be taken: the socket would route through the tunnel again and the
//! difference would be zero **by construction**. So the off-tunnel leg fails
//! **closed** — the socket is refused, the leg is short, and the measurement
//! is `None`. That is the same answer as "this platform cannot measure it",
//! and it is the honest one: a zero that means "the tunnel cost nothing" is
//! the failure this whole module exists to prevent.

use crate::ShadowMeshError;
use async_trait::async_trait;
use socket2::{Domain, Socket, Type};
use std::net::SocketAddr;
use std::os::fd::AsRawFd;
use std::sync::Arc;
use std::time::{Duration, Instant};

/// The path both legs are measured against.
///
/// The same endpoint the control-plane latency figure uses
/// (`{base_url}/api/v1/speedtest/ping`), so the two legs differ only in the
/// path and never in the target.
pub const PATH_PROBE_PATH: &str = "/api/v1/speedtest/ping";

/// How long one probe may take before it counts as a hole rather than a
/// sample.
///
/// Five seconds. The control-plane figure's HTTP client allows thirty, but
/// this measurement runs **both legs back to back** and a blackholed endpoint
/// must not hold the caller for minutes.
const PROBE_TIMEOUT: Duration = Duration::from_secs(5);

/// The two legs of the differential, as raw samples in milliseconds.
///
/// A leg's samples are the round trips that **survived**. A probe that failed
/// or timed out is a hole, not a zero: a hole makes the leg short, and a
/// short leg yields no figure at all.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct TunnelPathLegs {
    /// Samples taken **through** the tunnel.
    pub over_tunnel_ms: Vec<u32>,
    /// Samples taken **over the underlying network**.
    pub off_tunnel_ms: Vec<u32>,
}

/// Measures the two legs of the tunnel-path differential.
///
/// The two legs must differ in **exactly one** thing: the path. The endpoint,
/// the request and the resolved address are the same for both.
#[async_trait]
pub trait TunnelPathProbe: Send + Sync {
    /// Take `samples` round trips on each leg, back to back, in one call.
    ///
    /// Both legs in one call is the contract: the on-tunnel leg is
    /// meaningless with the tunnel down and the off-tunnel leg is meaningless
    /// with the underlying network down, so the two are only comparable when
    /// they are taken together, while the tunnel is up.
    ///
    /// A leg whose samples could not be taken comes back **short**, not zero.
    /// The caller applies the sample floor, and a short leg yields no figure
    /// rather than a fabricated one.
    async fn sample_legs(&self, samples: usize) -> Result<TunnelPathLegs, ShadowMeshError>;
}

/// A probe socket, before it is connected.
///
/// The socket is created for one leg and, for the off-tunnel leg, excluded
/// from the tunnel by the platform's [`crate::SocketProtector`].
///
/// This type exists as a public seam because the property it owns — *the
/// off-tunnel leg's socket is protected and the over-tunnel leg's is not* — is
/// the property the whole differential rests on, and it must be observable
/// without a network.
pub struct ProbeSocket(Socket);

impl ProbeSocket {
    /// Open the socket for one leg.
    ///
    /// `off_tunnel` selects the leg:
    ///
    /// * `true` — the socket is **protected**, that is excluded from the
    ///   tunnel, through the registered [`crate::SocketProtector`]. This is
    ///   the off-tunnel leg. On a platform with no protector this **fails**,
    ///   because an unprotected socket on this leg would measure the tunnel
    ///   again and the difference would be zero by construction.
    /// * `false` — the socket is left alone, so it routes **through** the
    ///   tunnel. This is the over-tunnel leg, and protecting it would defeat
    ///   the measurement.
    ///
    /// The socket is returned unconnected and non-blocking.
    pub fn open(addr: SocketAddr, off_tunnel: bool) -> Result<Self, ShadowMeshError> {
        let socket = Socket::new(Domain::for_address(addr), Type::STREAM, None)
            .map_err(|e| ShadowMeshError::IoError(format!("probe socket creation failed: {e}")))?;

        if off_tunnel && !crate::protect_socket(socket.as_raw_fd()) {
            return Err(ShadowMeshError::IoError(
                "the off-tunnel leg needs a socket the platform can exclude from the tunnel, \
                 and the platform refused"
                    .into(),
            ));
        }

        socket.set_nonblocking(true).map_err(|e| {
            ShadowMeshError::IoError(format!("probe socket could not be made non-blocking: {e}"))
        })?;

        Ok(Self(socket))
    }

    /// The socket's file descriptor.
    pub fn as_raw_fd(&self) -> i32 {
        self.0.as_raw_fd()
    }

    /// Start the connection and hand the socket to tokio.
    async fn connect(self, addr: SocketAddr) -> Result<tokio::net::TcpStream, ShadowMeshError> {
        match self.0.connect(&addr.into()) {
            Ok(_) => {}
            // A non-blocking connect in progress is the expected answer.
            Err(e) if e.raw_os_error() == Some(libc::EINPROGRESS) => {}
            Err(e) => {
                return Err(ShadowMeshError::IoError(format!("probe connect failed: {e}")));
            }
        }

        let std_stream: std::net::TcpStream = self.0.into();
        let tcp = tokio::net::TcpStream::from_std(std_stream)
            .map_err(|e| ShadowMeshError::IoError(format!("probe socket handover failed: {e}")))?;

        // Writable on a connecting socket means the connect finished.
        tcp.writable()
            .await
            .map_err(|e| ShadowMeshError::IoError(format!("probe connect failed: {e}")))?;

        // `take_error` reports the pending socket error, if any: a connect
        // that failed is not visible from `writable()` alone.
        if let Ok(Some(e)) = tcp.take_error() {
            return Err(ShadowMeshError::IoError(format!("probe connect failed: {e}")));
        }

        Ok(tcp)
    }
}

/// The default probe: an HTTP/1.1 `GET` to `{base_url}/api/v1/speedtest/ping`,
/// taken on a socket that either routes through the tunnel or is protected
/// from it.
pub struct HttpTunnelPathProbe {
    base_url: String,
}

impl HttpTunnelPathProbe {
    /// A probe against `{base_url}/api/v1/speedtest/ping`.
    ///
    /// Infallible: a base URL that cannot be parsed is discovered when the
    /// measurement is taken, and it is discovered as a failure of the
    /// measurement rather than as a failure to construct one.
    pub fn new(base_url: impl Into<String>) -> Self {
        Self { base_url: base_url.into() }
    }
}

#[async_trait]
impl TunnelPathProbe for HttpTunnelPathProbe {
    async fn sample_legs(&self, samples: usize) -> Result<TunnelPathLegs, ShadowMeshError> {
        let endpoint = ProbeEndpoint::from_base_url(&self.base_url)?;
        let addresses = endpoint.resolve().await?;

        let mut legs = TunnelPathLegs::default();
        for _ in 0..samples {
            // Back to back, one leg then the other: a link that drifts in the
            // middle of the measurement moves both legs the same way.
            if let Some(ms) = self.probe(&endpoint, &addresses, false).await {
                legs.over_tunnel_ms.push(ms);
            }
            if let Some(ms) = self.probe(&endpoint, &addresses, true).await {
                legs.off_tunnel_ms.push(ms);
            }
        }

        Ok(legs)
    }
}

impl HttpTunnelPathProbe {
    /// One round trip on one leg. `None` is a hole, not a zero.
    async fn probe(
        &self,
        endpoint: &ProbeEndpoint,
        addresses: &[SocketAddr],
        off_tunnel: bool,
    ) -> Option<u32> {
        let mut last: Option<ShadowMeshError> = None;
        for addr in addresses {
            match tokio::time::timeout(PROBE_TIMEOUT, self.probe_addr(endpoint, *addr, off_tunnel))
                .await
            {
                Ok(Ok(ms)) => return Some(ms),
                Ok(Err(e)) => last = Some(e),
                Err(_) => {
                    last = Some(ShadowMeshError::Other("probe timed out".into()));
                }
            }
        }
        tracing::debug!("tunnel-path probe leg failed: {last:?}");
        None
    }

    /// One round trip to one address, on one leg.
    async fn probe_addr(
        &self,
        endpoint: &ProbeEndpoint,
        addr: SocketAddr,
        off_tunnel: bool,
    ) -> Result<u32, ShadowMeshError> {
        let started = Instant::now();

        let socket = ProbeSocket::open(addr, off_tunnel)?;
        let tcp = socket.connect(addr).await?;

        let mut stream: Box<dyn ProbeStream> = if endpoint.tls {
            let connector = tls_connector()?;
            let name = rustls::pki_types::ServerName::try_from(endpoint.host.clone())
                .map_err(|e| ShadowMeshError::Other(format!("invalid TLS server name: {e}")))?;
            let tls = connector
                .connect(name, tcp)
                .await
                .map_err(|e| ShadowMeshError::Other(format!("probe TLS handshake failed: {e}")))?;
            Box::new(tls)
        } else {
            Box::new(tcp)
        };

        let request = format!(
            "GET {path} HTTP/1.1\r\n\
             Host: {host}\r\n\
             User-Agent: ShadowMesh/1.0.0 (Native; Rust)\r\n\
             Accept: */*\r\n\
             Connection: close\r\n\
             \r\n",
            path = PATH_PROBE_PATH,
            host = endpoint.host_header.as_str(),
        );

        use tokio::io::AsyncWriteExt;
        stream
            .write_all(request.as_bytes())
            .await
            .map_err(|e| ShadowMeshError::Other(format!("probe request write failed: {e}")))?;
        stream
            .flush()
            .await
            .map_err(|e| ShadowMeshError::Other(format!("probe request flush failed: {e}")))?;

        let head = read_response_head(&mut stream).await?;
        if !is_success(&head) {
            return Err(ShadowMeshError::Other(
                "probe endpoint did not answer with a success status".into(),
            ));
        }

        Ok(started.elapsed().as_millis() as u32)
    }
}

/// The endpoint both legs are measured against.
#[derive(Debug, Clone)]
struct ProbeEndpoint {
    host: String,
    port: u16,
    /// The `Host:` header value, which carries the port when it is not the
    /// scheme's default.
    host_header: String,
    tls: bool,
}

impl ProbeEndpoint {
    fn from_base_url(base_url: &str) -> Result<Self, ShadowMeshError> {
        let parsed = url::Url::parse(base_url)
            .map_err(|e| ShadowMeshError::Other(format!("probe endpoint is not a URL: {e}")))?;

        let tls = match parsed.scheme() {
            "https" => true,
            "http" => false,
            other => {
                return Err(ShadowMeshError::Other(format!(
                    "probe endpoint scheme '{other}' is neither http nor https"
                )))
            }
        };

        let host = parsed
            .host_str()
            .ok_or_else(|| ShadowMeshError::Other("probe endpoint has no host".into()))?
            .to_string();
        let port = parsed
            .port_or_known_default()
            .ok_or_else(|| ShadowMeshError::Other("probe endpoint has no port".into()))?;
        let host_header = match parsed.port() {
            Some(p) => format!("{host}:{p}"),
            None => host.clone(),
        };

        Ok(Self { host, port, host_header, tls })
    }

    /// The addresses the endpoint resolves to.
    ///
    /// Resolved **once** per measurement, and both legs use the result, so
    /// the two legs talk to the same server and a warm resolver cannot make
    /// the second leg look cheaper than the first.
    async fn resolve(&self) -> Result<Vec<SocketAddr>, ShadowMeshError> {
        let lookup = tokio::net::lookup_host((self.host.as_str(), self.port));
        let addresses = tokio::time::timeout(PROBE_TIMEOUT, lookup)
            .await
            .map_err(|_| ShadowMeshError::Other("probe endpoint lookup timed out".into()))?
            .map_err(|e| ShadowMeshError::Other(format!("probe endpoint lookup failed: {e}")))?
            .collect::<Vec<_>>();

        if addresses.is_empty() {
            return Err(ShadowMeshError::Other("probe endpoint resolved to no address".into()));
        }
        Ok(addresses)
    }
}

/// A byte stream the probe can both read and write.
trait ProbeStream: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send {}
impl<T: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send> ProbeStream for T {}

/// Read the response head, up to and including the blank line that ends it.
///
/// The clock stops when the head is complete, which is the same moment the
/// control-plane figure's client stops its own clock: the response **headers**
/// have arrived, not the body.
async fn read_response_head(stream: &mut Box<dyn ProbeStream>) -> Result<Vec<u8>, ShadowMeshError> {
    use tokio::io::AsyncReadExt;

    let mut head = Vec::with_capacity(256);
    let mut buf = [0u8; 512];
    loop {
        let n = stream
            .read(&mut buf)
            .await
            .map_err(|e| ShadowMeshError::Other(format!("probe response read failed: {e}")))?;
        if n == 0 {
            return Err(ShadowMeshError::Other(
                "probe response ended before its headers did".into(),
            ));
        }
        head.extend_from_slice(&buf[..n]);

        if head.windows(4).any(|w| w == b"\r\n\r\n".as_slice()) {
            return Ok(head);
        }
        if head.len() > 16 * 1024 {
            return Err(ShadowMeshError::Other(
                "probe response headers are implausibly long".into(),
            ));
        }
    }
}

/// Whether the response head carries a success status.
fn is_success(head: &[u8]) -> bool {
    head.starts_with(b"HTTP/1.1 2") || head.starts_with(b"HTTP/1.0 2")
}

/// The TLS client configuration the probe uses.
///
/// The platform trust store, verified — the same policy the transports use
/// (`transport/tls_trust`). A probe that skipped verification would measure
/// a different endpoint than the one it claims to.
fn tls_connector() -> Result<tokio_rustls::TlsConnector, ShadowMeshError> {
    let builder = rustls::ClientConfig::builder_with_provider(Arc::new(
        rustls::crypto::ring::default_provider(),
    ))
    .with_safe_default_protocol_versions()
    .map_err(|e| ShadowMeshError::Other(format!("probe TLS configuration failed: {e}")))?;

    let config = builder
        .with_root_certificates(crate::transport::tls_trust::root_store())
        .with_no_client_auth();

    Ok(tokio_rustls::TlsConnector::from(Arc::new(config)))
}
