plugins {
    `kotlin-dsl`
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
        }
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    "functionalTestImplementation"(gradleTestKit())
}

val functionalTest = tasks.register<Test>("functionalTest") {
    description = "Runs the Gradle TestKit functional tests."
    group = "verification"
    testClassesDirs = functionalTestSourceSet.output.classesDirs
    classpath = functionalTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    // Fork the nested TestKit builds on a JDK the min-supported Gradle (8.8) can run on — Gradle 8.x does
    // not support JDK 25, so the daemon JDK would otherwise make the 8.8 compatibility test impossible.
    javaLauncher.set(
        javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) },
    )
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.named("check") {
    dependsOn(functionalTest)
}
