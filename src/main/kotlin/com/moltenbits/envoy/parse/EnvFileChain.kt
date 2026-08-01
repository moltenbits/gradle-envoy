package com.moltenbits.envoy.parse

import java.io.File

/**
 * Locates the ordered chain of env files feeding a build and merges their parsed contents.
 *
 * Precedence, highest first:
 * 1. the build directory's own `.env` — machine-local overrides
 * 2. explicitly configured files, in list order (earlier = higher) — committed project config
 * 3. `.env` files discovered walking up parent directories, nearest first — workspace fallbacks.
 *    When the build lives inside the home directory the walk stops there (inclusive), so a
 *    planted `.env` in a world-writable ancestor like `/Users` or `/tmp`'s parents is never merged.
 *
 * A variable already present in the real process environment is left untouched regardless; that
 * rule lives in [com.moltenbits.envoy.resolver.EnvResolutionEngine], above any file.
 */
object EnvFileChain {

    /**
     * Ordered, deduplicated, highest-precedence-first list of the env files that exist.
     * An explicit entry that is not a regular file is dropped and reported to [onMissingExplicit],
     * keeping the caller's warning in lockstep with what is actually skipped here.
     */
    fun locate(
        startDir: File,
        explicit: List<File>,
        searchParents: Boolean,
        home: File?,
        onMissingExplicit: (File) -> Unit,
    ): List<File> {
        val chain = mutableListOf<File>()
        File(startDir, ENV_FILE_NAME).takeIf(File::isFile)?.let(chain::add)
        explicit.forEach { if (it.isFile) chain.add(it) else onMissingExplicit(it) }
        if (searchParents) {
            val start = startDir.absoluteFile.normalize()
            val boundary = home?.absoluteFile?.normalize()?.takeIf { start.startsWith(it) }
            var dir: File? = start.parentFile
            while (dir != null && (boundary == null || dir.startsWith(boundary))) {
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
