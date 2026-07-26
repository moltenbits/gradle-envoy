package com.moltenbits.envoy.parse

/**
 * Parses `.env` file text into ordered key/value pairs.
 *
 * The grammar mirrors the well-established [uzzu/dotenv-gradle](https://github.com/uzzu/dotenv-gradle)
 * parser, with a tolerated leading `export ` (common in direnv-style files):
 *
 * - Blank lines and full-line `#` comments are ignored (a `#` inside a value is kept).
 * - A line is `KEY=VALUE`, where `KEY` is `[\w.-]+` and may be prefixed with `export `.
 * - Surrounding single or double quotes are stripped. Inside **double** quotes, `\n` is unescaped to a
 *   newline; single-quoted values are taken literally. Whitespace inside quotes is preserved.
 * - `KEY=` yields an empty string. Lines that do not match are skipped.
 *
 * Values are returned raw: an `op://` reference is preserved verbatim for a later resolver — parsing does
 * not resolve secrets and does no `${VAR}` interpolation.
 */
object EnvFileParser {

    private val LINE = Regex("""^\s*(?:export\s+)?([\w.-]+)\s*=\s*(.*?)\s*$""")
    private val NEWLINES = Regex("""\r\n|\r|\n""")

    /** Returns KEY -> raw value, preserving declaration order. */
    fun parse(text: String): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        for (line in text.split(NEWLINES)) {
            val leading = line.trimStart()
            if (leading.isEmpty() || leading.startsWith("#")) continue
            val match = LINE.matchEntire(line) ?: continue
            result[match.groupValues[1]] = unquote(match.groupValues[2])
        }
        return result
    }

    private fun unquote(raw: String): String {
        if (raw.length >= 2) {
            val first = raw.first()
            val last = raw.last()
            if (first == '"' && last == '"') {
                return raw.substring(1, raw.length - 1).replace("\\n", "\n")
            }
            if (first == '\'' && last == '\'') {
                return raw.substring(1, raw.length - 1)
            }
        }
        return raw
    }
}
