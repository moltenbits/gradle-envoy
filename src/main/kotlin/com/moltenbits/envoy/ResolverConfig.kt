package com.moltenbits.envoy

/**
 * Configuration for one custom resolver, registered via [EnvoyExtension.resolver].
 */
class ResolverConfig {

    /**
     * The command run to resolve each reference, as an argv list (never passed through a shell).
     *
     * Placeholders are substituted in every argument: `{ref}` (the full reference), `{path}` (the
     * reference minus scheme and final segment), `{field}` (the final `/`-separated segment). If no
     * argument contains a placeholder, the full reference is appended as the last argument.
     *
     * The command must print the secret to stdout; one trailing newline is trimmed.
     */
    var command: List<String> = emptyList()
}
