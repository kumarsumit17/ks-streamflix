// Not a plugin: the helper the FFmpeg plugins share (media-info, subtitles, later transcode).
// Finds FFmpeg (the copy bundled in the desktop apps, gradle/bundled-ffmpeg.gradle.kts, or one
// installed by hand) and runs it as a separate program. Its test fixtures check a bundled build.
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":shared:api"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testFixturesImplementation(kotlin("test"))
    testFixturesImplementation(libs.kotlinx.coroutines.core)
}

tasks.test { useJUnitPlatform() }
