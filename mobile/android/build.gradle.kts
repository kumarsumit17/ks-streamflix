// The phone app: an APK and AAB (<app>-v<version>.apk) with the phone launcher entry. Everything
// it runs is in :shared:client (androidMain). Package name, version and signing: the root
// build.gradle.kts, shared with the Google TV app.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.ks.streamflix.phone"
}
