import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.multiplatform.android.library) apply false
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
            namespace = "com.materialkolor"
            withHostTest {}

            optimization {
                consumerKeepRules.publish = true
                consumerKeepRules.file("consumer-rules.pro")
            }

            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_11)
            }
        }

        js {
            browser()
            binaries.executable()
        }

        @OptIn(ExperimentalWasmDsl::class)
        wasmJs {
            browser()
            binaries.executable()
        }

    }

    if (buildsApple) {
        macosArm64()

        listOf(
            iosArm64(),
            iosSimulatorArm64(),
        ).forEach { target ->
            target.binaries.framework {
                baseName = "material-kolor"
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
        commonMain.dependencies {
            // Keep upstream's 5.0.1 fix: the MPP artifact supplies Material3 to non-Android
            // targets, while an Android consumer remains free to select its own AndroidX
            // Material3 version. The compile-only dependency below validates Android source
            // against upstream MaterialKolor's current AndroidX dependency without exporting it.
            implementation(libs.compose.material3.get().toString()) {
                exclude(group = "androidx.compose.material3")
            }
            implementation(libs.compose.foundation)
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.colormath)

            api(project(":material-color-utilities"))
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        if (buildsWebAndroid) {
            androidMain.dependencies {
                compileOnly(libs.androidx.compose.material3)
            }

            named("androidHostTest").dependencies {
                runtimeOnly(libs.androidx.compose.material3)
            }
        }

        if (buildsWindows) {
            jvmTest.dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.compose.ui.test)
            }
        }
    }

    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

mavenPublishing {
    pom {
        name.set("MaterialKolor")
        description.set(
            "Material You dynamic color for Compose Multiplatform. " +
                "Fork of jordond/MaterialKolor 5.0.1 adding Kotlin/Native mingwX64 support.",
        )
        inceptionYear.set("2023")
        url.set("https://github.com/archivesteak/MaterialKolor")
        licenses {
            license {
                name.set("The MIT License")
                url.set("https://opensource.org/license/mit")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("archivesteak")
                name.set("Jack Harrington")
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
