package com.moltenbits.envoy.resolver

import com.moltenbits.envoy.EnvoyResolutionException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class OnePasswordResolverTest {

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun requirePosixShell() {
        assumeFalse(
            System.getProperty("os.name").startsWith("Windows"),
            "the fake `op` used here is a POSIX shell script",
        )
    }

    /** Writes an executable fake `op` whose body is [body], and returns its absolute path. */
    private fun fakeOp(body: String): String {
        val file = tempDir.resolve("op").toFile()
        file.writeText("#!/bin/sh\n$body\n")
        file.setExecutable(true)
        return file.absolutePath
    }

    @Test
    fun `handles only op references`() {
        val resolver = OnePasswordResolver()
        assertTrue(resolver.handles("op://Vault/Item/field"))
        assertFalse(resolver.handles("plain-value"))
        assertFalse(resolver.handles("https://example.com"))
    }

    @Test
    fun `resolves a reference and trims the trailing newline`() {
        // Real `op read` prints the secret followed by a newline.
        val resolver = OnePasswordResolver(executable = fakeOp("echo 'resolved-secret'"))

        val result = resolver.resolve(mapOf("GH_TOKEN" to "op://Private/Item/token"))

        assertEquals(mapOf("GH_TOKEN" to "resolved-secret"), result)
    }

    @Test
    fun `invokes the read subcommand`() {
        // Echo the first argument the CLI received.
        val resolver = OnePasswordResolver(executable = fakeOp("printf '%s' \"\$1\""))

        assertEquals(mapOf("K" to "read"), resolver.resolve(mapOf("K" to "op://x/y/z")))
    }

    @Test
    fun `passes the reference as a single argument preserving spaces`() {
        // Echo the LAST argument received; if spaces caused word-splitting this would be truncated.
        val resolver = OnePasswordResolver(
            executable = fakeOp("last=; for a in \"\$@\"; do last=\"\$a\"; done; printf '%s' \"\$last\""),
        )
        val reference = "op://Private/GitHub PAT for .env/token"

        assertEquals(mapOf("K" to reference), resolver.resolve(mapOf("K" to reference)))
    }

    @Test
    fun `resolves multiple references`() {
        val resolver = OnePasswordResolver(
            executable = fakeOp("last=; for a in \"\$@\"; do last=\"\$a\"; done; printf 'val-%s' \"\$last\""),
        )

        val result = resolver.resolve(mapOf("A" to "op://v/a/f", "B" to "op://v/b/f"))

        assertEquals(mapOf("A" to "val-op://v/a/f", "B" to "val-op://v/b/f"), result)
    }

    @Test
    fun `throws with the var name and stderr, without leaking, on non-zero exit`() {
        val resolver = OnePasswordResolver(executable = fakeOp("echo 'no item matches' >&2; exit 1"))

        val ex = assertThrows(EnvoyResolutionException::class.java) {
            resolver.resolve(mapOf("SECRET_KEY" to "op://x/y/z"))
        }

        assertTrue(ex.message!!.contains("SECRET_KEY"), "message should name the env var")
        assertTrue(ex.message!!.contains("no item matches"), "message should include CLI stderr")
    }

    @Test
    fun `throws a helpful error when the binary is missing`() {
        val resolver = OnePasswordResolver(executable = tempDir.resolve("nonexistent-op").toString())

        val ex = assertThrows(EnvoyResolutionException::class.java) {
            resolver.resolve(mapOf("K" to "op://x/y/z"))
        }

        assertTrue(ex.message!!.contains("1Password CLI"), "message should hint how to fix a missing CLI")
    }
}
