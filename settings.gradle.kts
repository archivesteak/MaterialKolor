pluginManagement {
    val explicitForkRepositoryPath = System.getProperty("maven.repo.local")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: error(
            "This build requires an explicit isolated fork repository. " +
                "Pass -Dmaven.repo.local=<absolute repository path>; " +
                "ambient ~/.m2 resolution is disabled.",
        )
    val suppliedForkRepository = java.io.File(explicitForkRepositoryPath)
    check(suppliedForkRepository.isAbsolute) {
        "The isolated fork repository path must be absolute: $explicitForkRepositoryPath"
    }
    val explicitForkRepository = suppliedForkRepository.canonicalFile
    val ambientMavenDirectory = file(System.getProperty("user.home"))
        .resolve(".m2")
        .canonicalFile
    check(explicitForkRepository.isDirectory) {
        "The isolated fork repository must be an existing absolute directory: " +
            explicitForkRepository.path
    }
    check(!explicitForkRepository.toPath().startsWith(ambientMavenDirectory.toPath())) {
        "The isolated fork repository must not be inside the ambient Maven directory " +
            "${ambientMavenDirectory.path}: ${explicitForkRepository.path}"
    }

    repositories {
        // The forked Compose Gradle plugin uses its own plugin id and is assembled only in the
        // selected local Maven repository while the publication freeze is active.
        exclusiveContent {
            forRepository {
                maven {
                    name = "isolatedForkRepository"
                    url = uri(explicitForkRepository)
                }
            }
            filter { includeGroupByRegex("io\\.github\\.archivesteak\\.compose(\\..+)?") }
        }
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    val explicitForkRepositoryPath = System.getProperty("maven.repo.local")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: error(
            "This build requires an explicit isolated fork repository. " +
                "Pass -Dmaven.repo.local=<absolute repository path>; " +
                "ambient ~/.m2 resolution is disabled.",
        )
    val suppliedForkRepository = java.io.File(explicitForkRepositoryPath)
    check(suppliedForkRepository.isAbsolute) {
        "The isolated fork repository path must be absolute: $explicitForkRepositoryPath"
    }
    val explicitForkRepository = suppliedForkRepository.canonicalFile
    val ambientMavenDirectory = file(System.getProperty("user.home"))
        .resolve(".m2")
        .canonicalFile
    check(explicitForkRepository.isDirectory) {
        "The isolated fork repository must be an existing absolute directory: " +
            explicitForkRepository.path
    }
    check(!explicitForkRepository.toPath().startsWith(ambientMavenDirectory.toPath())) {
        "The isolated fork repository must not be inside the ambient Maven directory " +
            "${ambientMavenDirectory.path}: ${explicitForkRepository.path}"
    }

    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    @Suppress("UnstableApiUsage")
    repositories {
        // Resolve fork artifacts only from the caller-selected isolated repository. Exclusive
        // content also prevents any public repository from satisfying an incomplete fork group.
        exclusiveContent {
            forRepository {
                maven {
                    name = "isolatedForkRepository"
                    url = uri(explicitForkRepository)
                }
            }
            filter { includeGroupByRegex("io\\.github\\.archivesteak(\\..+)?") }
        }
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

// Keep the plugin pinned so Gradle's --scan flag cannot auto-inject a newer uploader before this
// settings script executes. Publication is unconditionally disabled, and --scan is rejected above.
plugins {
    id("com.gradle.develocity") version "4.4.0"
}

develocity {
    buildScan {
        publishing.onlyIf { false }
    }
}

check(!gradle.startParameter.isBuildScan) {
    "External build-scan publication is frozen; remove --scan"
}

rootProject.name = "MaterialKolor"

include(
    ":material-kolor",
    ":material-color-utilities",
    ":mcu-upstream",
)
