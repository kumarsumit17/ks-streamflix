// Plugin `web`: setup, sign-in, libraries, browsing and playing in a browser.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

// The same exclusions as shared/server (see there).
configurations.matching { it.name in setOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath") }.configureEach {
    listOf("ktor-client-core", "ktor-client-core-jvm").forEach { exclude(group = "io.ktor", module = it) }
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
}

dependencies {
    implementation(project(":shared:server"))
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

// Language files, packed at lang/web (the plugin's id) and checked like the core's.
val langTool = rootProject.file("ks-utility/typescript/tools/lang.mjs")
val langDir = layout.projectDirectory.dir("src/main/lang")
tasks.processResources { from(langDir) { into("lang/web") } }
val checkLanguageFiles by tasks.registering(Exec::class) {
    description = "Checks the language files with the KS-Suite language-file check."
    inputs.dir(langDir)
    commandLine("node", langTool.path, "check", langDir.asFile.path, rootProject.file("shared/api/lang").path)
}
tasks.named("check") { dependsOn(checkLanguageFiles) }
