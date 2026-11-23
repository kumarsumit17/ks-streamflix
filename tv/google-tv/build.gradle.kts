// Google TV: what only the TV needs (leanback launcher entry and banner, TV detection).
// One APK serves phone and TV; this library is merged into it by :mobile:android.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.ks.streamflix.tv"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
