package com.moltenbits.envoy.parse

import java.io.File

/**
 * Locates the ordered chain of env files feeding a build and merges their parsed contents.
 *
 * Precedence, highest first:
 * 1. the build directory's own `.env` — machine-local overrides
 * 2. explicitly configured files, in list order (earlier = higher) — committed project config
 * 3. `.env` files discovered walking up parent directories, nearest first — workspace fallbacks
 *
 * A variable already present in the real process environment is left untouched regardless; that
 * rule lives in [com.moltenbits.envoy.resolver.EnvResolutionEngine], above any file.
 */
object EnvFileChain {

    /** Ordered, deduplicated, highest-precedence-first list of the env files that exist. */
    fun locate(startDir: File, explicit: List<File>, searchParents: Boolean): List<File> {
        val chain = mutableListOf<File>()
        File(startDir, ENV_FILE_NAME).takeIf(File::isFile)?.let(chain::add)
        explicit.filter(File::isFile).forEach(chain::add)
        if (searchParents) {
            var dir: File? = startDir.absoluteFile.parentFile
            while (dir != null) {
                File(dir, ENV_FILE_NAME).takeIf(File::isFile)?.let(chain::add)
                dir = dir.parentFile
            }
        }
        return chain.distinctBy { it.absoluteFile.normalize() }
    }

    /** Merges parsed file contents ordered highest precedence first; earlier maps win per key. */
    fun merge(parsedHighestFirst: List<Map<String, String>>): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        for (parsed in parsedHighestFirst) {
            for ((key, value) in parsed) merged.putIfAbsent(key, value)
        }
        return merged
    }

    private const val ENV_FILE_NAME = ".env"
}
