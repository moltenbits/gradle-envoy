# List available recipes
default:
    @just --list

# Install the CLIs the custom-resolver examples test against (see Brewfile)
deps:
    brew bundle

# Hermetic verification of all examples — what CI runs, no real tools needed
verify:
    ENVOY_EXAMPLE_OP="{{ justfile_directory() }}/examples/fake-op" ./examples/verify.sh

# Run vault-app against a throwaway Vault dev server and assert the resolved value
test-vault:
    #!/usr/bin/env bash
    set -euo pipefail
    export VAULT_ADDR=http://127.0.0.1:18200 VAULT_TOKEN=root
    log="$(mktemp -t envoy-vault-dev)"
    vault server -dev -dev-root-token-id=root -dev-listen-address=127.0.0.1:18200 >"$log" 2>&1 &
    pid=$!
    trap 'kill "$pid" 2>/dev/null || true' EXIT
    for _ in $(seq 10); do vault status >/dev/null 2>&1 && break; sleep 1; done
    vault status >/dev/null 2>&1 || { echo "Vault dev server failed to start (log: $log)" >&2; exit 1; }
    vault kv put secret/envoy-example token=envoy_cred >/dev/null
    # --no-daemon so VAULT_ADDR/VAULT_TOKEN reliably reach the CLI the build spawns; a pre-existing
    # daemon keeps the environment it started with.
    out="$(./gradlew --quiet --no-daemon -p examples/vault-app run)"
    echo "$out"
    grep -q 'ENVOY_EXAMPLE_TOKEN *= envoy_cred' <<<"$out"
    echo "PASS vault:// resolved against a real Vault dev server"

# Run secretspec-app against the real secretspec CLI (dotenv provider — no server needed)
test-secretspec:
    #!/usr/bin/env bash
    set -euo pipefail
    out="$(./gradlew --quiet -p examples/secretspec-app run)"
    echo "$out"
    grep -q 'ENVOY_EXAMPLE_TOKEN *= example-secretspec-secret' <<<"$out"
    echo "PASS secretspec:// resolved via the real secretspec CLI"

# Test both custom-resolver examples against the real tools
test-resolvers: test-vault test-secretspec
