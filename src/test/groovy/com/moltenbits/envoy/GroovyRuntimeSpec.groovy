package com.moltenbits.envoy

import spock.lang.Specification

/**
 * Guards against the classpath split-brain this build is prone to: gradleApi() puts the Gradle
 * distribution's Groovy 4 jars ahead of the declared Groovy 5 on the test runtime classpath, so
 * specs compiled by the Groovy 5 compiler can silently run on a Groovy 4 runtime.
 */
class GroovyRuntimeSpec extends Specification {

    def "specs run on the same Groovy line they are compiled with"() {
        expect:
        GroovySystem.version.startsWith('5.')
    }
}
