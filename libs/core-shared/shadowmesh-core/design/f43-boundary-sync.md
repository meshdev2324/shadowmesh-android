# F43 / F86 / F102 — the Boundary-Sync record

> **The UDL is the source of truth.** Everything below is derived from it: the
> generated bindings, the tracked native libraries, and the drift gates.
> Nothing in this file is hand-edited into an artifact — the bindings are
> **regenerated, never hand-edited** (RFC-026, RFC-048 §5.2).
>
> **What this file is.** Three changes moved the **UniFFI surface**, and all
> three are invisible to the crate's own compiler and invisible to the
> UDL/binary drift gate:
>
> * **F43-b** — three **new** functions (`connect_transport`, `attach_tun`,
>   `close_transport`). Additive; no existing signature changed.
> * **F86** — the `GlobalManifest` **record layout** changed: it gained a
>   required `projection` field, and the UDL gained the `NodeProjection` enum
>   to carry it. ⚠️ **A dictionary's layout IS its FFI wire format.** Adding a
>   field to the Rust type changes the bytes a foreign client reads.
> * **F102 + F98** — the roster **envelope** and the short-id **refusal**:
>   `ApiClient.get_nodes()` / `get_nodes_resilient()` changed from
>   `sequence<PublicNode>` to `NodeRoster` (**two signature changes**), the
>   `NodeProjection` enum gained a **third variant** (`LocalFallback`), the
>   `NodeRoster` dictionary is **new**, and `generate_reality_short_id`
>   became **`[Throws=ShadowMeshError]`**.
>
> ⇒ **five** tracked artefacts are stale, and the rebuild is **one** cycle for
> all of them.

**Provenance:** `shadowmesh-core` @ the F102/F98 UDL cycle — the F43-b
red-proofs, the manifest's projection, the roster envelope and the
`[Throws]` boundary, in **one** commit with **one** regeneration and **one**
`.so` rebuild (RFC-049 §15.6.4).

**Status: 🟡 THE CYCLE IS ONE.** The UDL has moved; the bindings and the
tracked `.so` are rebuilt in the **same** cycle, in the order §3 gives. The
"one commit" constraint (RFC-049 §15.6.4) is **satisfied**: F43-b's three
functions, F98's `[Throws]`, F102's two declarations and two signature
changes, the Kotlin regeneration, the Swift regeneration and both `.so` ABIs
are **one** commit.

**What has been RUN (exit codes observed):**

| # | Command | Result |
| :--- | :--- | :--- |
| 1 | `cargo nextest run -p shadowmesh-core --lib -E 'test(f43_capture_ordering_test)'` | ✅ **11/11 PASS, EXIT=0** — RFC-048 §7.1's eleven rows, all green |
| 2 | `cargo nextest run -p shadowmesh-core --test f86_client_projection_test` | ✅ **17/17 PASS, EXIT=0** — including the manifest's three new red-proofs |
| 3 | `bash scripts/ci/check-f43-capture-order.sh --selftest` | ✅ **EXIT=0** — each of the three rules can fail |
| 4 | `bash scripts/ci/check-f43-capture-order.sh` | 🔴 **EXIT=1** — (a) PASS, (b) and (c) FAIL, as designed (§4.4) |
| 5 | `bash scripts/ci/check-udl-binary-drift.sh` | ✅ **EXIT=0** — the blocking direction is clean; the advisory names the three un-rebuilt functions |
| 6 | `bash scripts/ci/check-udl-binary-drift.sh --release` | 🔴 **EXIT=1** — the advisory is blocking, as it must be before the rebuild (§4.1) |
| 7 | `bash scripts/ci/check-udl-binary-checksum.sh --selftest` | ✅ **EXIT=0** — the stale-third-artifact case is reported against **both** others |
| 8 | `bash scripts/ci/check-udl-binary-checksum.sh` | 🔴 **EXIT=1** before the rebuild — the `.so` carried the old checksums and the three un-rebuilt functions. **This is the gate L3 named**: a `.so`-versus-Kotlin-only comparison would have passed that day |



---

## 1. What moved on the FFI surface

### 1.1 F43-b — three additive functions

```udl
// RFC-048 §3.1 — FROZEN 2026-10-20. Changing any of these three signatures
// requires a new RFC: the Android client's call order depends on them.
[Throws=ShadowMeshError]
u64 connect_transport(VPNConfig config);

[Throws=ShadowMeshError]
void attach_tun(u64 handle, i32 fd);

// Not [Throws]: a teardown path must not raise. Returns whether the caller
// owned the session it asked to close.
boolean close_transport(u64 handle);
```

The Rust side is the **crate root** (`src/lib.rs`), because that is where the
scaffolding's FFI wrapper looks the functions up.

⚠️ **The `i32` descriptor on `attach_tun` is the deliberate deviation**
RFC-048 §3.6 carries: the `EngineDescriptor` conversion must cover
`attach_tun` **and** `start_vpn_engine` together, in one lockstep, under
RFC-042 OQ-1. It is not a defect to be tidied up here, and it must not be
"fixed" on its own.

⚠️ **`start_vpn_engine` is retained and deprecated** — its removal condition
is written into the UDL: *"no FFI consumer remains **and** the tracked `.so`
has been rebuilt without the symbol"*. Removing a UDL entry does not remove
the symbol from a shipped `.so`.

### 1.2 F86 — the manifest's projection

```rust
// libs/core-shared/shadowmesh-core/src/lib.rs
pub struct GlobalManifest {
    pub projection: NodeProjection,   // ← NEW, and REQUIRED
    pub nodes: Vec<VPNNode>,
    pub anycast_vips: Vec<AnycastVip>,
    pub version: String,
}
```

```udl
enum NodeProjection {          // ← NEW type on the foreign surface
  "Anonymous",
  "Full",
};

dictionary GlobalManifest {
  NodeProjection projection;   // ← NEW field, first in the record
  sequence<VPNNode> nodes;
  sequence<AnycastVip> anycast_vips;
  string version;
};
```

**Required, not optional.** RFC-047 §2.6: absent is an error, not a default. A
defaulted projection would let a client report *"the server narrowed this"*
about a manifest the server never narrowed.

⚠️ **The variant names are the contract.** The generated scaffolding derives
the enum's FFI converter from the **Rust** type's variants, in order. The Rust
variants are `Anonymous` and `Full`; the UDL strings match them exactly. A
rename on either side is a breaking change and the scaffolding will not
compile — which is the correct failure.

⚠️ **The wire names are NOT these.** The **wire** vocabulary is core's
`NodeProjection::as_str()` — lowercase, `"anonymous"` / `"full"` — carried by
the type's own `serde` derive. The FFI enum's names are the foreign side's
names and never touch the JSON on the wire. Two vocabularies, two jobs; do not
conflate them.

### 1.3 The discovery channels' document — a **different** type

The RFC-004 channels (the Cloudflare Worker and the DoH-published TXT record)
publish a **node list**, not an envelope, so their manifest carries no server
statement. It is parsed into a private `PublishedManifest`
(`libs/core-shared/shadowmesh-core/src/network/discovery.rs`), **not** into
`GlobalManifest`.

⚠️ Had the fallback kept parsing into `GlobalManifest`, the required field
would have made the RFC-004 cascade **refuse to parse the very manifest it
exists to read**: the resilience path would have died on a type change. Two
documents, two types.

### 1.4 F102 — the roster envelope, and a **third** projection value

```udl
// ── F102 ────────────────────────────────────────────────────────────────────
dictionary NodeRoster {          // ← NEW type on the foreign surface
  NodeProjection projection;
  sequence<PublicNode> nodes;
};

enum NodeProjection {            // ← THIRD variant
  "Anonymous",
  "Full",
  "LocalFallback",
};

// The two accessors return the ENVELOPE, not the rows.
[Throws=ShadowMeshError]
NodeRoster get_nodes();
[Throws=ShadowMeshError]
NodeRoster get_nodes_resilient();
```

⚠️ **Two signature changes, not two additions.** A foreign client that
compiled against the old `List<PublicNode>` does not link against the new
one: the return type **is** the wire format. That is the price of carrying
the claim, and RFC-049 §15.5 measured the blast radius — **two** production
call sites, both Android's, both L7's.

⚠️ **The third variant is a CLAIM, not a third shape.** `Anonymous` and `Full`
are the **server's** claims; `LocalFallback` is the **client's** — *"no server
was asked; the client narrowed these rows itself."* Before this delta the
core stated `Anonymous` for a roster no server ever described (the RFC-004
fallback channels publish a *manifest*, which the client narrows itself), so
the server's "I withheld by policy" and the client's "the control plane is
unreachable" were **one value** — and RFC-047 §2.4's first statement, *"the
server narrowed this"*, was **not expressible on the resilient path**.

⇒ The committed test that asserted the conflation was **renamed and
re-asserted, not deleted** (`GATE-102-1`), and a second test asserts the two
claims are **not** the same value (`GATE-102-2`). The server's own vocabulary
stays **two-valued** — the server never narrows locally and never speaks
`local_fallback`.

⚠️ **What must NOT be built** (RFC-049 §15.3.1), and was not:
a parallel `get_projection()` accessor (**two requests ⇒ two answers that can
disagree**); an out-parameter (**not expressible** in UniFFI 0.28.3); a
projection *parameter* (a request for a projection the server does not grant on
request); a `#[serde(default)]` on the projection (absent must be an error, not
a default); `VPNManager.get_nodes()` (the **local cache** — a different thing,
untouched); a widened `PublicNode`; and any hand-editing of the bindings.

### 1.5 F98 — the short-id refusal becomes an **exception**

```udl
// BEFORE
  string generate_reality_short_id();

// AFTER
  [Throws=ShadowMeshError]
  string generate_reality_short_id();
```

The Rust side changed with it: `generate_short_id()` now returns
`Result<String, ShadowMeshError>`, so the **adapter's own shape** carries the
refusal. The old adapter returned a `String` and refused with the **empty
string** — and a sentinel that is a legal value in the type is not a refusal,
it is a value that happens to be forbidden by a rule the consumer must know
(RFC-049 §6.2 item 14).

⚠️ **The helper whose only purpose was to erase the error is gone.** There is
no `String`-returning short-id function left in the module. `try_generate_short_id()`
survives **only** because the server's mirror test calls it by that name
(`services/server/tests/f94_short_id_mirror_test.rs:268`) and `services/` is
not this commit's to change; it delegates, so there is **one** implementation
and **one** shape.

---

## 2. What is now stale

| # | Artefact | Stale because | Owner |
| :--- | :--- | :--- | :--- |
| 1 | `apps/android/app/src/main/jniLibs/arm64-v8a/libshadowmesh_core.so` | it exports **none** of F43-b's three functions, and its `GlobalManifest` record layout is the pre-F86 one | L9 builds · L7 packages |
| 2 | `apps/android/app/src/main/jniLibs/x86_64/libshadowmesh_core.so` | same | L9 builds · L7 packages |
| 3 | `apps/android/core/vpn/src/main/java/uniffi/shadowmesh/shadowmesh.kt` | no `connectTransport` / `attachTun` / `closeTransport`; the `GlobalManifest` data class and its `FfiConverter` predate the new field; `NodeProjection` does not exist there at all | L7 lands · L9 generates |
| 4 | `apps/ios/ShadowMesh/ShadowMesh/shadowmesh.swift` (+ `shadowmeshFFI.h`) | same, and already ~2 months behind the UDL | L9 generates · L7 lands |
| 5 | `libs/core-shared/shadowmesh-core/bindings/**` — **three** copies of the generated Kotlin bindings (`bindings/uniffi/shadowmesh/shadowmesh.kt`, `bindings/kotlin/uniffi/shadowmesh/shadowmesh.kt`, `bindings/kotlin/shadowmesh.kt`) and the Swift pair (`bindings/shadowmesh.swift`, `bindings/shadowmeshFFI.h`, `bindings/swift/**`) | the crate's own copies of the generated output, from July 2026 | **L9** |

⚠️ **Artefacts 3, 4 and 5 are separate copies of the same generator output.**
Regenerate them from the same UDL or they will disagree — and a stale copy in
the crate is a copy that somebody will read as current.

⚠️ **A client built against the new bindings and shipped with the old `.so`
takes an `UnsatisfiedLinkError` on the first call to `connectTransport`** — and
for the **manifest** the failure is worse: see §4.2.

---

## 3. The rebuild cycle — one cycle, in this order

### 3.0 House rule: every build goes through the slot script

The box is shared (12 GB RAM, the pipeline's own lock). **Never raw `cargo`.**

```bash
export BUILD_SLOT_LOCK=/home/red/.build-slot.lock
bash scripts/build-slot.sh <label> <cmd>
```

### 3.1 The core red-proofs — already run, and green

The F43-b red-proofs are in the crate (not in `tests/`), so they run as the
lib's unit tests:

```bash
bash scripts/build-slot.sh core-f43-tests \
  cargo nextest run -p shadowmesh-core --lib --test-threads=2 -E 'test(f43_capture_ordering_test)'
```

✅ **11/11 PASS, EXIT=0** — run in this window, exit code observed. RFC-048
§7.1's eleven rows, all green:

```
PASS a_failed_connect_leaves_a_live_session_untouched
PASS a_failed_connect_leaves_no_session
PASS a_failed_connect_leaves_no_tun_attached
PASS a_failed_reconnect_leaves_no_transport_live
PASS a_successful_connect_supersedes_the_previous_session
PASS attach_tun_twice_on_one_session_is_refused
PASS attach_tun_with_a_stale_handle_is_refused
PASS close_transport_closes_the_transport_and_invalidates_the_generation
PASS close_transport_with_a_stale_handle_closes_nothing
PASS start_engine_still_composes_the_two_phases
PASS stop_engine_closes_the_active_transport
```

⚠️ **They were written before they were run, and two defects only a compiler
could see** — the E0507 in the test seam's session and a **name collision** in
the F86 test file that made the whole test binary fail to build. Both are
fixed and committed (§8). **A red-proof that has never been compiled is a
document, not a gate.**


### 3.2 The bindgen

```bash
bash scripts/build-slot.sh core-uniffi-bindgen \
  cargo build -p shadowmesh-core --bin uniffi-bindgen
```

### 3.3 The Kotlin bindings

```bash
STAGING="$(mktemp -d)"
./target/debug/uniffi-bindgen generate \
  libs/core-shared/shadowmesh-core/src/shadowmesh.udl \
  --language kotlin \
  --out-dir "$STAGING"

# The generated file carries the checksum constants as well as the
# declarations; both come from the same regeneration.
install -m 0644 "$STAGING/uniffi/shadowmesh/shadowmesh.kt" \
  apps/android/core/vpn/src/main/java/uniffi/shadowmesh/shadowmesh.kt
```

⚠️ **L9 regenerates; L7 owns the file's landing in `apps/android/**`.** Do not
hand-edit the result — a hand-edit leaves the **checksum constant** stale, which
is a load-time failure nobody can explain (RFC-049 §6.2 item 10). The crate's
own copies (artefact 5) are regenerated from the same staging directory.

### 3.4 The Swift bindings — **mandatory in this cycle**

```bash
SWIFT_STAGING="$(mktemp -d)"
./target/debug/uniffi-bindgen generate \
  libs/core-shared/shadowmesh-core/src/shadowmesh.udl \
  --language swift \
  --out-dir "$SWIFT_STAGING"
# → shadowmesh.swift and shadowmeshFFI.h land in apps/ios/ShadowMesh/ShadowMesh/
#   and in the crate's own bindings/ copies.
```

⚠️ **This is not politeness; it is a correctness requirement.** The committed
Swift bindings already carry **two checksum values the shipped `.so` does not
produce** — and they are the checksums of the two functions this delta changes
(RFC-049 §15.9.1). ⇒ **A cycle that regenerates Kotlin but not Swift leaves an
iOS client whose load-time checksum check is already failing**, and this delta
gives it two more reasons to fail.

⚠️ **The Swift regeneration also lands ~129 functions the tree has never had**
(the bindings were last regenerated at `c282c3b`, 2026-08-21, and carry 122
public functions where the Kotlin bindings carry 251). That is **RFC-041's
R-041c / OQ-5**, and it is not F43's to absorb silently — **say it in the
commit message**, do not hide it inside the diff.

### 3.5 Both `.so` ABIs — rebuilt **and force-tracked**

```bash
scripts/build-android-native.sh arm64-v8a
scripts/build-android-native.sh x86_64

git add -f apps/android/app/src/main/jniLibs/arm64-v8a/libshadowmesh_core.so
git add -f apps/android/app/src/main/jniLibs/x86_64/libshadowmesh_core.so
```

⚠️ **The `git add -f` is not optional**: `*.so` is globally gitignored
(`.gitignore:15`) and the two ABIs are **tracked**, so the artefact that ships
is the one that was committed, not the one that was built. Deleting source
code does not delete a symbol from a binary, and nothing in the tree notices.
The script prints the exact command (`scripts/build-android-native.sh:79`).

⚠️ **Record the provenance**: the SHA-256 of each rebuilt `.so`, and the commit
it was built from, belong in the commit message. L7's notes record that *a
stale but plausible APK is a trap* — a device verification that does not record
the artifact's hash proves nothing about the artifact.

---

## 4. The gates: what each catches, and what it cannot

| Gate | Command | What it proves | What it **cannot** prove |
| :--- | :--- | :--- | :--- |
| **the compiler** | `cargo check --workspace --all-targets` | the scaffolding is regenerated from the UDL by `build.rs`, so a UDL/Rust mismatch is a **compile error**. **This is the gate that catches a UDL/Rust mismatch.** | a stale `.so` |
| **GATE-F43-1** | `bash scripts/ci/check-udl-binary-drift.sh --release` | the tracked `.so` exports **exactly** what the UDL declares — **both directions, blocking** | the checksum *values*; **a record layout move** (§4.2); the call order |
| **GATE-F43-2** | the Kotlin JVM tests (L7's) | the client's runtime call order matches the core's contract | the artifact's freshness |
| **GATE-F43-3** | `bash scripts/ci/check-f43-capture-order.sh` (+ `--selftest`) | the three frozen signatures are declared; the bindings expose them; the client installs the capture in exactly one place | the order itself |

### 4.1 GATE-F43-1 — the advisory direction becomes blocking at the release

`scripts/ci/check-udl-binary-drift.sh` has two directions:

* **BLOCKING** — the `.so` exports a symbol the UDL does not declare (a
  removed primitive still shipping). Never acceptable, in either order.
* **ADVISORY** — the UDL declares a function the `.so` does not export (a
  stale binary). Legitimate *in the middle* of a change: "add the UDL entry,
  rebuild the Android binary before the next release".

⚠️ **That is not true at a release boundary.** A tracked `.so` that does not
export `connect_transport` means the shipped client crashes on the first call.
⇒ **The advisory direction must be treated as blocking for the F43 release**
(RFC-048 §5.3).

**The mechanism is now in the script**: `--release` turns the advisory into a
failure. The default behaviour is **unchanged** (the advisory stays advisory),
so the gate keeps working for the ordinary "add a UDL entry, rebuild later"
order — and the release invocation is explicit.

```bash
bash scripts/ci/check-udl-binary-drift.sh            # ordinary: advisory stays advisory
bash scripts/ci/check-udl-binary-drift.sh --release  # release boundary: both directions block
```

⚠️ **Right now `--release` fails**, and that is the negative control: the
tracked `.so` exports none of the three new functions. It goes green when §3.5
has run and been force-tracked.

### 4.2 ⚠️ The blind spot: the drift gate cannot see a record layout move

The drift gate compares **function** symbols
(`uniffi_shadowmesh_core_{fn,checksum}_func_*`) only. A **record layout**
change moves **no symbol**, so:

* the gate stays **green** while the binary is stale, and
* a client built against the new bindings and the old binary reads the record
  with the **wrong field order** — and the failure is **silent corruption**,
  not an `UnsatisfiedLinkError`.

⇒ **Treat a stale `.so` here as a release blocker, not a warning.** The
drift gate's own header says a UDL *addition* before the Android rebuild is "a
legitimate order of operations"; that is true for **functions** and **false
for a record layout**. That distinction was nowhere written down, and it is
the difference between a warning and a release blocker.

⚠️ `scripts/verify-code-quality.py` has **no** binding-freshness check, so
nothing else in the tree covers this either.

### 4.3 GATE-F43-2 — the Kotlin JVM tests (L7's)

The **only** gate that can see the order, because the order is a property of
what the client *does*, not of what the binary exports. RFC-048 §7.2 names the
nine tests; L7 owns them.

### 4.4 GATE-F43-3 — the new gate, and its `--selftest`

`scripts/ci/check-f43-capture-order.sh` — **created by L9** (see §6 for the
ownership note). It checks three cheap facts:

* **(a)** the UDL declares the three RFC-048 functions with the **frozen**
  signatures, each with its `[Throws]` attribute — asserted **by name and by
  signature**, not by asserting the UDL contains nothing else (RFC-049
  RULING F98-5);
* **(b)** the generated Kotlin bindings expose `connectTransport`, `attachTun`
  and `closeTransport`;
* **(c)** `MeshVpnService.kt` contains **no** `startVpnEngine(` call and
  **exactly one** `establish()` call site.

⚠️ **(c) counts CODE, not prose.** `MeshVpnService.kt` mentions `establish()`
in nine comments and calls it once, and mentions `startVpnEngine` in five
comments. The gate strips comments and string literals before counting — a gate
that counts prose is a gate that cries wolf.

```bash
bash scripts/ci/check-f43-capture-order.sh --selftest   # the rule can fail
bash scripts/ci/check-f43-capture-order.sh               # the gate
```

**Expected results, stage by stage:**

| Stage | (a) UDL | (b) bindings | (c) client |
| :--- | :--- | :--- | :--- |
| **now** | ✅ PASS | 🔴 FAIL | 🔴 FAIL |
| after §3.1–§3.5 (the rebuild) | ✅ PASS | ✅ PASS | 🔴 FAIL |
| after L7's F43-a lands | ✅ PASS | ✅ PASS | ✅ PASS |

⚠️ **The gate is RED by design until the cycle completes.** That is the gate
doing its job, not a broken gate. Its `--selftest` is green today, and it
proves each of the three rules can fail.

---

## 5. The consumer audit (RFC-048 §5.1) — what the UDL change actually reaches

| Consumer | FFI consumer of the engine API? | Consequence of this change |
| :--- | :--- | :--- |
| **Android** (`apps/android/**`) | ✅ **the only one** — `startVpnEngine` at `MeshVpnService.kt:628` | the three new functions become callable once the bindings and the `.so` are rebuilt; the client reorder (F43-a) is L7's |
| **Daemon** (`apps/daemon/**`) | ❌ **not an FFI consumer** — it calls the **Rust** `start_engine` at `native_engine.rs:96` and `stop_engine` at `:144` | ⚠️ **affected by the `stop_engine` semantics change, not by the UDL**: a failed restart no longer leaves a stale transport live. **L8 must confirm** that the shutdown path still closes the device (`native_engine.rs:47` — `dev: Mutex<Option<tun::AsyncDevice>>`) and that the new close does not race with that device being dropped |
| **iOS / macOS** (`apps/ios/**`) | ❌ the engine API is not in the Swift bindings at all (they predate it) | the Swift regeneration is still **mandatory** (§3.4) — for the checksums, not for the engine |
| **Dashboard** (`apps/dashboard`) | ❌ REST/WS only | unaffected |
| **Desktop** (`apps/desktop-native`) | ❌ a Rust crate linking `shadowmesh_core` for the control-plane client | the manifest's `projection` is now readable in-process — the desktop's manifest read can surface a narrowing without asking the daemon |
| **Server** (`services/server`) | ❌ no FFI | unaffected |

---

## 6. ✅ SATISFIED: RFC-049 §15.6.4's "one commit" constraint

RFC-049 §15.6.4 states a constraint on L9 for the UDL delta (F102):

> *"L9 is implementing the UDL now. This delta must land in the SAME commit as
> F43-b's three functions and F98's `[Throws]`. Concretely, one commit
> contains: 1. `dictionary NodeRoster { … }` — new. 2. `enum NodeProjection
> { "Anonymous", "Full", "LocalFallback" }` — new. 3. `ApiClient.get_nodes()`
> → `NodeRoster` — signature change. 4. `ApiClient.get_nodes_resilient()` →
> `NodeRoster` — signature change. 5. F43-b's three functions — additive.
> 6. F98's `[Throws=ShadowMeshError]` on `generate_reality_short_id` — signature
> change."*

⇒ **All six items, plus the Kotlin regeneration, the Swift regeneration and
both `.so` ABIs, are ONE commit.** A second UDL commit would have cost a
second `.so` rebuild — and therefore a second chance to ship a client against
a stale `.so`, which is the condition RFC-048 §5.3 warns about.

**What the commit contains, item by item:**

| # | Item | Where |
| :--- | :--- | :--- |
| 1 | `dictionary NodeRoster { NodeProjection projection; sequence<PublicNode> nodes; }` | `shadowmesh.udl` |
| 2 | `enum NodeProjection { "Anonymous", "Full", "LocalFallback" };` | `shadowmesh.udl` + `src/lib.rs` |
| 3 | `ApiClient.get_nodes()` → `NodeRoster` | `shadowmesh.udl` + `src/api_client.rs` |
| 4 | `ApiClient.get_nodes_resilient()` → `NodeRoster` | `shadowmesh.udl` + `src/api_client.rs` |
| 5 | F43-b's three functions | already in the UDL (`shadowmesh.udl:675-683`) |
| 6 | F98's `[Throws=ShadowMeshError]` on `generate_reality_short_id` | `shadowmesh.udl` + `src/reality.rs` |

⚠️ **The three things that had to move together, and why:**

1. **The Rust enum and the UDL enum.** The scaffolding derives the FFI
   converter from the Rust type's variants, in order, so a mismatch does not
   fail the build — it **silently mis-maps the discriminants**. That is the
   worst possible failure mode, and it is why the two must move in one
   commit. ⇒ `NodeProjection::ALL` (the one place the Rust order is written
   down) and the fixture-parity gate pin them to each other.
2. **The bindings and both `.so` ABIs**, regenerated and rebuilt **once**,
   from the UDL as it stands — and the **Swift** bindings in the same cycle
   (§3.4). The Swift tree was carrying **stale checksums for exactly the two
   functions this delta changes**; a Kotlin-only cycle would have left an iOS
   client whose load-time checksum check was already failing.
3. **The `.so` rebuild comes AFTER the delta.** A rebuild before it would
   ship a binary whose UDL no longer matches.

⚠️ **The projection vocabulary is a CLAIM vocabulary, and it has three
values, not two.** `Anonymous` and `Full` are the **server's** claims;
`LocalFallback` is the **client's** ("no server was asked"). Before this
delta the core stated `Anonymous` for a roster no server ever described, so
the server's "I withheld" and the client's "I fell back" were one value and
F86's claim was unexpressible. ⇒ The committed test that asserted the
conflation was **renamed and re-asserted, not deleted**
(`GATE-102-1`), and a second test asserts the two claims are not the same
value (`GATE-102-2`).


---

## 7. Ownership, stated plainly

* **L9 owns**: the UDL, the Rust FFI entry points, the core's red-proofs, the
  regeneration **commands**, the crate's own binding copies (artefact 5), the
  two `.so` ABIs (the build), the drift gates that read L9's artifacts.
* **L7 owns**: the landing of the generated Kotlin bindings in `apps/android/**`,
  the F43-a client reorder, the Kotlin JVM tests (GATE-F43-2), and the
  packaging side of the `.so` files.
* ⚠️ **`scripts/**` is unassigned in the level-router's routing map.**
  `check-udl-binary-drift.sh` gates the UDL — L9's artifact — and
  `check-f43-capture-order.sh` gates the same UDL plus the bindings that carry
  it, so by **nearest precedent** both are L9's, with L7 supplying the
  Kotlin-side assertions they grep. RFC-048 §5.3 records this as a judgement
  call, and says so. **The routing-map gap is L11's to close.**

---

## 8. What was NOT done — stated, not smoothed

* ✅ **The F43-b red-proofs were run and are green** (11/11, EXIT=0) — see §3.1.
  The F86 manifest's three red-proofs are green inside 17/17.
* ✅ **The UDL cycle is ONE**: F43-b's three functions, F98's `[Throws]`,
  F102's two declarations and two signature changes, the Kotlin
  regeneration, the Swift regeneration and both `.so` ABIs — one commit
  (§6).
* ❌ **The manifest's row contract (F103) is not settled.** The manifest route
  serves the projection's own rows, so the *anonymous* projection's four-field
  rows do not deserialize into the core's `Vec<VPNNode>` — the narrowing
  surfaces as a deserialization error, which the iOS client converts into
  cached-or-mock nodes. **L11 rules the shape; L8 implements the server side.**
  This cycle added the projection to the manifest; it did not change the rows.
  ⇒ RFC-050 (`docs/rfc/050-manifest-row-contract.md`) is the open item.
* ❌ **The Android client's call sites are not updated.** `NodeDiscovery.kt:35`
  and `VPNManagerViewModel.kt:849` still expect `List<PublicNode>` from
  `getNodes()` / `getNodesResilient()`, and `NodeDirectoryResult.Loaded` has
  no `projection` field. **L7's** (RFC-049 §15.4.2, §15.5) — and the Android
  JVM tests are the **only** place the FFI contract is observable without a
  device (GATE-F43-2), so **this delta breaks the Android test build** until
  L7 lands. That is the RFC's designed sequence, not a defect in the core.
* ❌ **The desktop's exhaustive `match` on `NodeProjection` is L7's.**
  `apps/desktop-native/src/ipc_arms.rs` matched the enum's two variants; the
  third variant does not compile against it (E0004). **L7 owns the arm**
  (GATE-102-8) and has it in flight: the label is **derived** from core's own
  `as_str()` rather than matched, so it cannot break again when the core's
  vocabulary grows.
* ❌ **The dashboard's projection union is L8's.** The daemon publishes core's
  `NodeRoster`, and the dashboard's Zod schema discriminates on
  `"anonymous" | "full"`; the client's own `"local_fallback"` needs a third
  arm there. The daemon does not publish it today (its roster comes from the
  control plane), so it is a latent drift, not a live break.

### 8.1 Two defects a compile found, and a process lesson

Both of these were invisible to review and to the diff, and both would have
shipped a red tree:

1. **E0507 in the test seam.** `if let Some(x) = *mutex.lock()…` moves an
   `Arc` out of a `MutexGuard`'s dereference, which Rust forbids. The fix
   clones the handle out of the lock and releases the lock before the caller
   gets it.
2. **A name collision in the F86 test file.** The manifest's red-proof was
   named exactly like the roster's red-proof, so the test binary did not
   build (E0428) and every gate that compiles the core's tests was red for a
   reason that had nothing to do with what they test.

⇒ **The lesson, and it is the house rule:** *a red-proof that has never been
compiled is a document, not a gate.* The fix for both was one compile — and
the compile is cheap enough that "no builds in this session" must not be
mistaken for "no build needed". The next window's first act should be the
same one: **compile, then run.**

