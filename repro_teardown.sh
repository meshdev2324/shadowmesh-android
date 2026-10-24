#!/usr/bin/env bash
# Reproduce the DNS-blackhole defect on a real device and sample DEVICE state,
# not UI. Usage: ./repro_teardown.sh <label>
set -u
LABEL="${1:-run}"
PKG=com.shadowmesh.app
SVC=com.shadowmesh.app/.MeshVpnService
# TEST-NET-3 (RFC 5737): guaranteed unroutable, so the handshake must fail.
DEAD_ENDPOINT="203.0.113.1:443"

probe() {
  local tag="$1"
  local tun dns curl
  tun=$(adb shell ip -4 addr show tun0 2>&1 | tr -d '\r')
  if echo "$tun" | grep -q 'does not exist'; then tun="ABSENT"; else tun="PRESENT $(echo "$tun" | grep -o 'inet [0-9./]*' | head -1)"; fi
  dns=$(adb shell ping -c1 -W2 example.com 2>&1 | head -1 | tr -d '\r')
  echo "$tun" | grep -q 'ABSENT' && dnsout="n/a" || dnsout="$dns"
  curl=$(adb shell 'curl -s -m 6 -o /dev/null -w "%{http_code}" http://example.com 2>/dev/null || echo FAIL' 2>&1 | tr -d '\r')
  printf '%-22s tun0=%-26s ping=%-46s curl=%s\n' "$tag" "$tun" "$dnsout" "$curl"
}

echo "================ $LABEL ================"
echo "--- apk lastUpdateTime ---"
adb shell dumpsys package $PKG | grep -E 'lastUpdateTime' | tr -d '\r'
echo "--- pre-state ---"
probe "T+0 before"
echo "--- cleanup any stale interface ---"
adb shell am force-stop $PKG
sleep 2
probe "T+0 after-forcestop"

echo "--- logcat clear + firing CONNECT to dead endpoint $DEAD_ENDPOINT ---"
adb logcat -c
adb shell am start-foreground-service -n $SVC \
  -a com.shadowmesh.app.ACTION_CONNECT \
  --es address "10.0.0.97/32" \
  --es endpoint "$DEAD_ENDPOINT" \
  --es dns "10.0.0.53" \
  --ei mtu 1420 \
  --es traffic_mode normal >/dev/null 2>&1
echo "fired at $(date +%T)"

for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
  sleep 2
  probe "T+$((i*2))s"
done

echo "--- route table 1040 ---"
adb shell ip route show table 1040 2>&1 | tr -d '\r'
echo "--- pid ---"
adb shell pidof $PKG | tr -d '\r'
echo "--- relevant logcat ---"
adb logcat -d -v time 2>&1 | grep -iE 'ShadowMesh|MeshVpnService|tun|Vpn' | tail -60