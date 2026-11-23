package com.ks.streamflix.mac

import com.ks.streamflix.client.DesktopJaiPlayer
import com.ks.streamflix.client.plugin.JaiPlayerPlugin
import com.ks.streamflix.client.plugin.MediaInfoPlugin as MediaInfoClient
import com.ks.streamflix.client.plugin.MetadataPlugin as MetadataClient
import com.ks.streamflix.client.plugin.WatchStatePlugin as WatchStateClient
import com.ks.streamflix.desktop.runDesktopApp
import com.ks.streamflix.plugins.discovery.DiscoveryPlugin
import com.ks.streamflix.plugins.localmetadata.LocalMetadataPlugin
import com.ks.streamflix.plugins.mediainfo.MediaInfoPlugin
import com.ks.streamflix.plugins.subtitles.SubtitlesPlugin
import com.ks.streamflix.plugins.tmdb.TmdbPlugin
import com.ks.streamflix.plugins.transcode.TranscodePlugin
import com.ks.streamflix.plugins.watchstate.WatchStatePlugin
import com.ks.streamflix.plugins.web.WebPlugin
import java.nio.file.Paths

/** macOS entry point: the server and its window, with the plugins this app includes. */
fun main(args: Array<String>) = runDesktopApp(
    args,
    dataDir = Paths.get(System.getProperty("user.home"), "Library", "Application Support", "KS StreamFlix"),
    serverPlugins = listOf(
        WebPlugin(), WatchStatePlugin(), DiscoveryPlugin(),
        LocalMetadataPlugin(), TmdbPlugin(), MediaInfoPlugin(), SubtitlesPlugin(), TranscodePlugin(),
    ),
    clientPlugins = listOf(
        WatchStateClient(), MetadataClient(), MediaInfoClient(),
        JaiPlayerPlugin(DesktopJaiPlayer::isInstalled) { url, _ -> DesktopJaiPlayer.open(url) },
    ),
)
