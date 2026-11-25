// The Google TV app: a separate APK and AAB (<app>-v<version>-google-tv.apk) with the TV layout,
// the Leanback launcher entry and the banner. Everything it runs is in :shared:client (androidMain).
// Package name, version (plus the TV offset) and signing: the root build.gradle.kts.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.ks.streamflix.tv"
}
