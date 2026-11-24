import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// The apps' shared code: the API client, state (ViewModel + StateFlow), the client plugin API and
// the Compose Multiplatform screens, used by macOS, Windows, Android and Google TV. Platform
// folders hold only entry points and packaging.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvmToolchain(17)

    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    android {
        namespace = "com.ks.streamflix.client"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
        androidResources { enable = true }
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared:api"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.compose.resources)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // JVM code shared by desktop and Android: HTTP and discovery on java.net (no HTTP library).
        val jvmSharedMain = create("jvmSharedMain") {
            dependsOn(commonMain.get())
            // ks-utility's app-update rules (JVM only, so not in commonMain).
            dependencies { implementation(libs.ks.utility) }
        }
        val desktopMain = getByName("desktopMain") {
            dependsOn(jvmSharedMain)
            dependencies { implementation(libs.kotlinx.coroutines.swing) }
        }
        val desktopTest = getByName("desktopTest") {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":shared:plugins:watch-state"))
                implementation(project(":shared:plugins:local-metadata"))
                implementation(project(":shared:plugins:subtitles"))
                implementation(project(":shared:plugins:transcode"))
                implementation(project(":shared:ffmpeg"))
            }
        }
        androidMain.get().dependsOn(jvmSharedMain)
        androidMain.dependencies {
            implementation(libs.media3.exoplayer)
            // The server's remux and transcode streams (plugin `transcode`).
            implementation(libs.media3.exoplayer.hls)
            implementation(libs.media3.ui.compose)
            implementation(libs.androidx.activity.compose)
            // The activity of both apps lives here; app updates use ks-utility's rules.
            implementation(libs.ks.utility)
            implementation(libs.kotlinx.coroutines.android)
        }
    }
}

dependencies {
    // The client is tested against the real server (TestServer).
    "desktopTestImplementation"(testFixtures(project(":shared:server")))
    // Decoding images on desktop needs Compose's native graphics for this OS (the apps have it already).
    "desktopTestImplementation"(compose.desktop.currentOs)
}

// Tests run the real server; it leaves out the same unused parts as the apps.
configurations.matching { it.name == "desktopTestRuntimeClasspath" || it.name == "desktopTestCompileClasspath" }.configureEach {
    listOf("ktor-client-core", "ktor-client-core-jvm").forEach { exclude(group = "io.ktor", module = it) }
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
}

// ---- Interface text: ks-utility language files → Compose resources ------------------------------
// The app's en.json / hi.json and ks-utility's shared catalogue become strings.xml escaped for
// Compose Multiplatform resources (`lang.mjs compose`), read on every platform. Needs Node 23.6+.
val langTool = rootProject.file("ks-utility/typescript/tools/lang.mjs")
val langDir = layout.projectDirectory.dir("src/commonMain/lang")
// The API's error texts, shared with the server.
val apiLangDir = rootProject.layout.projectDirectory.dir("shared/api/lang")
val sharedLangDir = rootProject.layout.projectDirectory.dir("ks-utility/lang/common")
val langResources = layout.buildDirectory.dir("generated/lang/composeResources")

val languageResources = tasks.register<Exec>("languageResources") {
    description = "Converts the language files into Compose string resources."
    inputs.dir(langDir)
    inputs.dir(apiLangDir)
    inputs.dir(sharedLangDir)
    outputs.dir(langResources)
    val out = langResources.get().asFile
    commandLine("node", langTool.path, "compose", langDir.asFile.path, apiLangDir.asFile.path, "--out", out.path)
}

val checkLanguageFiles = tasks.register<Exec>("checkLanguageFiles") {
    description = "Checks the language files with the KS-Suite language-file check."
    inputs.dir(langDir)
    inputs.dir(sharedLangDir)
    commandLine("node", langTool.path, "check", langDir.asFile.path, apiLangDir.asFile.path)
}

tasks.named("check") { dependsOn(checkLanguageFiles) }

compose.resources {
    publicResClass = true
    packageOfResClass = "com.ks.streamflix.client.resources"
    generateResClass = always
    customDirectory(
        sourceSetName = "commonMain",
        directoryProvider = languageResources.map { langResources.get() },
    )
}

tasks.withType<Test>().configureEach {
    val budgets = Properties().apply { rootProject.file("quality-budgets.properties").inputStream().use(::load) }
    budgets.stringPropertyNames().forEach { systemProperty("budget.$it", budgets.getProperty(it)) }
}
