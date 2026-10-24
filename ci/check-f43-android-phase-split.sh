#!/usr/bin/env bash
# GATE-F43-A — the Android seam's invariants, the ones a runtime assertion cannot see.
#
# ## What this is
#
# `TunnelCaptureCoordinator` is the JVM seam that owns the connect attempt's
# ORDER (RFC-048 §4.1). It carries two properties that are **structural** —
# they are facts about what the class CAN do, not about what it did on a
# particular run:
#
#   (a) the seam has **no global stop** in it. `stopVpnEngine()` closes
#       whichever session the core currently holds, so a per-attempt teardown
#       that can reach it is how one attempt's failure becomes every attempt's
#       failure (RFC-048 §4.5). The seam has no parameter through which one
#       could be called, and that is the guarantee;
#   (b) the seam's API is the **phase split** — `connectTransport` (PHASE 1),
#       `attachTun` (PHASE 3) and `closeTransport` (the attempt-scoped close)
#       — and NOT a single combined `startTransport`, which is the shape the
#       pre-fix FFI forces;
#   (c) the test file drives the seam through that same phase-split API, so
#       the two cannot drift apart.
#
# ## Why a static gate at all
#
# ⚠️ **Because a runtime assertion cannot see these.**
#
# `CaptureOrderingTest` used to assert "the global stop was not used" over a
# list of global stops — and **nothing could ever write to that list**, because
# the seam has no global stop to call. The assertion could not fail. That is
# the register's "a test that cannot fail" class, and an assertion that cannot
# go red is worth less than no assertion: it reads as coverage.
#
# ⇒ The property moved here, where a structural fact CAN be checked, and the
# behavioural half stayed in the JVM test, asserted on the observable that can
# actually move: which sessions were closed, and whether a newer attempt's
# session survived.
#
# ## Why it lives here and not in `scripts/ci/`
#
# ⚠️ **`scripts/**` is unassigned in the level-router's routing map**, and
# RFC-048 §5.3 assigned the sibling gate (`check-f43-capture-order.sh`) to L9
# by nearest precedent — because that gate reads L9's UDL. This gate reads
# **only** `apps/android/**`, which is L7's tree, so it lives with the tree it
# guards (the precedent `apps/android/device-tests/` sets).
#
# ## The house convention
#
# Run it with `--selftest` to prove each rule can fail. A gate nobody has seen
# go red is decorative.
set -uo pipefail

REPO_ROOT="${SHADOWMESH_REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)}"
ANDROID_TREE="${SHADOWMESH_ANDROID_TREE:-$REPO_ROOT/apps/android}"
SEAM="${SHADOWMESH_F43_SEAM:-$ANDROID_TREE/app/src/main/java/com/shadowmesh/app/TunnelCaptureCoordinator.kt}"
TEST="${SHADOWMESH_F43_TEST:-$ANDROID_TREE/app/src/test/java/com/shadowmesh/app/CaptureOrderingTest.kt}"
SERVICE="${SHADOWMESH_F43_SERVICE:-$ANDROID_TREE/app/src/main/java/com/shadowmesh/app/MeshVpnService.kt}"
BINDING="${SHADOWMESH_ANDROID_BINDING:-$ANDROID_TREE/core/vpn/src/main/java/uniffi/shadowmesh/shadowmesh.kt}"

rc=0
note() { printf '%s\n' "$*"; }
fail() { printf 'FAIL: %s\n' "$*" >&2; rc=1; }

# Prints a source file with its comments and string literals removed.
#
# A gate that counts prose is a gate that cries wolf: the seam's own KDoc
# NAMES the global stop it refuses to have, and the test file names the
# parameter it no longer uses. Counting code and not prose is the difference
# between a gate and a noise generator.
code_only() {
  awk '
    function strip(s,   out, i, c, d, n) {
      out = ""; n = length(s)
      for (i = 1; i <= n; i++) {
        c = substr(s, i, 1); d = substr(s, i + 1, 1)
        if (S == "line")  { if (c == "\n") { S = "code"; out = out c }; continue }
        if (S == "block") { if (c == "*" && d == "/") { S = "code"; i++ }; continue }
        if (S == "str")   { if (c == "\\") { i++ } else if (c == "\"") { S = "code" }; continue }
        if (c == "/" && d == "/") { S = "line";  i++; continue }
        if (c == "/" && d == "*") { S = "block"; i++; continue }
        if (c == "\"")              { S = "str";   out = out " "; continue }
        out = out c
      }
      if (S == "line") { S = "code" }
      return out
    }
    BEGIN { S = "code" }
    { print strip($0) }
  ' "$1"
}

# Counts the CODE occurrences of a literal in a source file.
count_code() {
  local file="$1" needle="$2"
  { code_only "$file" | grep -F -o -- "$needle" || true; } | wc -l | tr -d ' '
}

# ---------------------------------------------------------------------------
# The three rules
# ---------------------------------------------------------------------------

# (a) The seam has no global stop.
check_no_global_stop() {
  local seam="$1" r=0 needle
  if [ ! -f "$seam" ]; then
    fail "the Android seam is not there: $seam"
    return 1
  fi
  for needle in 'stopVpnEngine' 'stop_vpn_engine' 'stopEngine'; do
    if [ "$(count_code "$seam" "$needle")" -ne 0 ]; then
      fail "the seam reaches the global stop ('$needle') — a per-attempt teardown that can close whichever session the core holds is the hazard RFC-048 §4.5 rules out"
      r=1
    fi
  done
  return "$r"
}

# (b) The seam's API is the phase split.
check_phase_split_api() {
  local seam="$1" r=0 name
  if [ ! -f "$seam" ]; then
    fail "the Android seam is not there: $seam"
    return 1
  fi
  for name in connectTransport attachTun closeTransport awaitProof supersede; do
    if [ "$(count_code "$seam" "private val $name:")" -lt 1 ]; then
      fail "the seam does not declare '$name' as a step — the phase split is connect (PHASE 1), attach (PHASE 3) and the attempt-scoped close (RFC-048 §4.1)"
      r=1
    fi
  done
  if [ "$(count_code "$seam" 'private val startTransport:')" -ne 0 ]; then
    fail "the seam still declares a combined 'startTransport' — the pre-fix FFI fuses connect and attach, and the seam's shape is what breaks that"
    r=1
  fi
  return "$r"
}

# (c) The test drives the seam through the phase-split API.
check_test_uses_phase_split_api() {
  local test="$1" r=0 name
  if [ ! -f "$test" ]; then
    fail "the capture-ordering test is not there: $test"
    return 1
  fi
  for name in connectTransport attachTun closeTransport; do
    if [ "$(count_code "$test" "$name =")" -lt 1 ]; then
      fail "the capture-ordering test does not drive the seam's '$name' — the test and the seam's API must not drift apart"
      r=1
    fi
  done
  if [ "$(count_code "$test" 'startTransport =')" -ne 0 ]; then
    fail "the capture-ordering test still drives a combined 'startTransport' — the seam's API is the phase split now"
    r=1
  fi
  return "$r"
}

# (d) The PRODUCTION service installs the capture through the same seam.
#
# RFC-048 §4.3: the capture specification is a pure value and "the act of
# installing it is behind one injectable seam" — "the ONLY call to establish()"
# in the tree.
#
# ⚠️ **That claim was false in the production path.** `MeshVpnService` called
# `builder.establish()` directly, so the seam the JVM tests drive was a helper
# with no production caller: the tree's own documentation said one thing and
# the tree did another. A seam that production does not go through is not a
# seam — it is a second, untested implementation of the same thing.
#
# ⇒ The production path now installs the capture through
# `VpnServiceCaptureInstaller`, so the ONE call to `establish()` really is the
# seam's, and RFC-048 §4.1's reorder ("prove, then install") becomes moving
# ONE block instead of unpicking a builder chain.
check_capture_installed_through_the_seam() {
  local service="$1" r=0
  if [ ! -f "$service" ]; then
    fail "the Android service is not there: $service"
    return 1
  fi
  if [ "$(count_code "$service" 'builder.establish()')" -ne 0 ]; then
    fail "the production service calls builder.establish() directly — the ONE call to establish() belongs to the seam (VpnServiceCaptureInstaller), or the JVM tests and the production path are two different implementations (RFC-048 §4.3)"
    r=1
  fi
  if [ "$(count_code "$service" 'VpnServiceCaptureInstaller(')" -lt 1 ]; then
    fail "the production service does not install the capture through VpnServiceCaptureInstaller — the seam has to be the production path's, not only the test path's"
    r=1
  fi
  if [ "$(count_code "$service" 'TunnelCaptureSpec(')" -lt 1 ]; then
    fail "the production service does not build a TunnelCaptureSpec — the capture is a pure value (RFC-048 §4.3), and the reorder moves it as one value"
    r=1
  fi
  return "$r"
}

# (e) The client does not call the new FFI against an UNCOMMITTED binding.
#
# ⚠️ **This is a freeze, and it is dated on purpose.**
#
# L9's F43-b landed the three frozen functions in the UDL
# (`connect_transport`, `attach_tun`, `close_transport`). The KOTLIN bindings
# are generated from the UDL, and the generated file is a build artefact that
# is committed in this tree. Until that file is committed, the functions are
# not callable from Kotlin — and **client code written against them would not
# compile**, which is the honest way for that mistake to show up.
#
# ⇒ The rule: the client may call the new FFI only once the generated binding
# that declares it is COMMITTED. Untracked, modified, missing, or not declaring
# the function ⇒ the client must not call it yet.
#
# The rule becomes vacuous the moment the binding is committed, and that is
# correct: a freeze ends when the thing it froze on has landed.
check_no_ffi_call_against_an_uncommitted_binding() {
  local tree="$1" binding="$2" repo="${3:-$REPO_ROOT}" r=0 file hit=0 committed=0 rel=""
  if [ ! -d "$tree" ]; then
    fail "the Android tree is not there: $tree"
    return 1
  fi

  # Is the generated binding COMMITTED? Tracked AND unmodified.
  #
  # ⚠️ A missing `git`, or a tree that is not a checkout, means the question
  # cannot be answered — and the conservative answer is "not committed", so
  # the freeze HOLDS. A rule that cannot be verified must not wave the client
  # through.
  if command -v git >/dev/null 2>&1 && [ -f "$binding" ]; then
    rel="${binding#"$repo"/}"
    if git -C "$repo" ls-files --error-unmatch -- "$rel" >/dev/null 2>&1 &&
      git -C "$repo" diff --quiet HEAD -- "$rel"; then
      committed=1
    fi
  fi

  while IFS= read -r file; do
    case "$file" in
      "$binding") continue ;;
      */uniffi/shadowmesh/shadowmesh.kt) continue ;;
    esac
    if grep -qE 'uniffi\.shadowmesh\.(connectTransport|attachTun|closeTransport)\(' "$file" 2>/dev/null; then
      hit=1
      if [ "$committed" -ne 1 ]; then
        fail "the client calls the new FFI in $file, but the generated Kotlin binding that declares it is not committed — the bindings are generated, never hand-written, and an uncommitted binding is not a binding the client may build against"
        r=1
      else
        note "     (the client calls the new FFI and the generated binding is committed — the reorder may proceed)"
      fi
    fi
  done < <(find "$tree" -name '*.kt' -type f)

  if [ "$hit" -eq 0 ]; then
    note "     (the client calls none of connectTransport/attachTun/closeTransport — the reorder is still blocked on the bindings)"
  fi
  return "$r"
}

# ---------------------------------------------------------------------------
# --selftest
# ---------------------------------------------------------------------------

expect_pass() {
  local what="$1"; shift
  if ! "$@" 2>/dev/null; then
    echo "selftest FAIL: $what must PASS the rule" >&2
    self_rc=1
  fi
}

expect_fail() {
  local what="$1"; shift
  if "$@" 2>/dev/null; then
    echo "selftest FAIL: $what must FAIL the rule" >&2
    self_rc=1
  fi
}

selftest() {
  local tmp self_rc=0
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN

  # A seam that is already correct. Its KDoc NAMES the global stop it refuses
  # to have — a gate that counted prose would report this fixture as broken.
  cat >"$tmp/seam.ok" <<'KT'
class TunnelCaptureCoordinator<H, F>(
    private val specFor: () -> TunnelCaptureSpec,
    private val installer: CaptureInstaller<F>,
    private val supersede: () -> Unit,
    private val connectTransport: () -> H?,
    private val attachTun: (H, F) -> Boolean,
    private val awaitProof: (H) -> Boolean,
    private val closeTransport: (H) -> Boolean,
    private val onFailure: (F) -> Unit,
) {
    fun run(): Boolean = true
}
KT
  # A seam that reaches the global stop.
  cat >"$tmp/seam.global_stop" <<'KT'
class TunnelCaptureCoordinator<H, F>(
    private val connectTransport: () -> H?,
    private val attachTun: (H, F) -> Boolean,
    private val awaitProof: (H) -> Boolean,
    private val closeTransport: (H) -> Boolean,
) {
    fun run(): Boolean {
        stopVpnEngine()
        return true
    }
}
KT

  # A seam with the old, combined API.
  cat >"$tmp/seam.combined" <<'KT'
class TunnelCaptureCoordinator<H, F>(
    private val specFor: () -> TunnelCaptureSpec,
    private val installer: CaptureInstaller<F>,
    private val supersede: () -> Unit,
    private val startTransport: (F) -> H?,
    private val awaitProof: (H) -> Boolean,
    private val closeTransport: (H) -> Boolean,
    private val onFailure: (F) -> Unit,
) {
    fun run(): Boolean = true
}
KT

  # A test that drives the phase-split API, and one that still drives the
  # combined one.
  cat >"$tmp/test.ok" <<'KT'
class CaptureOrderingTest {
    @Test fun one() {
        val c = TunnelCaptureCoordinator<Long, FakeHandle>(
            connectTransport = { 1L },
            attachTun = { _, _ -> true },
            closeTransport = { true },
        )
    }
}
KT
  cat >"$tmp/test.combined" <<'KT'
class CaptureOrderingTest {
    @Test fun one() {
        val c = TunnelCaptureCoordinator<Long, FakeHandle>(
            startTransport = { 1L },
        )
    }
}
KT

  # A production service that installs the capture through the seam, and one
  # that calls `establish()` itself.
  cat >"$tmp/service.ok" <<'KT'
class MeshVpnService {
    private fun installCapture(builder: Builder): Int? {
        val spec = TunnelCaptureSpec(address = "10.0.0.2", prefixLength = 32)
        val pfd = VpnServiceCaptureInstaller(builder).install(spec)
        return pfd?.fd
    }
}
KT
  cat >"$tmp/service.direct" <<'KT'
class MeshVpnService {
    private fun installCapture(builder: Builder): Int? {
        val pfd = builder.establish()
        return pfd?.fd
    }
}
KT

  # A client that calls the new FFI, and a tree that does not.
  mkdir -p "$tmp/ffi.call/uniffi/shadowmesh" "$tmp/ffi.quiet/uniffi/shadowmesh"
  cat >"$tmp/ffi.call/uniffi/shadowmesh/shadowmesh.kt" <<'KT'
package uniffi.shadowmesh
fun `connectTransport`(`config`: VpnConfig): kotlin.ULong = 0uL
KT
  cat >"$tmp/ffi.call/Client.kt" <<'KT'
package com.shadowmesh.app
import uniffi.shadowmesh.connectTransport
class Client {
    fun go() { uniffi.shadowmesh.connectTransport(config) }
}
KT
  cat >"$tmp/ffi.quiet/Client.kt" <<'KT'
package com.shadowmesh.app
class Client {
    fun go() { /* not yet */ }
}
KT

  # A repository whose generated binding is COMMITTED, and one whose binding
  # has been regenerated since — the two states the freeze exists to tell
  # apart. The gate reads the binding's git state, so each fixture has to be a
  # real (tiny) repository.
  mkdir -p "$tmp/repo_ok/uniffi/shadowmesh" "$tmp/repo_dirty/uniffi/shadowmesh"
  for r in repo_ok repo_dirty; do
    git init -q "$tmp/$r" >/dev/null 2>&1 || true
    cp "$tmp/ffi.call/uniffi/shadowmesh/shadowmesh.kt" "$tmp/$r/uniffi/shadowmesh/shadowmesh.kt"
    git -C "$tmp/$r" add -A >/dev/null 2>&1 || true
    git -C "$tmp/$r" -c user.email=t@t -c user.name=t commit -qm binding >/dev/null 2>&1 || true
  done
  # …and now the second one is regenerated since its commit, which is the
  # state the tree is in today.
  printf '// regenerated\n' >>"$tmp/repo_dirty/uniffi/shadowmesh/shadowmesh.kt"

  # The rule must PASS on the correct fixtures ...
  expect_pass "a seam with no global stop"     check_no_global_stop "$tmp/seam.ok"
  expect_pass "a seam with the phase-split API" check_phase_split_api "$tmp/seam.ok"
  expect_pass "a test driving the phase split" check_test_uses_phase_split_api "$tmp/test.ok"
  expect_pass "a service that installs through the seam" check_capture_installed_through_the_seam "$tmp/service.ok"
  expect_pass "a client that calls none of the new FFI" check_no_ffi_call_against_an_uncommitted_binding "$tmp/ffi.quiet" "$tmp/repo_ok/uniffi/shadowmesh/shadowmesh.kt" "$tmp/repo_ok"
  expect_pass "a client calling the new FFI against a COMMITTED binding" check_no_ffi_call_against_an_uncommitted_binding "$tmp/ffi.call" "$tmp/repo_ok/uniffi/shadowmesh/shadowmesh.kt" "$tmp/repo_ok"

  # ... and FAIL on each of the ways it must fail.
  expect_fail "a seam that reaches the global stop" check_no_global_stop "$tmp/seam.global_stop"
  expect_fail "a seam with the combined API"        check_phase_split_api "$tmp/seam.combined"
  expect_fail "a test driving the combined API"     check_test_uses_phase_split_api "$tmp/test.combined"
  expect_fail "a service that calls establish() itself" check_capture_installed_through_the_seam "$tmp/service.direct"
  expect_fail "a client calling the new FFI against an uncommitted binding" \
    check_no_ffi_call_against_an_uncommitted_binding "$tmp/ffi.call" "$tmp/repo_dirty/uniffi/shadowmesh/shadowmesh.kt" "$tmp/repo_dirty"

  if [ "$self_rc" -eq 0 ]; then
    echo "selftest OK: the global-stop rule fires on a seam that calls"
    echo "             stopVpnEngine(); the phase-split rule fires on a seam with"
    echo "             the old combined startTransport; the test rule fires on a"
    echo "             test that still drives the combined API; the seam-wiring"
    echo "             rule fires on a service that calls establish() itself; the"
    echo "             binding-freeze rule fires on a client that calls the new"
    echo "             FFI while the generated binding is uncommitted."
  fi
  return "$self_rc"
}

if [ "${1:-}" = "--selftest" ]; then
  selftest
  exit $?
fi

# ---------------------------------------------------------------------------
# The gate
# ---------------------------------------------------------------------------

note "==> (a) the seam has no global stop"
check_no_global_stop "$SEAM" || rc=1

note "==> (b) the seam's API is the phase split"
check_phase_split_api "$SEAM" || rc=1

note "==> (c) the capture-ordering test drives the phase split"
check_test_uses_phase_split_api "$TEST" || rc=1

note "==> (d) the production service installs the capture through the seam"
check_capture_installed_through_the_seam "$SERVICE" || rc=1

note "==> (e) the client does not call the new FFI against an uncommitted binding"
check_no_ffi_call_against_an_uncommitted_binding "$ANDROID_TREE" "$BINDING" || rc=1

if [ "$rc" -ne 0 ]; then
  echo >&2
  echo "Android phase-split gate: the seam's invariants do not hold." >&2
  echo "The seam must carry the phases (connect, attach, the attempt-scoped" >&2
  echo "close) and must not be able to reach the global stop; the production" >&2
  echo "path must install the capture through that seam; and the client must" >&2
  echo "not call the new FFI until the generated binding is committed." >&2
fi
exit "$rc"
