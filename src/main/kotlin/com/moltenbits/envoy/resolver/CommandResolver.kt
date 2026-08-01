package com.moltenbits.envoy.resolver

import com.moltenbits.envoy.EnvoyResolutionException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Resolves references of a user-registered [scheme] (e.g. `vault://`) by running a configured command
 * [template] — the generic escape hatch that covers any secret backend with a CLI, without shipping a
 * bespoke resolver per vendor.
 *
 * Placeholders are substituted in every template argument:
 * - `{ref}` — the full reference (`vault://secret/app/token`)
 * - `{path}` — the reference minus the scheme and the final segment (`secret/app`)
 * - `{field}` — the final `/`-separated segment (`token`)
 *
 * If no argument contains a placeholder, the full reference is appended as the last argument — mirroring
 * how `op read <ref>` takes its reference. Each expanded value is a single process argument, so spaces in
 * references need no quoting.
 *
 * The output contract matches [OnePasswordResolver]: the secret is the command's stdout with one trailing
 * newline trimmed; failures throw [EnvoyResolutionException] carrying the env-var name and the command's
 * stderr — never a resolved value.
 */
class CommandResolver(
    private val scheme: String,
    private val template: List<String>,
    private val timeoutSeconds: Long = 60,
) : SecretResolver {

    override fun handles(rawValue: String): Boolean = rawValue.startsWith(scheme)

    override fun resolve(references: Map<String, String>): Map<String, String> =
        references.mapValues { (name, reference) -> read(name, reference) }

    private fun read(name: String, reference: String): String {
        val command = expand(reference)
        val executable = command.first()

        val process = try {
            ProcessBuilder(command).start()
        } catch (e: IOException) {
            throw EnvoyResolutionException(
                "Could not run '$executable' to resolve $name (registered for '$scheme'). " +
                    "Is it installed and on PATH?",
                e,
            )
        }

        // The command writes only the secret to stdout. Read it fully (output is small), then stderr.
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

    private fun expand(reference: String): List<String> {
        val stripped = reference.removePrefix(scheme)
        val substitutions = mapOf(
            "{ref}" to reference,
            "{path}" to stripped.substringBeforeLast('/'),
            "{field}" to stripped.substringAfterLast('/'),
        )
        var substituted = false
        val expanded = template.map { arg ->
            substitutions.entries
                .fold(arg) { acc, (placeholder, value) -> acc.replace(placeholder, value) }
                .also { if (it != arg) substituted = true }
        }
        return if (substituted) expanded else expanded + reference
    }
}
