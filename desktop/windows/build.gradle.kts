// Windows entry point: the server with its window (shared/desktop). The installer comes in stage 6.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin { jvmToolchain(17) }

// The same exclusions as shared/server (see there).
configurations.matching { it.name in setOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath") }.configureEach {
    listOf("ktor-client-core", "ktor-client-core-jvm").forEach { exclude(group = "io.ktor", module = it) }
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
    // Compose Desktop brings Material 2; the apps use Material 3 only.
    exclude(group = "org.jetbrains.compose.material", module = "material")
    exclude(group = "org.jetbrains.compose.material", module = "material-desktop")
}

dependencies {
    implementation(project(":shared:desktop"))
    implementation(compose.desktop.currentOs)
    // The plugins this app includes (specs/media-server-plan.md); each one left out is left out of the app.
    implementation(project(":shared:plugins:web"))
    implementation(project(":shared:plugins:watch-state"))
    implementation(project(":shared:plugins:discovery"))
    implementation(project(":shared:plugins:local-metadata"))
    implementation(project(":shared:plugins:tmdb"))
    implementation(project(":shared:plugins:media-info"))
    implementation(project(":shared:plugins:subtitles"))
    implementation(project(":shared:plugins:transcode"))
    implementation(project(":shared:plugins:users"))
    implementation(project(":shared:plugins:folder-watch"))
    runtimeOnly(libs.slf4j.simple)
}

compose.desktop {
    application {
        mainClass = "com.ks.streamflix.windows.MainKt"
        jvmArgs += listOf("-Dstreamflix.version=${rootProject.extra["appVersionName"]}")
    }
}
