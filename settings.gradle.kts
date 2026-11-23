pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ks-streamflix"

// Generic KS-Suite helpers, from the pinned submodule (com.ks:utility).
includeBuild("ks-utility/kotlin")

include(":shared:api", ":shared:server")
include(":shared:plugins:web")
include(":desktop:mac", ":desktop:windows")
