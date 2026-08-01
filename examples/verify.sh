#!/usr/bin/env bash
#
# Consumer-perspective validation of the example projects.
#
# Each example applies gradle-envoy from source (pluginManagement { includeBuild("../..") }) and
# never wires environment(...) itself, so a green run here proves the whole chain end to end:
# .env is found, op:// references are resolved, and the values reach a Gradle-forked JVM.
#
# Two modes, selected by whether ENVOY_EXAMPLE_OP is set:
#
#   hermetic  ENVOY_EXAMPLE_OP=/path/to/examples/fake-op   — no vault needed; the fake CLI returns
#             a fixed value, so the resolved secret is asserted exactly.
#   live      (ENVOY_EXAMPLE_OP unset)                     — the real `op` on PATH resolves against a
#             real vault. The value is unknown to this script and is never printed; we assert only
#             that resolution *happened*.
#
# Usage:
#   examples/verify.sh                 # live   (requires `op` authenticated)
#   ENVOY_EXAMPLE_OP=... examples/verify.sh   # hermetic
set -euo pipefail

readonly REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly GRADLEW="$REPO_ROOT/gradlew"
readonly FAKE_SECRET="example-resolved-secret"

# The live value is a dummy in the dedicated "Envoy" test vault, so it is safe to assert on and
# print. Never do this for a reference that resolves to a genuine secret.
readonly LIVE_SECRET="envoy_cred"

if [[ -n "${ENVOY_EXAMPLE_OP:-}" ]]; then
  readonly MODE="hermetic"
else
  readonly MODE="live"
fi

failures=0

log()  { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
pass() { printf '  \033[32mPASS\033[0m %s\n' "$*"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$*"; failures=$((failures + 1)); }

# Extracts "KEY = value" from an example's stdout. The examples print in that fixed format.
value_of() {
  local key="$1" output="$2"
  sed -n "s/^${key}[[:space:]]*=[[:space:]]*//p" <<<"$output" | head -1
}

# A plain .env literal must arrive verbatim.
assert_literal() {
  local key="$1" expected="$2" actual="$3"
  if [[ "$actual" == "$expected" ]]; then
    pass "$key injected as '$expected'"
  else
    fail "$key expected '$expected', got '$actual'"
  fi
}

# A resolved op:// secret. The value is only known in hermetic mode; in live mode we assert that
# resolution occurred without ever printing what came back.
assert_resolved_secret() {
  local key="$1" actual="$2"

  if [[ -z "$actual" || "$actual" == "null" ]]; then
    fail "$key is empty — the op:// reference was skipped, not resolved"
    return
  fi
  if [[ "$actual" == op://* ]]; then
    fail "$key arrived as an unresolved reference (still 'op://...')"
    return
  fi

  local expected
  if [[ "$MODE" == "hermetic" ]]; then
    expected="$FAKE_SECRET"
  else
    expected="$LIVE_SECRET"
  fi

  if [[ "$actual" == "$expected" ]]; then
    pass "$key resolved to '$expected'"
  else
    fail "$key expected '$expected', got '$actual'"
  fi
}

run_example() {
  local project="$1"
  shift
  "$GRADLEW" --quiet -p "$REPO_ROOT/examples/$project" "$@"
}

log "Mode: $MODE"
if [[ "$MODE" == "live" ]]; then
  command -v op >/dev/null 2>&1 || { echo "live mode needs the 'op' CLI on PATH" >&2; exit 1; }
fi

# --- kotlin-app: injection into JavaExec (run) ------------------------------------------------
log "kotlin-app: run (JavaExec)"
kotlin_out="$(run_example kotlin-app run)"
echo "$kotlin_out"
assert_literal "ENVOY_EXAMPLE_GREETING" "Hello from .env" "$(value_of ENVOY_EXAMPLE_GREETING "$kotlin_out")"
assert_resolved_secret "ENVOY_EXAMPLE_TOKEN" "$(value_of ENVOY_EXAMPLE_TOKEN "$kotlin_out")"

# --- kotlin-app: injection into Test ------------------------------------------------------------
# AppSpec asserts from inside the forked test JVM, so this covers the Test task path too.
#
# `--rerun` is load-bearing, not belt-and-braces. The injected environment is deliberately NOT a
# task input (secrets must never enter the configuration cache or up-to-date checks), so Gradle
# cannot see that the environment changed and will happily report a stale UP-TO-DATE pass. In CI
# that is worse: setup-gradle restores the build cache and org.gradle.caching=true is set, so a
# green result from an earlier run can be replayed without executing a single assertion.
log "kotlin-app: test (Test)"
if run_example kotlin-app test --rerun; then
  pass "kotlin-app test task passed (assertions ran inside the forked test JVM)"
else
  fail "kotlin-app test task failed"
fi

# --- groovy-app: the Groovy DSL + envoy { } extension -------------------------------------------
log "groovy-app: run (JavaExec)"
groovy_out="$(run_example groovy-app run)"
echo "$groovy_out"
assert_literal "ENVOY_EXAMPLE_GREETING" "Hello from .env" "$(value_of ENVOY_EXAMPLE_GREETING "$groovy_out")"
assert_resolved_secret "ENVOY_EXAMPLE_TOKEN" "$(value_of ENVOY_EXAMPLE_TOKEN "$groovy_out")"

# --- vault-app: generic command resolver (vault://) ---------------------------------------------
# Always hermetic via the bundled fake CLI — the hermetic/live axis above is about 1Password.
# A distinct fixed value proves the custom resolver ran, not the built-in op:// one.
log "vault-app: run (JavaExec, custom vault:// resolver)"
vault_out="$(ENVOY_EXAMPLE_VAULT="$REPO_ROOT/examples/fake-vault" run_example vault-app run)"
echo "$vault_out"
assert_literal "ENVOY_EXAMPLE_GREETING" "Hello from .env" "$(value_of ENVOY_EXAMPLE_GREETING "$vault_out")"
assert_literal "ENVOY_EXAMPLE_TOKEN" "example-vault-secret" "$(value_of ENVOY_EXAMPLE_TOKEN "$vault_out")"

# --- secretspec-app: generic command resolver (secretspec://) -----------------------------------
log "secretspec-app: run (JavaExec, custom secretspec:// resolver)"
secretspec_out="$(ENVOY_EXAMPLE_SECRETSPEC="$REPO_ROOT/examples/fake-secretspec" run_example secretspec-app run)"
echo "$secretspec_out"
assert_literal "ENVOY_EXAMPLE_GREETING" "Hello from .env" "$(value_of ENVOY_EXAMPLE_GREETING "$secretspec_out")"
assert_literal "ENVOY_EXAMPLE_TOKEN" "example-secretspec-secret" "$(value_of ENVOY_EXAMPLE_TOKEN "$secretspec_out")"

log "Summary"
if (( failures > 0 )); then
  printf '  \033[31m%d check(s) failed\033[0m\n' "$failures"
  exit 1
fi
printf '  \033[32mAll checks passed (%s mode)\033[0m\n' "$MODE"
