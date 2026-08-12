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

    sourceSets {
        androidMain.dependencies {
            implementation(libs.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.room.runtime)
            implementation(libs.room.ktx)
            implementation(libs.work.runtime.ktx)
            implementation(libs.okhttp)
            // Resolved via JitPack normally; substituted for the local checkout when one exists
            // as a sibling directory — see the root settings.gradle.kts.
            implementation("com.github.automaciej:task-sync-kotlin:v0.2.1")
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }
    }
}

// KSP generates Room's implementation code — not for any entity defined in this module (it
// defines none of its own), but for TaskSyncDatabase's Room.databaseBuilder(...) call site to
// resolve correctly, matching the same inclusion in task-sync-kotlin's and
// github-issues-kotlin's own build.gradle.kts.
dependencies {
    add("kspAndroid", libs.room.compiler)
}
