package com.moltenbits.envoy.parse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnvFileParserTest {

    @Test
    fun `parses a 1Password op reference with spaces in the item name`() {
        // The real-world line this plugin exists to support.
        val env = EnvFileParser.parse("""GH_TOKEN="op://Private/GitHub PAT for .env/token"""")

        assertEquals(mapOf("GH_TOKEN" to "op://Private/GitHub PAT for .env/token"), env)
    }

    @Test
    fun `keeps a bare value as-is`() {
        assertEquals(mapOf("FOO" to "bar"), EnvFileParser.parse("FOO=bar"))
    }

    @Test
    fun `preserves internal spaces and trims surrounding whitespace`() {
        assertEquals(mapOf("FOO" to "bar baz"), EnvFileParser.parse("  FOO =   bar baz   "))
    }

    @Test
    fun `treats an empty value as an empty string`() {
        assertEquals(mapOf("FOO" to ""), EnvFileParser.parse("FOO="))
    }

    @Test
    fun `unescapes backslash-n only inside double quotes`() {
        // File content: MULTI="a\nb"  (a literal backslash-n between a and b)
        assertEquals(mapOf("MULTI" to "a\nb"), EnvFileParser.parse("""MULTI="a\nb""""))
    }

    @Test
    fun `does not unescape inside single quotes`() {
        // File content: LITERAL='a\nb'  -> backslash-n stays literal
        assertEquals(mapOf("LITERAL" to "a\\nb"), EnvFileParser.parse("""LITERAL='a\nb'"""))
    }

    @Test
    fun `preserves spaces inside quotes`() {
        // File content: PADDED="  x  "
        assertEquals(mapOf("PADDED" to "  x  "), EnvFileParser.parse("PADDED=\"  x  \""))
    }

    @Test
    fun `skips blank lines and full-line comments`() {
        val text = """
            |# a comment
            |
            |FOO=1
            |   # indented comment
            |BAR=2
        """.trimMargin()

        assertEquals(mapOf("FOO" to "1", "BAR" to "2"), EnvFileParser.parse(text))
    }

    @Test
    fun `keeps a hash inside a value`() {
        assertEquals(mapOf("FOO" to "a#b"), EnvFileParser.parse("FOO=a#b"))
    }

    @Test
    fun `tolerates a leading export keyword`() {
        assertEquals(mapOf("FOO" to "bar"), EnvFileParser.parse("export FOO=bar"))
    }

    @Test
    fun `allows a key literally named export`() {
        assertEquals(mapOf("export" to "1"), EnvFileParser.parse("export=1"))
    }

    @Test
    fun `accepts dots and hyphens in keys`() {
        assertEquals(mapOf("my.key-1" to "v"), EnvFileParser.parse("my.key-1=v"))
    }

    @Test
    fun `keeps an equals sign inside the value`() {
        assertEquals(mapOf("CONN" to "a=b=c"), EnvFileParser.parse("CONN=a=b=c"))
    }

    @Test
    fun `preserves declaration order`() {
        val env = EnvFileParser.parse("C=3\nA=1\nB=2")
        assertEquals(listOf("C", "A", "B"), env.keys.toList())
    }

    @Test
    fun `skips malformed lines without a key`() {
        val env = EnvFileParser.parse("this line has no equals\nFOO=ok")
        assertEquals(mapOf("FOO" to "ok"), env)
    }

    @Test
    fun `returns empty for blank input`() {
        assertTrue(EnvFileParser.parse("").isEmpty())
    }
}
