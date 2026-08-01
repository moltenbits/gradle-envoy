package com.moltenbits.envoy.resolver

import com.moltenbits.envoy.EnvoyResolutionException
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs a secret-resolving CLI and returns its stdout — the single implementation of the process
 * contract shared by [OnePasswordResolver] and [CommandResolver]:
 *
 * - stdout is the secret, with one trailing newline trimmed;
 * - stdin is closed immediately, so a CLI that tries to prompt (locked keyring, interactive auth)
 *   reads EOF and fails fast with a real stderr message instead of hanging the build;
 * - stdout and stderr are drained on background threads, so the timeout holds even when the child
 *   never closes its streams, and a child flooding the stderr pipe buffer cannot deadlock;
 * - on timeout the child is killed and an [EnvoyResolutionException] is thrown; on a non-zero exit
 *   the exception names the env var and carries the CLI's stderr — never a resolved value.
 */
internal object SecretCliRunner {

    fun run(
        command: List<String>,
        name: String,
        timeoutSeconds: Long,
        missingExecutableMessage: String,
    ): String {
        val executable = command.first()
        val process = try {
            ProcessBuilder(command).start()
        } catch (e: IOException) {
            throw EnvoyResolutionException(missingExecutableMessage, e)
        }

        try {
            // The child's stdin (the JDK calls it outputStream): close it so a prompting CLI sees
            // EOF instead of waiting forever for input this process will never send.
            process.outputStream.close()

            var stdout = ByteArray(0)
            var stderr = ByteArray(0)
            val stdoutDrain = thread(isDaemon = true) { stdout = process.inputStream.readBytes() }
            val stderrDrain = thread(isDaemon = true) { stderr = process.errorStream.readBytes() }

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw EnvoyResolutionException(
                    "Timed out after ${timeoutSeconds}s resolving $name via '$executable'.",
                )
            }

            // EOF normally arrives with process exit; the bound only guards against a grandchild
            // that inherited the pipes and holds them open.
            stdoutDrain.join(STREAM_DRAIN_TIMEOUT_MILLIS)
            stderrDrain.join(STREAM_DRAIN_TIMEOUT_MILLIS)
            if (stdoutDrain.isAlive) {
                throw EnvoyResolutionException("Timed out reading '$executable' output while resolving $name.")
            }

            if (process.exitValue() != 0) {
                throw EnvoyResolutionException(
                    "'$executable' exited ${process.exitValue()} resolving $name: " +
                        stderr.decodeToString().trim(),
                )
            }
            return stdout.decodeToString().trimEnd('\n', '\r')
        } finally {
            // Kills a hung or timed-out child; a no-op when the process already exited.
            process.destroyForcibly()
        }
    }

    private const val STREAM_DRAIN_TIMEOUT_MILLIS = 10_000L
}
