// Not a plugin: the helper the FFmpeg plugins share (media-info, subtitles, later transcode).
// Finds the owner's FFmpeg and runs it as a separate program; FFmpeg is never bundled.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":shared:api"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
