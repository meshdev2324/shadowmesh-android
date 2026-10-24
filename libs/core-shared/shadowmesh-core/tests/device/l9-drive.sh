#!/usr/bin/env bash
# L9 device probe for the TransportStack lock wedge.
#
# `MeshVpnService` is android:exported="false", so this drives the real slider.
# The control this must expose is a RECONNECT inside one process: the defect only
# appears after the first connect has parked a reader in the stack.
#
# Proof required is bytes actually observed, not the absence of an error:
#   - "Unified Engine Started" appearing once per attempt (was 0 on a wedge)
#   - traffic-probe-result flowing=true (driven by the engine's packets_received)
#   - the UI's own TRAFFIC counter and MESH latency reading non-zero
set -u
PKG=com.shadowmesh.app
LABEL="${1:-run}"
ATTEMPTS="${2:-2}"
WAIT="${3:-20}"

ui_dump() {
  adb shell uiautomator dump /sdcard/l9-ui.xml >/dev/null 2>&1
  adb pull /sdcard/l9-ui.xml /tmp/l9-ui.xml >/dev/null 2>&1
}

# The slider is the same control in both states, and the THUMB side tells us which
# way it has to be dragged:
#   "SLIDE TO PROTECT"    -> thumb at the left  -> swipe right to connect
#   "RELEASE TO UNPROTECT"-> thumb at the right -> swipe left  to disconnect
#
# The screen also carries a plain "PROTECT" label near the top, so a broad
# alternation matches that first and swipes the middle of the screen, which is
# not a control at all. The label row is only ~39px tall, and the track below it
# is taller, so the y is taken from the track rather than the text.
slider_state() {
  ui_dump; [ -s /tmp/l9-ui.xml ] || { echo "nodump"; return; }
  if grep -q 'RELEASE TO UNPROTECT' /tmp/l9-ui.xml; then echo connected
  elif grep -q 'SLIDE TO PROTECT' /tmp/l9-ui.xml; then echo idle
  else echo nostate; fi
}

swipe_connect() {
  [ "$(slider_state)" = "idle" ] || { echo "   (not idle; skipping connect)"; return 0; }
  adb shell input swipe 140 1920 876 1920 500
}

swipe_disconnect() {
  [ "$(slider_state)" = "connected" ] || { echo "   (not connected; skipping disconnect)"; return 0; }
  adb shell input swipe 876 1920 130 1920 500
}

# What the UI itself claims. Only meaningful after a connect.
ui_evidence() {
  ui_dump; [ -s /tmp/l9-ui.xml ] || { echo "   ui dump unavailable"; return; }
  local prot traffic lat ip
  prot=$(grep -oE 'YOU ARE PROTECTED' /tmp/l9-ui.xml | head -1)
  traffic=$(grep -oE 'TRAFFIC: [0-9]+ [KMG]?B' /tmp/l9-ui.xml | head -1)
  lat=$(grep -oE '[0-9]+MS MESH' /tmp/l9-ui.xml | head -1)
  ip=$(grep -oE 'MESH IP: [0-9.]+/[0-9]+' /tmp/l9-ui.xml | head -1)
  echo "   ui: ${prot:-NOT-PROTECTED} | ${traffic:-TRAFFIC:n/a} | ${lat:-LATENCY:n/a} | ${ip:-IP:n/a}"
}

echo "##### $LABEL: fresh process (am force-stop) #####"
adb shell am force-stop "$PKG"; sleep 2
adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 8

for n in $(seq 1 "$ATTEMPTS"); do
  echo "----- $LABEL: attempt $n : connect -----"
  swipe_connect
  sleep "$WAIT"
  ui_evidence
  if [ "$n" -lt "$ATTEMPTS" ]; then
    echo "----- $LABEL: attempt $n : disconnect -----"
    swipe_disconnect
    sleep 8
  fi
done

echo
echo "##### $LABEL: engine start events #####"
printf 'Unified Engine Started count: %s (expected %s)\n' \
  "$(adb logcat -d 2>/dev/null | grep -c 'Unified Engine Started')" "$ATTEMPTS"
adb logcat -d -v time 2>/dev/null \
  | grep -E "Starting Native Rust engine|Unified Engine Started|WireGuard Transport bound|Engine did not confirm" \
  | sed -E 's/^([0-9-]+ +)?([0-9:.]+).*(shadowmesh_core[^:]*: |SHADOWMESH_[A-Z0-9]+: )?(.*)$/\2  \3/'

echo
echo "##### $LABEL: client probe results (flowing= is the engine receive counter) #####"
adb logcat -d -v time 2>/dev/null | grep -E "traffic-probe-result" \
  | sed -E 's/^([0-9-]+ +)?([0-9:.]+).*(SHADOWMESH_STAGE: )(.*)$/\2  \4/'

echo
echo "##### $LABEL: engine errors #####"
adb logcat -d 2>/dev/null | grep -cE "Transport receive error|Transport send error|AsyncFd .* error" \
  | sed 's/^/transport io errors: /'
adb shell ip addr show tun0 2>/dev/null | sed -n '2p;3p'