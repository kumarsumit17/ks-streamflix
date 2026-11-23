// macOS entry point. Stage 1 runs the server; the desktop window and the installer come later
// (specs/media-server-plan.md).
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(17) }

// The same exclusions as shared/server (see there).
configurations.matching { it.name in setOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath") }.configureEach {
    listOf("ktor-client-core", "ktor-client-core-jvm").forEach { exclude(group = "io.ktor", module = it) }
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
}

dependencies {
    implementation(project(":shared:server"))
    // The plugins this app includes (specs/media-server-plan.md); each one left out is left out of the app.
    implementation(project(":shared:plugins:web"))
    runtimeOnly(libs.slf4j.simple)
}

application {
    mainClass = "com.ks.streamflix.mac.MainKt"
    applicationDefaultJvmArgs = listOf("-Dstreamflix.version=${rootProject.extra["appVersionName"]}")
}
