#!/usr/bin/env bash
# The sampler.
#
# The production defect was a *duration*: the device held a catch-all route and a
# dead resolver for ~25 seconds. A sampler that reports only "tun0 is up: yes" or
# "tun0 is up: no" cannot see it. So this records a timeline and every assertion
# in the suite is made against an interval on that timeline.
#
# One row per sample:  <elapsed_ms>  <tun0>  <dns>  <default_via_tun0>
# where dns may be `-` for "not sampled this tick" (see sampler_run), and
# default_via_tun0 is `-` for "not sampled this tick" as well.
#
# The fourth column is F43's: the CAPTURE's signature. The interface being up
# and the capture being installed are different facts — an interface can be
# up with no default route through it, and a default route can outlive the
# interface — and the defect F43 is about is the capture (a catch-all route
# plus a resolver that cannot answer), not the interface.
#
# Resolution matters as much as the samples themselves: a duration measured on a
# timeline sampled every N ms can be wrong by up to N, so the median interval is
# *measured and reported*, and the slack term in every duration assertion is
# derived from it rather than guessed.

SAMPLE_FILE=""
SAMPLE_INTERVAL_MS="${SAMPLE_INTERVAL_MS:-500}"

# Skip the resolver probe on this fraction of ticks while the interface is down.
# While tun0 is up it is sampled every tick — that pairing is the defect. While
# tun0 is down the interface, not the resolver, is what is under test, and a dead
# resolver is sampled often enough to prove recovery.
DNS_EVERY_N_WHEN_DOWN="${DNS_EVERY_N_WHEN_DOWN:-4}"

sampler_reset() {
  SAMPLE_FILE="$(mktemp "${TMPDIR:-/tmp}/l7-devsamples.XXXXXX")"
  : >"$SAMPLE_FILE"
  SAMPLER_T0=$(now_ms)
  SAMPLER_TICKS=0
}

sampler_cleanup() {
  [ -n "$SAMPLE_FILE" ] && rm -f "$SAMPLE_FILE"
  SAMPLE_FILE=""
}

# Points the measurements at a specific sample file.
#
# Every measurement function reads the global SAMPLE_FILE, so this is how a
# caller that is NOT sampling a live device — the self-test — hands the
# instrument a fixture to measure. It is the only way the file is set outside
# `sampler_reset`, and it exists so the instrument's own correctness can be
# pinned off-device: `lib/sampler_selftest.sh` measures fixture sample files
# with exactly the functions the device gate uses.
sampler_use_file() {
  SAMPLE_FILE="$1"
}

# sampler_run <max_ticks> [stop_predicate]
#
# `stop_predicate` is called with the latest tun0 state and ends the window when
# it returns true. The final sample is always taken before the predicate is
# consulted, so the teardown transition is never the thing that goes unrecorded.
sampler_run() {
  local budget_ticks="$1" predicate="${2:-}" i tun dns route
  for ((i = 0; i < budget_ticks; i++)); do
    tun=$(probe_tun0)
    if [ "$tun" = "ABSENT" ] && [ $((i % DNS_EVERY_N_WHEN_DOWN)) -ne 0 ]; then
      dns="-"
    else
      dns=$(probe_dns)
    fi
    # The capture's signature, sampled only while the interface is up — that
    # is the only window in which the capture can exist, and a third adb
    # round trip on every tick would coarsen the timeline the duration
    # bounds are measured on.
    if [ "$tun" = "ABSENT" ]; then
      route="-"
    else
      route=$(probe_default_via_tun0)
    fi
    printf '%s\t%s\t%s\t%s\n' "$(( $(now_ms) - SAMPLER_T0 ))" "$tun" "$dns" "$route" >>"$SAMPLE_FILE"
    SAMPLER_TICKS=$((SAMPLER_TICKS + 1))
    if [ -n "$predicate" ] && $predicate "$tun"; then
      break
    fi
    sleep_for_ms "$SAMPLE_INTERVAL_MS"
  done
}

sleep_for_ms() {
  if [ "$1" -ge 1000 ]; then
    sleep "$(awk "BEGIN{print $1/1000}")"
  else
    sleep "0.$(printf '%03d' "$1")" 2>/dev/null || sleep 1
  fi
}

# ------------------------------------------------------------- measurements ---

sampler_ticks() { wc -l <"$SAMPLE_FILE" | tr -d ' '; }

# Median gap between consecutive samples. The resolution of every duration this
# suite reports.
sampler_median_interval_ms() {
  local n total
  n=$(sampler_ticks)
  [ "$n" -lt 2 ] && { printf '0'; return 0; }
  total=$(awk 'NR==1{p=$1;next}{d=$1-p;p=$1;s+=d;c++}END{printf "%d", (c?s/c:0)}' "$SAMPLE_FILE")
  printf '%s' "$total"
}

# The slack term used by every duration assertion: three sample intervals,
# hard-capped. Three because a duration is measured between two samples and each
# endpoint can be attributed to the later tick; capped because an unbounded slack
# would turn the bound into no bound at all.
sampler_slack_ms() {
  local med slack
  med=$(sampler_median_interval_ms)
  slack=$(( med * 3 ))
  [ "$slack" -gt 4000 ] && slack=4000
  printf '%s' "$slack"
}

# Every contiguous run during which tun0 was PRESENT, one per line:
#     <start_ms> <end_ms> <duration_ms> <n_samples>
#
# While tun0 is up the platform has installed 0.0.0.0/0 and a resolver at
# 10.0.0.53, so the sum of these durations is the total time the device was
# pinned into the tunnel's resolver. A run still open at the end of the window is
# reported at its full observed extent — that is how an interface which never
# comes back appears, as a number rather than as an absence.
sampler_tun_up_runs() {
  awk -F'\t' '
    { up = ($2 ~ /^UP/) ? 1 : 0
      if (up && !inrun) { start = $1; inrun = 1; count = 0 }
      if (up) { count++; last = $1 }
      if (!up && inrun) { printf "%d %d %d %d\n", start, last, last-start, count; inrun = 0 } }
    END { if (inrun) { printf "%d %d %d %d\n", start, last, last-start, count } }' "$SAMPLE_FILE"
}

# Longest single tun0-up run, in ms. Echoes "<ms> <n_samples>".
sampler_tun_up_run_ms() {
  sampler_tun_up_runs | awk '{ if ($3 > best) best = $3 } END { printf "%d", best+0 }'
}

# Number of distinct tun0-up runs. The connect ladder normally produces several
# (one per mode); more than a handful means something re-established the
# interface that should not have.
sampler_tun_up_run_count() {
  sampler_tun_up_runs | grep -c . || true
}

# Samples where tun0 was PRESENT *and* DNS was sampled DEAD. The exact
# production signature.
sampler_blackholed_samples() {
  awk -F'\t' '($2 ~ /^UP/) && ($3 ~ /^DEAD/) { c++ } END { printf "%d", c+0 }' "$SAMPLE_FILE"
}

# Samples after the interface first came back down in which it was PRESENT again.
# A resurrected interface is the two-descriptor leak observed from outside: the
# device had been given back its DNS and then had it taken away again.
sampler_resurrection_samples() {
  awk -F'\t' '
    $2 ~ /^UP/ { if (downonce) { c++ }; next }
    { downonce = 1 }
    END { printf "%d", c+0 }' "$SAMPLE_FILE"
}

# Longest contiguous stretch of up+dead samples, in ms.
sampler_blackhole_run_ms() {
  awk -F'\t' '
    { b = (($2 ~ /^UP/) && ($3 ~ /^DEAD/)) ? 1 : 0
      if (b && !inrun) { start = $1; inrun = 1 }
      if (b) { last = $1 }
      if (!b && inrun) { d = last-start; if (d > best) best = d; inrun = 0 } }
    END { if (inrun) { d = last-start; if (d > best) best = d }
          printf "%d", best+0 }' "$SAMPLE_FILE"
}

# Samples with tun0 ABSENT but a sampled-dead resolver. Reported separately from
# the interface claims so a transient network drop is never silently counted as
# the descriptor defect, or the reverse.
sampler_absent_but_dead_samples() {
  awk -F'\t' '($2 ~ /^ABSENT/) && ($3 ~ /^DEAD/) { c++ } END { printf "%d", c+0 }' "$SAMPLE_FILE"
}

# ---------------------------------------------------------------------------
# F43 — the CAPTURE's window.
# ---------------------------------------------------------------------------

# Samples in which the catch-all route was installed through tun0.
#
# This is the capture's signature: the device holds a default route through
# the tunnel AND a resolver that can only be answered by the tunnel. A
# sample with the route but a working resolver is still a capture; a sample
# with the interface up and no default route is not.
sampler_default_route_samples() {
  awk -F'\t' '($2 ~ /^UP/) && ($4 == "yes") { c++ } END { printf "%d", c+0 }' "$SAMPLE_FILE"
}

# The longest contiguous stretch in which the catch-all was installed, in ms.
#
# **This is the "blackhole window" F43 is about**, measured on the route
# rather than on the interface: the pre-fix client installs the capture
# before the transport is proven, so this window covers the whole handshake.
# A client that proves the transport FIRST installs nothing on a failed
# connect, so this window is zero.
sampler_default_route_run_ms() {
  awk -F'\t' '
    { b = (($2 ~ /^UP/) && ($4 == "yes")) ? 1 : 0
      if (b && !inrun) { start = $1; inrun = 1 }
      if (b) { last = $1 }
      if (!b && inrun) { d = last-start; if (d > best) best = d; inrun = 0 } }
    END { if (inrun) { d = last-start; if (d > best) best = d }
          printf "%d", best+0 }' "$SAMPLE_FILE"
}

# The longest contiguous stretch in which the device was captured AND its
# resolver could not answer, in ms.
#
# The two windows are different and both are reported: the capture window
# (the route was installed) and the blackhole window (the route was
# installed AND the resolver was dead). A capture with a working resolver is
# a capture that did not yet hurt.
sampler_captured_and_dead_run_ms() {
  awk -F'\t' '
    { b = (($2 ~ /^UP/) && ($4 == "yes") && ($3 ~ /^DEAD/)) ? 1 : 0
      if (b && !inrun) { start = $1; inrun = 1 }
      if (b) { last = $1 }
      if (!b && inrun) { d = last-start; if (d > best) best = d; inrun = 0 } }
    END { if (inrun) { d = last-start; if (d > best) best = d }
          printf "%d", best+0 }' "$SAMPLE_FILE"
}

# ---------------------------------------------------------------------------
# F43 — the two windows nobody has measured (RFC-048 §7.3).
# ---------------------------------------------------------------------------

# The moment the CAPTURE was installed, in ms since the sampler started.
#
# The sampler starts immediately after the connect is driven, so this is the
# time the device spent NOT captured while the attempt was in flight.
#
# Pre-fix the capture goes up at once, so this is ~0. Post-fix the transport
# is proven first, so this is the handshake duration — and the device keeps
# its own resolver for all of it. **The two builds swap the two windows
# below**, and their sum is the total time the device was held.
#
# ⚠️ **THE ANCHOR IS COLUMN 4 — THE CAPTURE'S SIGNATURE — AND NOT COLUMN 2.**
#
# Column 4 is `default_via_tun0`: "is there a `default` route through tun0, in
# ANY routing table". **That is the capture.** Column 2 is `tun0`: "is the
# interface up". They are DIFFERENT facts: an interface can be up with no
# catch-all installed, and a catch-all can outlive the interface.
#
# The windows this function anchors — the pre-capture window and the pre-proof
# window — are the CAPTURE's windows, and they must share the predicate the
# capture window itself uses (`sampler_default_route_run_ms` and
# `sampler_captured_and_dead_run_ms` both require `($2 ~ /^UP/) && ($4 ==
# "yes")`). Anchoring the INSTANT on the interface while the WINDOW is measured
# on the capture is the same predicate for the start of a stretch and a
# different one for its length.
#
# ⚠️ **Why this was wrong, and why nobody saw it.** Anchored on column 2, the
# instant was the interface's, and on a PRE-FIX build the interface and the
# catch-all come up together — so the two anchors COINCIDE and the instrument
# reads correctly. They DIVERGE the moment the capture and the interface come
# up at different moments, which is exactly what RFC-048's reorder (prove →
# install capture → attach tun) can produce. So the instrument would have
# measured the wrong instants on the fixed build, and a "passing" post-fix
# measurement could have been an artefact of the instrument rather than a change
# in the client. **That is the F68 shape applied to the measuring tool: a
# metric that improves when the thing it measures is degraded.**
#
# The anchor is pinned by `lib/sampler_selftest.sh`, which runs these functions
# over fixture sample files with **no device attached** — the device gate cannot
# be the only thing that knows whether the instrument is right.
sampler_capture_installed_at_ms() {
  awk -F'\t' '($2 ~ /^UP/) && ($4 == "yes") { printf "%d", $1; exit }' "$SAMPLE_FILE"
}

# The moment the tunnel PROVED itself, in ms since the sampler started.
#
# The proof is observable from outside the app: the in-tunnel resolver can
# only answer once the tunnel carries a packet, so the first sample in which
# tun0 is up AND the resolver answers IS the proof. (That is the same fact
# `TransportProof.kt` observes as a receive-counter movement — here it is
# observed as the resolver coming back.)
#
# ⚠️ **This is deliberately a DIFFERENT event from the capture's anchor.** The
# pre-proof window is "the capture → the proof": it has one start (the
# capture's signature, column 4) and one end (the proof, the resolver coming
# back). Anchoring the start on the interface and the end on the proof would
# measure a stretch that is neither window.
sampler_proved_at_ms() {
  awk -F'\t' '($2 ~ /^UP/) && ($3 ~ /^OK/) { printf "%d", $1; exit }' "$SAMPLE_FILE"
}

# The PRE-CAPTURE window: connect → capture install, in ms.
#
# "How long was the device left alone while the transport was being proven?"
# Pre-fix this is ~0 (the capture goes up immediately, which is the defect).
# Post-fix it is the handshake duration, and the device's own DNS works
# throughout it.
#
# ⚠️ The instant is the CAPTURE's signature (column 4), not the interface's —
# see `sampler_capture_installed_at_ms`. This window and the pre-proof window
# share that one anchor, so the two are measured on the same event.
sampler_pre_capture_window_ms() {
  local installed
  installed=$(sampler_capture_installed_at_ms)
  [ -z "$installed" ] && { printf '0'; return 0; }
  printf '%s' "$installed"
}

# The PRE-PROOF window: capture install → proof, in ms.
#
# "How long was the device held by the capture before the tunnel proved
# itself?" Pre-fix this covers the whole handshake (the capture is up while
# the handshake is still running) plus the proof budget. Post-fix it is the
# proof budget alone.
#
# ⚠️ Both ends are pinned to named events: the start is the capture's
# signature (column 4 — the same anchor as the pre-capture window and as the
# captured/blackhole windows), the end is the proof (the resolver answering).
#
# ⚠️ When the tunnel never proves, the pre-proof window is the whole capture
# window — that is the measurement, not a bug in it.
sampler_pre_proof_window_ms() {
  local installed proved
  installed=$(sampler_capture_installed_at_ms)
  [ -z "$installed" ] && { printf '0'; return 0; }
  proved=$(sampler_proved_at_ms)
  if [ -z "$proved" ]; then
    # Never proved: the window runs to the end of the capture.
    sampler_default_route_run_ms
    return 0
  fi
  printf '%s' "$(( proved - installed ))"
}

# Timestamps of the tun0-up runs, for correlating with logcat.
sampler_tun_up_spans() {
  sampler_tun_up_runs | awk '{ printf "[%d-%d]" $1 $2 }'
}

# The whole timeline, for the fixture record. Timestamps and enum labels only —
# no hostnames, no local addresses.
sampler_dump() { cat "$SAMPLE_FILE"; }
