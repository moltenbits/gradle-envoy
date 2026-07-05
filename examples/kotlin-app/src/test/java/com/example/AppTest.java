package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AppTest {

    /**
     * Proves gradle-envoy injected the .env value into the test JVM — this build never calls
     * environment(...) itself. Run via Gradle (./gradlew test), or from IntelliJ with test
     * execution delegated to Gradle.
     */
    @Test
    void greetingFromDotEnvReachesTheTestJvm() {
        assertEquals("Hello from .env", System.getenv("ENVOY_EXAMPLE_GREETING"));
    }
}
