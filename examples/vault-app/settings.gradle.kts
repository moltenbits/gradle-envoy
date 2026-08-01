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

rootProject.name = "vault-app"

// By default the example calls the real `vault` on your PATH (see examples/README.md for the dev-server
// setup). For a hermetic demo without a server, set ENVOY_EXAMPLE_VAULT to the bundled fake CLI.
val vaultCli = System.getenv("ENVOY_EXAMPLE_VAULT") ?: "vault"

// Vault's CLI takes the field as a flag and the path as an argument, so the reference
// vault://secret/envoy-example/token expands to: vault kv get -field=token secret/envoy-example
envoy {
    resolver("vault://") {
        command = listOf(vaultCli, "kv", "get", "-field={field}", "{path}")
    }
}
