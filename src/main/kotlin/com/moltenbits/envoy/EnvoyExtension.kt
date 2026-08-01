package com.moltenbits.envoy

import com.moltenbits.envoy.resolver.OnePasswordResolver
import org.gradle.api.Action
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import java.io.File

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
     * Additional env files layered into the discovered `.env` chain, earlier entries taking
     * precedence over later ones. For duplicate keys they rank below the build directory's own
     * `.env` and above files discovered in parent directories. A missing entry is skipped with
     * a warning. Default: empty.
     */
    abstract val envFiles: ListProperty<File>

    /**
     * Walk up parent directories, merging every `.env` found (direnv-style; nearer files win
     * duplicate keys). Default: `true`.
     */
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

    /** Custom scheme → command template, populated via [resolver]. Read by the plugin; not set directly. */
    abstract val commandResolvers: MapProperty<String, List<String>>

    /**
     * Registers a custom resolver: any `.env` value starting with [scheme] is resolved by running the
     * configured [ResolverConfig.command] and reading its stdout.
     *
     * ```kotlin
     * envoy {
     *     resolver("vault://") {
     *         command = listOf("vault", "kv", "get", "-field={field}", "{path}")
     *     }
     * }
     * ```
     *
     * The built-in `op://` scheme cannot be re-registered; customize it via [cliExecutable]/[cliArgs].
     */
    fun resolver(scheme: String, configure: Action<ResolverConfig>) {
        require(SCHEME_PATTERN.matches(scheme)) {
            "envoy.resolver: scheme must be a letter followed by letters/digits/+.- and end in \"://\" " +
                "(like \"vault://\"), got \"$scheme\""
        }
        require(scheme != OnePasswordResolver.SCHEME) {
            "envoy.resolver: \"${OnePasswordResolver.SCHEME}\" is built in and always wins; " +
                "customize it via cliExecutable/cliArgs instead"
        }
        require(!commandResolvers.get().containsKey(scheme)) {
            "envoy.resolver: scheme \"$scheme\" is already registered"
        }
        val config = ResolverConfig()
        configure.execute(config)
        require(config.command.isNotEmpty()) {
            "envoy.resolver(\"$scheme\"): command must not be empty"
        }
        commandResolvers.put(scheme, config.command.toList())
    }

    private companion object {
        val SCHEME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9+.-]*://$")
    }
}
