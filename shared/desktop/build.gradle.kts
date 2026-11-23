// The desktop app shared by macOS and Windows: the server and its window (the shared app beside
// the server's address). The entry points in desktop/mac and desktop/windows choose the plugins.
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
    api(project(":shared:server"))
    api(project(":shared:client"))
    implementation(compose.desktop.common)
    implementation(libs.compose.material3)
    implementation(libs.compose.resources)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.swing)
}
