package com.moltenbits.envoy.task

import com.moltenbits.envoy.service.EnvoySecretService
import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.process.ProcessForkOptions

/**
 * A `doFirst` action that injects the resolved environment into a forked-JVM task (`Test`, `JavaExec`, and
 * their subtypes such as `application`'s `run` and Spring Boot's `bootRun`) at **execution time**.
 *
 * It is a named class rather than a capturing lambda so that, under the configuration cache, only its two
 * fields are serialized: a [Provider] to the build service and a boolean. It captures no `Project`/`Task`
 * state, and the actual secret resolution happens when [execute] runs — never at configuration time.
 *
 * By default existing environment entries are left in place (the plugin only fills gaps); set
 * [overrideExisting] to have `.env` values win instead.
 */
class InjectEnvAction(
    private val service: Provider<EnvoySecretService>,
    private val overrideExisting: Boolean,
) : Action<Task> {

    override fun execute(task: Task) {
        val fork = task as ProcessForkOptions
        for ((key, value) in service.get().environment()) {
            if (overrideExisting || !fork.environment.containsKey(key)) {
                fork.environment(key, value)
            }
        }
    }
}
