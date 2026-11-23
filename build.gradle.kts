// Plugins are declared once here and applied in the modules that use them.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.sqldelight) apply false
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

// ---- Size budget (specs/quality-budgets.md) -------------------------------------------------------
// The server's runtime files (every jar the macOS app ships) must stay within size.server.maxMb.
val checkSize by tasks.registering {
    description = "Fails when an app is bigger than its budget in quality-budgets.properties."
    group = "verification"
    val budgetFile = layout.projectDirectory.file("quality-budgets.properties")
    val libDir = layout.projectDirectory.dir("desktop/mac/build/install/mac/lib")
    val maxOverride = providers.gradleProperty("budget.size.server.maxMb")
    dependsOn(":desktop:mac:installDist")
    inputs.file(budgetFile)
    inputs.dir(libDir)
    doLast {
        val budgets = java.util.Properties().apply { budgetFile.asFile.inputStream().use(::load) }
        val maxMb = (maxOverride.orNull ?: budgets.getProperty("size.server.maxMb")).toDouble()
        val bytes = libDir.asFile.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }
        val mb = bytes / 1_048_576.0
        println("budget size.server.maxMb: %.1f MB of %.0f MB".format(mb, maxMb))
        check(mb <= maxMb) { "The server is %.1f MB, over its budget of %.0f MB".format(mb, maxMb) }
    }
}
tasks.register("check") {
    group = "verification"
    description = "Runs every module's checks and the size budget."
    dependsOn(":shared:api:check", ":shared:server:check", ":shared:plugins:web:check", ":desktop:mac:check", ":desktop:windows:check")
    dependsOn(checkSize)
}
