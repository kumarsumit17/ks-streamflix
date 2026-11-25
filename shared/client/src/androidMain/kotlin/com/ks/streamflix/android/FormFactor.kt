package com.ks.streamflix.android

import android.content.Context
import android.content.pm.PackageManager
import com.ks.utility.appupdate.UpdatePlatform

/** The manifest meta-data that marks the Google TV app (tv/google-tv); the phone app has none. */
const val FORM_FACTOR_KEY = "com.ks.streamflix.FORM_FACTOR"

/**
 * Whether this is the Google TV app. The phone and TV apps are separate APKs with the same package
 * name, so it is a fact of the build, not of the device: the TV app's manifest says `tv`.
 */
fun isTvBuild(context: Context): Boolean =
    context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData?.getString(FORM_FACTOR_KEY) == "tv"

/** Each app updates to its own build: the TV app to …-google-tv.apk, the phone app to the plain APK. */
fun updatePlatformOf(tv: Boolean): UpdatePlatform = if (tv) UpdatePlatform.GOOGLE_TV else UpdatePlatform.ANDROID
