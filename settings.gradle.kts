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
include(":shared:ffmpeg")
include(":shared:plugins:web", ":shared:plugins:watch-state", ":shared:plugins:discovery")
include(":shared:plugins:media-info", ":shared:plugins:subtitles", ":shared:plugins:local-metadata", ":shared:plugins:tmdb", ":shared:plugins:transcode", ":shared:plugins:users", ":shared:plugins:folder-watch")
include(":shared:client", ":shared:desktop")
include(":desktop:mac", ":desktop:windows")
include(":mobile:android", ":tv:google-tv")
