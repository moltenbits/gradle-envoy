package com.moltenbits.envoy.resolver

import com.moltenbits.envoy.EnvoyResolutionException
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

@IgnoreIf({ os.windows })
// the fake CLIs used throughout are POSIX shell scripts
class CommandResolverSpec extends Specification {

    @TempDir
    Path tempDir

    /** Writes an executable fake CLI with the given body and returns its absolute path. */
    private String fakeCli(String body) {
        def file = tempDir.resolve('fake-cli').toFile()
        file.text = "#!/bin/sh\n$body\n"
        file.setExecutable(true)
        file.absolutePath
    }

    private static CommandResolver resolver(String scheme, List<String> template) {
        // Kotlin default arguments are invisible to Groovy, so the timeout is explicit.
        new CommandResolver(scheme, template, 60L)
    }

    def "handles only its registered scheme"() {
        given:
        def vault = resolver('vault://', ['cmd'])

        expect:
        vault.handles(value) == expected

        where:
        value                 || expected
        'vault://secret/a/b'  || true
        'op://Vault/Item/f'   || false
        'bws://some-id'       || false
        'plain-value'         || false
    }

    def "appends the full reference when the template has no placeholder, trimming the trailing newline"() {
        given: 'a fake that echoes its last argument, like `op read <ref>` echoes the secret'
        def r = resolver('bws://', [fakeCli('last=; for a in "$@"; do last="$a"; done; echo "$last"')])

        expect:
        r.resolve([K: 'bws://some-id']) == [K: 'bws://some-id']
    }

    def "substitutes {ref} with the full reference instead of appending"() {
        given: 'a fake that echoes "<argc>:<first arg>" so an appended extra arg would show up'
        def r = resolver('bws://', [fakeCli('printf \'%s:%s\' "$#" "$1"'), '{ref}'])

        expect:
        r.resolve([K: 'bws://some-id']) == [K: '1:bws://some-id']
    }

    def "substitutes {path} and {field} from the scheme-stripped reference"() {
        given:
        def r = resolver('vault://', [fakeCli('printf \'%s|%s\' "$1" "$2"'), '{path}', '{field}'])

        expect:
        r.resolve([K: 'vault://secret/envoy-demo/token']) == [K: 'secret/envoy-demo|token']
    }

    def "substitutes a placeholder embedded inside a larger argument"() {
        given: 'a Vault-style flag: -field=<field>'
        def r = resolver('vault://', [fakeCli('printf \'%s\' "$1"'), '-field={field}'])

        expect:
        r.resolve([K: 'vault://secret/app/token']) == [K: '-field=token']
    }

    def "passes the reference as a single argument, preserving spaces"() {
        given: 'a fake that echoes its LAST argument, so word-splitting would truncate it'
        def r = resolver('kp://', [fakeCli('last=; for a in "$@"; do last="$a"; done; printf \'%s\' "$last"')])
        def reference = 'kp://My Database/An Entry With Spaces/password'

        expect:
        r.resolve([K: reference]) == [K: reference]
    }

    def "resolves multiple references"() {
        given:
        def r = resolver('v://', [fakeCli('printf \'val-%s\' "$1"'), '{ref}'])

        expect:
        r.resolve([A: 'v://a', B: 'v://b']) == [A: 'val-v://a', B: 'val-v://b']
    }

    def "reports the variable name and CLI stderr on a non-zero exit"() {
        given:
        def r = resolver('vault://', [fakeCli('echo \'permission denied\' >&2; exit 2')])

        when:
        r.resolve([SECRET_KEY: 'vault://secret/x/y'])

        then:
        def e = thrown(EnvoyResolutionException)

        and: 'the message names the env var and includes CLI stderr, never a secret'
        e.message.contains('SECRET_KEY')
        e.message.contains('permission denied')
    }

    def "gives an actionable error when the binary is missing"() {
        given:
        def missing = tempDir.resolve('nonexistent-cli').toString()
        def r = resolver('vault://', [missing])

        when:
        r.resolve([K: 'vault://secret/x/y'])

        then:
        def e = thrown(EnvoyResolutionException)

        and: 'the message names the executable and the scheme it was registered for'
        e.message.contains(missing)
        e.message.contains('vault://')
    }
}
