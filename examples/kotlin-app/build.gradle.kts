plugins {
    application
    groovy
}

application {
    mainClass = "com.example.App"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    testImplementation("org.apache.groovy:groovy:5.0.7")
    testImplementation("org.spockframework:spock-core:2.4-groovy-5.0")

    // Gradle 9 requires the JUnit Platform *launcher* on the test runtime classpath. Spock is the
    // engine that runs on top of it; the BOM version is the one Spock 2.4 itself imports, so the
    // launcher and engine can never drift apart.
    testRuntimeOnly(platform("org.junit:junit-bom:5.14.1"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    // Spock 2.x is executed *by* the JUnit Platform — this selects the runner, and is unrelated
    // to how assertions are written (the specs use Spock blocks and power assertions).
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
}

// Note: nowhere in this build do we wire `environment(...)` into `run` or `test`.
// gradle-envoy injects the .env values (and resolved op:// secrets) into both automatically.
