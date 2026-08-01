pluginManagement {
    // Use gradle-envoy straight from this repo's source — no publishing needed.
    includeBuild("../..")
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    // No version: the plugin is supplied by the included build above.
    id("com.moltenbits.envoy")
    // Auto-downloads the Java 21 toolchain (see build.gradle.kts) if it isn't already installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "secretspec-app"

// By default the example calls the real `secretspec` on your PATH (brew install secretspec) — its
// bundled dotenv provider needs no server, so that works out of the box. For a hermetic demo without
// secretspec installed, set ENVOY_EXAMPLE_SECRETSPEC to the bundled fake CLI (see examples/README.md).
val secretspecCli = System.getenv("ENVOY_EXAMPLE_SECRETSPEC") ?: "secretspec"

// SecretSpec resolves declared keys by name, so {field} strips the scheme from
// secretspec://ENVOY_EXAMPLE_TOKEN. Absolute paths matter: the CLI is spawned by the Gradle
// daemon, whose working directory is not this project, so secretspec.toml and the provider's
// store are pinned explicitly. The --reason satisfies SecretSpec's agent-access audit policy.
envoy {
    resolver("secretspec://") {
        command = listOf(
            secretspecCli, "get", "{field}",
            "-f", settingsDir.resolve("secretspec.toml").absolutePath,
            "--provider", "dotenv:${settingsDir.resolve("store.env").absolutePath}",
            "--reason", "gradle-envoy example",
        )
    }
}
