#!/usr/bin/env bash
# The sampler's OWN self-test.
#
# ## Why this exists
#
# The device gate measures the CAPTURE's window: the stretch in which the
# phone holds a `default` route through the tunnel. The measurement is made by
# `lib/sampler.sh`, and a measurement that is wrong cannot be made right by
# the thing it measures.
#
# ⚠️ **This defect was in the instrument, not in the client.** L3's finding:
# `sampler_capture_installed_at_ms` anchored BOTH windows on tun0 being UP
# (column 2) while the capture/blackhole windows are measured on the CAPTURE's
# signature (column 4 — "a `default` route through tun0, in any routing table").
# The two anchors COINCIDE on a pre-fix build (the interface and the catch-all
# come up together) and DIVERGE once the capture and the interface come up at
# different moments — which is exactly what RFC-048's reorder can produce. So
# after the reorder the instrument would have measured the wrong instants, and
# a "passing" post-fix run could have been an artefact of the instrument
# rather than a change in the client. **That is the F68 shape applied to the
# measuring tool: a metric that improves when the thing it measures is
# degraded.**
#
# ⇒ **The instrument's correctness is pinned here, over fixture sample files,
# with NO device attached.** L3 could not run the device gate in this cycle
# (`adb devices` lists nothing), so a defect in the instrument would have
# nothing to catch it.
#
# ## What is asserted
#
# Every assertion calls the SAME functions the device gate calls — nothing is
# re-implemented here, because a test of a re-implementation proves nothing
# about the implementation.
#
#   1. the capture's INSTANT is the capture's SIGNATURE (column 4), not the
#      interface's (column 2);
#   2. the two windows are DISTINGUISHABLE: on a timeline where the capture
#      precedes the proof, the captured window and the blackhole window
#      differ, and the pre-capture and pre-proof windows differ;
#   3. the two windows COINCIDE on a timeline where the capture does not
#      precede the proof — otherwise "differ" would be trivially true of any
#      two numbers, and would prove nothing.
#
# ## The red-proof
#
# Fixture 2 ("the interface precedes the capture") is the fixture the old
# anchoring gets wrong: it reports the capture's instant as the INTERFACE's
# instant. Revert the anchor in `sampler.sh` and this file FAILS — which is
# the only way to know that a green run means the instrument is right.
#
# ## Running it
#
#     bash lib/sampler_selftest.sh
#
# Exit 0 = every assertion held. Exit 1 = the instrument is wrong.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=sampler.sh
source "$HERE/sampler.sh"

rc=0
checks=0
failed=0

# One sample row: <elapsed_ms> <tun0> <dns> <default_via_tun0>
#
# The columns are the sampler's own: `tun0` is "UP <addr>/<prefix>" or
# "ABSENT"; `dns` is "OK <ip>", "DEAD <reason>" or "-" (not sampled this
# tick); `default_via_tun0` is "yes", "no" or "-" (not sampled this tick —
# the route is only sampled while the interface is up).
row() {
  printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4"
}

check() {
  local what="$1" got="$2" want="$3"
  checks=$((checks + 1))
  if [ "$got" = "$want" ]; then
    printf '  ok   %-62s %s\n' "$what" "$got"
  else
    printf '  FAIL %-62s got %s, want %s\n' "$what" "$got" "$want" >&2
    rc=1
    failed=$((failed + 1))
  fi
}

WORK="$(mktemp -d "${TMPDIR:-/tmp}/l7-sampler-selftest.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

# ---------------------------------------------------------------------------
# Fixture 1 — THE CAPTURE PRECEDES THE PROOF.
#
# The interface and the catch-all come up together, the resolver stays dead
# until the tunnel proves itself, and then it answers. This is the shape the
# defect had: the device is held by the capture for a while before the tunnel
# works.
#
#   t=0     no interface
#   t=500   interface up, catch-all installed, resolver DEAD
#   t=1000  interface up, catch-all installed, resolver DEAD
#   t=1500  interface up, catch-all installed, resolver OK   <- the proof
#   t=2000  interface up, catch-all installed, resolver OK
#   t=2500  interface gone
# ---------------------------------------------------------------------------
F1="$WORK/capture-precedes-proof.tsv"
{
  row 0    ABSENT          "OK 10.0.0.1"  "-"
  row 500  "UP 10.0.0.2/24" "DEAD unresolved" "yes"
  row 1000 "UP 10.0.0.2/24" "DEAD unresolved" "yes"
  row 1500 "UP 10.0.0.2/24" "OK 10.0.0.53"   "yes"
  row 2000 "UP 10.0.0.2/24" "OK 10.0.0.53"   "yes"
  row 2500 ABSENT          "OK 10.0.0.1"  "-"
} >"$F1"

# ---------------------------------------------------------------------------
# Fixture 2 — THE INTERFACE PRECEDES THE CAPTURE. **The red-proof.**
#
# The interface is up with NO catch-all installed, and the catch-all arrives
# later. This is the timeline on which the old anchoring (column 2) and the
# correct anchoring (column 4) disagree, and it is a real shape: a platform
# that establishes the interface before the routing table is updated, and a
# client whose capture and whose interface come up at different moments.
#
#   t=0     no interface
#   t=500   interface up, NO catch-all installed, resolver DEAD
#   t=1000  interface up, catch-all installed,     resolver DEAD
#   t=1500  interface up, catch-all installed,     resolver OK  <- the proof
# ---------------------------------------------------------------------------
F2="$WORK/interface-precedes-capture.tsv"
{
  row 0    ABSENT          "OK 10.0.0.1"  "-"
  row 500  "UP 10.0.0.2/24" "DEAD unresolved" "no"
  row 1000 "UP 10.0.0.2/24" "DEAD unresolved" "yes"
  row 1500 "UP 10.0.0.2/24" "OK 10.0.0.53"   "yes"
} >"$F2"

# ---------------------------------------------------------------------------
# Fixture 3 — THE CAPTURE AND THE PROOF COINCIDE.
#
# The catch-all goes up in the same sample in which the resolver answers: the
# tunnel was already carrying packets when the capture was installed. The
# pre-proof window is therefore ZERO — the capture did not precede the proof.
# ---------------------------------------------------------------------------
F3="$WORK/capture-and-proof-coincide.tsv"
{
  row 0    ABSENT          "OK 10.0.0.1"  "-"
  row 500  "UP 10.0.0.2/24" "OK 10.0.0.53" "yes"
  row 1000 "UP 10.0.0.2/24" "OK 10.0.0.53" "yes"
} >"$F3"

# ---------------------------------------------------------------------------
# Fixture 4 — THE CAPTURE IS NEVER PROVED.
#
# The catch-all is installed and the resolver never answers: the device is
# held and the tunnel never works. Here the captured window and the blackhole
# window are the SAME stretch — the resolver was dead for the whole of it.
# ---------------------------------------------------------------------------
F4="$WORK/capture-never-proved.tsv"
{
  row 0    ABSENT          "OK 10.0.0.1"  "-"
  row 500  "UP 10.0.0.2/24" "DEAD unresolved" "yes"
  row 1000 "UP 10.0.0.2/24" "DEAD unresolved" "yes"
  row 1500 ABSENT          "OK 10.0.0.1"  "-"
} >"$F4"

# --- 1: the capture precedes the proof ---------------------------------------
printf 'fixture 1 — the capture precedes the proof\n'
sampler_use_file "$F1"
check "the capture's instant is the CATCH-ALL's instant" "$(sampler_capture_installed_at_ms)" "500"
check "the proof's instant"                                "$(sampler_proved_at_ms)"         "1500"
check "the pre-capture window (drive -> capture)"           "$(sampler_pre_capture_window_ms)" "500"
check "the pre-proof window (capture -> proof)"             "$(sampler_pre_proof_window_ms)"   "1000"
check "the captured window (the catch-all installed)"       "$(sampler_default_route_run_ms)"  "1500"
check "the blackhole window (installed AND the resolver dead)" "$(sampler_captured_and_dead_run_ms)" "500"
check "the samples that carried the catch-all"              "$(sampler_default_route_samples)" "4"

# --- 2: the interface precedes the capture — THE RED-PROOF --------------------
printf 'fixture 2 — the interface precedes the capture (the red-proof)\n'
sampler_use_file "$F2"
check "the capture's instant is the CATCH-ALL's, NOT the interface's" "$(sampler_capture_installed_at_ms)" "1000"
check "the proof's instant"                                          "$(sampler_proved_at_ms)"         "1500"
check "the pre-capture window (drive -> capture)"                   "$(sampler_pre_capture_window_ms)" "1000"
check "the pre-proof window (capture -> proof)"                     "$(sampler_pre_proof_window_ms)"   "500"
check "the captured window (the catch-all installed)"               "$(sampler_default_route_run_ms)"  "500"

# --- 3: the capture and the proof coincide -----------------------------------
printf 'fixture 3 — the capture and the proof coincide\n'
sampler_use_file "$F3"
check "the capture's instant"        "$(sampler_capture_installed_at_ms)" "500"
check "the proof's instant"          "$(sampler_proved_at_ms)"         "500"
check "the pre-proof window is ZERO (the capture did not precede the proof)" "$(sampler_pre_proof_window_ms)" "0"
check "the captured window"          "$(sampler_default_route_run_ms)"  "500"
check "the blackhole window is zero (the resolver answered throughout)" "$(sampler_captured_and_dead_run_ms)" "0"

# --- 4: the capture is never proved ------------------------------------------
printf 'fixture 4 — the capture is never proved\n'
sampler_use_file "$F4"
check "the capture's instant" "$(sampler_capture_installed_at_ms)" "500"
check "there is no proof"     "$(sampler_proved_at_ms)"         ""
check "the captured window"   "$(sampler_default_route_run_ms)"  "500"
check "the blackhole window (the resolver was dead throughout)" "$(sampler_captured_and_dead_run_ms)" "500"
check "the pre-proof window is the whole capture window" "$(sampler_pre_proof_window_ms)" "500"

# --- 5: THE TWO WINDOWS ARE DISTINGUISHABLE ----------------------------------
#
# This is the assertion the whole file exists for: a pair of windows that
# cannot be told apart is a pair of measurements that proves nothing, and a
# "passing" measurement that is an artefact of the instrument is the F68 shape.
#
# So: on a timeline where the capture PRECEDES the proof the two windows
# DIFFER, and on a timeline where it does NOT they COINCIDE. Both halves are
# asserted, because "they differ" on its own is true of any two numbers.
printf 'fixture 5 — the two windows are distinguishable\n'
sampler_use_file "$F1"
CAPTURE_FIRST="$(sampler_default_route_run_ms)"
BLACKHOLE_FIRST="$(sampler_captured_and_dead_run_ms)"
PRE_CAPTURE_FIRST="$(sampler_pre_capture_window_ms)"
PRE_PROOF_FIRST="$(sampler_pre_proof_window_ms)"
sampler_use_file "$F4"
CAPTURE_SECOND="$(sampler_default_route_run_ms)"
BLACKHOLE_SECOND="$(sampler_captured_and_dead_run_ms)"

check "the captured and the blackhole window DIFFER when the capture precedes the proof" \
  "$([ "$CAPTURE_FIRST" != "$BLACKHOLE_FIRST" ] && echo differ || echo same)" "differ"
check "the pre-capture and the pre-proof window DIFFER when the capture precedes the proof" \
  "$([ "$PRE_CAPTURE_FIRST" != "$PRE_PROOF_FIRST" ] && echo differ || echo same)" "differ"
check "the captured and the blackhole window COINCIDE when the capture is never proved" \
  "$([ "$CAPTURE_SECOND" = "$BLACKHOLE_SECOND" ] && echo same || echo differ)" "same"

# ---------------------------------------------------------------------------
if [ "$rc" -eq 0 ]; then
  printf '\nsampler self-test: OK (%d assertions)\n' "$checks"
  printf 'The instrument measures the CAPTURE (column 4), on every fixture, with no device.\n'
else
  printf '\nsampler self-test: FAILED (%d assertions, %d failed)\n' "$checks" "$failed" >&2
  printf 'The measuring tool is wrong. A gate built on it cannot report anything.\n' >&2
fi
exit "$rc"
