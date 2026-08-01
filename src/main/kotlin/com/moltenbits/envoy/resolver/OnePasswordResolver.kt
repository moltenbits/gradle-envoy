package com.moltenbits.envoy.resolver

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

    override fun handles(rawValue: String): Boolean = rawValue.startsWith(SCHEME)

    override fun resolve(references: Map<String, String>): Map<String, String> =
        references.mapValues { (name, reference) -> read(name, reference) }

    private fun read(name: String, reference: String): String {
        val command = buildList {
            add(executable)
            addAll(readArgs)
            add(reference)
        }
        return SecretCliRunner.run(
            command = command,
            name = name,
            timeoutSeconds = timeoutSeconds,
            missingExecutableMessage =
                "Could not run '$executable' to resolve $name. Is the 1Password CLI installed and on PATH? " +
                    "Point envoy.cliExecutable at it if needed (e.g. \"op-fast\").",
        )
    }

    companion object {
        /** The reference scheme this built-in resolver owns; custom resolvers may not claim it. */
        const val SCHEME = "op://"
    }
}
