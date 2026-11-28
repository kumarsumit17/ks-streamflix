// Plugin `users`: more users, the libraries each one sees, and an age limit from certifications.
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
    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":shared:server")))
    testImplementation(project(":shared:plugins:local-metadata"))
}

tasks.test {
    useJUnitPlatform()
}

// Language files, packed at lang/users (the plugin's id) and checked.
val langTool = rootProject.file("ks-utility/typescript/tools/lang.mjs")
val langDir = layout.projectDirectory.dir("src/main/lang")
tasks.processResources { from(langDir) { into("lang/users") } }
val checkLanguageFiles = tasks.register<Exec>("checkLanguageFiles") {
    description = "Checks the language files with the KS-Suite language-file check."
    inputs.dir(langDir)
    commandLine("node", langTool.path, "check", langDir.asFile.path, rootProject.file("shared/api/lang").path)
}
tasks.named("check") { dependsOn(checkLanguageFiles) }
