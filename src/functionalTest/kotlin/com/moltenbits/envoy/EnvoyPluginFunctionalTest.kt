package com.moltenbits.envoy

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class EnvoyPluginFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    @BeforeEach
    fun requirePosixShell() {
        assumeFalse(
            System.getProperty("os.name").startsWith("Windows"),
            "the fake `op` used here is a POSIX shell script",
        )
    }

    // --- fixtures ------------------------------------------------------------------------------------

    private val defaultEnv = """
        ENVOY_IT_SECRET="op://Test Vault/Test Item/token"
        ENVOY_IT_PLAIN=hello
    """

    private val probeBuild = """
        plugins { java }
        tasks.register<JavaExec>("probe") {
            classpath = sourceSets["main"].runtimeClasspath
            mainClass.set("Probe")
        }
    """

    private val probeSource = """
        public class Probe {
            public static void main(String[] args) {
                System.out.println("ENVOY_IT_SECRET=" + System.getenv("ENVOY_IT_SECRET"));
                System.out.println("ENVOY_IT_PLAIN=" + System.getenv("ENVOY_IT_PLAIN"));
            }
        }
    """

    private fun write(path: String, content: String): File =
        File(projectDir, path).apply {
            parentFile.mkdirs()
            writeText(content.trimIndent())
        }

    /** Writes an executable fake `op` that prints [secret] for any `read <ref>`, optionally logging each call. */
    private fun writeFakeOp(secret: String, callLog: File? = null): File {
        val record = callLog?.let { "echo call >> '${it.absolutePath}'\n" } ?: ""
        return File(projectDir, "fake-op").apply {
            writeText("#!/bin/sh\n$record" + "printf '%s' '$secret'\n")
            setExecutable(true)
        }
    }

    private fun writeConsumer(dir: File, op: File, strict: Boolean = false, envDir: File = dir) {
        val strictLine = if (strict) "\n            strict.set(true)" else ""
        File(dir, "settings.gradle.kts").apply {
            parentFile.mkdirs()
            writeText(
                """
                plugins { id("com.moltenbits.envoy") }
                rootProject.name = "consumer"
                envoy {
                    cliExecutable.set(${quoted(op.absolutePath)})$strictLine
                }
                """.trimIndent(),
            )
        }
        File(dir, "build.gradle.kts").writeText(probeBuild.trimIndent())
        File(dir, "src/main/java/Probe.java").apply { parentFile.mkdirs(); writeText(probeSource.trimIndent()) }
        File(envDir, ".env").apply { parentFile.mkdirs(); writeText(defaultEnv.trimIndent()) }
    }

    private fun runnerIn(dir: File, vararg args: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withArguments(*args, "--stacktrace")
            .forwardOutput()

    private fun quoted(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // --- tests ---------------------------------------------------------------------------------------

    @Test
    fun `injects a resolved op secret and a literal into a JavaExec JVM`() {
        writeConsumer(projectDir, writeFakeOp(secret = "sk-test-123"))

        val result = runnerIn(projectDir, "probe").build()

        assertTrue(
            result.output.contains("ENVOY_IT_SECRET=sk-test-123"),
            "resolved op:// secret should reach the forked JVM without any manual environment() wiring:\n${result.output}",
        )
        assertTrue(
            result.output.contains("ENVOY_IT_PLAIN=hello"),
            "literal .env value should reach the forked JVM:\n${result.output}",
        )
    }

    @Test
    fun `finds a dot-env by walking up parent directories`() {
        val build = File(projectDir, "workspace/project")
        // .env lives one level ABOVE the build root, like ~/Projects/.env above a nested repo.
        writeConsumer(dir = build, op = writeFakeOp("sk-parent"), envDir = File(projectDir, "workspace"))
        assertFalse(File(build, ".env").exists(), "guard: no .env in the build root itself")

        val result = runnerIn(build, "probe").build()

        assertTrue(result.output.contains("ENVOY_IT_SECRET=sk-parent"), result.output)
        assertTrue(result.output.contains("ENVOY_IT_PLAIN=hello"), result.output)
    }

    @Test
    fun `does not invoke the CLI for a build that runs no forked-JVM task`() {
        val callLog = File(projectDir, "op-calls.log")
        writeConsumer(projectDir, writeFakeOp("sk", callLog))

        runnerIn(projectDir, "help").build()
        assertFalse(callLog.exists(), "the 1Password CLI must not run for a build with no Test/JavaExec task")

        runnerIn(projectDir, "probe").build()
        assertTrue(callLog.exists(), "the CLI should run once a forked-JVM task executes")
    }

    @Test
    fun `works with the configuration cache and never serializes the secret`() {
        writeConsumer(projectDir, writeFakeOp("sk-cc-secret"))

        val first = runnerIn(projectDir, "probe", "--configuration-cache").build()
        assertTrue(first.output.contains("ENVOY_IT_SECRET=sk-cc-secret"), first.output)

        val second = runnerIn(projectDir, "probe", "--configuration-cache").build()
        assertTrue(
            second.output.lowercase().contains("reusing configuration cache") ||
                second.output.lowercase().contains("configuration cache entry reused"),
            "second run should reuse the configuration cache:\n${second.output}",
        )
        assertTrue(second.output.contains("ENVOY_IT_SECRET=sk-cc-secret"), second.output)

        val cacheDir = File(projectDir, ".gradle/configuration-cache")
        val leaked = cacheDir.walkTopDown()
            .filter { it.isFile }
            .any { it.readBytes().toString(Charsets.ISO_8859_1).contains("sk-cc-secret") }
        assertFalse(leaked, "the resolved secret must never be serialized into the configuration cache")
    }

    @Test
    fun `lenient by default skips an unresolved reference but keeps literals`() {
        // Point at a binary that does not exist -> resolution fails.
        val missingOp = File(projectDir, "nonexistent-op")
        writeConsumer(projectDir, missingOp, strict = false)

        val result = runnerIn(projectDir, "probe").build()

        assertTrue(result.output.contains("ENVOY_IT_PLAIN=hello"), "literals still injected:\n${result.output}")
        assertTrue(result.output.contains("ENVOY_IT_SECRET=null"), "unresolved secret left unset:\n${result.output}")
        assertTrue(result.output.contains("skipping ENVOY_IT_SECRET"), "a warning should name the skipped var")
    }

    @Test
    fun `strict mode fails the build on an unresolved reference`() {
        val missingOp = File(projectDir, "nonexistent-op")
        writeConsumer(projectDir, missingOp, strict = true)

        val result = runnerIn(projectDir, "probe").buildAndFail()

        assertTrue(
            result.output.contains("ENVOY_IT_SECRET") &&
                (result.output.contains("Could not run") || result.output.contains("1Password CLI")),
            "strict mode should fail with an actionable error naming the variable:\n${result.output}",
        )
    }
}
