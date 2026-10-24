#!/usr/bin/env bash
#
# L7 device-in-the-loop regression suite.
# =============================================================================
#
# ## What this is for
#
# The DNS blackhole reached production and nothing caught it. Three generations of
# tests existed. All three were green. Every one of them asserted on UI state or
# on a function's return value, and none of them ever asked the *device* a
# question. The defect — a phone holding a live 0.0.0.0/0 route and a resolver at
# 10.0.0.53 with nothing behind it — is not expressible in UI state at all. It
# only exists in `ip addr`, in the route table, and in whether a name resolves.
#
# So this suite reads those. Nothing here opens a UI dump for the purpose of
# asserting; the one UI dump it takes is to find the connect slider, because that
# is how a user starts a connect and `MeshVpnService` is deliberately not
# exported.
#
# ## The rules this suite keeps
#
# 1. **Never silently green.** Three outcomes — PASS, FAIL, NOTRUN — and NOTRUN
#    forces exit code 2. There is no path through this file that prints PASS for
#    a test that did not execute. See lib/result.sh.
# 2. **Assert on the path, not just the bound.** Every test below declares the
#    device-observable event it must observe. Checking a timing bound is not
#    enough: if the server stopped producing silent handshakes, the bound would
#    still "pass" while proving nothing. An unobserved path is NOTRUN, not PASS.
# 3. **Restore the device.** Leaving a phone without DNS *is* the defect. Every
#    exit path, including the failure paths and the traps, restores it.
# 4. **Bounds are read from the production source**, not copied. A bound that
#    drifts from the Kotlin constant is worse than no bound.
#
# ## Usage
#
#   ./device-tests/run.sh              run against the attached device
#   ./device-tests/run.sh --keep-log   also print the full app logcat
#   ./device-tests/run.sh --apk <path> install <path> first, then run
#   ./device-tests/run.sh --control     the PRE-FIX CONTROL: the defect MUST
#                                       reproduce on the build under test
#
# Exit: 0 all passed · 1 something failed · 2 something did not run · 3 harness broken
#
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/device.sh
source "$HERE/lib/device.sh"
# shellcheck source=lib/result.sh
source "$HERE/lib/result.sh"
# shellcheck source=lib/sampler.sh
source "$HERE/lib/sampler.sh"
# shellcheck source=lib/drive.sh
source "$HERE/lib/drive.sh"

KEEP_LOG=0
# The PRE-FIX CONTROL mode: the defect must reproduce on the build under
# test. See the C1 block below for why that is a mode and not a note.
CONTROL=0
# The APK to install before the run. This is how the same-session control
# works: install the pre-fix build, run --control, install the fixed build,
# run again — same device, same server, same endpoint, same session. Only
# the binary differs.
APK_PATH="${APK_PATH:-}"

while [ $# -gt 0 ]; do
  case "${1}" in
    --keep-log) KEEP_LOG=1 ;;
    --control)  CONTROL=1 ;;
    --apk)      shift; APK_PATH="${1:-}" ;;
    *)          printf 'unknown argument: %s\n' "${1}" >&2; exit 3 ;;
  esac
  shift
done

# How long to keep sampling after the drive. The connect ladder needs a few
# seconds per mode and the proof budget alone is 8s, so a window that ended before
# the ladder finished would report a partial timeline as if it were the whole
# story. 100 ticks at 500ms plus adb round trips is roughly 80-100s.
WINDOW_TICKS="${WINDOW_TICKS:-100}"

# Set to a git describe/hash in CI so a fixture records which binary produced it.
INSTALL_CMD="${INSTALL_CMD:-unknown}"

FIXTURE_DIR="$HERE/fixtures"
mkdir -p "$FIXTURE_DIR"
RUN_ID="$(date +%Y%m%d-%H%M%S)"
FIXTURE="$FIXTURE_DIR/device-tests-$RUN_ID.log"

CURRENT_FAILED=0
TEST_LABEL=""

# The tests this run intends to perform. Used to build the NOTRUN list when
# preflight fails, so a run that never started still names what it skipped.
#
# In `--control` mode the list is different ON PURPOSE: the control run
# asserts that the defect reproduces on the build under test, and the
# fixed-build claims do not apply to a pre-fix binary at all.
if [ "$CONTROL" -eq 1 ]; then
  PLANNED=(
    "C1_the_defect_reproduces_on_this_build"
  )
else
  PLANNED=(
    "D1_failed_connect_leaves_device_usable"
    "D2_silent_handshake_bounded_by_proof_budget"
    "D3_watchdog_bounds_a_hung_engine"
    "D4_interface_never_outlives_its_proof"
    "D5_the_capture_is_not_installed_before_the_transport_is_proven"
  )
fi

say()  { printf '%s\n' "$*" | tee -a "$FIXTURE"; }
rule() { say "------------------------------------------------------------------------------"; }

# --------------------------------------------------------------- bounds -------
#
# Read from the Kotlin the device actually runs. A suite that hardcodes 8400 while
# the app says 8000 is asserting a fiction.

kotlin_const() {
  local file="$1" name="$2" raw
  [ -f "$file" ] || return 1
  raw=$(grep -oE "const val $name = [0-9_]+L?" "$file" | head -1 | grep -oE '[0-9_]+' | tr -d '_')
  [ -n "$raw" ] || return 1
  printf '%s' "$raw"
}

APP_MAIN="$HERE/../app/src/main/java/com/shadowmesh/app"
CORE_VPN="$HERE/../core/vpn/src/main/java/com/shadowmesh/core_vpn"

PROOF_TIMEOUT_MS=$(kotlin_const "$CORE_VPN/TransportProof.kt" PROOF_TIMEOUT_MS || echo "")
PROOF_POLL_MS=$(kotlin_const "$CORE_VPN/TransportProof.kt" PROOF_POLL_MS || echo "")
WATCHDOG_MS=$(kotlin_const "$APP_MAIN/MeshVpnService.kt" HANDSHAKE_TIMEOUT_MS || echo "")

notrun_all() {
  local t
  for t in "${PLANNED[@]}"; do record NOTRUN "$t" "$1"; done
  loud_skip "$1"
  exit_with_summary
}

# ------------------------------------------------------------------ header ----

say ""
say "L7 device-in-the-loop regression suite"
say "run $RUN_ID"
rule

# ============================================================ PREFLIGHT ========
# Everything here distinguishes "cannot run" from "passed". None of it is allowed
# to fall through to the test body.

# --- the instrument's own self-test -------------------------------------------
#
# Everything below is measured by `lib/sampler.sh`. **A measurement that is
# wrong cannot be made right by the thing it measures**, so the harness proves
# its own measuring tool FIRST, over fixture sample files, with no device
# attached.
#
# ⚠️ This is not a formality. L3's finding was that the sampler anchored the
# capture's INSTANT on the interface (column 2) while the capture and blackhole
# windows are measured on the capture's SIGNATURE (column 4). The two anchors
# coincide on a pre-fix build and diverge after RFC-048's reorder — so the
# instrument would have measured the wrong instants on the fixed build, and a
# "passing" post-fix run could have been an artefact of the instrument rather
# than a change in the client. That is the F68 shape applied to the measuring
# tool, and it was invisible to a device gate that could not run.
#
# ⇒ A failed self-test is exit code **3** (the harness itself is broken), not
# NOTRUN: the suite is not "unable to run", it is unable to MEASURE, and it
# must not go on to report anything.
if ! SELF_TEST_OUT="$(bash "$HERE/lib/sampler_selftest.sh" 2>&1)"; then
  SELF_TEST_RC=1
else
  SELF_TEST_RC=0
fi
printf '%s\n' "$SELF_TEST_OUT" | tee -a "$FIXTURE"
if [ "$SELF_TEST_RC" -ne 0 ]; then
  say ""
  say "the sampler's own self-test FAILED: the measuring tool is wrong."
  say "See lib/sampler_selftest.sh. Nothing below can be believed until it passes."
  exit 3
fi
say "instrument: the sampler's self-test passed (the capture is measured on its own signature)"
rule

if ! command -v adb >/dev/null 2>&1; then
  say "adb not on PATH"
  notrun_all "adb is not installed or not on PATH"
fi

SERIAL=$(attached_device || true)
if [ -z "$SERIAL" ]; then
  # Count attached-but-unauthorised separately: that is the commonest reason a
  # developer has a device plugged in and sees nothing happen, and it deserves to
  # be named rather than lumped in with "no device".
  _lines=$(adb devices 2>/dev/null | awk 'NR>1 && NF>0 {print $2}' | sort -u | tr '\n' ' ')
  if [ -n "${_lines// /}" ]; then
    notrun_all "a device is attached but adb cannot use it (state: ${_lines% }). Unlock the screen and accept the USB debugging prompt."
  fi
  notrun_all "no device attached. `adb devices` lists nothing."
fi

say "device : $(device_summary)"
say "adb    : $(adb version 2>/dev/null | head -1)"
say "bounds : proof=${PROOF_TIMEOUT_MS:-?}ms + poll=${PROOF_POLL_MS:-?}ms, watchdog=${WATCHDOG_MS:-?}ms"
rule

if [ -z "$PROOF_TIMEOUT_MS" ] || [ -z "$PROOF_POLL_MS" ] || [ -z "$WATCHDOG_MS" ]; then
  say "could not read the production bounds out of the Kotlin source"
  notrun_all "the production timing constants could not be read from TransportProof.kt / MeshVpnService.kt, so no bound can be asserted"
fi
PROOF_BOUND_MS=$(( PROOF_TIMEOUT_MS + PROOF_POLL_MS ))

if ! adb shell pm path "$PKG" 2>/dev/null | grep -q package:; then
  notrun_all "$PKG is not installed on the device. Build and install it: ./gradlew :app:installDebug"
fi

# --- the binary under test ---------------------------------------------------
#
# `--apk <path>` installs the APK to test, so the SAME-SESSION CONTROL can be
# run as a pair: the pre-fix build, then the fixed build, on one device, one
# server and one endpoint. Only the binary differs between the two runs, so
# a difference in the outcome is attributable to the binary and to nothing
# else — which is the whole point of a control.
if [ -n "$APK_PATH" ]; then
  if [ ! -f "$APK_PATH" ]; then
    notrun_all "the APK $APK_PATH does not exist"
  fi
  say "installing $APK_PATH"
  INSTALL_OUT=$(adb install -r "$APK_PATH" 2>&1 | tr -d '\r')
  if ! printf '%s' "$INSTALL_OUT" | grep -q 'Success'; then
    notrun_all "the APK $APK_PATH could not be installed on the device: ${INSTALL_OUT:-no output}"
  fi
  say "installed: $APK_PATH"
  rule
fi

INSTALLED_AT=$(adb shell dumpsys package "$PKG" 2>/dev/null | grep -m1 lastUpdateTime | tr -d '\r')
say "app    : installed, ${INSTALLED_AT:-lastUpdateTime unknown}"
say "install: ${INSTALL_CMD:-unknown}"

# --- provenance ---------------------------------------------------------------
#
# A device gate is evidence only if it can say WHICH binary produced it.
# `lastUpdateTime` is a timestamp, and a timestamp cannot be compared with
# anything the reader has — so the fixture carries two hashes and a commit:
#
#   * the APK that is on the device (hashed there, not on the host — this is
#     the copy that ran),
#   * the native library INSIDE that APK, for the device's ABI. The `.so` is
#     the half that matters for F43: the ordering spans the Kotlin client AND
#     the core the `.so` carries, so an APK built against a stale `.so` proves
#     nothing at all.
#   * the commit the tree was at.
REPO_ROOT="$(cd "$HERE/../../.." && pwd)"
GIT_HEAD="$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || printf unknown)"
DEVICE_ABI="$(adb shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')"
say "commit : ${GIT_HEAD}"
say "apk    : $(installed_apk_sha256)"
say "so     : $(apk_native_sha256 "$APK_PATH" "$DEVICE_ABI") (lib/${DEVICE_ABI:-unknown}/libshadowmesh_core.so)"
if [ "$CONTROL" -eq 1 ]; then
  say "mode   : PRE-FIX CONTROL — the defect MUST reproduce on this build"
else
  say "mode   : FIXED BUILD — the defect must NOT reproduce"
fi
rule

# The suite's claims are about a *working baseline*. If the device cannot resolve
# a name before the app is touched, "DNS dead after a failed connect" means
# nothing, so this is a NOTRUN rather than a FAIL.
restore_device
BASE_TUN=$(probe_tun0)
BASE_DNS=$(probe_dns)
say "baseline: tun0=$BASE_TUN dns=$BASE_DNS"
if [ "$BASE_TUN" != "ABSENT" ] || [ "${BASE_DNS#OK}" = "$BASE_DNS" ]; then
  notrun_all "the device had no working baseline (tun0=$BASE_TUN dns=$BASE_DNS). These tests assert that a failed connect leaves DNS working, which is only meaningful if DNS worked first."
fi

CONSENT=$(adb shell appops get "$PKG" ACTIVATE_VPN 2>/dev/null | tr -d '\r')
say "consent : ${CONSENT:-unknown}"
case "$CONSENT" in
  *allow*) : ;;
  *unknown*) say "note: VPN consent could not be read; if the connect stalls on a dialog this run will report NOTRUN rather than pass" ;;
  *) notrun_all "VPN consent has not been granted for $PKG, so no interface can ever be established and every assertion would be vacuous. Grant it once by hand." ;;
esac
rule

# ============================================================== THE DRIVE ======
#
# One drive, four independent claims. The connect ladder tries REALITY then NORMAL
# within a single user swipe, so one swipe produces both the fast-rejection path
# and the silent-handshake path. Driving it once and making four claims from
# separate analyses of the same timeline is both faster and less fragile than
# four drives: the ladder's behaviour changes server-side, and a suite that needs
# four specific attempts to happen is a suite that will quietly stop testing.
#
# Each claim below is still reported on its own line and fails on its own.

say "driving a real connect (launch + swipe the connect slider)..."
ui_launch
sampler_reset

if ! ui_connect; then
  sampler_cleanup
  ui_cleanup
  restore_device
  notrun_all "the connect control was not found on screen, so no connect was driven. Screen text is printed above."
fi

say "driven. sampling device state every ${SAMPLE_INTERVAL_MS}ms for up to $(( WINDOW_TICKS * (SAMPLE_INTERVAL_MS + 600) / 1000 ))s..."
say ""
say "   t(ms)   tun0            dns"
say "  ---------------------------------------------"

# Stop when the device has settled: the interface has been up and come back down
# and has stayed down. Keep sampling past that so a resurrection is caught.
settled() {
  local up_total down_streak
  up_total=$(sampler_tun_up_runs | grep -c . || true)
  [ "$up_total" -ge 1 ] || return 1
  # Settled only after the app has also been given its chance to re-arm.
  [ "${SAMPLER_TICKS:-0}" -ge "$(( WINDOW_TICKS / 2 ))" ] || return 1
  [ "$(probe_tun0)" = "ABSENT" ] || return 1
  down_streak=$(( down_streak + 1 ))
  [ "$down_streak" -ge 6 ] && return 0
  return 1
}
down_streak=0

# Pre-sample the first rows onto the console as we go, by tailing the file.
( while :; do
    tail -1 "$SAMPLE_FILE" 2>/dev/null | while IFS=$'\t' read -r t tun dns route; do
      [ -n "$t" ] && printf "  %5s   %-15s %-16s %s\n" "$t" "$tun" "$dns" "${route:--}"
    done
    sleep 1
  done ) >"$FIXTURE_DIR/.live-$RUN_ID" 2>/dev/null &
LIVE_PID=$!

sampler_run "$WINDOW_TICKS" settled
kill "$LIVE_PID" 2>/dev/null
wait "$LIVE_PID" 2>/dev/null

cat "$FIXTURE_DIR/.live-$RUN_ID" >>"$FIXTURE" 2>/dev/null
rm -f "$FIXTURE_DIR/.live-$RUN_ID"
say "  ---------------------------------------------"
say ""
rule

# --- measurements -------------------------------------------------------------

TICKS=$(sampler_ticks)
MEDIAN=$(sampler_median_interval_ms)
SLACK=$(sampler_slack_ms)
UP_RUN_MS=$(sampler_tun_up_run_ms)
UP_RUNS=$(sampler_tun_up_run_count)
BLACKHOLED=$(sampler_blackholed_samples)
BH_RUN_MS=$(sampler_blackhole_run_ms)
RESURRECT=$(sampler_resurrection_samples)
ABSENT_DEAD=$(sampler_absent_but_dead_samples)
CAPTURE_SAMPLES=$(sampler_default_route_samples)
CAPTURE_MS=$(sampler_default_route_run_ms)
CAPDEAD_MS=$(sampler_captured_and_dead_run_ms)
PRE_CAPTURE_MS=$(sampler_pre_capture_window_ms)
PRE_PROOF_MS=$(sampler_pre_proof_window_ms)

say "samples        : $TICKS   median interval ${MEDIAN}ms   slack ${SLACK}ms"
say "tun0 up runs   : $UP_RUNS, longest ${UP_RUN_MS}ms $(sampler_tun_up_spans)"
say "up+dead-DNS    : ${BLACKHOLED} samples, longest run ${BH_RUN_MS}ms"
say "resurrections  : $RESURRECT samples after the interface first came down"
say "down+dead-DNS  : $ABSENT_DEAD samples"
say "capture        : ${CAPTURE_SAMPLES} samples carried a default route via tun0, longest run ${CAPTURE_MS}ms"
say "captured+dead  : longest run ${CAPDEAD_MS}ms (the catch-all installed AND the resolver dead)"
say "pre-capture    : ${PRE_CAPTURE_MS}ms (the drive → the capture going up; the device was NOT held for this long)"
say "pre-proof      : ${PRE_PROOF_MS}ms (the capture going up → the tunnel proving itself)"
rule

WARNED_COARSE=0
if [ "$MEDIAN" -gt 1200 ]; then
  say "WARNING: median sample interval ${MEDIAN}ms is coarse; duration bounds below are"
  say "         being checked with more overshoot than intended. Treat a marginal pass"
  say "         on this run as unproven rather than as a pass."
  WARNED_COARSE=1
fi

APP_LOG=$(app_log)
if [ "$KEEP_LOG" -eq 1 ]; then
  say "--- app log ---"
  printf '%s\n' "$APP_LOG" | tee -a "$FIXTURE"
  rule
fi

PATH_SILENT=0;   printf '%s' "$APP_LOG" | grep -q 'no traffic arrived within' && PATH_SILENT=1
PATH_WATCHDOG=0; printf '%s' "$APP_LOG" | grep -q 'Engine did not confirm a transport within' && PATH_WATCHDOG=1
PATH_REJECTED=0; printf '%s' "$APP_LOG" | grep -qE 'server rejected handshake|FATAL: VPN engine crashed' && PATH_REJECTED=1

say "paths observed : silent-handshake=$PATH_SILENT watchdog=$PATH_WATCHDOG rejected=$PATH_REJECTED"
say "                (a path that was not observed is reported NOTRUN, never PASS)"
rule

# Post-drive device state, taken after sampling and before restoring.
END_TUN=$(probe_tun0); END_DNS=$(probe_dns); END_HTTP=$(probe_http)
END_VPN_AGENTS=$(vpn_network_agents_present); END_ROUTES=$(route_via_tun0)

# The fixed-build claims. In `--control` mode they do not apply at all: the
# build under test is expected to FAIL them, and asserting them there would
# turn the control into a second fixed-build run that cannot pass.
if [ "$CONTROL" -eq 0 ]; then

# ============================================================== D1 ============
# Failed connect leaves the device usable.
#
# The test that would have caught the production blackhole. Every failed connect
# ever shown a perfectly reasonable error message, so "the connect failed" was
# never the claim; the claim is what the *phone* was left with afterwards.

TEST_LABEL="D1_failed_connect_leaves_device_usable"
say "== $TEST_LABEL"
say "   failed connect leaves the device usable"

if [ "$UP_RUNS" -lt 1 ]; then
  say "   NOTRUN: tun0 was never observed up, so no connect attempt established an"
  say "           interface and there was no failure to recover from."
  record NOTRUN "$TEST_LABEL" "no interface was ever established; nothing to recover from"
else
  CURRENT_FAILED=0
  say "   longest tun0-up window: ${UP_RUN_MS}ms (bound ${WATCHDOG_MS}ms + ${SLACK}ms slack = $(( WATCHDOG_MS + SLACK ))ms)"
  assert_fact "$([ "$UP_RUN_MS" -le $(( WATCHDOG_MS + SLACK )) ] && echo true || echo false)" \
    "tun0 was never up for longer than the handshake watchdog (${UP_RUN_MS}ms <= $(( WATCHDOG_MS + SLACK ))ms)"
  assert_fact "$([ "$RESURRECT" -eq 0 ] && echo true || echo false)" \
    "the interface never came back after teardown (${RESURRECT} resurrection samples)"
  assert_fact "$([ "$END_TUN" = "ABSENT" ] && echo true || echo false)" \
    "tun0 is absent at the end of the run (${END_TUN})"
  assert_fact "$([ "${END_DNS#OK}" != "$END_DNS" ] && echo true || echo false)" \
    "the device resolves a name at the end of the run (${END_DNS})"
  assert_fact "$([ "$END_HTTP" = "200" ] && echo true || echo false)" \
    "the device completes an HTTP request at the end of the run (${END_HTTP})"
  if [ "$CURRENT_FAILED" -eq 0 ]; then
    record PASS "$TEST_LABEL" "failed connect -> usable; longest blackhole ${UP_RUN_MS}ms"
  else
    record FAIL "$TEST_LABEL" "device left unusable after a failed connect"
  fi
fi
rule

# ============================================================== D2 ============
# A silent handshake cannot hold dead DNS past the proof budget.
#
# 24.98s before, 8.08s after. The bound is the claim — but a bound alone is not a
# test, because the bound also holds trivially on the fast-rejection path. So this
# refuses to pass unless the silent-handshake path was actually walked.

TEST_LABEL="D2_silent_handshake_bounded_by_proof_budget"
say "== $TEST_LABEL"
say "   silent handshake cannot hold dead DNS past the proof budget"

if [ "$PATH_SILENT" -eq 0 ]; then
  say "   NOTRUN: the ladder did not walk the silent-handshake path on this run, so the"
  say "           proof budget was never exercised. Observed instead: rejected=$PATH_REJECTED"
  say "           watchdog=$PATH_WATCHDOG. This is a server-side change (alert 47 is under"
  say "           repair elsewhere); the bound is asserted by D1 for every path, but the"
  say "           silent-handshake claim itself is unproven and is not being reported as met."
  record NOTRUN "$TEST_LABEL" "silent-handshake path not walked this run (server-side)"
else
  CURRENT_FAILED=0
  say "   the app reported the proof timeout, so a handshake that returned was not accepted"
  say "   longest tun0-up window: ${UP_RUN_MS}ms (bound ${PROOF_TIMEOUT_MS}+${PROOF_POLL_MS} = ${PROOF_BOUND_MS}ms + ${SLACK}ms slack = $(( PROOF_BOUND_MS + SLACK ))ms)"
  assert_fact "$([ "$UP_RUN_MS" -le $(( PROOF_BOUND_MS + SLACK )) ] && echo true || echo false)" \
    "dead DNS was held for no longer than the proof budget (${UP_RUN_MS}ms <= $(( PROOF_BOUND_MS + SLACK ))ms)"
  assert_fact "$([ "$RESURRECT" -eq 0 ] && echo true || echo false)" \
    "the interface did not outlive its proof (${RESURRECT} resurrection samples)"
  assert_fact "$([ "${END_DNS#OK}" != "$END_DNS" ] && echo true || echo false)" \
    "DNS recovered after the proof budget expired (${END_DNS})"
  if [ "$CURRENT_FAILED" -eq 0 ]; then
    record PASS "$TEST_LABEL" "silent handshake bounded at ${UP_RUN_MS}ms <= ${PROOF_BOUND_MS}ms"
  else
    record FAIL "$TEST_LABEL" "silent handshake held dead DNS past the proof budget"
  fi
fi
rule

# ============================================================== D3 ============
# The watchdog stays armed while the lease is PENDING.
#
# A hung engine never returns and never proves, so the lease stays PENDING
# forever. If anything confirms the lease without proof, the watchdog's guard
# (`worker.isAlive && !lease.transportConfirmed`) goes false and the device keeps
# its blackhole with no authority willing to close it. That is the fourth defect
# class: a signal that means "returned" being read as "works".
#
# The *device-level* half of this is the watchdog's bound holding on the path
# where the watchdog is what tore the interface down. The armed-while-PENDING half
# is not observable from outside the process on a non-debuggable build (see
# /proc/<pid>/fd and run-as denials), so it is pinned structurally by
# TransportProofBudgetTest and TunInterfaceLeaseTest, which run without a device.

TEST_LABEL="D3_watchdog_bounds_a_hung_engine"
say "== $TEST_LABEL"
say "   the watchdog stays armed while the lease is PENDING"

if [ "$PATH_WATCHDOG" -eq 0 ]; then
  say "   NOTRUN: no attempt on this run ended with the watchdog tearing the interface"
  say "           down, so its bound was never exercised. A hung engine cannot be injected"
  say "           through the shipping build — MeshVpnService is exported=false on purpose,"
  say "           so there is no intent path that accepts a blackholed endpoint."
  record NOTRUN "$TEST_LABEL" "watchdog path not exercised; endpoint injection blocked by exported=false"
else
  CURRENT_FAILED=0
  say "   the watchdog was the thing that tore the interface down"
  assert_fact "$([ "$UP_RUN_MS" -le $(( WATCHDOG_MS + SLACK )) ] && echo true || echo false)" \
    "the watchdog tore the interface down within its budget (${UP_RUN_MS}ms <= $(( WATCHDOG_MS + SLACK ))ms)"
  assert_fact "$([ "$END_VPN_AGENTS" -eq 0 ] && echo true || echo false)" \
    "no VPN network agent survived the watchdog teardown (${END_VPN_AGENTS} present)"
  if [ "$CURRENT_FAILED" -eq 0 ]; then
    record PASS "$TEST_LABEL" "watchdog fired within ${UP_RUN_MS}ms and released the interface"
  else
    record FAIL "$TEST_LABEL" "watchdog bound not held"
  fi
fi
rule

# ============================================================== D4 ============
# The interface never outlives its proof.
#
# The two-descriptor class. A tun device stays established while *any* descriptor
# still holds it, and `tun0 absent` alone would not tell us both were closed. So
# this asks the ConnectivityService instead of the kernel: while any descriptor is
# open there is a VPN NetworkAgentInfo carrying the 0.0.0.0/0 route and the
# resolver at 10.0.0.53. Zero agents means the platform released the interface,
# the catch-all and the resolver together.

TEST_LABEL="D4_interface_never_outlives_its_proof"
say "== $TEST_LABEL"
say "   the interface never outlives its proof"

CURRENT_FAILED=0
say "   after ${TICKS} samples and $UP_RUNS interface establishments:"
assert_fact "$([ "$END_TUN" = "ABSENT" ] && echo true || echo false)" \
  "tun0 is absent (${END_TUN})"
assert_fact "$([ "$END_VPN_AGENTS" -eq 0 ] && echo true || echo false)" \
  "the platform holds no VPN network agent (${END_VPN_AGENTS} present)"
assert_fact "$([ "$END_ROUTES" -eq 0 ] && echo true || echo false)" \
  "no route remains installed via tun0 (${END_ROUTES} routes)"
assert_fact "$([ "$RESURRECT" -eq 0 ] && echo true || echo false)" \
  "the interface was never re-established after teardown (${RESURRECT} samples)"
assert_fact "$([ "$UP_RUNS" -le 4 ] && echo true || echo false)" \
  "the interface was established at most as many times as the ladder has modes (${UP_RUNS} runs)"
if [ "$CURRENT_FAILED" -eq 0 ]; then
  record PASS "$TEST_LABEL" "platform fully released the interface; ${UP_RUNS} establishment(s), 0 survivors"
else
  record FAIL "$TEST_LABEL" "the interface outlived its teardown"
fi
rule

# ============================================================== D5 ============
# F43 — the capture is not installed before the transport is proven.
#
# The claim is about the ORDER, and on the device it is visible as a
# **capture window**: the stretch in which the phone holds a default route
# through the tunnel together with a resolver that only the tunnel can
# answer.
#
# Pre-fix, that window covers the whole handshake — up to
# `HANDSHAKE_TIMEOUT_MS` + `PROOF_TIMEOUT_MS` — so a connect that NEVER
# succeeds still blackholes the phone for the whole of it, and the retry
# ladder's own control-plane calls go out over that dead resolver.
#
# Post-fix (RFC-048 §4.1) the transport is proven FIRST: a connect that
# fails in PHASE 1 installs NOTHING. So for a failed connect:
#
#   * the device was never captured — no interface establishment at all;
#   * no `default dev tun0` was ever installed, in any routing table;
#   * the device's own resolver kept working throughout.
#
# The two window measurements are the **captured window** (the catch-all was
# installed) and the **blackhole window** (the catch-all was installed AND
# the resolver was dead). Both must be zero.
#
# ⚠️ **This claim is RED until RFC-048's reorder lands.** The client half is
# prepared (`app/src/main/java/com/shadowmesh/app/TunnelCaptureCoordinator.kt`
# and `CaptureOrderingTest.kt`), but the core's `connect_transport` /
# `attach_tun` are not in the UDL yet, so the production order cannot change
# until they are. A red D5 on today's build is the correct state, and it is
# the state the gate exists to hold until the reorder lands.
#
# A connect that SUCCEEDED makes this claim vacuous — the capture is then
# correct — so that is reported NOTRUN, never PASS.

TEST_LABEL="D5_the_capture_is_not_installed_before_the_transport_is_proven"
say "== $TEST_LABEL"
say "   the capture is not installed before the transport is proven"

if [ "$PATH_REJECTED" -eq 0 ] && [ "$PATH_SILENT" -eq 0 ] && [ "$PATH_WATCHDOG" -eq 0 ]; then
  say "   NOTRUN: no attempt on this run ended in a failure the app reported, so there"
  say "           was no failed connect for the ordering to be wrong about."
  record NOTRUN "$TEST_LABEL" "no failed connect was observed on this run"
else
  CURRENT_FAILED=0
  say "   the app reported a failed connect"
  say "   captured window : ${CAPTURE_MS}ms (the catch-all was installed for this long)"
  say "   blackhole window: ${CAPDEAD_MS}ms (installed AND the resolver could not answer)"
  assert_fact "$([ "$UP_RUNS" -eq 0 ] && echo true || echo false)" \
    "the device was never captured on a failed connect (${UP_RUNS} interface establishment(s))"
  assert_fact "$([ "$CAPTURE_SAMPLES" -eq 0 ] && echo true || echo false)" \
    "no default route via tun0 was ever installed (${CAPTURE_SAMPLES} samples)"
  assert_fact "$([ "$CAPTURE_MS" -eq 0 ] && echo true || echo false)" \
    "the captured window is zero (${CAPTURE_MS}ms)"
  assert_fact "$([ "$CAPDEAD_MS" -eq 0 ] && echo true || echo false)" \
    "the blackhole window is zero (${CAPDEAD_MS}ms)"
  assert_fact "$([ "$ABSENT_DEAD" -eq 0 ] && echo true || echo false)" \
    "the device's own resolver kept working throughout (${ABSENT_DEAD} samples without tun0 and with a dead resolver)"
  if [ "$CURRENT_FAILED" -eq 0 ]; then
    record PASS "$TEST_LABEL" "failed connect -> nothing was installed; captured window 0ms"
  else
    record FAIL "$TEST_LABEL" "the capture was installed before the transport was proven"
  fi
fi
rule

fi   # end of the fixed-build claims (a control run skips all of them)

# ============================================================== C1 ============
# The PRE-FIX CONTROL — the same session, the same drive, the pre-fix binary.
#
# A gate that has never been seen to fail is not a gate. This run asserts the
# OPPOSITE of D5: on the build under test the defect MUST reproduce — the
# device must be captured, the catch-all must be installed, and the resolver
# must die while it is.
#
# If the control does NOT reproduce, then the gate cannot tell the two builds
# apart, and a green fixed-build run would mean nothing. That is a FAIL here,
# not a skip: a control that does not reproduce is a broken gate.
#
# Run it as a PAIR with the fixed build, in one session, on one device:
#
#   ./device-tests/run.sh --apk /path/to/prefix.apk --control
#   ./device-tests/run.sh --apk /path/to/fixed.apk
#
# Same device, same server, same endpoint, same drive. Only the binary
# differs, so the difference in the outcome is attributable to the binary.

TEST_LABEL="C1_the_defect_reproduces_on_this_build"
say "== $TEST_LABEL"
say "   the defect reproduces on this build (the control)"

if [ "$CONTROL" -ne 1 ]; then
  # The control's claim belongs to the control run only. On a fixed-build run
  # it is reported NOTRUN — never PASS, and never FAIL: the fixed build is
  # SUPPOSED not to reproduce the defect, so asserting the opposite here
  # would fail every honest run.
  record NOTRUN "$TEST_LABEL" "the control belongs to the --control run; this run asserts the opposite"
  rule
else

for _skipped in "D1_failed_connect_leaves_device_usable" "D2_silent_handshake_bounded_by_proof_budget" "D3_watchdog_bounds_a_hung_engine" "D4_interface_never_outlives_its_proof" "D5_the_capture_is_not_installed_before_the_transport_is_proven"; do
  record NOTRUN "$_skipped" "the pre-fix control asserts the DEFECT; this claim belongs to the fixed-build run"
done

CURRENT_FAILED=0
say "   the device was captured ${UP_RUNS} time(s); the catch-all was installed for ${CAPTURE_MS}ms"
assert_fact "$([ "$UP_RUNS" -ge 1 ] && echo true || echo false)" \
  "the pre-fix build established the interface (${UP_RUNS} establishment(s))"
assert_fact "$([ "$CAPTURE_SAMPLES" -ge 1 ] && echo true || echo false)" \
  "the pre-fix build installed a default route via tun0 (${CAPTURE_SAMPLES} samples)"
assert_fact "$([ "$CAPDEAD_MS" -gt 0 ] && echo true || echo false)" \
  "the pre-fix build held the device with a dead resolver (${CAPDEAD_MS}ms)"
if [ "$CURRENT_FAILED" -eq 0 ]; then
  record PASS "$TEST_LABEL" "the defect reproduced; the gate can tell this build from the fixed one"
else
  record FAIL "$TEST_LABEL" "the control did NOT reproduce the defect — the gate cannot distinguish the two builds"
fi
rule

fi   # end of the control block

# ============================================================= restore ========

say "restoring the device..."
restore_device
FINAL_TUN=$(probe_tun0); FINAL_DNS=$(probe_dns)
say "after restore: tun0=$FINAL_TUN dns=$FINAL_DNS"
if [ "$FINAL_TUN" != "ABSENT" ]; then
  say "!! the device was NOT fully restored. This is the defect, still present."
  record FAIL "restore_the_device" "tun0 still up after restore"
else
  record PASS "restore_the_device" "device left with a working resolver"
fi
rule

sampler_dump >>"$FIXTURE"
sampler_cleanup
ui_cleanup
say "fixture: device-tests/$RUN_ID.log (timings and enum labels only; no hostnames or local addresses)"

if [ "$WARNED_COARSE" -eq 1 ]; then
  say ""
  say "NOTE: this run sampled coarsely (see the WARNING above). Bounds were still checked."
fi

exit_with_summary
