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

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "kotlin-app"

// By default the examples call the real `op`/`op-fast` on your PATH. For a hermetic demo without a
// vault, set ENVOY_EXAMPLE_OP to the bundled fake CLI (see examples/README.md).
System.getenv("ENVOY_EXAMPLE_OP")?.let { fakeOp -> envoy { cliExecutable = fakeOp } }
