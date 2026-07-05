# Examples

Runnable consumer projects that apply `gradle-envoy` **straight from this repo's source** (via
`pluginManagement { includeBuild("../..") }` — no publishing needed). They double as living documentation and
a manual smoke test.

| Example | DSL | Demonstrates |
| --- | --- | --- |
| [`kotlin-app`](kotlin-app) | Kotlin | Injection into `run` (JavaExec) **and** `test` (Test) |
| [`groovy-app`](groovy-app) | Groovy | The `envoy { }` extension from a Groovy build, injection into `run` |

Neither build wires `environment(...)` into any task — gradle-envoy does it automatically.

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

## In IntelliJ

Open an example as a Gradle project and run `App` or the test with **execution delegated to Gradle**
(the default). The injected variables are present — which is the whole point: it works from the IDE, not just
a direnv shell.
