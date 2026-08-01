package com.moltenbits.envoy.parse

import kotlin.Unit
import kotlin.jvm.functions.Function1
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

class EnvFileChainSpec extends Specification {

    // EnvFileChain is a Kotlin `object`, so from Groovy it is reached through INSTANCE.
    static final EnvFileChain CHAIN = EnvFileChain.INSTANCE

    // Kotlin (File) -> Unit; a closure satisfies it once it returns Unit.INSTANCE.
    static final Function1<File, Unit> IGNORE_MISSING = { File f -> Unit.INSTANCE } as Function1

    @TempDir
    Path tempDir

    private File dir(String path) {
        def d = tempDir.resolve(path).toFile()
        d.mkdirs()
        d
    }

    private File env(File dir, String name = '.env') {
        def f = new File(dir, name)
        f.text = 'X=1'
        f
    }

    def "orders the chain: build dir .env, then explicit files, then parents nearest first"() {
        given:
        def grand = dir('g')
        def parent = dir('g/p')
        def build = dir('g/p/b')
        def buildEnv = env(build)
        def template = env(build, '.env.template')
        def parentEnv = env(parent)
        def grandEnv = env(grand)

        expect: 'take(4) keeps the assertion immune to stray .env files above the temp dir'
        CHAIN.locate(build, [template], true, null, IGNORE_MISSING).take(4) == [buildEnv, template, parentEnv, grandEnv]
    }

    def "explicit files keep their array order"() {
        given:
        def build = dir('b')
        def a = env(build, 'a.env')
        def b = env(build, 'b.env')

        expect:
        CHAIN.locate(build, [a, b], false, null, IGNORE_MISSING) == [a, b]
        CHAIN.locate(build, [b, a], false, null, IGNORE_MISSING) == [b, a]
    }

    def "skips explicit files that do not exist, reporting each to the callback"() {
        given:
        def build = dir('b')
        def real = env(build, 'real.env')
        def missing = new File(build, 'nope.env')
        def reported = []
        def onMissing = { File f -> reported << f; Unit.INSTANCE } as Function1

        expect:
        CHAIN.locate(build, [missing, real], false, null, onMissing) == [real]
        reported == [missing]
    }

    def "searchParents false keeps only the build dir .env and explicit files"() {
        given:
        def parent = dir('p')
        def build = dir('p/b')
        def buildEnv = env(build)
        env(parent)

        expect:
        CHAIN.locate(build, [], false, null, IGNORE_MISSING) == [buildEnv]
    }

    def "deduplicates an explicit file that is also the build dir .env"() {
        given:
        def build = dir('b')
        def buildEnv = env(build)

        expect:
        CHAIN.locate(build, [new File(build, '.env')], false, null, IGNORE_MISSING) == [buildEnv]
    }

    def "returns empty when nothing exists"() {
        expect:
        CHAIN.locate(dir('empty'), [], false, null, IGNORE_MISSING).isEmpty()
    }

    def "bounds the parent walk at the home directory when the build is inside it"() {
        given: 'a directory above home holding a .env that must never load'
        def outside = dir('outside')
        def home = dir('outside/home')
        def build = dir('outside/home/deep/project')
        env(outside)
        def homeEnv = env(home)
        def deepEnv = env(dir('outside/home/deep'))

        expect: 'the chain reaches home (inclusive) and stops'
        CHAIN.locate(build, [], true, home, IGNORE_MISSING) == [deepEnv, homeEnv]
    }

    def "walks unbounded when the build is outside the home directory"() {
        given:
        def home = dir('elsewhere-home')
        def parent = dir('tree')
        def build = dir('tree/project')
        def parentEnv = env(parent)

        expect: 'take(1) keeps the assertion immune to stray .env files above the temp dir'
        CHAIN.locate(build, [], true, home, IGNORE_MISSING).take(1) == [parentEnv]
    }

    def "merge lets earlier maps win duplicate keys and unions the rest"() {
        expect:
        CHAIN.merge([[A: 'high', B: 'high'], [B: 'low', C: 'low']]) == [A: 'high', B: 'high', C: 'low']
    }

    def "merge of nothing is empty"() {
        expect:
        CHAIN.merge([]).isEmpty()
    }
}
