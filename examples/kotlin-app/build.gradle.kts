plugins {
    application
}

application {
    mainClass = "com.example.App"
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
}

// Note: nowhere in this build do we wire `environment(...)` into `run` or `test`.
// gradle-envoy injects the .env values (and resolved op:// secrets) into both automatically.
