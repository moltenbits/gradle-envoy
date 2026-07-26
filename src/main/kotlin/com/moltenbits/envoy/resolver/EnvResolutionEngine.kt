package com.moltenbits.envoy.resolver

import com.moltenbits.envoy.EnvoyResolutionException

/**
 * Turns parsed `.env` entries into the environment map to inject into forked JVMs, applying:
 *
 * 1. **System-env precedence** — a key already present in [systemEnv] is left untouched and dropped from the
 *    result (so it is never re-resolved). From a direnv terminal every variable is already exported, so this
 *    resolves nothing and never invokes a password-manager CLI; from IntelliJ the variables are absent, so
 *    they are resolved from `.env`. The plugin only ever fills gaps.
 * 2. **Reference resolution** — a value recognised by one of the [resolvers] (e.g. an `op://` URI) is
 *    resolved to its secret; any other value is a literal and passed through verbatim.
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
        val result = LinkedHashMap<String, String>()
        for ((key, raw) in parsed) {
            if (key in systemEnv) continue // system env wins; nothing to inject or resolve

            val resolver = resolvers.firstOrNull { it.handles(raw) }
            if (resolver == null) {
                result[key] = raw // literal value
                continue
            }

            try {
                result[key] = resolver.resolve(mapOf(key to raw)).getValue(key)
            } catch (e: EnvoyResolutionException) {
                if (strict) throw e
                onSkip("envoy: skipping $key — ${e.message}")
            }
        }
        return result
    }
}
