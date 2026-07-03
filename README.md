# gradle-envoy

A Gradle **settings plugin** that loads a `.env` file, resolves **1Password secret references**
(`op://vault/item/field`) via the 1Password CLI, and **automatically injects the resulting variables into
every JVM Gradle forks** — `Test`, `JavaExec`, the `application` plugin's `run`, Spring Boot's `bootRun`, and
any other `JavaExec` subtype.

## The problem it solves

Secrets are commonly declared in a `.env` as 1Password references and loaded into the shell by `direnv`:

```dotenv
GH_TOKEN="op://Private/GitHub PAT for .env/token"
```

That works from a terminal, because `direnv` has populated the shell before Gradle starts. It **does not work
from IntelliJ IDEA**: the IDE doesn't go through the direnv-hooked shell, so the Gradle process — and every
JVM it forks for tests and runs — never sees the secret.

Existing plugins ([`gradle-envrc`](https://github.com/jonsmithers/gradle-envrc),
[`dotenv-gradle`](https://github.com/uzzu/dotenv-gradle)) only expose values to build scripts and make you
hand-wire `environment(...)` into every task. **Envoy resolves the `op://` references itself and propagates
the values automatically — no per-task wiring.**

## Usage

Apply it once in **`settings.gradle.kts`**:

```kotlin
plugins {
    id("com.moltenbits.envoy") version "0.1.0"
}
```

That's it. Put a `.env` next to your build (or anywhere up the directory tree — see
[Discovery](#how-it-works)), and its variables — including resolved `op://` secrets — are present in every
`Test`/`JavaExec`/`run`/`bootRun` JVM. Nothing else to configure.

> **Requires the [1Password CLI](https://developer.1password.com/docs/cli/)** (`op`) on your `PATH`, and
> **Gradle 8.8+**.

### Recommended: `op-fast`

The default CLI is `op`. If you use `op-fast` (a Keychain-cached wrapper around `op` that works offline and
avoids repeated biometric prompts), point Envoy at it:

```kotlin
// settings.gradle.kts
envoy {
    cliExecutable = "op-fast"
}
```

## How it works

1. **Discovery** — finds the nearest `.env` by walking up from the build root (like `direnv`), so a single
   `~/Projects/.env` covers every project nested beneath it.
2. **Parsing** — reads `KEY=VALUE` lines (quotes, comments, and a tolerated `export ` prefix), leaving any
   `op://` value as a reference.
3. **Precedence** — a variable **already present in the real process environment always wins** and is left
   untouched. From a direnv terminal everything is already exported, so Envoy resolves *nothing* and never
   calls the CLI; from IntelliJ the variables are missing, so Envoy fills them in. It only ever fills gaps.
4. **Resolution** — remaining `op://` references are resolved with `op read <ref>`, **lazily**: only when a
   forked-JVM task actually runs (never during configuration, never for `./gradlew help`), and **once per
   build**, shared across every task and project.
5. **Injection** — the resolved values are set on the forked JVM's environment at execution time.

It is **configuration-cache compatible**, and resolved secrets are never written to logs, task inputs, or the
configuration cache.

## Configuration

Everything has a sensible default; the common case configures nothing.

```kotlin
// settings.gradle.kts
envoy {
    enabled = true                       // master switch (default: true)
    cliExecutable = "op"                 // 1Password CLI; e.g. "op-fast" (default: "op")
    cliArgs = listOf("read")             // leading args before the reference (default: ["read"])
    searchParentDirectories = true       // walk up to find the nearest .env (default: true)
    // envFile = file("/abs/path/.env")  // use an explicit file; disables the walk-up search
    overrideTaskEnvironment = false      // let .env override values a task set explicitly (default: false)
    strict = false                       // fail the build on an unresolvable reference (default: false)
}
```

| Option | Default | Purpose |
| --- | --- | --- |
| `enabled` | `true` | Turn the plugin off entirely (e.g. on CI where secrets arrive as real env vars). |
| `cliExecutable` | `"op"` | The 1Password CLI executable. Set `"op-fast"` for Keychain caching. |
| `cliArgs` | `["read"]` | Leading arguments placed before the reference. |
| `searchParentDirectories` | `true` | Walk up parent directories to find the nearest `.env`. |
| `envFile` | *(unset)* | An explicit `.env`; when set, the walk-up search is skipped. |
| `overrideTaskEnvironment` | `false` | When `true`, `.env` overrides variables set explicitly on a task. The **real process environment always wins** regardless. |
| `strict` | `false` | Lenient by default: an unresolvable reference is skipped with a warning. Set `true` to fail the build. |

## Security notes

- Secret **values** never appear in build logs, task inputs, or the configuration cache — only `op://`
  *references* live in your (already non-secret) `.env`.
- Resolution runs the 1Password CLI only when a task that needs it executes, minimizing biometric prompts.
- Because the real environment always wins, running from a fully-provisioned shell or CI never invokes the CLI.

## Requirements & scope

- **Gradle 8.8+** (uses the isolated `Settings.gradle.lifecycle.beforeProject` API), any JDK Gradle supports.
- **1Password CLI** (`op`, or a drop-in like `op-fast`) on `PATH`.
- Propagates into JVMs **spawned by Gradle**. Run configurations that IntelliJ executes with its *own* runner
  (bypassing Gradle) are outside a Gradle plugin's reach; use Gradle-delegated run/test.
- v1 parses `.env` natively and does **not** source `.envrc` shell logic (the direnv/`op://` result is
  reproduced without needing bash), and does not (yet) expose a build-script read API — the focus is
  zero-config propagation to forked JVMs.

## Building & testing

```bash
./gradlew check              # unit tests + TestKit functional tests
./gradlew publishToMavenLocal
```

The functional tests use a fake `op` script, so they need no live 1Password session.

## License

Apache 2.0. See [LICENSE](LICENSE).
