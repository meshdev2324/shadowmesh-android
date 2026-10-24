#!/usr/bin/env bash
# L7 R5: drive a real connect and sample DEVICE state (tun0 + DNS), not UI.
# Fails fast on the tap target being absent so a run cannot hang.
set -u
LABEL="${1:-run}"
SECS="${2:-60}"
PKG=com.shadowmesh.app
A=.artifacts/l7-r5
mkdir -p "$A"

probe() {
  local tag="$1" tun dns
  tun=$(adb shell ip -4 addr show tun0 2>&1 | tr -d '\r')
  if echo "$tun" | grep -q 'does not exist'; then
    tun="ABSENT"
    dns="n/a"
  else
    tun="UP $(echo "$tun" | grep -o 'inet [0-9./]*' | head -1)"
    dns=$(adb shell ping -c1 -W2 example.com 2>&1 | head -1 | tr -d '\r')
  fi
  printf '%-9s tun0=%-22s ping=%s\n' "$tag" "$tun" "$dns"
}

echo "=========== $LABEL (fixed APK) ==========="
adb shell dumpsys package $PKG | grep lastUpdateTime | tr -d '\r'
probe "pre"
adb logcat -c

adb shell am force-stop $PKG >/dev/null 2>&1
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 8
probe "launched"

adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb pull /sdcard/ui.xml "$A/ui-$LABEL.xml" >/dev/null 2>&1
SW=$(grep -oE 'text="[^"]*(SLIDE TO PROTECT|PROTECT|CONNECT)[^"]*"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' "$A/ui-$LABEL.xml" \
     | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)
if [ -z "$SW" ]; then
  echo "!! no connect control found. Visible text:"
  grep -oE 'text="[^"]+"' "$A/ui-$LABEL.xml" | sort -u | head -40
  exit 3
fi
set -- $(echo "$SW" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/')
echo "swiping connect control ($SW)"
adb shell input swipe $1 $(( $2 + ($4 - $2) / 2 )) $3 $(( $2 + ($4 - $2) / 2 )) 400
echo "swiped at $(date +%T)"

for i in $(seq 1 "$SECS"); do
  probe "T+${i}s"
  sleep 1
done

echo "--- final DNS ---"
adb shell ping -c1 -W3 example.com 2>&1 | head -2 | tr -d '\r'
echo "--- final curl ---"
adb shell 'curl -s -m 8 -o /dev/null -w "%{http_code}" http://example.com' 2>&1 | tr -d '\r'
echo
echo "--- app logcat ---"
adb logcat -d -v time 2>&1 \
  | grep -E 'SHADOWMESH_DEBUG|SHADOWMESH_RUST' \
  | grep -viE 'FfiConverter|at uniffi|at com.shadowmesh|at dv\.|at tu0|at wd1|at qt3|at nn0|at tv\.|r8-map-id' \
  | tail -45