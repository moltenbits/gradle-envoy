plugins {
    `kotlin-dsl`
    groovy
    id("com.gradle.plugin-publish") version "2.1.1"
}

group = "com.moltenbits"
version = "0.1.0"

// Compile the plugin to Java 17 bytecode for broad consumer reach, even though the
// Gradle daemon that builds it may run on a newer JDK. Min supported Gradle: 8.8
// (for Settings' `gradle.lifecycle.beforeProject`).
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

// gradleApi() ships the distribution's Groovy 4 as file dependencies, which bypass version
// resolution and precede declared modules on the classpath. Two consequences, two fixes:
// GroovyCompile would infer the Groovy 4 compiler (Spock's groovy-5.0 variant refuses it), and
// the test JVM would load the Groovy 4 runtime ahead of the declared Groovy 5
// (GroovyRuntimeSpec guards that). This configuration isolates Groovy 5 for both.
val groovy5 = configurations.create("groovy5") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

tasks.withType<GroovyCompile>().configureEach {
    groovyClasspath = groovy5
}

tasks.withType<Test>().configureEach {
    classpath = groovy5 + classpath
}

// Dedicated source set for Gradle TestKit functional tests, kept apart from fast unit tests.
val functionalTestSourceSet: SourceSet = sourceSets.create("functionalTest")

configurations[functionalTestSourceSet.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[functionalTestSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

gradlePlugin {
    website = "https://github.com/moltenbits/gradle-envoy"
    vcsUrl = "https://github.com/moltenbits/gradle-envoy.git"

    // Wire the functional source set so it gets the plugin-under-test classpath and metadata.
    testSourceSets(functionalTestSourceSet)

    plugins {
        create("envoy") {
            id = "com.moltenbits.envoy"
            implementationClass = "com.moltenbits.envoy.EnvoySettingsPlugin"
            displayName = "Envoy — .env + 1Password secrets, auto-propagated to Gradle JVMs"
            description = "Loads .env, resolves op:// references via the 1Password CLI, and " +
                "auto-injects them into Test/JavaExec/run/bootRun — so secrets work from " +
                "IntelliJ, not just a direnv shell."
            tags = listOf(
                "dotenv", "env", "envrc", "1password", "secrets", "direnv",
                "configuration-cache", "settings-plugin",
            )
        }
    }
}

val groovyVersion = "5.0.7"

dependencies {
    // The same version in both places: testImplementation is what the specs compile and run
    // against; groovy5 is the compiler handed to GroovyCompile above. Diverging them would mean
    // compiling with one Groovy and running on another.
    testImplementation("org.apache.groovy:groovy:$groovyVersion")
    groovy5("org.apache.groovy:groovy:$groovyVersion")

    testImplementation("org.spockframework:spock-core:2.4-groovy-5.0")

    // Spock is the engine; Gradle 9 additionally requires the JUnit Platform launcher on the test
    // runtime classpath. The BOM version is the one Spock 2.4 imports, so they cannot drift.
    testRuntimeOnly(platform("org.junit:junit-bom:5.14.1"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    "functionalTestImplementation"(gradleTestKit())
}

val functionalTest = tasks.register<Test>("functionalTest") {
    description = "Runs the Gradle TestKit functional tests."
    group = "verification"
    testClassesDirs = functionalTestSourceSet.output.classesDirs
    classpath = functionalTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    // Nested TestKit builds fork on the daemon JVM, which this repo pins to JDK 21 via
    // gradle/gradle-daemon-jvm.properties — old enough to run the Gradle 8.8 compatibility test.
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.named("check") {
    dependsOn(functionalTest)
}
