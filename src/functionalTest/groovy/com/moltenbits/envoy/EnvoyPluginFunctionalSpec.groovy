package com.moltenbits.envoy

import org.gradle.testkit.runner.GradleRunner
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.TempDir

@IgnoreIf({ os.windows })
// the fake `op` used throughout is a POSIX shell script
class EnvoyPluginFunctionalSpec extends Specification {

    @TempDir
    File projectDir

    static final String DEFAULT_ENV = '''\
        ENVOY_IT_SECRET="op://Test Vault/Test Item/token"
        ENVOY_IT_PLAIN=hello
        '''.stripIndent()

    static final String PROBE_BUILD = '''\
        plugins { java }
        tasks.register<JavaExec>("probe") {
            classpath = sourceSets["main"].runtimeClasspath
            mainClass.set("Probe")
        }
        '''.stripIndent()

    static final String PROBE_SOURCE = '''\
        public class Probe {
            public static void main(String[] args) {
                System.out.println("ENVOY_IT_SECRET=" + System.getenv("ENVOY_IT_SECRET"));
                System.out.println("ENVOY_IT_PLAIN=" + System.getenv("ENVOY_IT_PLAIN"));
            }
        }
        '''.stripIndent()

    /** Writes an executable fake `op` printing [secret] for any `read <ref>`, optionally logging calls. */
    private File writeFakeOp(String secret, File callLog = null) {
        def record = callLog ? "echo call >> '${callLog.absolutePath}'\n" : ''
        def file = new File(projectDir, 'fake-op')
        file.text = "#!/bin/sh\n${record}printf '%s' '${secret}'\n"
        file.setExecutable(true)
        file
    }

    private void writeConsumer(File dir, File op, boolean strict = false, File envDir = dir) {
        def strictLine = strict ? '\n    strict.set(true)' : ''
        def settings = new File(dir, 'settings.gradle.kts')
        settings.parentFile.mkdirs()
        settings.text = """\
            |plugins { id("com.moltenbits.envoy") }
            |rootProject.name = "consumer"
            |envoy {
            |    cliExecutable.set(${quoted(op.absolutePath)})${strictLine}
            |}
            |""".stripMargin()

        new File(dir, 'build.gradle.kts').text = PROBE_BUILD

        def probe = new File(dir, 'src/main/java/Probe.java')
        probe.parentFile.mkdirs()
        probe.text = PROBE_SOURCE

        def env = new File(envDir, '.env')
        env.parentFile.mkdirs()
        env.text = DEFAULT_ENV
    }

    private static GradleRunner runnerIn(File dir, String... args) {
        GradleRunner.create()
                .withProjectDir(dir)
                .withPluginClasspath()
                .withArguments([*args, '--stacktrace'] as String[])
                .forwardOutput()
    }

    private static String quoted(String s) {
        '"' + s.replace('\\', '\\\\').replace('"', '\\"') + '"'
    }

    def "injects a resolved op secret and a literal into a JavaExec JVM"() {
        given:
        writeConsumer(projectDir, writeFakeOp('sk-test-123'))

        when:
        def result = runnerIn(projectDir, 'probe').build()

        then: 'the resolved secret reaches the forked JVM with no manual environment() wiring'
        result.output.contains('ENVOY_IT_SECRET=sk-test-123')

        and: 'so does the literal'
        result.output.contains('ENVOY_IT_PLAIN=hello')
    }

    def "injects on Gradle #gradleVersion"() {
        given:
        writeConsumer(projectDir, writeFakeOp('sk-cross-version'))

        when:
        def result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withGradleVersion(gradleVersion)
                .withArguments('probe', '--stacktrace')
                .forwardOutput()
                .build()

        then:
        result.output.contains('ENVOY_IT_SECRET=sk-cross-version')
        result.output.contains('ENVOY_IT_PLAIN=hello')

        where: 'the declared minimum and the current version'
        gradleVersion << ['8.8', '9.6.1']
    }

    def "finds a dot-env by walking up parent directories"() {
        given: '.env lives one level ABOVE the build root, like ~/Projects/.env above a nested repo'
        def build = new File(projectDir, 'workspace/project')
        writeConsumer(build, writeFakeOp('sk-parent'), false, new File(projectDir, 'workspace'))

        expect: 'guard: no .env in the build root itself'
        !new File(build, '.env').exists()

        when:
        def result = runnerIn(build, 'probe').build()

        then:
        result.output.contains('ENVOY_IT_SECRET=sk-parent')
        result.output.contains('ENVOY_IT_PLAIN=hello')
    }

    def "does not invoke the CLI for a build that runs no forked-JVM task"() {
        given:
        def callLog = new File(projectDir, 'op-calls.log')
        writeConsumer(projectDir, writeFakeOp('sk', callLog))

        when:
        runnerIn(projectDir, 'help').build()

        then: 'a build with no Test/JavaExec task must never reach the CLI'
        !callLog.exists()

        when:
        runnerIn(projectDir, 'probe').build()

        then: 'but a forked-JVM task does'
        callLog.exists()
    }

    def "works with the configuration cache and never serializes the secret"() {
        given:
        writeConsumer(projectDir, writeFakeOp('sk-cc-secret'))

        when:
        def first = runnerIn(projectDir, 'probe', '--configuration-cache').build()

        then:
        first.output.contains('ENVOY_IT_SECRET=sk-cc-secret')

        when:
        def second = runnerIn(projectDir, 'probe', '--configuration-cache').build()

        then: 'the second run reuses the cache'
        second.output.toLowerCase() =~ /reusing configuration cache|configuration cache entry reused/

        and:
        second.output.contains('ENVOY_IT_SECRET=sk-cc-secret')

        and: 'and the resolved secret was never written into it'
        def cacheDir = new File(projectDir, '.gradle/configuration-cache')
        !cacheDir.listFiles() || cacheDir.traverse(type: groovy.io.FileType.FILES).every {
            !new String(it.bytes, 'ISO-8859-1').contains('sk-cc-secret')
        }
    }

    def "lenient by default: skips an unresolved reference but keeps literals"() {
        given: 'a binary that does not exist, so resolution fails'
        writeConsumer(projectDir, new File(projectDir, 'nonexistent-op'), false)

        when:
        def result = runnerIn(projectDir, 'probe').build()

        then:
        result.output.contains('ENVOY_IT_PLAIN=hello')
        result.output.contains('ENVOY_IT_SECRET=null')

        and: 'a warning names the skipped variable'
        result.output.contains('skipping ENVOY_IT_SECRET')
    }

    def "resolves a custom scheme registered via the resolver DSL"() {
        given: 'a fake vault-style CLI that echoes the args it was invoked with'
        def fakeVault = new File(projectDir, 'fake-vault')
        fakeVault.text = '#!/bin/sh\nprintf \'resolved:%s:%s\' "$1" "$2"\n'
        fakeVault.setExecutable(true)

        and: 'a consumer registering vault:// with {path}/{field} placeholders'
        new File(projectDir, 'settings.gradle.kts').text = """\
            |plugins { id("com.moltenbits.envoy") }
            |rootProject.name = "consumer"
            |envoy {
            |    resolver("vault://") {
            |        command = listOf(${quoted(fakeVault.absolutePath)}, "{path}", "-field={field}")
            |    }
            |}
            |""".stripMargin()
        new File(projectDir, 'build.gradle.kts').text = PROBE_BUILD
        def probe = new File(projectDir, 'src/main/java/Probe.java')
        probe.parentFile.mkdirs()
        probe.text = '''\
            public class Probe {
                public static void main(String[] args) {
                    System.out.println("ENVOY_IT_VAULT=" + System.getenv("ENVOY_IT_VAULT"));
                }
            }
            '''.stripIndent()
        new File(projectDir, '.env').text = 'ENVOY_IT_VAULT="vault://secret/envoy-demo/token"\n'

        when:
        def result = runnerIn(projectDir, 'probe').build()

        then: 'the reference was split into path and field and resolved end-to-end'
        result.output.contains('ENVOY_IT_VAULT=resolved:secret/envoy-demo:-field=token')
    }

    def "strict mode fails the build on an unresolved reference"() {
        given:
        writeConsumer(projectDir, new File(projectDir, 'nonexistent-op'), true)

        when:
        def result = runnerIn(projectDir, 'probe').buildAndFail()

        then: 'the failure is actionable and names the variable'
        result.output.contains('ENVOY_IT_SECRET')
        result.output.contains('Could not run') || result.output.contains('1Password CLI')
    }
}
