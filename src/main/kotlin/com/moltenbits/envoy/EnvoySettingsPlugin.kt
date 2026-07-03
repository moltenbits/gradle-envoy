package com.moltenbits.envoy

import com.moltenbits.envoy.service.EnvoySecretService
import com.moltenbits.envoy.task.InjectEnvAction
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Task
import org.gradle.api.initialization.Settings
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test

/**
 * Settings plugin that makes `.env` variables — including 1Password `op://` references — automatically
 * present in the JVMs Gradle forks (`Test`, `JavaExec`, and subtypes like `application`'s `run` and Spring
 * Boot's `bootRun`), so secrets work when a build is launched from IntelliJ, not just from a direnv shell.
 *
 * Apply once in `settings.gradle.kts`:
 * ```kotlin
 * plugins { id("com.moltenbits.envoy") }
 * ```
 *
 * It registers a build-wide [EnvoySecretService] (which resolves secrets lazily, once per build) and, via
 * [Settings]'s isolated `gradle.lifecycle.beforeProject`, attaches an execution-time [InjectEnvAction] to
 * every forked-JVM task in every project — no per-task wiring required.
 */
class EnvoySettingsPlugin : Plugin<Settings> {

    override fun apply(settings: Settings) {
        val extension = settings.extensions.create("envoy", EnvoyExtension::class.java).apply {
            enabled.convention(true)
            searchParentDirectories.convention(true)
            cliExecutable.convention("op")
            cliArgs.convention(listOf("read"))
            overrideTaskEnvironment.convention(false)
            strict.convention(false)
        }

        // Gradle's kotlin-dsl treats Action/IsolatedAction as SAM-with-receiver, so `this` is the argument.
        val service: Provider<EnvoySecretService> = settings.gradle.sharedServices.registerIfAbsent(
            SERVICE_NAME,
            EnvoySecretService::class.java,
        ) {
            parameters.searchFromDir.set(settings.rootDir.absolutePath)
            parameters.explicitEnvFile.set(extension.envFile)
            parameters.searchParents.set(extension.searchParentDirectories)
            parameters.cliExecutable.set(extension.cliExecutable)
            parameters.cliArgs.set(extension.cliArgs)
            parameters.strict.set(extension.strict)
        }

        // Capture only Providers (never the extension object) so the isolated beforeProject action stays
        // configuration-cache / isolated-projects clean.
        val enabled: Provider<Boolean> = extension.enabled
        val overrideExisting: Provider<Boolean> = extension.overrideTaskEnvironment

        settings.gradle.lifecycle.beforeProject {
            // this: Project
            if (!enabled.get()) return@beforeProject

            val wire = Action<Task> {
                // this: Task
                usesService(service)
                doFirst(InjectEnvAction(service, overrideExisting.get()))
            }
            // withType(...).configureEach is a live view, so tasks registered later by the java/application/
            // spring-boot plugins (test, run, bootRun) are still wired.
            tasks.withType(Test::class.java).configureEach(wire)
            tasks.withType(JavaExec::class.java).configureEach(wire)
        }
    }

    private companion object {
        const val SERVICE_NAME = "envoySecretResolver"
    }
}
