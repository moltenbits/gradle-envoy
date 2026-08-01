# Examples

Runnable consumer projects that apply `gradle-envoy` **straight from this repo's source** (via
`pluginManagement { includeBuild("../..") }` — no publishing needed). They double as living documentation and
a manual smoke test.

| Example | DSL | Demonstrates |
| --- | --- | --- |
| [`kotlin-app`](kotlin-app) | Kotlin | Injection into `run` (JavaExec) **and** `test` (Test), asserted by a Spock spec |
| [`groovy-app`](groovy-app) | Groovy | The `envoy { }` extension from a Groovy build, injection into `run` |
| [`vault-app`](vault-app) | Kotlin | A custom `vault://` resolver mapped to the HashiCorp Vault CLI via `resolver(...)` |
| [`secretspec-app`](secretspec-app) | Kotlin | A custom `secretspec://` resolver mapped to the [SecretSpec](https://secretspec.dev) CLI |

Neither build wires `environment(...)` into any task — gradle-envoy does it automatically.

## Verifying them (what CI runs)

[`verify.sh`](verify.sh) drives both examples and asserts the results, so the examples are a
regression test rather than just a demo. It runs in two modes, selected by whether
`ENVOY_EXAMPLE_OP` is set:

```bash
# hermetic — no vault needed; the fake CLI returns a fixed value, asserted exactly
ENVOY_EXAMPLE_OP="$PWD/examples/fake-op" ./examples/verify.sh

# live — the real `op` on PATH resolves against a real vault
./examples/verify.sh
```

Both modes assert an exact value: `example-resolved-secret` from the fake CLI, `envoy_cred` from
the `Envoy` test vault, which holds only dummy values.
[`AppSpec`](kotlin-app/src/test/groovy/com/example/AppSpec.groovy) additionally asserts from
inside the forked test JVM that the reference was resolved rather than passed through verbatim —
a check that holds in either mode.

Live mode needs `op` authenticated: the desktop app integration locally, or
`OP_SERVICE_ACCOUNT_TOKEN` in CI.

## Run them (hermetic, no 1Password vault needed)

Each ships a bundled fake `op` ([`fake-op`](fake-op)) so you can see `op://` resolution without a real vault.
From the repo root, using the existing wrapper:

```bash
ENVOY_EXAMPLE_OP="$PWD/examples/fake-op" ./gradlew -p examples/kotlin-app run
ENVOY_EXAMPLE_OP="$PWD/examples/fake-op" ./gradlew -p examples/kotlin-app test
ENVOY_EXAMPLE_OP="$PWD/examples/fake-op" ./gradlew -p examples/groovy-app run
```

Expected `run` output:

```
ENVOY_EXAMPLE_GREETING = Hello from .env
ENVOY_EXAMPLE_TOKEN    = example-resolved-secret
```

`ENVOY_EXAMPLE_GREETING` is a plain `.env` literal; `ENVOY_EXAMPLE_TOKEN` is the `op://` reference in the
example's [`.env`](kotlin-app/.env), resolved by the fake CLI and injected — with no per-task wiring.

## Run them against real 1Password

1. Edit the example's `.env` so `ENVOY_EXAMPLE_TOKEN` points at a real item in your vault.
2. Make sure `op` (or `op-fast`) is on your `PATH`.
3. Run **without** `ENVOY_EXAMPLE_OP` (the `System.getenv("ENVOY_EXAMPLE_OP")` hook in each `settings` file is
   the only example-only line — a real project just applies the plugin):

   ```bash
   ./gradlew -p examples/kotlin-app run
   ```

The placeholder `op://Example Vault/...` reference won't resolve against your vault, so with the real CLI it
is skipped with a warning (lenient mode) and `ENVOY_EXAMPLE_TOKEN` prints as `null` until you point it at a
real item. The `GREETING` literal always works.

## Custom resolver examples

`vault-app` and `secretspec-app` demonstrate the generic command resolver: mapping a reference scheme to a
CLI command template in `settings.gradle.kts`, with no 1Password involvement at all. `verify.sh` always runs
them against their bundled fake CLIs ([`fake-vault`](fake-vault), [`fake-secretspec`](fake-secretspec)); by
default they call the real tools on your `PATH`:

```bash
# hermetic, no tools needed
ENVOY_EXAMPLE_VAULT="$PWD/examples/fake-vault" ./gradlew -p examples/vault-app run
ENVOY_EXAMPLE_SECRETSPEC="$PWD/examples/fake-secretspec" ./gradlew -p examples/secretspec-app run
```

Testing against the real tools is automated by the repo-root [`justfile`](../justfile) — `just deps`
installs them via the [`Brewfile`](../Brewfile), then `just test-vault` (spins up and tears down a
throwaway Vault dev server), `just test-secretspec`, or `just test-resolvers` for both. CI runs
`just test-resolvers` on a macOS runner too, so the real-tool paths are exercised on every PR. The
manual equivalents follow.

### vault-app against a real Vault

The example's `.env` references `vault://secret/envoy-example/token`, which the registered template expands
to `vault kv get -field=token secret/envoy-example`. A throwaway dev server proves it end to end:

```bash
vault server -dev -dev-root-token-id=root &
export VAULT_ADDR=http://127.0.0.1:8200 VAULT_TOKEN=root
vault kv put secret/envoy-example token=envoy_cred
./gradlew -p examples/vault-app run     # ENVOY_EXAMPLE_TOKEN = envoy_cred
```

`VAULT_ADDR`/`VAULT_TOKEN` must be in the environment the **Gradle daemon** inherits — a daemon started
before you exported them won't see them (`./gradlew --stop` and rerun, or add `--no-daemon`).

### secretspec-app against the real SecretSpec

No server needed: the example pins SecretSpec's `dotenv` provider to the committed
[`store.env`](secretspec-app/store.env) (a dummy value), so with `secretspec` installed
(`brew install secretspec`) it works out of the box:

```bash
./gradlew -p examples/secretspec-app run     # ENVOY_EXAMPLE_TOKEN = example-secretspec-secret
```

The registered template shows three things a real SecretSpec setup needs: `{field}` to strip the scheme
(`secretspec get` takes the bare key name), absolute `-f`/provider paths (the CLI inherits the daemon's
working directory, not the project's), and `--reason` for SecretSpec's agent-access audit policy. Swap the
`--provider` flag for `keyring` or `onepassword://...` to route through any of its other backends.

## In IntelliJ

Open an example as a Gradle project and run `App` or the test with **execution delegated to Gradle**
(the default). The injected variables are present — which is the whole point: it works from the IDE, not just
a direnv shell.
