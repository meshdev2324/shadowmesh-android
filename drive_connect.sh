#!/usr/bin/env bash
# Drive a real connect from the app UI and sample DEVICE state (tun0 + DNS),
# not UI. Usage: ./drive_connect.sh <label> [seconds]
set -u
LABEL="${1:-run}"
SECS="${2:-40}"
PKG=com.shadowmesh.app
A=.artifacts/l7-dns-teardown-r2
mkdir -p "$A"

probe() {
  local tag="$1" tun dns curl
  tun=$(adb shell ip -4 addr show tun0 2>&1 | tr -d '\r')
  if echo "$tun" | grep -q 'does not exist'; then tun="ABSENT"; else tun="UP $(echo "$tun" | grep -o 'inet [0-9./]*' | head -1)"; fi
  if [ "$tun" = "ABSENT" ]; then dns="n/a"; else dns=$(adb shell ping -c1 -W2 example.com 2>&1 | head -1 | tr -d '\r'); fi
  curl=$(adb shell 'curl -s -m 8 -o /dev/null -w "%{http_code}" http://example.com 2>/dev/null || echo FAIL' 2>&1 | tr -d '\r')
  local notif
  notif=$(adb shell dumpsys notification --noredact 2>/dev/null | grep -c 'com.shadowmesh.app' || true)
  printf '%-10s tun0=%-24s ping=%-44s curl=%-4s notifLines=%s\n' "$tag" "$tun" "$dns" "$curl" "$notif"
}

echo "=============== $LABEL ==============="
adb shell dumpsys package $PKG | grep lastUpdateTime | tr -d '\r'
probe "pre"
adb logcat -c

# Locate the "SLIDE TO PROTECT" control and swipe it.
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb pull /sdcard/ui.xml "$A/ui-$LABEL.xml" >/dev/null 2>&1
SW=$(grep -oE 'text="SLIDE TO PROTECT"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' "$A/ui-$LABEL.xml" \
     | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)
if [ -z "$SW" ]; then
  echo "!! could not locate SLIDE TO PROTECT; visible text:"
  grep -oE 'text="[^"]+"' "$A/ui-$LABEL.xml" | sort -u | head -30
  exit 3
fi
X1=$(echo "$SW" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/')
X2=$(echo "$SW" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/')
set -- $X2
SX1=$1; SY=$(( $2 + ($4 - $2) / 2 )); EX=$3
echo "swiping SLIDE TO PROTECT from ($SX1,$SY) to ($EX,$SY)"
adb shell input swipe $SX1 $SY $EX $SY 400
echo "swiped at $(date +%T)"

for i in $(seq 1 "$SECS"); do
  probe "T+${i}s"
  sleep 1
done

echo "--- final route table 1040 ---"; adb shell ip route show table 1040 2>&1 | tr -d '\r'
echo "--- service pid ---"; adb shell pidof $PKG | tr -d '\r'
echo "--- app logcat ---"
adb logcat -d -v time 2>&1 | grep -iE 'SHADOWMESH|MeshVpn|ShadowMeshVpn|tun0|VpnService' | grep -v OplusAtlas | tail -50