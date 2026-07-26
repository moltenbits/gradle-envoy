package com.example

import spock.lang.Specification

/**
 * Consumer-perspective spec: this build never calls {@code environment(...)} itself, so every
 * value asserted here can only have arrived because gradle-envoy injected it into the forked
 * test JVM.
 *
 * Run via Gradle ({@code ./gradlew -p examples/kotlin-app test}), or from IntelliJ with test
 * execution delegated to Gradle.
 */
class AppSpec extends Specification {

    def "a plain .env literal reaches the forked test JVM"() {
        expect:
        System.getenv("ENVOY_EXAMPLE_GREETING") == "Hello from .env"
    }

    def "an op:// reference is resolved before it reaches the forked test JVM"() {
        given: "the token gradle-envoy injected"
        def token = System.getenv("ENVOY_EXAMPLE_TOKEN")

        expect: "it arrived at all"
        token != null

        and: "it was actually resolved, not passed through verbatim"
        !token.startsWith("op://")

        and: "resolution produced something"
        !token.isBlank()
    }
}
