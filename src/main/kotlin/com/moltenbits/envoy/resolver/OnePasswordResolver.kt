package com.moltenbits.envoy.resolver

import com.moltenbits.envoy.EnvoyResolutionException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Resolves 1Password `op://vault/item/field` references by shelling out to the 1Password CLI
 * (`op read <reference>` by default).
 *
 * The [executable] and leading [readArgs] are configurable so a caller can point at a drop-in such as
 * `op-fast` (a Keychain-cached wrapper). Each reference is passed as a single process argument, so spaces
 * in vault/item names (e.g. `op://Private/GitHub PAT for .env/token`) need no quoting.
 *
 * Secrets are read from the process's stdout and returned to the caller; they are never logged, and error
 * messages carry only the env-var name and the CLI's stderr — never a resolved value.
 *
 * Intended to be invoked at task-execution time (inside the build service), not during configuration.
 */
class OnePasswordResolver(
    private val executable: String = "op",
    private val readArgs: List<String> = listOf("read"),
    private val timeoutSeconds: Long = 60,
) : SecretResolver {

    override fun handles(rawValue: String): Boolean = rawValue.startsWith(REFERENCE_PREFIX)

    override fun resolve(references: Map<String, String>): Map<String, String> =
        references.mapValues { (name, reference) -> read(name, reference) }

    private fun read(name: String, reference: String): String {
        val command = buildList {
            add(executable)
            addAll(readArgs)
            add(reference)
        }

        val process = try {
            ProcessBuilder(command).start()
        } catch (e: IOException) {
            throw EnvoyResolutionException(
                "Could not run '$executable' to resolve $name. Is the 1Password CLI installed and on PATH? " +
                    "Point envoy.cliExecutable at it if needed (e.g. \"op-fast\").",
                e,
            )
        }

        // `op read` writes only the secret to stdout. Read it fully (output is small), then stderr.
        val stdout = process.inputStream.readBytes()
        val stderr = process.errorStream.readBytes().decodeToString().trim()

        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw EnvoyResolutionException("Timed out after ${timeoutSeconds}s resolving $name via '$executable'.")
        }
        if (process.exitValue() != 0) {
            throw EnvoyResolutionException("'$executable' exited ${process.exitValue()} resolving $name: $stderr")
        }
        return stdout.decodeToString().trimEnd('\n', '\r')
    }

    private companion object {
        const val REFERENCE_PREFIX = "op://"
    }
}
