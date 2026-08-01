plugins {
    application
}

application {
    mainClass = "com.example.App"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// This build never wires `environment(...)` into `run` — gradle-envoy injects the
// .env values (and resolved vault:// secrets) automatically.
