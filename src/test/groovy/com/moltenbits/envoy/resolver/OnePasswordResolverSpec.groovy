package com.moltenbits.envoy.resolver

import com.moltenbits.envoy.EnvoyResolutionException
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

@IgnoreIf({ os.windows })
// the fake `op` used throughout is a POSIX shell script
class OnePasswordResolverSpec extends Specification {

    @TempDir
    Path tempDir

    /** Writes an executable fake `op` with the given body and returns its absolute path. */
    private String fakeOp(String body) {
        def file = tempDir.resolve('op').toFile()
        file.text = "#!/bin/sh\n$body\n"
        file.setExecutable(true)
        file.absolutePath
    }

    private static OnePasswordResolver resolverUsing(String executable) {
        // Kotlin default arguments are invisible to Groovy, so readArgs and timeout are explicit.
        new OnePasswordResolver(executable, ['read'], 60L)
    }

    def "handles op references and nothing else"() {
        given:
        def resolver = new OnePasswordResolver()

        expect:
        resolver.handles(value) == expected

        where:
        value                 || expected
        'op://Vault/Item/f'   || true
        'plain-value'         || false
        'https://example.com' || false
    }

    def "resolves a reference and trims the trailing newline"() {
        given: 'real `op read` prints the secret followed by a newline'
        def resolver = resolverUsing(fakeOp("echo 'resolved-secret'"))

        expect:
        resolver.resolve([GH_TOKEN: 'op://Private/Item/token']) == [GH_TOKEN: 'resolved-secret']
    }

    def "invokes the read subcommand"() {
        given: 'a fake that echoes the first argument it received'
        def resolver = resolverUsing(fakeOp("printf '%s' \"\$1\""))

        expect:
        resolver.resolve([K: 'op://x/y/z']) == [K: 'read']
    }

    def "passes the reference as a single argument, preserving spaces"() {
        given: 'a fake that echoes its LAST argument, so word-splitting would truncate it'
        def resolver = resolverUsing(fakeOp('last=; for a in "$@"; do last="$a"; done; printf \'%s\' "$last"'))
        def reference = 'op://Private/GitHub PAT for .env/token'

        expect:
        resolver.resolve([K: reference]) == [K: reference]
    }

    def "resolves multiple references"() {
        given:
        def resolver = resolverUsing(fakeOp('last=; for a in "$@"; do last="$a"; done; printf \'val-%s\' "$last"'))

        expect:
        resolver.resolve([A: 'op://v/a/f', B: 'op://v/b/f']) ==
                [A: 'val-op://v/a/f', B: 'val-op://v/b/f']
    }

    def "reports the variable name and CLI stderr on a non-zero exit"() {
        given:
        def resolver = resolverUsing(fakeOp("echo 'no item matches' >&2; exit 1"))

        when:
        resolver.resolve([SECRET_KEY: 'op://x/y/z'])

        then:
        def e = thrown(EnvoyResolutionException)

        and: 'the message names the env var and includes CLI stderr'
        e.message.contains('SECRET_KEY')
        e.message.contains('no item matches')
    }

    def "gives a helpful error when the binary is missing"() {
        given:
        def resolver = resolverUsing(tempDir.resolve('nonexistent-op').toString())

        when:
        resolver.resolve([K: 'op://x/y/z'])

        then:
        def e = thrown(EnvoyResolutionException)

        and:
        e.message.contains('1Password CLI')
    }
}
