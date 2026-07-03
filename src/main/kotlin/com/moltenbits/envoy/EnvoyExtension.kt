package com.moltenbits.envoy

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * Configuration for the Envoy plugin, applied in `settings.gradle.kts`:
 *
 * ```kotlin
 * plugins { id("com.moltenbits.envoy") }
 *
 * envoy {
 *     cliExecutable = "op-fast"   // optional: use a Keychain-cached, offline 1Password CLI wrapper
 * }
 * ```
 *
 * Every option has a sensible convention, so the common case configures nothing.
 */
abstract class EnvoyExtension {

    /** Master switch. When false, no task wiring or resolution happens. Default: `true`. */
    abstract val enabled: Property<Boolean>

    /**
     * Explicit `.env` file. When set, it is used directly and parent-directory search is skipped.
     * When unset (default), the nearest `.env` is found by walking up from the build root.
     */
    abstract val envFile: RegularFileProperty

    /** Walk up parent directories to find the nearest `.env` (direnv-style). Default: `true`. */
    abstract val searchParentDirectories: Property<Boolean>

    /** 1Password CLI executable. Default: `op`. Set to `op-fast` for a Keychain-cached, offline wrapper. */
    abstract val cliExecutable: Property<String>

    /** Leading CLI arguments placed before the reference. Default: `["read"]`. */
    abstract val cliArgs: ListProperty<String>

    /**
     * When true, `.env` values override variables already present in a task's environment. Default: `false`
     * (the plugin only fills gaps, so an intentionally-exported value always wins).
     */
    abstract val overrideTaskEnvironment: Property<Boolean>

    /** Fail the build when a reference cannot be resolved, instead of skipping it with a warning. Default: `false`. */
    abstract val strict: Property<Boolean>
}
