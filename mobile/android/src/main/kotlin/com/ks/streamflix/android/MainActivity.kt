package com.ks.streamflix.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ks.streamflix.client.AndroidHost
import com.ks.streamflix.client.AndroidJaiPlayer
import com.ks.streamflix.client.AndroidSettings
import com.ks.streamflix.client.GitHubUpdater
import com.ks.streamflix.client.androidUpdater
import com.ks.streamflix.client.plugin.AppUpdatePlugin
import com.ks.streamflix.client.plugin.JaiPlayerPlugin
import com.ks.streamflix.client.plugin.MediaInfoPlugin
import com.ks.streamflix.client.plugin.MetadataPlugin
import com.ks.streamflix.client.plugin.SubtitlesPlugin
import com.ks.streamflix.client.plugin.TranscodePlugin
import com.ks.streamflix.client.plugin.WatchStatePlugin
import com.ks.streamflix.client.ui.AppViewModel
import com.ks.streamflix.client.ui.StreamflixApp
import com.ks.streamflix.tv.isTelevision
import com.ks.utility.appupdate.UpdatePlatform

/** The one activity for phone and TV; the layout follows the device (UiModeManager). */
class MainActivity : ComponentActivity() {
    private var updater: GitHubUpdater? = null

    override fun onResume() {
        super.onResume()
        updater?.onForeground()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = applicationContext
        val vm = ViewModelProvider(this, viewModelFactory {
            initializer {
                val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"
                // On a TV this one APK already asks for the Google TV build, so when separate phone and
                // TV apps ship, copies installed on TVs move to the TV app (KS-Suite platform-folders.md).
                val platform = if (isTelevision(app)) UpdatePlatform.GOOGLE_TV else UpdatePlatform.ANDROID
                val updates = androidUpdater(app, AndroidSettings(app), version, platform).also { updater = it }
                AppViewModel(
                    AndroidHost(app, isTelevision(app), version),
                    // The plugins this app includes (specs/media-server-plan.md).
                    listOf(
                        WatchStatePlugin(), MetadataPlugin(), MediaInfoPlugin(),
                        // Subtitles first: the transcode plugin needs the subtitle choice (burn-in).
                        SubtitlesPlugin(), TranscodePlugin(),
                        JaiPlayerPlugin({ AndroidJaiPlayer.canOpen(app) }) { url, title -> AndroidJaiPlayer.open(app, url, title) },
                        AppUpdatePlugin(updates),
                    ),
                )
            }
        })[AppViewModel::class.java]
        // Back goes back one screen; at the first screen it leaves the app. The player handles Back itself.
        onBackPressedDispatcher.addCallback(this) {
            if (!vm.back()) finish()
        }
        setContent {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) { StreamflixApp(vm) }
        }
    }
}
