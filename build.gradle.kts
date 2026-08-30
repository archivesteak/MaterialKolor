import org.gradle.api.publish.maven.tasks.PublishToMavenLocal
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.plugins.signing.Sign

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.multiplatform) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.multiplatform.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.poko) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.binaryCompatibility)
    alias(libs.plugins.spotless)
}

// This fork is under a hard publication freeze. Keep the safety property executable: command-line
// properties, globally configured credentials, or a future publishing-plugin default must not be
// able to turn a validation build into a remote artifact or build-metadata upload. Maven-local
// tasks remain available, but they require an explicit isolated repository instead of silently
// writing to ~/.m2.
val forbiddenPublicationTaskName =
    Regex("(?i)(mavenCentral|sonatype|upload|close.*repository|release.*repository|buildScan)")
val forbiddenPublicationProperties = listOf(
    "mavenCentralPublishing",
    "mavenCentralAutomaticPublishing",
    "SONATYPE_AUTOMATIC_RELEASE",
    "RELEASE_SIGNING_ENABLED",
)
val enabledForbiddenPublicationProperties = forbiddenPublicationProperties.filter { property ->
    providers.gradleProperty(property).orNull?.toBoolean() == true
}
check(enabledForbiddenPublicationProperties.isEmpty()) {
    "Remote publication/signing is frozen; remove true value(s) for: " +
        enabledForbiddenPublicationProperties.joinToString()
}

allprojects {
    tasks.withType<PublishToMavenRepository>().configureEach {
        enabled = false
        onlyIf("remote Maven publication is frozen") { false }
        doFirst {
            throw GradleException("$path cannot run while remote publication is frozen")
        }
    }
    tasks.withType<Sign>().configureEach {
        enabled = false
        onlyIf("artifact signing is frozen") { false }
        doFirst {
            throw GradleException("$path cannot run while artifact signing is frozen")
        }
    }
    tasks.withType<PublishToMavenLocal>().configureEach {
        doFirst {
            val configuredRepository = System.getProperty("maven.repo.local")
            check(!configuredRepository.isNullOrBlank()) {
                "${path} requires an explicit isolated -Dmaven.repo.local path"
            }
            val destination = project.file(configuredRepository).canonicalFile
            val defaultRepository =
                project.file("${System.getProperty("user.home")}/.m2/repository").canonicalFile
            check(destination != defaultRepository) {
                "${path} refuses to write into the default Maven repository: $destination"
            }
        }
    }
    tasks.matching {
        it.name == "publish" || forbiddenPublicationTaskName.containsMatchIn(it.name)
    }.configureEach {
        enabled = false
        onlyIf("remote/aggregate publication is frozen") { false }
        doFirst {
            throw GradleException("$path cannot run while remote publication is frozen")
        }
    }
}

// Refuse an explicitly requested external-publication or signing task before any action can run.
// This supplements the disabled/onlyIf/doFirst guards above and makes an accidental command fail loudly
// rather than looking like a successful no-op.
gradle.taskGraph.whenReady {
    val requestedFrozenTasks = allTasks.filter {
        it is PublishToMavenRepository ||
            it is Sign ||
            it.name == "publish" ||
            forbiddenPublicationTaskName.containsMatchIn(it.name)
    }
    check(requestedFrozenTasks.isEmpty()) {
        "External publication/signing task graph is frozen: " +
            requestedFrozenTasks.joinToString { it.path }
    }
}

tasks.register("verifyPublicationFreeze") {
    group = "verification"
    description = "Verifies that only explicitly isolated Maven-local publication can run."

    doLast {
        // Inspect only publication/signing task types and suspicious task names. Realizing every
        // task in every project would also configure unrelated Android/Kotlin task graphs, which
        // makes this safety check slower and can surface deprecations from tasks it never runs.
        val enabledRemoteTasks = allprojects.flatMap { project ->
            project.tasks.withType<PublishToMavenRepository>().filter { it.enabled }
        }
        val enabledSigningTasks = allprojects.flatMap { project ->
            project.tasks.withType<Sign>().filter { it.enabled }
        }
        val enabledForbiddenNamedTasks = allprojects.flatMap { project ->
            project.tasks.names
                .filter { name ->
                    name == "publish" || forbiddenPublicationTaskName.containsMatchIn(name)
                }
                .mapNotNull { name -> project.tasks.named(name).get().takeIf { it.enabled } }
        }
        val localTasks = allprojects.flatMap { project ->
            project.tasks.withType<PublishToMavenLocal>().toList()
        }

        check(enabledRemoteTasks.isEmpty()) {
            "Remote Maven tasks are enabled: ${enabledRemoteTasks.joinToString { it.path }}"
        }
        check(enabledSigningTasks.isEmpty()) {
            "Signing tasks are enabled during the publication freeze: " +
                enabledSigningTasks.joinToString { it.path }
        }
        check(enabledForbiddenNamedTasks.isEmpty()) {
            "Remote/aggregate publication tasks are enabled: " +
                enabledForbiddenNamedTasks.joinToString { it.path }
        }
        check(localTasks.isNotEmpty()) { "No Maven-local publication tasks were registered" }

        logger.lifecycle(
            "Publication freeze verified: ${localTasks.size} guarded Maven-local task(s), " +
                "zero enabled remote, aggregate, signing, or build-scan tasks.",
        )
    }
}

apiValidation {
    ignoredProjects.addAll(
        listOf("mcu-upstream"),
    )
}

dokka {
    dokkaPublications.html {
        outputDirectory.set(rootDir.resolve("dokka"))
    }
}

dependencies {
    dokka(project.dependencies.project(":material-color-utilities"))
    dokka(project.dependencies.project(":material-kolor"))
}

subprojects {
    apply {
        plugin(rootProject.libs.plugins.spotless.get().pluginId)
    }

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            ktlint(libs.versions.ktlint.get()).setEditorConfigPath("${project.rootDir}/.editorconfig")
            target("**/*.kt")
            targetExclude(
                "${layout.buildDirectory}/**/*.kt",
            )
            toggleOffOn()
            endWithNewline()
        }
    }
}
