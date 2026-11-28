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

// ---- The two Android apps: phone (mobile/android) and Google TV (tv/google-tv) ---------------------
// Separate APKs and AABs with the same package name, version and keystore, so each updates over
// itself. Google Play needs a different versionCode for every bundle of one app: the TV app adds
// TV_CODE_OFFSET, which also stops the phone build being installed over the TV build.
val TV_CODE_OFFSET = 1_000_000_000

// ---- DEV and production side by side (specs/dev-variant.md) ----------------------------------------
// -PksVariant=dev (the release workflow's manual run) builds the DEV variant, which installs next to
// the production app and shares nothing with it; -PksDevVersion=X.Y.Z-dev+<commit> is its version.
val devVariant = providers.gradleProperty("ksVariant").orNull == "dev"
extra["ksDev"] = devVariant

subprojects {
    plugins.withId("com.android.application") {
        val tv = path == ":tv:google-tv"
        val keystoreProperties = java.util.Properties().apply {
            val file = rootProject.file("keystore.properties")
            if (file.exists()) file.inputStream().use(::load)
        }
        // Blank counts as missing: GitHub Actions passes an unset secret as an empty string.
        fun signingValue(env: String, key: String): String? =
            providers.environmentVariable(env).orNull?.takeIf { it.isNotBlank() }
                ?: keystoreProperties.getProperty(key)?.takeIf { it.isNotBlank() }
        val catalog = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
            compileSdk = catalog.findVersion("androidCompileSdk").get().requiredVersion.toInt()
            defaultConfig {
                applicationId = "com.ks.streamflix"
                minSdk = catalog.findVersion("androidMinSdk").get().requiredVersion.toInt()
                targetSdk = catalog.findVersion("androidTargetSdk").get().requiredVersion.toInt()
                versionCode = (rootProject.extra["appVersionCode"] as Int) + if (tv) TV_CODE_OFFSET else 0
                versionName = rootProject.extra["appReportedVersion.${if (tv) "google-tv" else "android"}"] as String
                if (devVariant) applicationIdSuffix = ".dev"
                manifestPlaceholders["appLabel"] = if (devVariant) "KS StreamFlix DEV" else "KS StreamFlix"
                manifestPlaceholders["tvBanner"] = if (devVariant) "@drawable/tv_banner_dev" else "@drawable/tv_banner"
            }
            // The DEV icon colours and TV banner; app resources win over :shared:client's.
            if (devVariant) sourceSets.getByName("main").res.srcDir(rootProject.file("shared/android-dev/res"))
            signingValue("SIGNING_STORE_FILE", "storeFile")?.let { storePath ->
                signingConfigs.create("release") {
                    storeFile = file(storePath)
                    storePassword = signingValue("SIGNING_STORE_PASSWORD", "storePassword")
                    keyAlias = signingValue("SIGNING_KEY_ALIAS", "keyAlias")
                    keyPassword = signingValue("SIGNING_KEY_PASSWORD", "keyPassword")
                }
            }
            buildTypes.getByName("release") {
                // R8 keeps each APK small.
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), rootProject.file("shared/client/proguard-rules.pro"))
                signingConfig = signingConfigs.findByName("release")
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            androidResources { localeFilters += listOf("en", "hi") }
        }
        dependencies.add("implementation", project(":shared:client"))
    }
}

// ---- Versioning (never hardcoded) ----------------------------------------------------------------
// Each platform has its own version (specs/plan-per-platform.md): a tag <platform>-vX.Y.Z releases
// that platform, a plain vX.Y.Z every platform. Priority: -PversionName / -PversionCode (the release
// workflow passes them from the tag), then the higher of the platform's latest own tag and the latest
// plain tag, then a development fallback. The modules read appVersionName.<platform> and appVersionCode.
fun git(vararg args: String): String? = runCatching {
    val result = providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }
    if (result.result.get().exitValue == 0) result.standardOutput.asText.get().trim().ifEmpty { null } else null
}.getOrNull()

fun versionParts(v: String) = v.split('.').map { it.toIntOrNull() ?: 0 }
fun higherVersion(a: String, b: String): String {
    val x = versionParts(a)
    val y = versionParts(b)
    for (i in 0 until maxOf(x.size, y.size)) {
        val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
        if (d != 0) return if (d > 0) a else b
    }
    return a
}
val plainTagVersion: String? = git("describe", "--tags", "--abbrev=0", "--match", "v[0-9]*")?.removePrefix("v")

/** Platforms: android, google-tv, mac, windows (the tag prefixes). */
fun appVersionNameOf(platform: String): String =
    providers.gradleProperty("versionName").orNull
        ?: listOfNotNull(
            plainTagVersion,
            git("describe", "--tags", "--abbrev=0", "--match", "$platform-v[0-9]*")?.removePrefix("$platform-v"),
        ).reduceOrNull(::higherVersion)
        ?: "0.0.0" // A build from source: no version.

val appVersionCode: Int =
    providers.gradleProperty("versionCode").orNull?.toIntOrNull()
        ?: git("rev-list", "--count", "HEAD")?.toIntOrNull()
        ?: 1

// appVersionName.<platform> is X.Y.Z (the installers need it); appReportedVersion.<platform> is what
// the app calls itself: the DEV version in a DEV build.
for (platform in listOf("android", "google-tv", "mac", "windows")) {
    val version = appVersionNameOf(platform)
    extra["appVersionName.$platform"] = version
    extra["appReportedVersion.$platform"] =
        if (!devVariant) version
        else providers.gradleProperty("ksDevVersion").orNull?.takeIf { it.isNotBlank() } ?: "${version.substringBefore('-')}-dev"
}
extra["appVersionCode"] = appVersionCode

// ---- Measurements (specs/quality-budgets.md): reported, never failing the build ------------------
// Tests (Measurement.record) and the size reports write one file per value into this folder; the
// `measurements` task prints them all, and adds them to the run's summary on GitHub Actions.
val measurementsDir = layout.buildDirectory.dir("measurements")
val measurements = tasks.register("measurements") {
    group = "verification"
    description = "Prints the measured sizes and speeds."
    val dir = measurementsDir
    val summary = providers.environmentVariable("GITHUB_STEP_SUMMARY")
    val os = providers.systemProperty("os.name")
    doLast {
        val lines = dir.get().asFile.listFiles().orEmpty().sortedBy { it.name }
            .map { "${it.nameWithoutExtension}: ${it.readText()}" }
        if (lines.isEmpty()) return@doLast
        println("Measurements (${os.get()}):")
        lines.forEach { println("  $it") }
        summary.orNull?.let { path ->
            File(path).appendText("### Measurements (${os.get()})\n\n" + lines.joinToString("") { "- $it\n" } + "\n")
        }
    }
}
subprojects {
    tasks.withType<Test>().configureEach {
        systemProperty("measurements.dir", measurementsDir.get().asFile.path)
        finalizedBy(measurements)
    }
}
// An object, not a function of this script, so the tasks work with the configuration cache.
object SizeFiles {
    fun write(dir: File, name: String, bytes: Long) {
        dir.mkdirs()
        dir.resolve("$name.txt").writeText("%.1f MB".format(java.util.Locale.ROOT, bytes / 1_048_576.0))
    }
}

// size.server: the server alone (core and server plugins, as --headless runs it).
// size.desktop: the whole macOS app (server, window, Compose for this OS).
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

val sizeReport = tasks.register("sizeReport") {
    description = "Measures the sizes of the apps."
    group = "verification"
    val serverJars: FileCollection = serverRuntime
    val desktopJars: FileCollection = desktopRuntime
    val apkDirs = listOf("android" to "mobile/android", "googleTv" to "tv/google-tv")
        .map { (name, folder) -> name to layout.projectDirectory.dir("$folder/build/outputs/apk/release") }
    dependsOn(":mobile:android:assembleRelease", ":tv:google-tv:assembleRelease")
    inputs.files(serverJars, desktopJars)
    val out = measurementsDir
    finalizedBy(measurements)
    doLast {
        val dir = out.get().asFile
        SizeFiles.write(dir, "size.server", serverJars.files.sumOf { it.length() })
        // Every jar the macOS app runs (Compose's native code for this OS is inside a jar).
        SizeFiles.write(dir, "size.desktop", desktopJars.files.sumOf { it.length() })
        // The release APKs, shrunk by R8 (unsigned here; signing adds a few KB).
        apkDirs.forEach { (name, apks) -> apks.asFile.listFiles().orEmpty().filter { it.extension == "apk" }.forEach { SizeFiles.write(dir, "size.apk.$name", it.length()) } }
    }
}
// The installers' sizes, after `packageReleaseDmg` or `packageReleaseMsi` (not part of `check`:
// packaging needs a non-Homebrew JDK, or -Pcompose.desktop.packaging.checkJdkVendor=false on the
// owner's Mac).
tasks.register("installerSizeReport") {
    group = "verification"
    description = "Measures the sizes of the installers that were built."
    mustRunAfter(":desktop:mac:packageReleaseDmg", ":desktop:windows:packageReleaseMsi")
    val installers = listOf("mac" to "dmg", "windows" to "msi")
        .map { (os, type) -> type to layout.projectDirectory.dir("desktop/$os/build/compose/binaries") }
    val out = measurementsDir
    finalizedBy(measurements)
    doLast {
        installers.forEach { (type, folder) ->
            folder.asFile.walkTopDown().firstOrNull { it.extension == type }?.let { SizeFiles.write(out.get().asFile, "size.installer.$type", it.length()) }
        }
    }
}

tasks.register("check") {
    group = "verification"
    description = "Runs every module's checks and reports the measurements."
    dependsOn(":shared:api:check", ":shared:server:check", ":shared:plugins:web:check", ":shared:plugins:watch-state:check", ":shared:plugins:discovery:check", ":shared:ffmpeg:check", ":shared:plugins:media-info:check", ":shared:plugins:subtitles:check", ":shared:plugins:local-metadata:check", ":shared:plugins:tmdb:check", ":shared:plugins:transcode:check", ":shared:plugins:users:check", ":shared:plugins:folder-watch:check", ":shared:client:check", ":shared:desktop:check", ":mobile:android:check", ":tv:google-tv:check", ":desktop:mac:check", ":desktop:windows:check")
    dependsOn(sizeReport)
}

