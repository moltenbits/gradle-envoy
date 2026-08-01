package com.moltenbits.envoy.resolver

import com.moltenbits.envoy.EnvoyResolutionException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Turns parsed `.env` entries into the environment map to inject into forked JVMs, applying:
 *
 * 1. **System-env precedence** — a key already present in [systemEnv] is left untouched and dropped from the
 *    result (so it is never re-resolved). From a direnv terminal every variable is already exported, so this
 *    resolves nothing and never invokes a password-manager CLI; from IntelliJ the variables are absent, so
 *    they are resolved from `.env`. The plugin only ever fills gaps.
 * 2. **Reference resolution** — a value recognised by one of the [resolvers] (e.g. an `op://` URI) is
 *    resolved to its secret; any other value is a literal and passed through verbatim. Independent
 *    references resolve concurrently (each is a CLI process spawn, so N references cost roughly the
 *    slowest one rather than the sum), while results, skip callbacks, and strict-mode failures keep
 *    declaration order deterministically.
 * 3. **Strict vs lenient failure** — when a reference fails to resolve, [strict] rethrows (failing the build);
 *    otherwise the key is skipped, [onSkip] is notified, and the remaining variables are still returned.
 *
 * Pure and Gradle-free, so it is unit-testable by injecting a fake resolver and a fixed [systemEnv].
 */
class EnvResolutionEngine(
    private val resolvers: List<SecretResolver>,
    private val systemEnv: Map<String, String> = System.getenv(),
    private val strict: Boolean = false,
    private val onSkip: (String) -> Unit = {},
) {

    fun resolve(parsed: Map<String, String>): Map<String, String> {
        val pendingResolvers = LinkedHashMap<String, SecretResolver>()
        for ((key, raw) in parsed) {
            if (key in systemEnv) continue // system env wins; nothing to inject or resolve
            resolvers.firstOrNull { it.handles(raw) }?.let { pendingResolvers[key] = it }
        }

        val resolved = ConcurrentHashMap<String, String>()
        val failed = ConcurrentHashMap<String, EnvoyResolutionException>()
        resolveReferences(parsed, pendingResolvers, resolved, failed)

        val result = LinkedHashMap<String, String>()
        for ((key, raw) in parsed) {
            if (key in systemEnv) continue
            when {
                key !in pendingResolvers -> result[key] = raw // literal value
                resolved.containsKey(key) -> result[key] = resolved.getValue(key)
                else -> {
                    val failure = failed.getValue(key)
                    if (strict) throw failure
                    onSkip("envoy: skipping $key — ${failure.message}")
                }
            }
        }
        return result
    }

    private fun resolveReferences(
        parsed: Map<String, String>,
        pending: Map<String, SecretResolver>,
        resolved: MutableMap<String, String>,
        failed: MutableMap<String, EnvoyResolutionException>,
    ) {
        val tasks = pending.map { (key, resolver) ->
            {
                try {
                    resolved[key] = resolver.resolve(mapOf(key to parsed.getValue(key))).getValue(key)
                } catch (e: EnvoyResolutionException) {
                    failed[key] = e
                }
            }
        }
        if (tasks.size <= 1) {
            tasks.forEach { it() }
            return
        }

        val pool = Executors.newFixedThreadPool(min(tasks.size, MAX_CONCURRENT_RESOLUTIONS))
        try {
            // invokeAll waits for every task; get() surfaces anything that escaped the per-key
            // catch above (i.e. unexpected non-resolution failures), matching inline behavior.
            pool.invokeAll(tasks.map { Callable(it) }).forEach { future ->
                try {
                    future.get()
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
            }
        } finally {
            pool.shutdown()
        }
    }

    private companion object {
        const val MAX_CONCURRENT_RESOLUTIONS = 4
    }
}
