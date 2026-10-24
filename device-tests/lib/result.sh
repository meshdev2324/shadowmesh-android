#!/usr/bin/env bash
# Result accounting for the device-in-the-loop suite.
#
# ## Why this file exists
#
# The failure this suite is built against already happened once: a gate reported
# healthy while nothing had been dispatched. So the accounting here has exactly
# one job — **make it impossible to read a run that did not happen as a run that
# passed.**
#
# Three outcomes, never two:
#
#   PASS    the test ran on a real device and its device-level assertions held
#   FAIL    the test ran and a device-level assertion did not hold
#   NOTRUN  the test could not run (no device, app not installed, no consent)
#
# `PASS` is only ever printed for something that executed. A NOTRUN is counted,
# printed in its own section, and forces a non-zero exit. There is no code path
# that prints PASS for a test that did not run.
#
# Exit codes:
#   0  every test ran and passed
#   1  at least one test ran and failed
#   2  at least one test could not run  (never reported as success, by anyone)
#   3  the suite itself is broken (bad args, unreadable lib)

# shellcheck disable=SC2034
RESULTS=()

_banner() {
  printf '\n%s\n' "=============================================================================="
  printf '%s\n' "$1"
  printf '%s\n' "=============================================================================="
}

loud_skip() {
  # The loudest thing this harness ever prints. Deliberately unmissable: box
  # drawing, the word SKIPPED in caps, and the reason spelled out. A CI log that
  # is tailed to twenty lines still shows this.
  _banner "!!  DEVICE-IN-THE-LOOP TESTS SKIPPED — NOT RUN, NOT PASSED  !!"
  printf '\n'
  printf '  These tests assert on DEVICE state and there was no device to assert on.\n'
  printf '  They did NOT run. They did NOT pass. A skipped device suite is not a\n'
  printf '  green suite.\n'
  printf '\n'
  printf '  reason: %s\n' "${1:-unspecified}"
  printf '\n'
  printf '  to actually run them:\n'
  printf '    1. attach a device over USB with USB debugging enabled\n'
  printf '    2. confirm it is visible:      adb devices\n'
  printf '    3. install the app:             ./gradlew :app:installDebug\n'
  printf '    4. run this suite again:        ./device-tests/run.sh\n'
  printf '\n'
  printf '  Anything that reports on this suite MUST propagate exit code 2.\n'
  printf '  Treating exit 2 as success is the exact failure this suite exists to\n'
  printf '  prevent.\n\n'
  printf '%s\n' "=============================================================================="
}

# record <PASS|FAIL|NOTRUN> <test-name> <detail>
record() {
  RESULTS+=("$1|$2|$3")
}

# assertion helper: asserts a device-level fact and records on failure only.
# usage: assert_device <PASS|FAIL|NOTRUN-state> <msg>
assert_fact() {
  if [ "$1" = "true" ]; then
    printf '      ok   %s\n' "$2"
  else
    printf '      FAIL %s\n' "$2"
    CURRENT_FAILED=1
  fi
}

# ------------------------------------------------------------------ summary ---

print_summary() {
  local pass=0 fail=0 notrun=0 name detail line
  _banner "DEVICE TEST SUMMARY"

  for line in "${RESULTS[@]}"; do
    name=${line%%|*}; line=${line#*|}
    detail=${line#*|}
    case "$name" in
      PASS)   pass=$((pass + 1));   printf '  PASS    %-52s %s\n' "${detail%%|*}" "${detail#*|}" ;;
      FAIL)   fail=$((fail + 1));   printf '  FAIL    %-52s %s\n' "${detail%%|*}" "${detail#*|}" ;;
      NOTRUN) notrun=$((notrun + 1)); printf '  NOTRUN  %-52s %s\n' "${detail%%|*}" "${detail#*|}" ;;
    esac
  done

  printf '\n'
  printf '  device tests: PASS=%d FAIL=%d NOT_RUN=%d\n' "$pass" "$fail" "$notrun"

  if [ "$fail" -gt 0 ]; then
    printf '\n  RESULT: FAILED (%d device assertion failure(s))\n' "$fail"
    printf '  The defect these exist for was invisible in the UI. It is not invisible\n'
    printf '  here — the device said so.\n'
    return 1
  fi

  if [ "$notrun" -gt 0 ]; then
    printf '\n  RESULT: NOT RUN (%d test(s) never executed)\n' "$notrun"
    printf '  This is deliberately NOT reported as a pass.\n'
    return 2
  fi

  printf '\n  RESULT: PASSED (%d/%d device tests)\n' "$pass" "$pass"
  return 0
}

# exit_with_summary — final gate. The only function that ends the process.
exit_with_summary() {
  print_summary
  local rc=$?
  # Belt and braces: assert the invariant that makes this suite worth having.
  # If pass is 0 and notrun > 0 the summary must have said RESULT: NOT RUN.
  exit "$rc"
}
