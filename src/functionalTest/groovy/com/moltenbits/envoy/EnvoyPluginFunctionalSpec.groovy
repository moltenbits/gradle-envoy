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

    def "merges every .env up the directory chain, the nearer file winning duplicate keys"() {
        given: 'a workspace .env two levels up and a project .env overriding one of its keys'
        def build = new File(projectDir, 'workspace/project')
        writeConsumer(build, writeFakeOp('sk-chain'), false, new File(projectDir, 'workspace'))
        new File(build, '.env').text = 'ENVOY_IT_PLAIN=from-project\n'

        when:
        def result = runnerIn(build, 'probe').build()

        then: 'the project-local value wins the duplicate key'
        result.output.contains('ENVOY_IT_PLAIN=from-project')

        and: 'keys unique to the parent file are still injected'
        result.output.contains('ENVOY_IT_SECRET=sk-chain')
    }

    def "skips an unreadable ancestor env file with a warning instead of failing the build"() {
        given: 'a parent .env the build user cannot read, and a readable project .env'
        def build = new File(projectDir, 'workspace/project')
        writeConsumer(build, writeFakeOp('sk-unreadable'))
        def parentEnv = new File(projectDir, 'workspace/.env')
        parentEnv.text = 'ENVOY_IT_HIDDEN=nope\n'
        parentEnv.setReadable(false, false)

        when:
        def result = runnerIn(build, 'probe').build()

        then: 'the build succeeds and the readable layers still inject'
        result.output.contains('ENVOY_IT_PLAIN=hello')
        result.output.contains('ENVOY_IT_SECRET=sk-unreadable')

        and: 'the unreadable file is named in a warning'
        result.output.contains('could not read env file')
        result.output.contains(parentEnv.absolutePath)

        cleanup:
        parentEnv.setReadable(true, false)
    }

    def "layers envFiles below the local .env and above parent-directory files"() {
        given: 'three layers each claiming ENVOY_IT_LAYER, plus missing and directory envFiles entries'
        def workspace = new File(projectDir, 'workspace')
        def build = new File(workspace, 'project')
        new File(build, 'src/main/java').mkdirs()
        new File(build, 'a-directory').mkdirs()
        new File(workspace, '.env').text =
                'ENVOY_IT_LAYER=parent\nENVOY_IT_BOTH=parent\nENVOY_IT_PARENT_ONLY=from-parent\n'
        new File(build, '.env').text = 'ENVOY_IT_LAYER=local\n'
        new File(build, '.env.template').text =
                'ENVOY_IT_LAYER=template\nENVOY_IT_BOTH=template\nENVOY_IT_TEMPLATE_ONLY=from-template\n'
        new File(build, 'settings.gradle.kts').text = '''\
            |plugins { id("com.moltenbits.envoy") }
            |rootProject.name = "consumer"
            |envoy {
            |    envFiles.set(listOf(File("missing.env"), File("a-directory"), File(".env.template")))
            |}
            |'''.stripMargin()
        new File(build, 'build.gradle.kts').text = PROBE_BUILD
        new File(build, 'src/main/java/Probe.java').text = '''\
            public class Probe {
                public static void main(String[] args) {
                    System.out.println("ENVOY_IT_LAYER=" + System.getenv("ENVOY_IT_LAYER"));
                    System.out.println("ENVOY_IT_BOTH=" + System.getenv("ENVOY_IT_BOTH"));
                    System.out.println("ENVOY_IT_PARENT_ONLY=" + System.getenv("ENVOY_IT_PARENT_ONLY"));
                    System.out.println("ENVOY_IT_TEMPLATE_ONLY=" + System.getenv("ENVOY_IT_TEMPLATE_ONLY"));
                }
            }
            '''.stripIndent()

        when:
        def result = runnerIn(build, 'probe').build()

        then: 'the local .env beats the template'
        result.output.contains('ENVOY_IT_LAYER=local')

        and: 'the template beats the parent'
        result.output.contains('ENVOY_IT_BOTH=template')

        and: 'unique keys from every layer are injected'
        result.output.contains('ENVOY_IT_PARENT_ONLY=from-parent')
        result.output.contains('ENVOY_IT_TEMPLATE_ONLY=from-template')

        and: 'the missing and directory entries were each skipped with an accurate warning'
        result.output.contains('does not exist; skipping it')
        result.output.contains('is not a regular file; skipping it')
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
