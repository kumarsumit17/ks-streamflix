package com.ks.streamflix.tv

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration

/** Whether the app runs on a TV (Google TV, Android TV); the layout switches at runtime from this. */
fun isTelevision(context: Context): Boolean =
    context.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
