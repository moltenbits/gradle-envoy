package com.moltenbits.envoy.resolver

import com.moltenbits.envoy.EnvoyResolutionException
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

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

    @Timeout(10)
    def "times out instead of hanging when the CLI never closes its streams"() {
        given: 'a fake that produces no output and outlives the timeout, like a CLI stuck on auth'
        def r = new CommandResolver('vault://', [fakeCli('sleep 30')], 1L)

        when:
        r.resolve([K: 'vault://secret/x/y'])

        then:
        def e = thrown(EnvoyResolutionException)
        e.message.contains('Timed out after 1s')
    }

    @Timeout(10)
    def "closes the child stdin so a prompting CLI sees EOF instead of hanging"() {
        given: 'a fake that consumes stdin to exhaustion before answering, like an interactive prompt'
        def r = resolver('kp://', [fakeCli('cat >/dev/null; printf \'after-stdin-eof\'')])

        expect:
        r.resolve([K: 'kp://x']) == [K: 'after-stdin-eof']
    }

    @Timeout(10)
    def "does not deadlock when the CLI floods stderr before printing the secret"() {
        given: 'a fake that writes ~130 KB of stderr — beyond the pipe buffer — before its stdout'
        def flood = 'i=0; while [ $i -lt 2000 ]; do echo "................................................................" >&2; i=$((i+1)); done; printf \'flood-ok\''
        def r = resolver('vault://', [fakeCli(flood)])

        expect:
        r.resolve([K: 'vault://secret/x/y']) == [K: 'flood-ok']
    }

    def "refuses a reference whose expansion turns a positional argument into an option"() {
        given: 'the documented vault template and a reference smuggling a flag through {path}'
        def r = resolver('vault://', [fakeCli('printf \'%s\' "$2"'), '-field={field}', '{path}'])

        when:
        r.resolve([SECRET_KEY: 'vault://-no-color/x'])

        then:
        def e = thrown(EnvoyResolutionException)

        and: 'the message names the variable and the offending argument, and no process ran'
        e.message.contains('SECRET_KEY')
        e.message.contains('option-like')
        e.message.contains('-no-color')
    }

    def "still allows option arguments the template itself declares"() {
        given:
        def r = resolver('vault://', [fakeCli('printf \'%s\' "$1"'), '-field={field}'])

        expect:
        r.resolve([K: 'vault://secret/app/token']) == [K: '-field=token']
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
