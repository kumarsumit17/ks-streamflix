// Plugin `media-info`: duration, codecs and tracks of each item, read with the owner's ffprobe after every scan.
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

tasks.test { useJUnitPlatform() }
