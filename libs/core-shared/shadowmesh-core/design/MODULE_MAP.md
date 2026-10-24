# ShadowMesh Core Module Map

> **Generated from the tree.** Every path below is a directory or file that exists in
> `libs/core-shared/shadowmesh-core/src/` at the commit named in the provenance line.
> This map is checked by a test — see "Drift guard" below. **Do not hand-edit a row without
> running the guard.**

**Provenance:** `libs/core-shared/shadowmesh-core` @ `40fab07`

*No file has been added to or removed from `src/` since `e25d160` (the read RFC-047 §4 was
taken at); the commits since then changed file contents, not the shape of the tree. The
`fuzz/` workspace and the F86/F92–F95 test files appeared after that read and are recorded
in §4 and §5.*

## 0. Two programs live in this crate

This crate contains **two engines**, not one. They share the abstractions in `engine/` and
`transport/traits.rs` and diverge at dispatch. Read this before reading any module list.

| | **World A — the unified client engine** | **World B — the composed system** |
| :--- | :--- | :--- |
| Entry | `transport/engine.rs` — `start_engine(fd, config)` | `engine/system.rs` — `ShadowMeshSystem::new(config)` |
| Wired from | `apps/daemon/src/native_engine.rs` (thread/FFI) | `src/main.rs` — the `shadowmesh` binary; `tests/edge_system_interop_test.rs` |
| Dispatch | `TransportStack::send` / `recv` — one active transport, **no routing** | `Dispatcher` + `RoutingPipeline` + `OutboundRegistry` |
| DNS | **none** (raw forwarding) | `DnsRouter` + `FakeIPEngine` |
| Event channel | none | `async_channel::bounded(4096)` |

**World A is the shipped client.** World B is reachable only through the `shadowmesh` binary's
configuration. Do not read a routing or DNS behaviour in `engine/` and assume the client has
it.

**This split is the project's structural truth (RFC-046 §2) and nothing else in the repository
used to name it.** A maintainer reading the previous version of this map learned a
single-program architecture that does not exist — and every later "just add routing to the
client engine" proposal re-discovered the split expensively. The map now names it, and the
drift guard below keeps the names honest.

## 1. Module directories

| Module | Path | What it owns |
| :--- | :--- | :--- |
| `api` | `api/` | Tonic gRPC control API (`ControlApi`, `ShadowMeshControlServer`) over the generated proto. **Not** the HTTP control plane — that is the `services/server/` crate. |
| `config` | `config/` | Typed per-protocol inbound/outbound settings (RFC-012 G4) and `UserSettings`. `config/settings.rs` holds the strict `deny_unknown_fields` inbound settings types. |
| `dns` | `dns/`, `dns/upstream/` | `DnsRouter`, the Fake-IP engine (`dns/fakeip.rs`), and the upstreams (`dns/upstream/udp.rs`, `dns/upstream/doh.rs`). **World B only** — World A does not intercept DNS. |
| `engine` | `engine/`, `engine/procfs/` | Event orchestration: the actor pump (`engine/actor.rs`), `engine/dispatcher.rs`, `engine/events.rs`, `engine/context.rs`, `engine/metadata.rs`, `engine/lifecycle.rs`, `engine/observability.rs` (Prometheus + tracing), `engine/registry.rs` (**the connection registry — the real home of the "session" module**), `engine/system.rs` (**World B's composition root**), `engine/process.rs`, and `engine/procfs/` (the process walk and the io_uring path, `engine/procfs/uring.rs`, F22; `engine/procfs/cache.rs`, the **off-by-default** reprocessor for the single-worker 121 lookups/s ceiling, and `engine/procfs/portable.rs`, the byte-for-byte `std::fs` path for the platforms without io_uring). |
| `identity` | `identity/` | Sovereign identity primitives and enclave integration (`identity/sovereign.rs`). |
| `network` | `network/` | `network/detector.rs` (network detection and the control-plane latency figure), `network/discovery.rs` (resilient node discovery, RFC-004), `network/leak_guard.rs` (DNS-leak checks), `network/throttler.rs` (bandwidth throttling), `network/tunnel_path.rs` (the tunnel-path cost model, F52). |
| `platform` | `platform/` | OS-specific work: `platform/linux.rs`, `platform/socket.rs` (socket tuning). |
| `protocol` | `protocol/` | Wire-protocol implementations: `protocol/shadowsocks.rs`, `protocol/ss2022.rs`, `protocol/ss2022_nonce.rs`, `protocol/ss2022_stream.rs`, `protocol/padding.rs` (RFC-012 G5 length-hiding padding), `protocol/binary.rs`. **Protocol, not transport** — the transport dials, this parses. |
| `router` | `router/` | `RoutingPipeline` (`router/engine.rs`) and `RoutingRule` / `Condition` / `Action` (`router/rule.rs`). **This is the module the old map called `routing`.** |
| `sniff` | `sniff/` | The `Sniffer` trait and `TlsSniffer`. |
| `transport` | `transport/` | The transport layer: `transport/traits.rs` (the `AsyncIoStream` / `InboundListener` / `OutboundDialer` interfaces — the crate's highest-fan-in abstraction), `transport/engine.rs` (**World A's `start_engine` / `stop_engine`**), `transport/mod.rs` (`TransportStack`), `transport/wg_buffer.rs` (the F89 single owner of the encapsulation buffer), `transport/wg_frame.rs` (**the single owner of the `[len][pkt]` UDP framing codec** — RFC-046 §3.2's one approved modularization), `transport/l4.rs` (the F90 L4 axis: "can this transport carry a datagram?"), the per-protocol transports (`transport/reality.rs`, `transport/reality_tls.rs`, `transport/reality_server.rs`, `transport/shadowsocks.rs`, `transport/shadowsocks2022.rs`, `transport/shadowsocks2022_transport.rs`, `transport/hysteria.rs`, `transport/wireguard.rs`, `transport/websocket.rs`, `transport/quantum.rs`, `transport/quic.rs`, `transport/tls_trust.rs`, `transport/utls_profile.rs`), the `transport/engine/` directory (`transport/engine/f43_capture_ordering_test.rs` — the F43-b red-proofs, which live **inside** the crate because the transport has to be observable and the session isolable, RFC-048 §7.1), and the `transport/inbound/` and `transport/outbound/` subtrees. |
| `transport/inbound` | `transport/inbound/` | Listeners: `transport/inbound/tun.rs`, `transport/inbound/socks.rs`, `transport/inbound/http.rs`, `transport/inbound/trojan.rs` (+ `transport/inbound/trojan_availability.rs`), `transport/inbound/shadowsocks.rs`, `transport/inbound/vmess.rs` (VLESS and VMess), `transport/inbound/tuic.rs`, `transport/inbound/tls_util.rs`. |
| `transport/outbound` | `transport/outbound/`, `transport/outbound/tuic/` | Dialers: `transport/outbound/direct.rs`, `transport/outbound/wireguard.rs`, `transport/outbound/shadowsocks.rs`, `transport/outbound/trojan.rs`, `transport/outbound/vmess.rs`, `transport/outbound/tuic.rs` (+ `transport/outbound/tuic/wire.rs`), `transport/outbound/group.rs`, `transport/outbound/registry.rs`. |
| `vault` | `vault/` | `SovereigntyVault` — encrypted at-rest storage for emergency node lists and session metadata. |

## 2. Top-level modules (crate root)

These are **not** subdirectories, and the previous version of this map did not list any of them.
Several are load-bearing.

| Module | What it owns |
| :--- | :--- |
| `lib.rs` | The crate root: the public type surface (`VPNNode`, `PublicNode`, `VPNConfig`, `ShadowMeshError`) **and** a stateful orchestrator — `REALITY_PROXY`, the `start_engine` / `stop_engine` wiring, the QR payload helpers. RFC-046 §1.4 records it as "the crate root is a behavioural hub". |
| `api_client.rs` | The HTTP client for the control plane: roster, config, heartbeat, speed-test probes. Its roster accessors now return the **envelope** (`get_nodes_async` → `NodeRoster`), so the projection the server applied is readable on this side of the wire (F86). |
| `vpn_manager.rs` | Session state, the node list, traffic modes, `refresh_node_latencies`, `get_best_node`. |
| `shadow_router.rs` | `score_node` and `shadow_route_best_node` — the shadow-routing scorer (RFC-043 §5.1). |
| `node_cache.rs` | The in-memory node cache (`DashMap`). |
| `protocol_registry.rs` | The typed protocol registry (RFC-012 G4): inbound/outbound selection and strict settings validation. |
| `reality.rs` | The REALITY **QR-pairing payload** (`encrypt_qr_payload` / `decrypt_qr_payload`). **Not** the transport — that is `transport/reality.rs`. |
| `speed_test.rs` | `SpeedTest` and `SpeedTestResult` — the control-plane speed test. |
| `fragment.rs` | Traffic fragmentation (`TrafficMode::Fragmented`). |
| `kill_switch.rs` | The kill switch and its state-change events. |
| `traffic_modes.rs` | `TrafficMode` and user traffic preferences. |
| `pow.rs` | The proof-of-work solver. |
| `anti_tamper.rs` | Anti-tamper configuration (`AntiTamperChecker`). |
| `security_logger.rs` | The security event log (`SecurityEventLogger`). |
| `torrent_policy.rs` | The BitTorrent traffic policy (`TorrentDecision` and `evaluate`). |
| `short_id_rule.rs` | The **short-id rule, in ONE fixture table** (RFC-049 §4.4, RULING F98-3b) — the rule, the serde names, the UDL names and the generated bindings' names, pinned to each other by `tests/f94_short_id_fixture_parity_test.rs` and `tests/f102_projection_vocabulary_parity_test.rs`. |
| `desktop_controller.rs` | The desktop-platform controller (two synchronous FFI exports — RFC-046 §4.1 site B). |
| `main.rs` | The `shadowmesh` binary — **World B's entry point**. Not a library module. |

## 3. Integration tests

The crate's integration tests live in `tests/`. Two are named here because they are the
contract tests for the modules above: `tests/f89_mtu_bounds_test.rs` (the MTU bound, F89) and
`tests/f91_module_map_drift_test.rs` (this map's own guard).

The roster-projection contract has two halves and they live in different crates, which is
worth recording: the **server's** half is `services/server/tests/f86_narrowing_observability_test.rs`
and the **daemon's** IPC half is `apps/daemon/tests/f86_daemon_projection_test.rs`, while the
**client's** half is `tests/f86_client_projection_test.rs` in this crate. The anonymous-projection
contract for the daemon's node type is `apps/daemon/tests/anonymous_node_projection.rs`.

## 4. Three files in `src/` that are not part of the crate

`src/api_sync_test.rs`, `src/lib_test.rs` and `src/subscription_test.rs` are **unit-test modules
of the crate root**: `lib.rs` declares each of them with `#[cfg(test)] mod …;`, and they are
compiled by the library target's test build.

⚠️ **They were not, until F104.** They sat in the crate's source root with **no** `mod`
declaration reaching them, and `Cargo.toml` declared no `[[test]]` target for them — so they were
**never compiled**, and they read as coverage in a diff and in the register while asserting
**nothing**. **A test that cannot fail is worse than no test** (F12's class). They are wired now,
and what the wiring found is recorded in the commits that did it.

They are named here so that the drift guard does not read them as modules of the crate, and so
that the next reader who finds them does not spend an afternoon on the question.

## 5. The fuzz workspace

The crate carries its **own** fuzz workspace at `fuzz/` — a separate Cargo package
(`fuzz/Cargo.toml`) with its own lock file, not a member of the repository workspace. It holds
the fuzz targets under `fuzz/fuzz_targets/` and their seed corpora under `fuzz/corpus/`; the
VLESS-URI target is `fuzz/fuzz_targets/fuzz_vless_uri.rs` with seeds in
`fuzz/corpus/fuzz_vless_uri/`.

## Drift guard

`tests/f91_module_map_drift_test.rs` fails when this map and the tree disagree, in either
direction. **A row that names a path that does not exist is a failure; a module in the tree that
this map does not name is a failure.** Run it after any structural change.

The guard reads this file **from the repository**, relative to the crate root — never from a
copy — and it carries no hand-maintained exception list: every exception would be a second
place for the map to go stale.

| Test | What it proves |
| :--- | :--- |
| `every_path_named_in_the_module_map_exists` | The red-proof. Every path the map names resolves — the `Module` and `Path` columns under `src/`, and every path-shaped token anywhere in the document. |
| `every_module_directory_in_the_tree_is_named_in_the_module_map` | The other direction: no module directory escapes the map. |
| `every_top_level_production_module_is_named_in_the_module_map` | The sixteen top-level modules and `main.rs` are named — by their bare file name, so the crate root's `reality.rs` and `transport/reality.rs` cannot be confused for one another. |
| `the_architecture_module_boundaries_name_no_phantom_module` | The **second** document is pinned too: `design/ARCHITECTURE.md` names no path that does not exist, and names every top-level module directory. |
