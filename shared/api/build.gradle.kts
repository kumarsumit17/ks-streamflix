// The API's wire models (/api/v1), shared by the server and every app. Kotlin Multiplatform: the
// JVM target now (server and desktop); the Android target is added with the apps in stage 2.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    jvm()
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
    }
}
