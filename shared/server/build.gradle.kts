// The server core: libraries, scanning, database, the owner account, the HTTP API, direct play
// and the plugin API. Nothing else: every other feature is a plugin (shared/plugins/<id>).
// Used by the macOS and Windows apps.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
    // TestServer and test media for the plugins' tests.
    `java-test-fixtures`
}

kotlin { jvmToolchain(17) }

// Ktor 3.6's server core pulls in the HTTP client and Kotlin reflection, which this server does
// not use (its OpenAPI parts are needed by content negotiation, so they stay); leaving them out keeps the apps small.
configurations.matching { it.name in setOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath", "testFixturesCompileClasspath", "testFixturesRuntimeClasspath") }.configureEach {
    exclude(group = "io.ktor", module = "ktor-client-core")
    exclude(group = "io.ktor", module = "ktor-client-core-jvm")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
}

dependencies {
    api(project(":shared:api"))
    api(libs.kotlinx.coroutines.core)
    api(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.partial.content)
    // Plugins use authenticate(SESSION_AUTH) and principal().
    api(libs.ktor.server.auth)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.sqldelight.sqlite.driver)
    // The language-file look-up (Translator) from the suite's shared helpers.
    implementation(libs.ks.utility)
    testImplementation(kotlin("test"))
    testFixturesImplementation(kotlin("test"))
}

sqldelight {
    databases {
        create("StreamflixDb") {
            packageName.set("com.ks.streamflix.server.data")
            // Every released schema is kept here; migrations (N.sqm) are checked against them.
            schemaOutputDirectory.set(file("src/main/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}

tasks.test {
    useJUnitPlatform()
    // Failures show their message.
    testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// ---- Interface text: the API's error texts and ks-utility's shared catalogue --------------------
// The error codes the API sends are texts in shared/api/lang, used by the server and the apps.
// Packed as resources (lang/core, lang/common) and read at run time by Strings. The check needs
// Node 23.6+.
val langTool = rootProject.file("ks-utility/typescript/tools/lang.mjs")
val langDir = rootProject.layout.projectDirectory.dir("shared/api/lang")
val sharedLangDir = rootProject.layout.projectDirectory.dir("ks-utility/lang/common")

tasks.processResources {
    from(langDir) { into("lang/core") }
    from(sharedLangDir) { into("lang/common") }
}

val checkLanguageFiles = tasks.register<Exec>("checkLanguageFiles") {
    description = "Checks the language files with the KS-Suite language-file check."
    inputs.dir(langDir)
    inputs.dir(sharedLangDir)
    commandLine("node", langTool.path, "check", langDir.asFile.path)
}

tasks.named("check") { dependsOn(checkLanguageFiles) }
