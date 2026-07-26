package com.moltenbits.envoy

/**
 * Thrown when a secret reference (e.g. an `op://` URI) cannot be resolved by its [resolver.SecretResolver].
 *
 * Messages intentionally carry only the failing reference and CLI stderr — never a resolved secret value —
 * so they are safe to surface in build logs.
 */
class EnvoyResolutionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
