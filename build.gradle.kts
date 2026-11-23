// Plugins are declared once here and applied in the modules that use them.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// ---- Versioning (never hardcoded) ----------------------------------------------------------------
// Priority: -PversionName / -PversionCode (the release workflow passes them from the tag), then the
// latest git tag, then a development fallback. The modules read these two extras.
fun git(vararg args: String): String? = runCatching {
    val result = providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }
    if (result.result.get().exitValue == 0) result.standardOutput.asText.get().trim().ifEmpty { null } else null
}.getOrNull()

val appVersionName: String =
    providers.gradleProperty("versionName").orNull
        ?: git("describe", "--tags", "--abbrev=0")?.removePrefix("v")
        ?: "0.0.0" // A build from source: no version.

val appVersionCode: Int =
    providers.gradleProperty("versionCode").orNull?.toIntOrNull()
        ?: git("rev-list", "--count", "HEAD")?.toIntOrNull()
        ?: 1

extra["appVersionName"] = appVersionName
extra["appVersionCode"] = appVersionCode

// ---- Size budgets (specs/quality-budgets.md) ------------------------------------------------------
// size.server.maxMb: the server alone (core and server plugins, as --headless runs it).
// size.desktop.maxMb: the whole macOS app (server, window, Compose for this OS).
fun Configuration.jvmRuntime() {
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.attribute, org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.jvm)
    }
    listOf("ktor-client-core", "ktor-client-core-jvm").forEach { exclude(group = "io.ktor", module = it) }
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
    exclude(group = "org.jetbrains.compose.material", module = "material")
    exclude(group = "org.jetbrains.compose.material", module = "material-desktop")
}
val serverRuntime: Configuration by configurations.creating { jvmRuntime() }
val desktopRuntime: Configuration by configurations.creating { jvmRuntime() }
dependencies {
    desktopRuntime(project(":desktop:mac"))
    listOf(":shared:server", ":shared:plugins:web", ":shared:plugins:watch-state", ":shared:plugins:discovery", ":shared:plugins:media-info", ":shared:plugins:subtitles", ":shared:plugins:local-metadata", ":shared:plugins:tmdb", ":shared:plugins:transcode").forEach {
        serverRuntime(project(it))
    }
    serverRuntime(libs.slf4j.simple)
}

val checkSize by tasks.registering {
    description = "Fails when an app is bigger than its budget in quality-budgets.properties."
    group = "verification"
    val budgetFile = layout.projectDirectory.file("quality-budgets.properties")
    val serverJars: FileCollection = serverRuntime
    val desktopJars: FileCollection = desktopRuntime
    val apkDir = layout.projectDirectory.dir("mobile/android/build/outputs/apk/release")
    dependsOn(":mobile:android:assembleRelease")
    val overrides = listOf("size.server.maxMb", "size.desktop.maxMb", "size.apk.maxMb").associateWith { providers.gradleProperty("budget.$it") }
    inputs.file(budgetFile)
    inputs.files(serverJars, desktopJars)
    doLast {
        val budgets = java.util.Properties().apply { budgetFile.asFile.inputStream().use(::load) }
        fun check(key: String, bytes: Long) {
            val max = (overrides.getValue(key).orNull ?: budgets.getProperty(key)).toDouble()
            val mb = bytes / 1_048_576.0
            println("budget $key: %.1f MB of %.0f MB".format(mb, max))
            check(mb <= max) { "$key: %.1f MB is over the budget of %.0f MB".format(mb, max) }
        }
        check("size.server.maxMb", serverJars.files.sumOf { it.length() })
        // Every jar the macOS app runs (Compose's native code for this OS is inside a jar); the Java
        // runtime the installer bundles is measured when the installers exist (stage 6).
        check("size.desktop.maxMb", desktopJars.files.sumOf { it.length() })
        // The release APK, shrunk by R8 (unsigned here; signing adds a few KB).
        val apk = apkDir.asFile.listFiles().orEmpty().single { it.extension == "apk" }
        check("size.apk.maxMb", apk.length())
    }
}
tasks.register("check") {
    group = "verification"
    description = "Runs every module's checks and the size budget."
    dependsOn(":shared:api:check", ":shared:server:check", ":shared:plugins:web:check", ":shared:plugins:watch-state:check", ":shared:plugins:discovery:check", ":shared:ffmpeg:check", ":shared:plugins:media-info:check", ":shared:plugins:subtitles:check", ":shared:plugins:local-metadata:check", ":shared:plugins:tmdb:check", ":shared:plugins:transcode:check", ":shared:client:check", ":shared:desktop:check", ":desktop:mac:check", ":desktop:windows:check")
    dependsOn(checkSize)
}

