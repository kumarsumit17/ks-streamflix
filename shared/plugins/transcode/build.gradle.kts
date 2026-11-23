import java.util.Properties

// Plugin `transcode`: direct play, remux or transcode to HLS with the owner's FFmpeg, decided from what the client can play.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

// The same exclusions as shared/server (see there).
configurations.matching { it.name in setOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath") }.configureEach {
    listOf("ktor-client-core", "ktor-client-core-jvm").forEach { exclude(group = "io.ktor", module = it) }
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
}

dependencies {
    implementation(project(":shared:server"))
    implementation(project(":shared:ffmpeg"))
    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":shared:server")))
}

tasks.test {
    useJUnitPlatform()
    // Budgets (quality-budgets.properties) for the real-FFmpeg test.
    val budgets = Properties().apply { rootProject.file("quality-budgets.properties").inputStream().use(::load) }
    budgets.stringPropertyNames().forEach { systemProperty("budget.$it", providers.gradleProperty("budget.$it").orNull ?: budgets.getProperty(it)) }
}
