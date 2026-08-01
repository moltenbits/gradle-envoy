package com.moltenbits.envoy.service

import com.moltenbits.envoy.parse.EnvFileChain
import com.moltenbits.envoy.parse.EnvFileParser
import com.moltenbits.envoy.resolver.CommandResolver
import com.moltenbits.envoy.resolver.EnvResolutionEngine
import com.moltenbits.envoy.resolver.OnePasswordResolver
import com.moltenbits.envoy.resolver.SecretResolver
import org.gradle.api.logging.Logging
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.io.File
import java.io.IOException

/**
 * Build-scoped service that locates the chain of env files (the build dir's `.env`, configured `envFiles`,
 * and parent-directory `.env`s — merged with [EnvFileChain]'s precedence), resolves the `op://`/custom
 * references, and hands the resulting environment map to task actions — computed **once per build** and
 * shared across every project and forked JVM.
 *
 * Correctness properties this class is responsible for:
 * - **Lazy**: [environment] is only invoked from task execution (via the inject action / extension providers),
 *   so nothing is read or resolved for builds that don't run a relevant task, and the password-manager CLI is
 *   never called at configuration time.
 * - **Config-cache-safe**: all [Params] are non-secret (paths/flags); no secret is ever stored as a task
 *   input or serialized. The `.env` is read here, at execution, not folded into configured state.
 * - **Memoized + thread-safe**: resolution happens at most once even under parallel task execution.
 * - **Wiped at build end**: [close] drops the reference to the resolved secrets.
 */
abstract class EnvoySecretService : BuildService<EnvoySecretService.Params>, AutoCloseable {

    interface Params : BuildServiceParameters {
        /** Absolute path of the directory to begin the walk-up `.env` search from (typically the build root). */
        val searchFromDir: Property<String>

        /** Explicit env files layered between the build dir's `.env` and parent files; earlier entries win. */
        val envFiles: ListProperty<File>

        /** Whether to walk up parent directories, merging every `.env` found. Defaults to true. */
        val searchParents: Property<Boolean>

        /** 1Password CLI executable (default `op`; set `op-fast` for a Keychain-cached, offline wrapper). */
        val cliExecutable: Property<String>

        /** Leading CLI arguments before the reference (default `["read"]`). */
        val cliArgs: ListProperty<String>

        /** Fail the build when a reference cannot be resolved, instead of skipping it with a warning. */
        val strict: Property<Boolean>

        /** Custom scheme → command template registered via `envoy.resolver(...)` (non-secret argv strings). */
        val commandResolvers: MapProperty<String, List<String>>
    }

    private val logger = Logging.getLogger(EnvoySecretService::class.java)

    @Volatile
    private var cached: Map<String, String>? = null
    private val lock = Any()

    /** Resolved KEY -> value to inject. Computed once per build; safe to call from parallel task execution. */
    fun environment(): Map<String, String> {
        cached?.let { return it }
        return synchronized(lock) {
            cached ?: compute().also { cached = it }
        }
    }

    private fun compute(): Map<String, String> {
        val chain = EnvFileChain.locate(
            startDir = File(parameters.searchFromDir.get()).absoluteFile,
            explicit = parameters.envFiles.getOrElse(emptyList()),
            searchParents = parameters.searchParents.getOrElse(true),
            onMissingExplicit = { file ->
                val reason = if (file.exists()) "is not a regular file" else "does not exist"
                logger.warn("envoy: configured env file '${file.path}' $reason; skipping it")
            },
        )
        // Lenient like reference resolution: one unreadable file (permissions, TOCTOU deletion)
        // must not fail every build beneath it — skip it, keep the readable layers.
        val parsed = EnvFileChain.merge(
            chain.mapNotNull { file ->
                try {
                    EnvFileParser.parse(file.readText())
                } catch (e: IOException) {
                    logger.warn("envoy: could not read env file '${file.path}' (${e.message}); skipping it")
                    null
                }
            },
        )
        if (parsed.isEmpty()) return emptyMap()

        // Built-in resolver first, so op:// always wins (the DSL also refuses to re-register it).
        val resolvers: List<SecretResolver> = buildList {
            add(
                OnePasswordResolver(
                    executable = parameters.cliExecutable.getOrElse("op"),
                    readArgs = parameters.cliArgs.getOrElse(listOf("read")),
                ),
            )
            parameters.commandResolvers.getOrElse(emptyMap()).forEach { (scheme, template) ->
                add(CommandResolver(scheme, template))
            }
        }
        val engine = EnvResolutionEngine(
            resolvers = resolvers,
            strict = parameters.strict.getOrElse(false),
            onSkip = { logger.warn(it) },
        )
        return engine.resolve(parsed)
    }

    override fun close() {
        // Drop references to resolved secrets at the end of the build.
        cached = null
    }
}
