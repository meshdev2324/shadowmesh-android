#!/usr/bin/env bash
# Device-state probes.
#
# Everything in here asserts on DEVICE state — the tun interface, the route the
# interface installed, the resolver it installed, and whether the platform still
# considers a VPN to exist. Nothing here reads UI state.
#
# The production defect was invisible in the UI: three test generations asserted
# on UI state or on function return values, all of them green, and the phone was
# still blackholed. So there is deliberately no function in this file that reads a
# widget, a view hierarchy, or a return value from the app.
#
# ZPII: no probe persists a hostname, a local IP, an SSID, a BSSID or a MAC.
# Only counts, booleans and enum-like labels leave this file. That is why
# vpn_network_agents_present() counts matches instead of dumping `dumpsys
# connectivity`, which on a real handset carries the joined SSID and the wlan MAC
# inline.

PKG="${PKG:-com.shadowmesh.app}"
SVC="${SVC:-com.shadowmesh.app/.MeshVpnService}"
ACTION_CONNECT="${ACTION_CONNECT:-com.shadowmesh.app.ACTION_CONNECT}"
ACTION_DISCONNECT="${ACTION_DISCONNECT:-com.shadowmesh.app.ACTION_DISCONNECT}"

# TEST-NET-3 (RFC 5737). Guaranteed unroutable, so the handshake cannot succeed
# and the connect is guaranteed to fail. Chosen over a real-but-wrong address so
# the failure is a property of the address space, not of the network the test is
# running on.
DEAD_ENDPOINT="${DEAD_ENDPOINT:-203.0.113.1:443}"

# The host this device must be able to resolve and reach to prove its DNS works.
# A reserved example domain, not a real service.
PROBE_HOST="${PROBE_HOST:-example.com}"

# ---------------------------------------------------------------- preflight ---

# Echoes the serial of the single attached device, or nothing.
attached_device() {
  local line state serial
  line=$(adb devices 2>/dev/null | awk 'NR>1 && NF>0 {print $1" "$2; exit}')
  [ -z "$line" ] && return 1
  serial=${line%% *}
  state=${line##* }
  [ "$state" = "device" ] || return 1
  printf '%s' "$serial"
}

# Describes the device for the run header. No PII: model and Android version
# only, never the serial, never the build fingerprint.
device_summary() {
  local model release sdk abi
  model=$(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')
  release=$(adb shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')
  sdk=$(adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')
  abi=$(adb shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')
  printf '%s / Android %s (API %s) / %s' "${model:-unknown}" "${release:-?}" "${sdk:-?}" "${abi:-?}"
}

# ------------------------------------------------------------------ probes ----

# "ABSENT" or "UP <addr>/<prefix>".
#
# `ip -4 addr show tun0` on a device with no tun0 exits non-zero with
# "Device \"tun0\" does not exist." on stdout, so the exit code cannot be the
# test; the text has to be.
probe_tun0() {
  local out
  out=$(adb shell ip -4 addr show tun0 2>&1 | tr -d '\r')
  if printf '%s' "$out" | grep -q 'does not exist'; then
    printf 'ABSENT'
  else
    local addr
    addr=$(printf '%s' "$out" | grep -oE 'inet [0-9.]+/[0-9]+' | head -1 | awk '{print $2}')
    if [ -n "$addr" ]; then printf 'UP %s' "$addr"; else printf 'UP <no-inet>'; fi
  fi
}

# "OK <first-hop-ip>" or "DEAD <reason>".
#
# A device with a live tun0 and a resolver at 10.0.0.53 that has nothing behind
# it answers "unknown host" here. That is the exact production symptom, and it
# is what this function exists to detect.
probe_dns() {
  local out
  # -W1 not -W2: the resolver-dead case (`unknown host`) returns immediately, and
  # only the silent-drop case waits, so this halves the worst-case sample cost
  # without weakening detection.
  out=$(adb shell ping -c1 -W1 "$PROBE_HOST" 2>&1 | tr -d '\r')
  if printf '%s' "$out" | grep -qE 'unknown host|bad address|Name or service not known'; then
    printf 'DEAD unresolved'
  elif printf '%s' "$out" | grep -q 'bytes from'; then
    printf 'OK %s' "$(printf '%s' "$out" | grep -oE 'PING [^ ]+ \(([0-9.]+)\)' | head -1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+' | head -1)"
  elif printf '%s' "$out" | grep -q '100% packet loss'; then
    printf 'DEAD no-reply'
  else
    printf 'DEAD no-answer'
  fi
}

# "200" on success, "000"/"FAIL" otherwise. curl(1) is present on this image;
# see device_summary/run header if it ever is not.
probe_http() {
  local code
  code=$(adb shell "curl -s -m 8 -o /dev/null -w '%{http_code}' http://$PROBE_HOST" 2>/dev/null | tr -d '\r')
  case "$code" in
    200) printf '200' ;;
    '')  printf 'FAIL' ;;
    *)   printf '%s' "$code" ;;
  esac
}

# How many VPN network agents the platform still has up.
#
# This is the structural device-level stand-in for "both descriptors are
# closed", and it is strictly stronger than `tun0 absent`. While *any* descriptor
# still holds the tun fd, the ConnectivityService keeps a NetworkAgentInfo alive
# carrying the `0.0.0.0/0` route and the resolver at 10.0.0.53. So this counter
# returning to zero means the platform has released the interface, the catch-all
# and the resolver together.
#
# Counting, not dumping: the raw output carries the joined SSID and the wlan MAC.
vpn_network_agents_present() {
  local n
  n=$(adb shell dumpsys connectivity 2>/dev/null \
      | tr -d '\r' | grep -cE 'InterfaceName: tun0')
  printf '%s' "${n:-0}"
}

# Whether the VPN's catch-all route is still installed.
route_via_tun0() {
  local n
  n=$(adb shell ip -4 route show 2>/dev/null | tr -d '\r' | grep -cE '(^| )tun0( |$)')
  printf '%s' "${n:-0}"
}

# ---------------------------------------------------------------------------
# F43 — the CAPTURE's signature: a `default` route pointing at tun0.
# ---------------------------------------------------------------------------

# "yes" or "no": is a `default` route installed through tun0, in ANY table?
#
# ⚠️ **The `main` table is not where a VPN's routes live.** Android puts a
# VPN's routes in a per-network table (the legacy one is 1040), and
# `ip route show` prints the `main` table only. So the old probe —
# `ip -4 route show | grep tun0` — cannot see the catch-all the capture
# installs, and "no route via tun0" was a claim about a table the VPN never
# used. `table all` prints every table, so the catch-all is either there or
# it is not.
#
# This is the difference between "the interface is gone" and "the CAPTURE is
# gone". The capture is the route plus the resolver: an interface can be up
# with no default route through it, and a default route can outlive the
# interface.
probe_default_via_tun0() {
  local out
  out=$(adb shell ip -4 route show table all 2>/dev/null | tr -d '\r')
  if printf '%s' "$out" | grep -qE '^default .*tun0'; then
    printf 'yes'
  else
    printf 'no'
  fi
}

# How many `default` routes point at tun0, in any table. The count, not the
# routes: the routes carry the device's local addresses.
default_via_tun0_count() {
  local n
  n=$(adb shell ip -4 route show table all 2>/dev/null | tr -d '\r' | grep -cE '^default .*tun0')
  printf '%s' "${n:-0}"
}

# ---------------------------------------------------------------------------
# Provenance — which binary produced this run.
# ---------------------------------------------------------------------------

# The sha256 of the APK that is installed on the device.
#
# The fixture has to be able to say WHICH build it observed. `lastUpdateTime`
# is not enough: it is a timestamp, and a timestamp cannot be compared with
# anything the reader has.
installed_apk_sha256() {
  local path sum
  path=$(adb shell pm path "$PKG" 2>/dev/null | tr -d '\r' | head -1 | cut -d: -f2)
  if [ -z "$path" ]; then
    printf 'UNKNOWN'
    return 0
  fi
  sum=$(adb shell sha256sum "$path" 2>/dev/null | tr -d '\r' | awk '{print $1}')
  printf '%s' "${sum:-UNKNOWN}"
}

# The sha256 of the native library inside a local APK, for one ABI.
#
# `usage: apk_native_sha256 <apk> <abi>` — e.g. `arm64-v8a`.
#
# The `.so` is the half that matters for F43: the ordering spans the Kotlin
# client AND the core the `.so` carries, and an APK built against a stale
# `.so` proves nothing at all.
apk_native_sha256() {
  local apk="$1" abi="$2" sum
  [ -n "$apk" ] && [ -n "$abi" ] || { printf 'UNKNOWN'; return 0; }
  [ -f "$apk" ] || { printf 'UNKNOWN'; return 0; }
  sum=$(unzip -p "$apk" "lib/$abi/libshadowmesh_core.so" 2>/dev/null | sha256sum | awk '{print $1}')
  if [ -z "$sum" ] || [ "$sum" = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" ]; then
    # The sha256 of the empty string — i.e. the entry was not found.
    printf 'UNKNOWN'
    return 0
  fi
  printf '%s' "$sum"
}

# The single device-level predicate every test in this suite leans on:
# no interface, and a resolver that answers. A device in this state is one the
# user can actually use.
device_is_usable() {
  local tun dns
  tun=$(probe_tun0)
  [ "$tun" = "ABSENT" ] || return 1
  dns=$(probe_dns)
  case "$dns" in OK*) return 0 ;; *) return 1 ;; esac
}

# The exact signature of the production defect: interface up, DNS dead. If any
# sample in a run shows this *after* the connect attempt has terminated, the
# defect is live.
is_blackholed() {
  local tun dns
  tun=$(probe_tun0)
  case "$tun" in ABSENT*) return 1 ;; esac
  dns=$(probe_dns)
  case "$dns" in DEAD*) return 0 ;; *) return 1 ;; esac
}

# ------------------------------------------------------------------- action ---

app_force_stop() { adb shell am force-stop "$PKG" >/dev/null 2>&1; sleep 2; }

# Fires a connect attempt at an arbitrary endpoint.
# usage: connect_to <endpoint> [mode]
connect_to() {
  local endpoint="$1" mode="${2:-normal}"
  adb shell am start-foreground-service -n "$SVC" \
    -a "$ACTION_CONNECT" \
    --es address "10.0.0.97/32" \
    --es endpoint "$endpoint" \
    --es dns "10.0.0.53" \
    --ei mtu 1420 \
    --es traffic_mode "$mode" >/dev/null 2>&1
}

disconnect() {
  adb shell am start-foreground-service -n "$SVC" -a "$ACTION_DISCONNECT" >/dev/null 2>&1
}

logcat_clear() { adb logcat -c >/dev/null 2>&1; }

# Filtered to the app's own tags. The negative filters drop uniffi/java stack
# noise that would otherwise bury the signal.
app_log() {
  adb logcat -d -v time 2>/dev/null \
    | tr -d '\r' \
    | grep -E 'SHADOWMESH_DEBUG|SHADOWMESH_RUST|MeshVpnService' \
    | grep -viE 'FfiConverter|at uniffi|at com\.shadowmesh|at dv\.|at tu0|at wd1|at qt3|at nn0|at tv\.|r8-map-id' \
    || true
}

# True when the app logged that it tore the interface down because the transport
# never proved itself. This is the R6 fix's own words.
log_says_proof_timeout() { app_log | grep -q 'no traffic arrived within'; }
log_says_watchdog()      { app_log | grep -q 'Engine did not confirm a transport within'; }
log_says_handshake_fail(){ app_log | grep -qE 'FATAL|server rejected handshake'; }

# Restores the device. Leaving a phone without DNS *is* the defect, so every
# test path and every trap restores it, including the failure paths.
restore_device() {
  app_force_stop
  adb reverse --remove-all >/dev/null 2>&1 || true
  local tries=0
  while [ "$(probe_tun0)" != "ABSENT" ] && [ "$tries" -lt 10 ]; do
    app_force_stop
    tries=$((tries + 1))
  done
}

now_ms() { echo $(( $(date +%s%N) / 1000000 )); }
