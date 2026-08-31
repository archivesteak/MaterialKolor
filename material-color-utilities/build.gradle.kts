@file:Suppress("OPT_IN_USAGE")

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.multiplatform.android.library) apply false
    alias(libs.plugins.poko)
    alias(libs.plugins.dokka)
    alias(libs.plugins.publish)
}

val hostShard = providers.gradleProperty("materialKolor.hostShard").orElse("all").get()
check(hostShard in setOf("all", "windows", "web-android", "apple")) {
    "materialKolor.hostShard must be one of all, windows, web-android, or apple: $hostShard"
}
val buildsWindows = hostShard == "all" || hostShard == "windows"
val buildsWebAndroid = hostShard == "all" || hostShard == "web-android"
val buildsApple = hostShard == "all" || hostShard == "apple"

if (buildsWebAndroid) {
    pluginManager.apply(libs.plugins.multiplatform.android.library.get().pluginId)
}

kotlin {
    explicitApi()

    applyDefaultHierarchyTemplate()

    if (buildsWebAndroid) {
        targets.withType<KotlinMultiplatformAndroidLibraryTarget>().configureEach {
            compileSdk = libs.versions.sdk.compile.get().toInt()
            minSdk = libs.versions.sdk.min.get().toInt()
            namespace = "com.materialkolor.colorutilities"
            withHostTest {}

            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_11)
            }
        }

        js {
            browser()
        }

        wasmJs {
            browser()
        }

    }

    if (buildsApple) {
        macosArm64()

        listOf(
            iosX64(),
            iosArm64(),
            iosSimulatorArm64(),
        ).forEach { target ->
            target.binaries.framework {
                baseName = "material-color-utilities"
            }
        }
    }

    if (buildsWindows) {
        jvm {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_11)
            }
        }

        mingwX64()
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }

    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

mavenPublishing {
    pom {
        name.set("Material Color Utilities for Kotlin Multiplatform")
        description.set(
            "Kotlin Multiplatform port of Google's Material Color Utilities, " +
                "including Kotlin/Native mingwX64 support.",
        )
        inceptionYear.set("2023")
        url.set("https://github.com/archivesteak/MaterialKolor")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("archivesteak")
                name.set("archivesteak")
                url.set("https://github.com/archivesteak")
            }
            developer {
                id.set("jordond")
                name.set("Jordon de Hoog")
                url.set("https://github.com/jordond")
            }
        }
        scm {
            url.set("https://github.com/archivesteak/MaterialKolor")
            connection.set("scm:git:https://github.com/archivesteak/MaterialKolor.git")
            developerConnection.set("scm:git:ssh://git@github.com/archivesteak/MaterialKolor.git")
        }
    }
}
