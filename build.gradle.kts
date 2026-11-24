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

// ---- SQLite's native libraries: only the ones for the OS an app is built for ---------------------
// sqlite-jdbc carries its native library for every OS (about 11 MB). An artifact transform copies
// the jar without the others for the macOS and Windows apps (and for measuring their size).
abstract class KeepSqliteNatives : TransformAction<KeepSqliteNatives.Params> {
    interface Params : TransformParameters {
        @get:Input val keep: ListProperty<String>
    }

    @get:InputArtifact abstract val input: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val file = input.get().asFile
        if (!file.name.startsWith("sqlite-jdbc")) {
            outputs.file(input)
            return
        }
        val keep = parameters.keep.get().map { "org/sqlite/native/$it/" }
        val out = outputs.file(file.nameWithoutExtension + "-" + parameters.keep.get().joinToString("-") { it.replace('/', '_') } + ".jar")
        java.util.zip.ZipFile(file).use { zip ->
            java.util.zip.ZipOutputStream(out.outputStream()).use { zos ->
                zip.entries().asSequence()
                    .filter { e -> !e.name.startsWith("org/sqlite/native/") || keep.any { e.name.startsWith(it) || it.startsWith(e.name) } }
                    .forEach { e ->
                        zos.putNextEntry(java.util.zip.ZipEntry(e.name))
                        if (!e.isDirectory) zip.getInputStream(e).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
            }
        }
    }
}

val sqliteTrimmed: Attribute<Boolean> = Attribute.of("ks.sqliteTrimmed", Boolean::class.javaObjectType)

/** Resolves [configuration] of [project] with SQLite's natives for [keep] (`Mac/aarch64`) only. */
fun trimSqlite(project: Project, configuration: String, keep: List<String>) = with(project) {
    dependencies.attributesSchema { attribute(sqliteTrimmed) }
    dependencies.artifactTypes.maybeCreate("jar").attributes.attribute(sqliteTrimmed, false)
    dependencies.registerTransform(KeepSqliteNatives::class) {
        from.attribute(sqliteTrimmed, false).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
        to.attribute(sqliteTrimmed, true).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
        parameters.keep.set(keep)
    }
    configurations.matching { it.name == configuration }.configureEach { attributes.attribute(sqliteTrimmed, true) }
}
val macNatives = listOf("Mac/aarch64", "Mac/x86_64")
val windowsNatives = listOf("Windows/x86_64", "Windows/aarch64")
project(":desktop:mac") { pluginManager.withPlugin("org.jetbrains.kotlin.jvm") { trimSqlite(this@project, "runtimeClasspath", macNatives) } }
project(":desktop:windows") { pluginManager.withPlugin("org.jetbrains.kotlin.jvm") { trimSqlite(this@project, "runtimeClasspath", windowsNatives) } }

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
val serverRuntime: Configuration = configurations.create("serverRuntime") { jvmRuntime() }
val desktopRuntime: Configuration = configurations.create("desktopRuntime") { jvmRuntime() }
trimSqlite(project, "desktopRuntime", macNatives)
trimSqlite(project, "serverRuntime", macNatives)
dependencies {
    desktopRuntime(project(":desktop:mac"))
    listOf(":shared:server", ":shared:plugins:web", ":shared:plugins:watch-state", ":shared:plugins:discovery", ":shared:plugins:media-info", ":shared:plugins:subtitles", ":shared:plugins:local-metadata", ":shared:plugins:tmdb", ":shared:plugins:transcode", ":shared:plugins:users", ":shared:plugins:folder-watch").forEach {
        serverRuntime(project(it))
    }
    serverRuntime(libs.slf4j.simple)
}

val checkSize = tasks.register("checkSize") {
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
// The macOS installer's size, after `:desktop:mac:packageReleaseDmg` (not part of `check`: packaging
// needs a non-Homebrew JDK, or -Pcompose.desktop.packaging.checkJdkVendor=false on the owner's Mac).
tasks.register("checkInstallerSize") {
    group = "verification"
    mustRunAfter(":desktop:mac:packageReleaseDmg")
    description = "Fails when the macOS installer is bigger than size.installer.macMb."
    val budgetFile = layout.projectDirectory.file("quality-budgets.properties")
    val dmgDir = layout.projectDirectory.dir("desktop/mac/build/compose/binaries")
    doLast {
        val budgets = java.util.Properties().apply { budgetFile.asFile.inputStream().use(::load) }
        val dmg = dmgDir.asFile.walkTopDown().firstOrNull { it.extension == "dmg" } ?: error("No dmg: run :desktop:mac:packageReleaseDmg first")
        val mb = dmg.length() / 1_048_576.0
        val max = budgets.getProperty("size.installer.macMb").toDouble()
        println("budget size.installer.macMb: %.1f MB of %.0f MB".format(mb, max))
        check(mb <= max) { "The dmg is %.1f MB, over its budget of %.0f MB".format(mb, max) }
    }
}

tasks.register("check") {
    group = "verification"
    description = "Runs every module's checks and the size budget."
    dependsOn(":shared:api:check", ":shared:server:check", ":shared:plugins:web:check", ":shared:plugins:watch-state:check", ":shared:plugins:discovery:check", ":shared:ffmpeg:check", ":shared:plugins:media-info:check", ":shared:plugins:subtitles:check", ":shared:plugins:local-metadata:check", ":shared:plugins:tmdb:check", ":shared:plugins:transcode:check", ":shared:plugins:users:check", ":shared:plugins:folder-watch:check", ":shared:client:check", ":shared:desktop:check", ":desktop:mac:check", ":desktop:windows:check")
    dependsOn(checkSize)
}

