package com.moltenbits.envoy.parse

import spock.lang.Specification

class EnvFileParserSpec extends Specification {

    // EnvFileParser is a Kotlin `object`, so from Groovy it is reached through INSTANCE.
    static final EnvFileParser PARSER = EnvFileParser.INSTANCE

    def "parses a 1Password op reference with spaces in the item name"() {
        given: "the real-world line this plugin exists to support"
        def line = 'GH_TOKEN="op://Private/GitHub PAT for .env/token"'

        expect:
        PARSER.parse(line) == [GH_TOKEN: 'op://Private/GitHub PAT for .env/token']
    }

    def "parses #scenario"() {
        expect:
        PARSER.parse(line) == expected

        where:
        scenario                                 | line                   || expected
        'a bare value'                           | 'FOO=bar'              || [FOO: 'bar']
        'internal spaces, trimming the outside'  | '  FOO =   bar baz   ' || [FOO: 'bar baz']
        'an empty value as an empty string'      | 'FOO='                 || [FOO: '']
        'a hash kept inside a value'             | 'FOO=a#b'              || [FOO: 'a#b']
        'a tolerated leading export keyword'     | 'export FOO=bar'       || [FOO: 'bar']
        'a key literally named export'           | 'export=1'             || [export: '1']
        'dots and hyphens in keys'               | 'my.key-1=v'           || ['my.key-1': 'v']
        'an equals sign kept in the value'       | 'CONN=a=b=c'           || [CONN: 'a=b=c']
    }

    def "unescapes backslash-n inside double quotes"() {
        given: 'file content: MULTI="a\\nb" — a literal backslash then n'
        def line = 'MULTI="a\\nb"'

        expect: 'it becomes a real newline'
        PARSER.parse(line) == [MULTI: 'a\nb']
    }

    def "does not unescape inside single quotes"() {
        given: "file content: LITERAL='a\\nb'"
        def line = "LITERAL='a\\nb'"

        expect: 'the backslash-n stays literal'
        PARSER.parse(line) == [LITERAL: 'a\\nb']
    }

    def "preserves spaces inside quotes"() {
        expect:
        PARSER.parse('PADDED="  x  "') == [PADDED: '  x  ']
    }

    def "skips blank lines and full-line comments"() {
        given:
        def text = '''\
            # a comment

            FOO=1
               # indented comment
            BAR=2
            '''.stripIndent()

        expect:
        PARSER.parse(text) == [FOO: '1', BAR: '2']
    }

    def "preserves declaration order"() {
        expect:
        PARSER.parse('C=3\nA=1\nB=2').keySet() as List == ['C', 'A', 'B']
    }

    def "skips malformed lines without a key"() {
        expect:
        PARSER.parse('this line has no equals\nFOO=ok') == [FOO: 'ok']
    }

    def "returns empty for blank input"() {
        expect:
        PARSER.parse('').isEmpty()
    }
}
