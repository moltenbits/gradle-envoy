package com.moltenbits.envoy.resolver

import com.moltenbits.envoy.EnvoyResolutionException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnvResolutionEngineTest {

    /** Fake resolver: handles `op://` and maps a reference to a value via [behavior]. */
    private class FakeResolver(val behavior: (String) -> String) : SecretResolver {
        override fun handles(rawValue: String) = rawValue.startsWith("op://")
        override fun resolve(references: Map<String, String>) = references.mapValues { behavior(it.value) }
    }

    private fun engine(
        systemEnv: Map<String, String> = emptyMap(),
        strict: Boolean = false,
        onSkip: (String) -> Unit = {},
        behavior: (String) -> String = { "resolved" },
    ) = EnvResolutionEngine(
        resolvers = listOf(FakeResolver(behavior)),
        systemEnv = systemEnv,
        strict = strict,
        onSkip = onSkip,
    )

    @Test
    fun `passes literal values through untouched`() {
        assertEquals(mapOf("FOO" to "bar"), engine().resolve(mapOf("FOO" to "bar")))
    }

    @Test
    fun `resolves references via the resolver`() {
        val result = engine(behavior = { "secret-for-$it" })
            .resolve(mapOf("GH_TOKEN" to "op://Private/Item/token"))

        assertEquals(mapOf("GH_TOKEN" to "secret-for-op://Private/Item/token"), result)
    }

    @Test
    fun `system env wins and the key is dropped without resolving`() {
        var resolverCalled = false
        val result = EnvResolutionEngine(
            resolvers = listOf(FakeResolver { resolverCalled = true; "should-not-happen" }),
            systemEnv = mapOf("GH_TOKEN" to "already-set"),
        ).resolve(mapOf("GH_TOKEN" to "op://Private/Item/token"))

        assertTrue(result.isEmpty(), "a system-provided key must not be injected")
        assertTrue(!resolverCalled, "a system-provided key must not trigger the resolver")
    }

    @Test
    fun `mixes literals and references, filling only the gaps`() {
        val result = engine(
            systemEnv = mapOf("PRESENT" to "x"),
            behavior = { "S" },
        ).resolve(
            linkedMapOf(
                "PRESENT" to "op://should/be/skipped",
                "PLAIN" to "literal",
                "SECRET" to "op://v/i/f",
            ),
        )

        assertEquals(mapOf("PLAIN" to "literal", "SECRET" to "S"), result)
    }

    @Test
    fun `strict mode rethrows a resolution failure`() {
        val failing = engine(strict = true, behavior = { throw EnvoyResolutionException("boom") })

        assertThrows(EnvoyResolutionException::class.java) {
            failing.resolve(mapOf("SECRET" to "op://v/i/f"))
        }
    }

    @Test
    fun `lenient mode skips a failed reference but keeps the rest`() {
        val skipped = mutableListOf<String>()
        val result = engine(
            strict = false,
            onSkip = { skipped += it },
            behavior = { if (it.contains("bad")) throw EnvoyResolutionException("nope") else "ok" },
        ).resolve(linkedMapOf("GOOD" to "op://v/good/f", "BAD" to "op://v/bad/f"))

        assertEquals(mapOf("GOOD" to "ok"), result)
        assertEquals(1, skipped.size)
        assertTrue(skipped.single().contains("BAD"))
    }
}
