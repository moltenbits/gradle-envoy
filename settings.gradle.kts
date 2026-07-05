plugins {
    // Lets Gradle auto-download the daemon JVM and Java toolchains (e.g. JDK 21) when they are not
    // already installed, via the Foojay Disco API.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "gradle-envoy"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
