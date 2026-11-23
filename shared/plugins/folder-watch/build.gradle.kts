import java.util.Properties

// Plugin `folder-watch`: rescans a library soon after files in its folders change.
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
    testImplementation(testFixtures(project(":shared:server")))
}

tasks.test {
    useJUnitPlatform()
    val budgets = Properties().apply { rootProject.file("quality-budgets.properties").inputStream().use(::load) }
    budgets.stringPropertyNames().forEach { systemProperty("budget.$it", providers.gradleProperty("budget.$it").orNull ?: budgets.getProperty(it)) }
}
