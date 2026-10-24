#!/usr/bin/env bash
# Driving the connect, the way a user does.
#
# ## Why the UI and not an intent
#
# The obvious harness shape is `am start-foreground-service -a ACTION_CONNECT
# --es endpoint 203.0.113.1:443`, which injects any endpoint and makes every
# failure mode deterministic. It does not work, and it must not be made to work:
#
#     Permission Denial: Accessing service com.shadowmesh.app/.MeshVpnService
#     from pid=10552, uid=2000 that is not exported from uid 10361
#
# `MeshVpnService` is `android:exported="false"` with
# `android:permission="android.permission.BIND_VPN_SERVICE"`. That is correct and
# it is load-bearing: an exported VPN service can be driven by any app on the
# device. Weakening it to make a test convenient would trade a security property
# for testability, which is exactly backwards for an anti-censorship client.
#
# So this file drives the real slider. The cost is that a failure mode has to be
# produced by the server rather than injected; the benefit is that every assertion
# here is made against the path that actually ships. Tests that cannot be driven
# this way are reported as NOTRUN rather than quietly redefined into something
# that can — see `require_path_coverage` in result.sh.

UI_DUMP_DIR="${UI_DUMP_DIR:-$(mktemp "${TMPDIR:-/tmp}/l7-uidump.XXXXXX")}"

# Words that identify the connect control across the main screen's copy variants.
_CONNECT_TEXT_RE='SLIDE TO PROTECT|PROTECT|CONNECT|连接|保护'

ui_dump() {
  local out="$UI_DUMP_DIR/ui-$$.xml"
  adb shell uiautomator dump /sdcard/l7-ui.xml >/dev/null 2>&1
  adb pull /sdcard/l7-ui.xml "$out" >/dev/null 2>&1
  printf '%s' "$out"
}

# Prints "x1 y1 x2 y2" for the connect control, or nothing.
find_connect_control() {
  local dump
  dump=$(ui_dump)
  [ -s "$dump" ] || return 1
  grep -oE "text=\"[^\"]*($CONNECT_TEXT_RE)[^\"]*\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" "$dump" \
    | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1
}

ui_launch() {
  adb shell am force-stop "$PKG" >/dev/null 2>&1
  sleep 1
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  # Long enough for first-run work but short enough not to eat the sample window.
  sleep 8
}

# Swipes the connect slider. Returns 0 only if the control was found and swiped —
# a missing control is an error, never a silent no-op, because "we could not find
# the thing to press" and "we pressed it" must never look the same.
ui_connect() {
  local bounds x1 y1 x2 y2
  bounds=$(find_connect_control)
  if [ -z "$bounds" ]; then
    printf '!! connect control not found on screen.\n' >&2
    printf '!! visible text was:\n' >&2
    local dump
    dump=$(ui_dump)
    grep -oE 'text="[^"]+"' "$dump" 2>/dev/null | sort -u | head -30 >&2
    return 1
  fi
  set -- $(echo "$bounds" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/')
  x1=$1; y1=$2; x2=$3; y2=$4
  adb shell input swipe "$x1" "$(( y1 + (y2 - y1) / 2 ))" "$x2" "$(( y1 + (y2 - y1) / 2 ))" 400
  return 0
}

ui_disconnect() {
  local bounds
  bounds=$(find_connect_control)
  # After connecting, the same control is the disconnect control.
  if [ -n "$bounds" ]; then
    set -- $(echo "$bounds" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/')
    adb shell input swipe "$1" "$(( $2 + ($4 - $2) / 2 ))" "$3" "$(( $2 + ($4 - $2) / 2 ))" 400
    return 0
  fi
  return 1
}

ui_cleanup() { rm -rf "$UI_DUMP_DIR"; }
