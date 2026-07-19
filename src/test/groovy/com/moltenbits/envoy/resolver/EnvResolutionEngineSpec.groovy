package com.moltenbits.envoy.resolver

import com.moltenbits.envoy.EnvoyResolutionException
import kotlin.Unit
import kotlin.jvm.functions.Function1
import spock.lang.Specification

class EnvResolutionEngineSpec extends Specification {

    List<String> skipped = []

    // Kotlin default arguments are not visible to Groovy, so every parameter is passed explicitly.
    // onSkip is a Kotlin (String) -> Unit, which a closure satisfies once it returns Unit.INSTANCE.
    private EnvResolutionEngine engine(
            List<SecretResolver> resolvers,
            Map<String, String> systemEnv = [:],
            boolean strict = false) {
        new EnvResolutionEngine(
                resolvers,
                systemEnv,
                strict,
                { String message -> skipped << message; Unit.INSTANCE } as Function1)
    }

    /** Resolver that claims every op:// reference and maps it through [behaviour]. */
    private SecretResolver resolverThat(Closure<String> behaviour) {
        Stub(SecretResolver) {
            handles(_) >> { String raw -> raw.startsWith('op://') }
            resolve(_) >> { Map<String, String> refs -> refs.collectEntries { k, v -> [k, behaviour(v)] } }
        }
    }

    def "passes literal values through untouched"() {
        expect:
        engine([resolverThat { 'resolved' }]).resolve([FOO: 'bar']) == [FOO: 'bar']
    }

    def "resolves references via the resolver"() {
        given:
        def subject = engine([resolverThat { "secret-for-$it" }])

        expect:
        subject.resolve([GH_TOKEN: 'op://Private/Item/token']) ==
                [GH_TOKEN: 'secret-for-op://Private/Item/token']
    }

    def "system env wins, and the key is dropped without ever resolving"() {
        given:
        def resolver = Mock(SecretResolver)
        def subject = engine([resolver], [GH_TOKEN: 'already-set'])

        when:
        def result = subject.resolve([GH_TOKEN: 'op://Private/Item/token'])

        then: 'a system-provided key is not injected'
        result.isEmpty()

        and: 'and never reaches the resolver at all'
        0 * resolver.resolve(_)
    }

    def "mixes literals and references, filling only the gaps"() {
        given:
        def subject = engine([resolverThat { 'S' }], [PRESENT: 'x'])

        when:
        def result = subject.resolve([
                PRESENT: 'op://should/be/skipped',
                PLAIN  : 'literal',
                SECRET : 'op://v/i/f',
        ])

        then:
        result == [PLAIN: 'literal', SECRET: 'S']
    }

    def "strict mode rethrows a resolution failure"() {
        given:
        def subject = engine([resolverThat { throw new EnvoyResolutionException('boom', null) }], [:], true)

        when:
        subject.resolve([SECRET: 'op://v/i/f'])

        then:
        thrown(EnvoyResolutionException)
    }

    def "lenient mode skips a failed reference but keeps the rest"() {
        given:
        def subject = engine([resolverThat { ref ->
            if (ref.contains('bad')) throw new EnvoyResolutionException('nope', null)
            'ok'
        }])

        when:
        def result = subject.resolve([GOOD: 'op://v/good/f', BAD: 'op://v/bad/f'])

        then:
        result == [GOOD: 'ok']

        and: 'the skip is reported once, naming the failing variable'
        skipped.size() == 1
        skipped.first().contains('BAD')
    }
}
