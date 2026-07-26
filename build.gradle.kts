import org.gradle.plugin.compatibility.compatibility

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
                "dotenv", "env", "envrc", "1password", "op", "secrets", "direnv",
                "configuration-cache", "settings-plugin",
            )
            // Backed by EnvoyPluginFunctionalSpec: CC reuse works and the resolved
            // secret is never serialized into the cache.
            compatibility {
                features {
                    configurationCache.set(true)
                }
            }
        }
    }
}

// The Portal page is fed from the gradlePlugin block above, but the published POMs would
// otherwise carry only coordinates — no license, developer, or SCM info for auditors.
publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name = "gradle-envoy"
            description = "Loads .env, resolves op:// references via the 1Password CLI, and " +
                "auto-injects them into the JVMs Gradle forks."
            url = "https://github.com/moltenbits/gradle-envoy"
            organization {
                name = "MoltenBits"
                url = "https://moltenbits.com"
            }
            licenses {
                license {
                    name = "Apache-2.0"
                    url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                }
            }
            developers {
                developer {
                    id = "jamesdh"
                    name = "James Hardwick"
                    organization = "MoltenBits"
                    organizationUrl = "https://moltenbits.com"
                }
            }
            scm {
                connection = "scm:git:https://github.com/moltenbits/gradle-envoy.git"
                developerConnection = "scm:git:git@github.com:moltenbits/gradle-envoy.git"
                url = "https://github.com/moltenbits/gradle-envoy"
            }
        }
    }
}

dependencies {
    // The groovy-4.0 Spock variant matches the Groovy that gradleApi() already provides from the
    // Gradle distribution, so no compiler or classpath overrides are needed.
    testImplementation("org.spockframework:spock-core:2.4-groovy-4.0")

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
