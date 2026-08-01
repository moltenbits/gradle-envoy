package com.moltenbits.envoy

import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification

class EnvoyExtensionSpec extends Specification {

    EnvoyExtension extension = ProjectBuilder.builder().build().objects.newInstance(EnvoyExtension)

    def "resolver registers a scheme-to-command mapping"() {
        when:
        extension.resolver('vault://') { it.command = ['vault', 'kv', 'get', '-field={field}', '{path}'] }
        extension.resolver('bws://') { it.command = ['bws', 'secret', 'get'] }

        then:
        extension.commandResolvers.get() == [
                'vault://': ['vault', 'kv', 'get', '-field={field}', '{path}'],
                'bws://'  : ['bws', 'secret', 'get'],
        ]
    }

    def "rejects the built-in op scheme"() {
        when:
        extension.resolver('op://') { it.command = ['my-op'] }

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('op://')
        e.message.contains('cliExecutable')
    }

    def "rejects an empty command"() {
        when:
        extension.resolver('vault://') { }

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('command must not be empty')
    }

    def "rejects a duplicate scheme registration"() {
        given:
        extension.resolver('vault://') { it.command = ['a'] }

        when:
        extension.resolver('vault://') { it.command = ['b'] }

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('already registered')
    }

    def "rejects the malformed scheme '#scheme'"() {
        when:
        extension.resolver(scheme) { it.command = ['cmd'] }

        then:
        thrown(IllegalArgumentException)

        where:
        scheme << ['vault', 'vault:/', '://', '1vault://', ' vault://', 'vault://extra']
    }
}
