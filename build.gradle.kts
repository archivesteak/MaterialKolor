import org.gradle.api.artifacts.component.ModuleComponentIdentifier
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

val validHostShards = setOf("windows", "web-android", "apple")
val configuredHostShard = providers.gradleProperty("materialKolor.hostShard").orNull
check(configuredHostShard == null || configuredHostShard in validHostShards) {
    "materialKolor.hostShard must be one of ${validHostShards.joinToString()}: " +
        configuredHostShard
}

fun publicationPaths(module: String, vararg publications: String): Set<String> =
    publications.mapTo(mutableSetOf()) { publication ->
        ":$module:publish${publication}PublicationToMavenLocal"
    }

val publicationPathsByHostShard = mapOf(
    "windows" to (
        publicationPaths(
            "material-color-utilities",
            "KotlinMultiplatform",
            "Jvm",
            "MingwX64",
        ) +
            publicationPaths("material-kolor", "KotlinMultiplatform", "Jvm", "MingwX64")
    ),
    "web-android" to (
        publicationPaths(
            "material-color-utilities",
            "KotlinMultiplatform",
            "Android",
            "Js",
            "WasmJs",
        ) +
            publicationPaths(
                "material-kolor",
                "KotlinMultiplatform",
                "Android",
                "Js",
                "WasmJs",
            )
    ),
    "apple" to (
        publicationPaths(
            "material-color-utilities",
            "KotlinMultiplatform",
            "MacosArm64",
            "IosX64",
            "IosArm64",
            "IosSimulatorArm64",
        ) +
            publicationPaths(
                "material-kolor",
                "KotlinMultiplatform",
                "MacosArm64",
                "IosArm64",
                "IosSimulatorArm64",
            )
    ),
)
val allowedMavenLocalPublicationPaths = configuredHostShard
    ?.let(publicationPathsByHostShard::getValue)
    .orEmpty()

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
        val isAllowedHostPublication = path in allowedMavenLocalPublicationPaths
        enabled = isAllowedHostPublication
        onlyIf("only the exact selected host shard may be staged") {
            isAllowedHostPublication
        }
        if (isAllowedHostPublication) {
            dependsOn(":verifyHostShardRelease")
        }
        doFirst {
            check(isAllowedHostPublication) {
                "$path is outside the exact ${configuredHostShard ?: "unselected"} " +
                    "publication whitelist"
            }
            check(configuredHostShard != null) {
                "$path requires an explicit -PmaterialKolor.hostShard=<host>"
            }
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
        it.name == "publish" ||
            it.name == "publishToMavenLocal" ||
            forbiddenPublicationTaskName.containsMatchIn(it.name)
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
            it.name == "publishToMavenLocal" ||
            forbiddenPublicationTaskName.containsMatchIn(it.name)
    }
    check(requestedFrozenTasks.isEmpty()) {
        "External publication/signing task graph is frozen: " +
            requestedFrozenTasks.joinToString { it.path }
    }

    val requestedLocalPublications = allTasks.filterIsInstance<PublishToMavenLocal>()
    val requestedHostRelease = allTasks.any { task ->
        task.path == ":verifyHostShardRelease" ||
            task.path == ":publishHostShardToMavenLocal" ||
            task is PublishToMavenLocal
    }
    check(!requestedHostRelease || configuredHostShard != null) {
        "Host release tasks require -PmaterialKolor.hostShard=<windows|web-android|apple> " +
            "so root metadata advertises only leaves built by that host"
    }
    val disallowedLocalPublications = requestedLocalPublications.filterNot { task ->
        task.path in allowedMavenLocalPublicationPaths
    }
    check(disallowedLocalPublications.isEmpty()) {
        "Maven-local task graph is outside the exact $configuredHostShard whitelist: " +
            disallowedLocalPublications.joinToString { it.path }
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
                    name == "publish" ||
                        name == "publishToMavenLocal" ||
                        forbiddenPublicationTaskName.containsMatchIn(name)
                }
                .mapNotNull { name -> project.tasks.named(name).get().takeIf { it.enabled } }
        }
        val localTasks = allprojects.flatMap { project ->
            project.tasks.withType<PublishToMavenLocal>().toList()
        }
        val enabledLocalTaskPaths = localTasks.filter { it.enabled }.map { it.path }.toSet()
        val registeredLocalTaskPaths = localTasks.map { it.path }.toSet()

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
        check(enabledLocalTaskPaths == allowedMavenLocalPublicationPaths) {
            "Enabled Maven-local publications differ from the exact " +
                "${configuredHostShard ?: "unselected"} whitelist. " +
                "Expected $allowedMavenLocalPublicationPaths, found $enabledLocalTaskPaths"
        }
        check(registeredLocalTaskPaths.containsAll(allowedMavenLocalPublicationPaths)) {
            "Missing required $configuredHostShard publications: " +
                (allowedMavenLocalPublicationPaths - registeredLocalTaskPaths)
        }

        logger.lifecycle(
            "Publication freeze verified: exactly ${enabledLocalTaskPaths.size} " +
                "${configuredHostShard ?: "unselected"} " +
                "Maven-local publication task(s), zero enabled remote, aggregate, signing, " +
                "or build-scan tasks.",
        )
    }
}

tasks.register("verifyForkDependencyLineage") {
    group = "verification"
    description = "Resolves every selected host compile graph and verifies exact fork lineage."

    doLast {
        val shard = configuredHostShard
            ?: error("verifyForkDependencyLineage requires materialKolor.hostShard")
        val configurationNames = when (shard) {
            "windows" -> listOf("jvmCompileClasspath", "mingwX64CompileKlibraries")
            "web-android" -> listOf(
                "androidCompileClasspath",
                "jsCompileClasspath",
                "wasmJsCompileClasspath",
            )
            "apple" -> listOf(
                "macosArm64CompileKlibraries",
                "iosArm64CompileKlibraries",
                "iosSimulatorArm64CompileKlibraries",
            )
            else -> error("Unsupported host shard: $shard")
        }

        val expectedDirectComponents = setOf(
            "io.github.archivesteak.compose.foundation:foundation:1.12.0-beta02-mingw",
            "io.github.archivesteak.compose.runtime:runtime:1.12.0-beta02-mingw",
            "io.github.archivesteak.compose.ui:ui:1.12.0-beta02-mingw",
            "io.github.archivesteak.compose.material3:material3:1.12.0-alpha03-mingw",
        )
        val allowedJetBrainsComposeInternals = mapOf(
            "org.jetbrains.compose.annotation-internal" to "1.10.0",
            "org.jetbrains.compose.collection-internal" to "1.10.0",
        )

        configurationNames.forEach { configurationName ->
            val configuration = project(":material-kolor")
                .configurations
                .getByName(configurationName)
            val components = configuration.incoming.resolutionResult.allComponents
                .mapNotNull { component -> component.id as? ModuleComponentIdentifier }
                .toSet()
            val resolvedCoordinates = components.map { component ->
                "${component.group}:${component.module}:${component.version}"
            }.toSet()

            val unexpectedUpstreamComponents = components.filter { component ->
                component.group == "com.materialkolor" ||
                    (
                        component.group.startsWith("org.jetbrains.compose") &&
                            allowedJetBrainsComposeInternals[component.group] != component.version
                    )
            }
            check(unexpectedUpstreamComponents.isEmpty()) {
                "$configurationName resolved forbidden or unexpected upstream coordinates: " +
                    unexpectedUpstreamComponents.joinToString()
            }

            val missingDirectComponents = expectedDirectComponents - resolvedCoordinates
            check(missingDirectComponents.isEmpty()) {
                "$configurationName did not resolve the exact fork dependencies: " +
                    "$missingDirectComponents. Resolved fork coordinates: " +
                    resolvedCoordinates.filter { it.startsWith("io.github.archivesteak") }
            }

            val wrongForkVersions = components.filter { component ->
                when {
                    component.group.startsWith("io.github.archivesteak.compose.material3") ->
                        component.version != "1.12.0-alpha03-mingw"
                    component.group.startsWith("io.github.archivesteak.compose") ->
                        component.version != "1.12.0-beta02-mingw"
                    else -> false
                }
            }
            check(wrongForkVersions.isEmpty()) {
                "$configurationName resolved unexpected fork versions: " +
                    wrongForkVersions.joinToString()
            }

            logger.lifecycle(
                "$configurationName lineage verified: ${components.size} module component(s), " +
                    "exact fork Compose versions, and only the two expected JetBrains internal " +
                    "compatibility modules.",
            )
        }

        logger.lifecycle(
            "Dependency lineage verified for all ${configurationNames.size} $shard compile graphs.",
        )
    }
}

tasks.register("verifyHostShardRelease") {
    group = "verification"
    description = "Runs the exact tests, ABI, formatting, lineage, and freeze gates for one host."
    dependsOn(
        ":material-color-utilities:apiCheck",
        ":material-kolor:apiCheck",
        "spotlessCheck",
        "verifyForkDependencyLineage",
        "verifyPublicationFreeze",
    )

    when (configuredHostShard) {
        "windows" -> dependsOn(
            ":material-color-utilities:jvmTest",
            ":material-color-utilities:mingwX64Test",
            ":material-kolor:jvmTest",
            ":material-kolor:mingwX64Test",
            ":mcu-upstream:test",
        )
        "web-android" -> dependsOn(
            ":material-color-utilities:jsBrowserTest",
            ":material-color-utilities:testAndroidHostTest",
            ":material-color-utilities:wasmJsBrowserTest",
            ":material-kolor:jsBrowserTest",
            ":material-kolor:testAndroidHostTest",
            ":material-kolor:wasmJsBrowserTest",
        )
        "apple" -> dependsOn(
            ":material-color-utilities:iosSimulatorArm64Test",
            ":material-color-utilities:macosArm64Test",
            ":material-kolor:iosSimulatorArm64Test",
            ":material-kolor:macosArm64Test",
        )
    }
}

tasks.register("publishHostShardToMavenLocal") {
    group = "publishing"
    description = "Stages and validates exactly one selected host shard; never publishes remotely."
    dependsOn(allowedMavenLocalPublicationPaths)

    doLast {
        val shard = configuredHostShard
            ?: error("publishHostShardToMavenLocal requires materialKolor.hostShard")
        val configuredRepository = System.getProperty("maven.repo.local")
            ?.takeIf { it.isNotBlank() }
            ?: error("An explicit isolated -Dmaven.repo.local path is required")
        val repository = file(configuredRepository).canonicalFile
        val groupDirectory = repository.resolve("io/github/archivesteak/materialkolor")
        val version = "5.0.1-mingw"
        val expectedArtifacts = when (shard) {
            "windows" -> mapOf(
                "material-color-utilities" to "jar",
                "material-color-utilities-jvm" to "jar",
                "material-color-utilities-mingwx64" to "klib",
                "material-kolor" to "jar",
                "material-kolor-jvm" to "jar",
                "material-kolor-mingwx64" to "klib",
            )
            "web-android" -> mapOf(
                "material-color-utilities" to "jar",
                "material-color-utilities-android" to "aar",
                "material-color-utilities-js" to "klib",
                "material-color-utilities-wasm-js" to "klib",
                "material-kolor" to "jar",
                "material-kolor-android" to "aar",
                "material-kolor-js" to "klib",
                "material-kolor-wasm-js" to "klib",
            )
            "apple" -> mapOf(
                "material-color-utilities" to "jar",
                "material-color-utilities-macosarm64" to "klib",
                "material-color-utilities-iosx64" to "klib",
                "material-color-utilities-iosarm64" to "klib",
                "material-color-utilities-iossimulatorarm64" to "klib",
                "material-kolor" to "jar",
                "material-kolor-macosarm64" to "klib",
                "material-kolor-iosarm64" to "klib",
                "material-kolor-iossimulatorarm64" to "klib",
            )
            else -> error("Unsupported host shard: $shard")
        }

        check(groupDirectory.isDirectory) {
            "MaterialKolor group was not staged in $repository"
        }
        val actualArtifacts = groupDirectory.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?.toSet()
            .orEmpty()
        check(actualArtifacts == expectedArtifacts.keys) {
            "Staged MaterialKolor artifacts differ from the exact whitelist. " +
                "Expected ${expectedArtifacts.keys}, found $actualArtifacts"
        }

        expectedArtifacts.forEach { (artifact, primaryExtension) ->
            val versionDirectory = groupDirectory.resolve("$artifact/$version")
            val baseName = "$artifact-$version"
            val requiredFiles = listOf(
                "$baseName.pom",
                "$baseName.module",
                "$baseName.$primaryExtension",
                "$baseName-sources.jar",
                "$baseName-javadoc.jar",
            )
            val missingFiles = requiredFiles.filterNot { versionDirectory.resolve(it).isFile }
            check(missingFiles.isEmpty()) {
                "$artifact publication is incomplete; missing $missingFiles in $versionDirectory"
            }

            val pomText = versionDirectory.resolve("$baseName.pom").readText()
            val expectedName = if (artifact.startsWith("material-color-utilities")) {
                "Material Color Utilities for Kotlin Multiplatform"
            } else {
                "MaterialKolor"
            }
            val expectedLicense = if (artifact.startsWith("material-color-utilities")) {
                "The Apache License, Version 2.0"
            } else {
                "The MIT License"
            }
            listOf(
                "<groupId>io.github.archivesteak.materialkolor</groupId>",
                "<version>$version</version>",
                "<name>$expectedName</name>",
                "<url>https://github.com/archivesteak/MaterialKolor</url>",
                "<name>$expectedLicense</name>",
                "<id>archivesteak</id>",
                "<id>jordond</id>",
                "<connection>scm:git:https://github.com/archivesteak/MaterialKolor.git</connection>",
            ).forEach { requiredText ->
                check(requiredText in pomText) {
                    "$artifact POM is missing required identity: $requiredText"
                }
            }
            check(Regex("<license>").findAll(pomText).count() == 1) {
                "$artifact POM must declare exactly one module-specific license"
            }
            check(Regex("<developer>").findAll(pomText).count() == 2) {
                "$artifact POM must declare exactly the fork maintainer and upstream author"
            }
            for (developerId in listOf("archivesteak", "jordond")) {
                check(Regex("<id>$developerId</id>").findAll(pomText).count() == 1) {
                    "$artifact POM must declare developer $developerId exactly once"
                }
            }

            val moduleText = versionDirectory.resolve("$baseName.module").readText()
            check("org.jetbrains.compose" !in moduleText) {
                "$artifact module metadata leaked upstream Compose coordinates"
            }
            check("com.materialkolor" !in moduleText) {
                "$artifact module metadata leaked upstream MaterialKolor coordinates"
            }
            // The multiplatform root delegates platform-specific dependency metadata to its
            // available-at leaves. Each concrete MaterialKolor leaf must carry the exact forked
            // Material3 coordinate itself.
            if (artifact.startsWith("material-kolor-")) {
                check("io.github.archivesteak.compose.material3" in moduleText) {
                    "$artifact metadata does not retain the forked Material3 lineage"
                }
            }

            if (artifact == "material-kolor" || artifact == "material-color-utilities") {
                val expectedLeaves = expectedArtifacts.keys.filter { candidate ->
                    candidate.startsWith("$artifact-")
                }
                val missingLeaves = expectedLeaves.filterNot(moduleText::contains)
                check(missingLeaves.isEmpty()) {
                    "$artifact root metadata is missing $shard leaves: $missingLeaves"
                }
            }
        }

        logger.lifecycle(
            "Validated exact MaterialKolor $shard shard: ${expectedArtifacts.keys.joinToString()}, " +
                "version $version, complete POM/GMM/source/javadoc/binary files, clean lineage.",
        )
    }
}

apiValidation {
    ignoredProjects.addAll(
        listOf("mcu-upstream"),
    )
    @OptIn(kotlinx.validation.ExperimentalBCVApi::class)
    klib {
        // One common/native ABI baseline is sufficient; Windows owns it so other host shards do
        // not race to produce the same file during release assembly.
        enabled = configuredHostShard == null || configuredHostShard == "windows"
    }
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
    // mcu-upstream is a read-only Java-to-Kotlin oracle copied from Google's reference source;
    // reformatting it would make parity review harder and turn oracle updates into noisy rewrites.
    if (name == "mcu-upstream") return@subprojects

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
