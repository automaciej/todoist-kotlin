import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlinSerialization)
    `maven-publish`
}

group = "pl.blizinski"
version = "0.1.0"

// Publishing more than one target breaks JitPack's Gradle module metadata for downstream KMP
// consumers (see task-sync-kotlin's build.gradle.kts) — TaskCompass only consumes this
// library's android target via JitPack, so wasmJs is skipped for JitPack builds (set via
// `-PjitpackBuild=true` in jitpack.yml). Local/POC development on the target is unaffected.
val isJitpackBuild = project.hasProperty("jitpackBuild")

kotlin {
    android {
        namespace = "pl.blizinski.todoiststore"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }

        withHostTestBuilder {}.configure {
            isReturnDefaultValues = true
        }
    }

    // wasmJs target — the Ktor-based `TodoistNetworkSourceWasm` + `todoistWasmStore` counterpart
    // of the Android OkHttp path. Additive only. Skipped on JitPack — see isJitpackBuild above.
    if (!isJitpackBuild) {
        @OptIn(ExperimentalWasmDsl::class)
        wasmJs {
            browser()
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            // Resolved via JitPack normally; substituted for the local checkout when one exists
            // as a sibling directory — see the root settings.gradle.kts.
            implementation("com.github.automaciej:task-sync-kotlin:v0.4.0")
        }
        androidMain.dependencies {
            implementation(libs.room.runtime)
            implementation(libs.room.ktx)
            implementation(libs.work.runtime.ktx)
            implementation(libs.okhttp)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }
        if (!isJitpackBuild) {
            val wasmJsMain by getting {
                dependencies {
                    implementation(libs.ktor.client.core)
                    implementation(libs.ktor.client.js)
                    implementation(libs.ktor.client.content.negotiation)
                    implementation(libs.ktor.serialization.kotlinx.json)
                    implementation("com.github.automaciej:task-sync-kotlin:v0.4.0")
                }
            }
        }
    }
}

// KSP generates Room's implementation code for TaskSyncDatabase's Room.databaseBuilder(...)
// call site — matching the same inclusion in task-sync-kotlin's and github-issues-kotlin's
// own build.gradle.kts.
dependencies {
    add("kspAndroid", libs.room.compiler)
}

// Don't publish Gradle Module Metadata — JitPack serves the synthetic flat coordinate
// (com.github.automaciej:todoist-kotlin) as POM + stub jar, and a stray .module file makes its
// flat-coordinate synthesis emit the POM without the stub jar it references, breaking downstream
// resolution ("Could not find todoist-kotlin-<tag>.jar"). Nothing consuming this library needs the .module.
tasks.withType<GenerateModuleMetadata>().configureEach { enabled = false }
