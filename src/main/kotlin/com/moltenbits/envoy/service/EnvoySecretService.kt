package com.moltenbits.envoy.service

import com.moltenbits.envoy.parse.EnvFileParser
import com.moltenbits.envoy.resolver.CommandResolver
import com.moltenbits.envoy.resolver.EnvResolutionEngine
import com.moltenbits.envoy.resolver.OnePasswordResolver
import com.moltenbits.envoy.resolver.SecretResolver
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logging
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.io.File

/**
 * Build-scoped service that locates the `.env`, resolves its `op://` references, and hands the resulting
 * environment map to task actions — computed **once per build** and shared across every project and forked
 * JVM.
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

        /** Explicit `.env` file; when present it is used directly and the walk-up search is skipped. */
        val explicitEnvFile: RegularFileProperty

        /** Whether to walk up parent directories to find the nearest `.env`. Defaults to true. */
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
        val envFile = locateEnvFile() ?: return emptyMap()
        val parsed = EnvFileParser.parse(envFile.readText())
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

    /** Explicit file if configured, else the nearest `.env` walking up from [Params.searchFromDir]. */
    private fun locateEnvFile(): File? {
        parameters.explicitEnvFile.orNull?.asFile?.let { explicit ->
            if (explicit.isFile) return explicit
            logger.warn("envoy: configured envFile '${explicit.path}' does not exist; no variables loaded")
            return null
        }

        val start = File(parameters.searchFromDir.get()).absoluteFile
        if (!parameters.searchParents.getOrElse(true)) {
            return File(start, ".env").takeIf { it.isFile }
        }

        var dir: File? = start
        while (dir != null) {
            val candidate = File(dir, ".env")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }

    override fun close() {
        // Drop references to resolved secrets at the end of the build.
        cached = null
    }
}
