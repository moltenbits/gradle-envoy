package com.moltenbits.envoy.resolver

/**
 * Resolves password-manager references (such as 1Password `op://` URIs) found in a `.env` file to their
 * secret values.
 *
 * This is the extensibility seam: 1Password is the first implementation ([OnePasswordResolver]); a future
 * Bitwarden/Vault resolver is simply another implementation recognised by its own [handles] scheme. The
 * batch [resolve] signature lets an implementation that talks to a remote API do a single round-trip.
 */
interface SecretResolver {

    /** Returns true if [rawValue] is a reference this resolver knows how to resolve (e.g. starts with `op://`). */
    fun handles(rawValue: String): Boolean

    /**
     * Resolves each reference to its secret value.
     *
     * @param references env-var name -> reference string; every value satisfies [handles].
     * @return env-var name -> resolved secret value.
     * @throws com.moltenbits.envoy.EnvoyResolutionException if a reference cannot be resolved.
     */
    fun resolve(references: Map<String, String>): Map<String, String>
}
