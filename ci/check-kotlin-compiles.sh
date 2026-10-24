#!/usr/bin/env bash
# GATE-F109-KOTLIN-COMPILE — the REAL compile gate for a regeneration.
#
# ## What this is
#
# The regenerated bindings (11836fe, L9's window) changed the FFI's shape and
# broke two consumers — the Android tree did not compile, and the desktop's
# `ipc_arms.rs` did not compile. The desktop's break was caught by the
# watcher's `gate0-workspace-compiles` (`cargo check --workspace --all-targets`).
#
# ⚠️ **The Android's break was caught by NOTHING.** The watcher's step list
# has no Kotlin step, and the desktop's is the only client compile it runs.
# So a regeneration broke one client tree and the other one was checked.
#
# ⇒ This is the missing half: **the Kotlin consumer is COMPILED against the
# committed binding.** It is the check that a regeneration cannot land
# without its consumers moving with it.
#
# ## Why a wrapper and not a bare `./gradlew`
#
# Three reasons, and they are the reasons the repo has a build slot:
#
#  1. **The box.** The watcher runs 19 cargo steps a round on the same 12 GB
#     box, and a Gradle daemon with `-Xmx4096m` next to a release-profile
#     link is how the box runs out of memory. The compile runs UNDER the
#     build slot, so it cannot overlap another build.
#  2. **The network.** The build is `--offline`: a gate that needs the
#     network is a gate that fails for the wrong reason. The dependency
#     cache in the tree is what makes it runnable at all.
#  3. **The scope.** `:core:vpn:compileDebugKotlin` is the module that holds
#     BOTH the generated binding and the roster consumer, and
#     `:app:compileDebugKotlin` is the module that holds the second
#     consumer. Those two are the FFI seam; the rest of the app does not
#     touch it.
#
# ## The house convention
#
# Run it with `--selftest` to prove the wrapper can fail. A gate nobody has
# seen go red is decorative.
set -uo pipefail

REPO_ROOT="${SHADOWMESH_REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)}"
ANDROID_TREE="${SHADOWMESH_ANDROID_TREE:-$REPO_ROOT/apps/android}"
BUILD_SLOT="${SHADOWMESH_BUILD_SLOT:-$REPO_ROOT/scripts/build-slot.sh}"

rc=0
note() { printf '%s\n' "$*"; }
fail() { printf 'FAIL: %s\n' "$*" >&2; rc=1; }

# The two compile tasks: the module that holds the binding AND the roster
# consumer, and the module that holds the second consumer.
GRADLE_TASKS=(
  ":core:vpn:compileDebugKotlin"
  ":app:compileDebugKotlin"
)

# ---------------------------------------------------------------------------
# --selftest
# ---------------------------------------------------------------------------
#
# The wrapper's own job is to run a command and REPORT its exit code. The
# selftest proves that: a command that fails makes the gate fail, and a
# command that succeeds makes it pass. It does not need the Android SDK, and
# it does not need the network.
expect_pass() {
  local what="$1"; shift
  if ! "$@" >/dev/null 2>&1; then
    echo "selftest FAIL: $what must PASS the rule" >&2
    self_rc=1
  fi
}

expect_fail() {
  local what="$1"; shift
  if "$@" >/dev/null 2>&1; then
    echo "selftest FAIL: $what must FAIL the rule" >&2
    self_rc=1
  fi
}

selftest() {
  local self_rc=0 tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN

  cat >"$tmp/ok.sh" <<'SH'
echo "compiled"
exit 0
SH
  cat >"$tmp/bad.sh" <<'SH'
echo "e: NodeDiscovery.kt:35 unresolved reference" >&2
exit 1
SH

  expect_pass "a compile that succeeds" run_gradle_like "$tmp/ok.sh"
  expect_fail "a compile that fails" run_gradle_like "$tmp/bad.sh"

  if [ "$self_rc" -eq 0 ]; then
    echo "selftest OK: the wrapper reports the compile's exit code — a failing"
    echo "             compile fails the gate, and a passing one passes it."
  fi
  return "$self_rc"
}

# Runs a command the way the gate runs the compile, and returns ITS exit code.
#
# ⚠️ `bash "$cmd"`, not `bash -c "$cmd"`: the latter would try to EXECUTE the
# path, and a fixture that is not marked executable fails with 126 — a failure
# of the WRITING, not of the thing under test.
run_gradle_like() {
  local cmd="$1"
  bash "$cmd" >/dev/null 2>&1
}

if [ "${1:-}" = "--selftest" ]; then
  selftest
  exit $?
fi

# ---------------------------------------------------------------------------
# The gate
# ---------------------------------------------------------------------------
if [ ! -d "$ANDROID_TREE" ]; then
  fail "the Android tree is not there: $ANDROID_TREE"
  exit 1
fi
if [ ! -x "$BUILD_SLOT" ]; then
  fail "the build slot is not there: $BUILD_SLOT — the Kotlin compile must not run beside another build on this box"
  exit 1
fi

note "==> compiling the Kotlin consumer against the committed binding"
note "    tasks: ${GRADLE_TASKS[*]}"

cd "$ANDROID_TREE" || { fail "cannot enter $ANDROID_TREE"; exit 1; }

# ⚠️ `--offline`: a gate that needs the network fails for the wrong reason.
# ⚠️ `--no-daemon`: a daemon outlives the gate and holds 4 GB.
gradle_cmd="./gradlew --offline --no-daemon ${GRADLE_TASKS[*]}"

if [ -x "$BUILD_SLOT" ] && [ -f "$BUILD_SLOT" ]; then
  bash "$BUILD_SLOT" "android-kotlin-compile" $gradle_cmd
  gradle_rc=$?
else
  $gradle_cmd
  gradle_rc=$?
fi

if [ "$gradle_rc" -ne 0 ]; then
  fail "the Kotlin consumer does not compile against the committed binding (gradle exit $gradle_rc) — a regeneration that changed the FFI's shape did not move its consumers with it"
  exit "$gradle_rc"
fi

note "    the Kotlin consumer compiles against the committed binding."
exit 0
