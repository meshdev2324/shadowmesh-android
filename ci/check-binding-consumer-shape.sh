#!/usr/bin/env bash
# GATE-F109-BINDING — the Android client's FFI consumer, against the SHAPE the
# generated binding actually declares.
#
# ## What this is
#
# The regenerated bindings (11836fe, L9's window) changed the FFI's roster
# accessor's shape: `get_nodes()` used to return `sequence<PublicNode>` and now
# returns the roster ENVELOPE, `NodeRoster { projection, nodes }`. The consumer
# side had not moved with it, and the Android tree did not compile:
#
#     e: NodeDiscovery.kt:35   -- getNodes().map(::toEntry) on a NodeRoster
#     e: VPNManagerViewModel.kt -- getNodesResilient().map { } on a NodeRoster
#
# ⚠️ **And nothing in the tree noticed.** The generated Kotlin binding is a
# committed build artefact, the consumer is committed source, and the two are
# related only by a human remembering to update both. There was **no gate for
# a regeneration** — F99's shape, and the class of defect that recurs on every
# FFI change.
#
# ⇒ **This gate is the check that the consumer matches the binding.** It reads
# the DECLARED SIGNATURES out of the generated binding and checks every
# consumer call site against them. It needs no toolchain, so it can be a
# watcher's step, and it is red-by-construction for the defect it guards.
#
# ## The house convention
#
# Run it with `--selftest` to prove each rule can fail. A gate nobody has seen
# go red is decorative.
set -uo pipefail

REPO_ROOT="${SHADOWMESH_REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)}"
ANDROID_TREE="${SHADOWMESH_ANDROID_TREE:-$REPO_ROOT/apps/android}"
BINDING="${SHADOWMESH_ANDROID_BINDING:-$ANDROID_TREE/core/vpn/src/main/java/uniffi/shadowmesh/shadowmesh.kt}"

rc=0
note() { printf '%s\n' "$*"; }
fail() { printf 'FAIL: %s\n' "$*" >&2; rc=1; }

# The consumer sources — everything under the Android tree that is NOT the
# generated binding itself.
consumer_sources() {
  local tree="$1"
  find "$tree" -name '*.kt' -type f \
    ! -path '*/uniffi/shadowmesh/shadowmesh.kt' \
    ! -path '*/build/*' \
    ! -path '*/.gradle*/*' 2>/dev/null | sort
}

# ---------------------------------------------------------------------------
# (a) The generated binding is COMMITTED and UNMODIFIED.
# ---------------------------------------------------------------------------
#
# ⚠️ **A regeneration and its consumers are ONE change.**
#
# The binding is a generated artefact that is committed to the tree. If it is
# regenerated and committed WITHOUT the consumer moving with it, the tree is
# broken in a way no local build notices until somebody runs one. If it is
# regenerated and NOT committed, the client is building against an artefact
# that does not exist in the history — worse, because the next checkout breaks.
#
# ⇒ The binding must be tracked AND unmodified relative to HEAD.
check_binding_is_committed_and_clean() {
  local binding="$1" repo="${2:-$REPO_ROOT}" rel="" tracked=0 clean=0
  if [ ! -f "$binding" ]; then
    fail "the generated Kotlin binding is not there: $binding — the client's FFI surface cannot be checked against a binding that does not exist"
    return 1
  fi
  if ! command -v git >/dev/null 2>&1; then
    fail "git is not available — the binding's committed state cannot be verified, and a rule that cannot be verified must not wave the tree through"
    return 1
  fi
  rel="${binding#"$repo"/}"
  if git -C "$repo" ls-files --error-unmatch -- "$rel" >/dev/null 2>&1; then
    tracked=1
  else
    fail "the generated Kotlin binding ($rel) is not tracked by git — a generated binding that is not committed is not a binding the client may build against"
  fi
  if git -C "$repo" diff --quiet HEAD -- "$rel"; then
    clean=1
  else
    fail "the generated Kotlin binding ($rel) has been regenerated and NOT committed — a regeneration and the consumer that matches it are ONE change, and this one is half-landed"
  fi
  [ "$tracked" -eq 1 ] && [ "$clean" -eq 1 ]
}

# ---------------------------------------------------------------------------
# What the binding DECLARES.
# ---------------------------------------------------------------------------
#
# Two sets, both read out of the generated file:
#
#  - the ACCESSORS: every `fun \`name\`(` — the FFI's public surface.
#  - the RETURN TYPES: `fun \`name\`<args>: <Type>` — what each accessor hands
#    back. ⚠️ **The return type is the whole point of rule (c)**: a `List<…>`
#    return may be iterated, a RECORD return may not, and the difference
#    between the two is exactly what a regeneration changes.
#
# - the TYPES: the binding's own declarations — `interface ApiClientInterface`,
#    `data class PublicNode`, `enum class NodeProjection`. A consumer variable
#    typed with one of these is an FFI object, and every method called on it
#    must be an accessor the binding declares.
declared_accessors() {
  local binding="$1"
  [ -f "$binding" ] || return 1
  grep -oE 'fun `[A-Za-z0-9_]+`\(' "$binding" | sed -E 's/.*`([A-Za-z0-9_]+)`.*/\1/' | sort -u
}

# `name<TYPE>` per accessor, for the accessors that RETURN something.
declared_returns() {
  local binding="$1"
  [ -f "$binding" ] || return 1
  grep -oE 'fun `[A-Za-z0-9_]+`\([^)]*\):[[:space:]]*[A-Za-z0-9_.<>?, ]+' "$binding" \
    | sed -E 's/fun `([A-Za-z0-9_]+)`\([^)]*\):[[:space:]]*/\1|/' \
    | sed -E 's/[[:space:]]+$//' | sort -u
}

# The binding's own type names.
declared_types() {
  local binding="$1"
  [ -f "$binding" ] || return 1
  grep -oE '(data class|enum class|class|interface|object) [A-Za-z0-9_]+' "$binding" \
    | awk '{print $NF}' | sort -u
}

# The names of the FFI-typed receivers IN ONE FILE.
#
# ⚠️ **Per file, deliberately.** A global name set is a false-positive
# generator: a name like `client` or `api` is used for half a dozen unrelated
# things across a tree, and a global set would demand that every one of them
# be an FFI accessor. The receiver set is only trusted where the file itself
# says the type is an FFI type.
#
# Two forms: a typed declaration (`apiClient: ApiClient`) and a mock
# (`val client = mockk<uniffi.shadowmesh.ApiClient>()`).
ffi_receivers_in_file() {
  local file="$1" types="$2" name type
  # The FFI types this file imports.
  local imported
  imported="$(grep -oE 'import uniffi\.shadowmesh\.[A-Za-z0-9_]+' "$file" 2>/dev/null \
    | sed -E 's/.*\.//' | sort -u)"
  # A declared parameter or property whose type is one of them.
  while IFS= read -r name; do
    [ -z "$name" ] && continue
    printf '%s\n' "$name"
  done < <(
    grep -oE '\b[A-Za-z0-9_]+[[:space:]]*:[[:space:]]*[A-Za-z0-9_]+' "$file" 2>/dev/null \
      | sed -E 's/[[:space:]]*:[[:space:]]*/|/' \
      | while IFS='|' read -r n t; do
          if printf '%s\n' "$imported" | grep -qx "$t"; then printf '%s\n' "$n"; fi
        done
    grep -oE '\b[A-Za-z0-9_]+[[:space:]]*=[[:space:]]*mockk<uniffi\.shadowmesh\.[A-Za-z0-9_]+>' "$file" 2>/dev/null \
      | sed -E 's/[[:space:]]*=.*//'
  )
}

# ---------------------------------------------------------------------------
# (b) Every FFI accessor the CLIENT calls is declared by the binding.
# ---------------------------------------------------------------------------
#
# A call to an accessor the committed binding does not declare cannot compile.
# This catches the other half of the drift: the consumer reaching for a
# function the regeneration removed or renamed.
check_client_calls_are_declared() {
  local tree="$1" binding="$2" declared="" file bad=0 name recv
  declared="$(declared_accessors "$binding")" || return 1

  while IFS= read -r file; do
    [ -z "$file" ] && continue
    while IFS= read -r recv; do
      [ -z "$recv" ] && continue
      while IFS= read -r name; do
        [ -z "$name" ] && continue
        if ! printf '%s\n' "$declared" | grep -qx "$name"; then
          fail "$file calls '$recv.$name()' on an FFI object, but the committed generated binding does not declare it — a regeneration that removed or renamed an accessor must move its consumers with it"
          bad=1
        fi
      done < <(grep -oE "\b${recv}\.[A-Za-z0-9_]+\(" "$file" 2>/dev/null \
        | sed -E "s/^${recv}\.//; s/\($//" | sort -u)
    done < <(ffi_receivers_in_file "$file" "" | sort -u)
  done < <(consumer_sources "$tree")

  [ "$bad" -eq 0 ]
}

# ---------------------------------------------------------------------------
# (c) The consumer's USE of an accessor matches its DECLARED return type.
# ---------------------------------------------------------------------------
#
# ⚠️ **This is the rule that would have caught the defect.** The consumer read
# `getNodes()` as a list of rows; the binding declares a record. The mismatch
# is a COMPILE error, but nothing in the tree says so until a compiler runs —
# and the compiler is not in the watcher's step list.
#
# ⇒ For every accessor whose declared return type is NOT a list, a consumer may
# not chain a list operation onto the call. It must read a FIELD of the record
# (`.nodes`), which is what the envelope's shape demands.
check_consumer_matches_declared_return_type() {
  local tree="$1" binding="$2" file accessor rettype bad=0
  local declared
  declared="$(declared_returns "$binding")" || return 1

  while IFS='|' read -r accessor rettype; do
    [ -z "$accessor" ] && continue
    case "$rettype" in
      List\<*|kotlin.collections.List\<*|Sequence\<*) continue ;;
    esac
    while IFS= read -r file; do
      [ -z "$file" ] && continue
      if grep -oE "\.${accessor}\(\)[.]?(map|filter|forEach|first|firstOrNull|last|lastOrNull|size|isEmpty|isNotEmpty|any|all|toList|toTypedArray|contains|indexOf|sorted|sortedBy|joinToString|sumOf|count)\b" \
        "$file" >/dev/null 2>&1; then
        fail "$file chains a LIST operation onto '$accessor()', but the generated binding declares it as '$rettype' — a record is not a list, and this is the exact mismatch a regeneration leaves behind"
        bad=1
      fi
    done < <(grep -rl "\.${accessor}()" "$tree" --include='*.kt' 2>/dev/null \
      | grep -v 'uniffi/shadowmesh/shadowmesh.kt' \
      | grep -v '/build/')
  done <<<"$declared"

  [ "$bad" -eq 0 ]
}

# ---------------------------------------------------------------------------
# --selftest
# ---------------------------------------------------------------------------
expect_pass() {
  local what="$1"; shift
  if ! "$@" >/dev/null 2>&1; then
    echo "selftest FAIL: $what must PASS the rule" >&2
    self_rc=1
  fi
}

expect_fail() {
  local what="$1"; shift
  if "$@" >/dev/null 2>&1; then
    echo "selftest FAIL: $what must FAIL the rule" >&2
    self_rc=1
  fi
}

selftest() {
  local tmp self_rc=0
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN

  # A binding that declares the envelope, and a consumer that reads it right.
  cat >"$tmp/binding.ok.kt" <<'KT'
interface ApiClientInterface {
    fun `getNodes`(): NodeRoster
    fun `getNodesResilient`(): NodeRoster
    fun `getConfig`(`nodeId`: kotlin.String): VpnConfig
}
data class NodeRoster(
    var `projection`: NodeProjection,
    var `nodes`: List<PublicNode>
)
data class PublicNode(
    var `id`: kotlin.String
)
KT
  cat >"$tmp/consumer.ok.kt" <<'KT'
package com.shadowmesh.core_vpn.domain

import uniffi.shadowmesh.ApiClient

object NodeDirectorySource {
    fun loadDirectory(apiClient: ApiClient): List<MeshNodeEntry> =
        apiClient.getNodes().nodes.map(::toEntry)
}
KT
  # The SAME consumer, written against the pre-envelope shape. This is the
  # defect, verbatim.
  cat >"$tmp/consumer.broken.kt" <<'KT'
package com.shadowmesh.core_vpn.domain

import uniffi.shadowmesh.ApiClient

object NodeDirectorySource {
    fun loadDirectory(apiClient: ApiClient): List<MeshNodeEntry> =
        apiClient.getNodes().map(::toEntry)
}
KT
  # A consumer that calls an accessor the binding does not declare.
  cat >"$tmp/consumer.undeclared.kt" <<'KT'
package com.shadowmesh.core_vpn.domain

import uniffi.shadowmesh.ApiClient

object NodeDirectorySource {
    fun loadDirectory(apiClient: ApiClient): List<MeshNodeEntry> =
        apiClient.getNodesLegacy().nodes.map(::toEntry)
}
KT

  mkdir -p "$tmp/tree.ok" "$tmp/tree.broken" "$tmp/tree.undeclared"
  cp "$tmp/consumer.ok.kt" "$tmp/tree.ok/NodeDiscovery.kt"
  cp "$tmp/consumer.broken.kt" "$tmp/tree.broken/NodeDiscovery.kt"
  cp "$tmp/consumer.undeclared.kt" "$tmp/tree.undeclared/NodeDiscovery.kt"

  # The rule must PASS on the correct fixtures ...
  expect_pass "a consumer that reads the envelope's field" \
    check_consumer_matches_declared_return_type "$tmp/tree.ok" "$tmp/binding.ok.kt"
  expect_pass "a consumer that calls only declared accessors" \
    check_client_calls_are_declared "$tmp/tree.ok" "$tmp/binding.ok.kt"

  # ... and FAIL on each of the ways it must fail.
  expect_fail "a consumer that treats the envelope as a list" \
    check_consumer_matches_declared_return_type "$tmp/tree.broken" "$tmp/binding.ok.kt"
  expect_fail "a consumer that calls an undeclared accessor" \
    check_client_calls_are_declared "$tmp/tree.undeclared" "$tmp/binding.ok.kt"

  if [ "$self_rc" -eq 0 ]; then
    echo "selftest OK: the return-type rule fires on a consumer that chains a list"
    echo "             operation onto a record-typed accessor; the declared-accessor rule"
    echo "             fires on a consumer that calls an accessor the binding does not"
    echo "             declare."
  fi
  return "$self_rc"
}

if [ "${1:-}" = "--selftest" ]; then
  selftest
  exit $?
fi

# ---------------------------------------------------------------------------
# The gate
# ---------------------------------------------------------------------------
note "==> (a) the generated binding is committed and unmodified"
check_binding_is_committed_and_clean "$BINDING" || rc=1

note "==> (b) every FFI accessor the client calls is declared by the binding"
check_client_calls_are_declared "$ANDROID_TREE" "$BINDING" || rc=1

note "==> (c) the consumer's use of an accessor matches its declared return type"
check_consumer_matches_declared_return_type "$ANDROID_TREE" "$BINDING" || rc=1

if [ "$rc" -ne 0 ]; then
  echo >&2
  echo "Android binding-consumer gate: the client's FFI surface and the" >&2
  echo "generated binding have drifted apart. A regeneration changes the" >&2
  echo "binding's declared shape; the consumer must move with it, in the" >&2
  echo "same commit." >&2
fi
exit "$rc"
