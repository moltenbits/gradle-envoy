package com.moltenbits.envoy.resolver

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
        return SecretCliRunner.run(
            command = command,
            name = name,
            timeoutSeconds = timeoutSeconds,
            missingExecutableMessage =
                "Could not run '${command.first()}' to resolve $name (registered for '$scheme'). " +
                    "Is it installed and on PATH?",
        )
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
