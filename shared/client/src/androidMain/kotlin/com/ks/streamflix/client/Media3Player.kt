package com.ks.streamflix.client

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.CueGroup
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import com.ks.streamflix.client.resources.Res
import com.ks.streamflix.client.resources.client_player_error
import com.ks.streamflix.client.ui.formatTime
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

private const val SEEK_MS = 10_000L
private const val PROGRESS_EVERY_MS = 10_000L
private const val CONTROLS_HIDE_MS = 4_000L

/**
 * The built-in player (Media3 ExoPlayer, direct play). Remote and keyboard: OK or Space plays and
 * pauses, left and right seek 10 s, Back stops. On a phone a tap shows the controls and pauses.
 */
@Composable
fun Media3Player(request: PlayRequest, isTv: Boolean, onProgress: (Long, Long) -> Unit, onExit: (Long, Long) -> Unit) {
    val context = LocalContext.current
    val player = remember {
        val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(request.headers)
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build().apply {
            val item = MediaItem.Builder().setUri(request.url).apply {
                // The chosen subtitles (WebVTT from the server), fetched with the same sign-in header.
                request.subtitle?.let { sub ->
                    setSubtitleConfigurations(listOf(
                        MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(sub.url))
                            .setMimeType(MimeTypes.TEXT_VTT).setLanguage(sub.language).setLabel(sub.label)
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build(),
                    ))
                }
            }.build()
            setMediaItem(item, request.startMs)
            if (request.subtitle == null) trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            prepare()
            playWhenReady = true
        }
    }
    var aspect by remember { mutableFloatStateOf(0f) }
    var failed by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(request.startMs) }
    var duration by remember { mutableLongStateOf(0L) }
    var controlsShownAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var tick by remember { mutableIntStateOf(0) }
    var cues by remember { mutableStateOf("") }

    fun exit() {
        val p = player.currentPosition
        val d = player.duration.coerceAtLeast(0)
        player.release()
        onExit(p, d)
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlayerError(error: PlaybackException) { failed = true }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) exit() }
            override fun onCues(cueGroup: CueGroup) { cues = cueGroup.cues.mapNotNull { it.text?.toString() }.joinToString("\n") }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    // The position for the controls every half second; the server hears it every 10 s.
    LaunchedEffect(player) {
        var lastSaved = System.currentTimeMillis()
        while (true) {
            delay(500)
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0)
            tick++
            if (playing && System.currentTimeMillis() - lastSaved >= PROGRESS_EVERY_MS) {
                lastSaved = System.currentTimeMillis()
                onProgress(position, duration)
            }
        }
    }
    BackHandler { exit() }

    val focus = remember { FocusRequester() }
    fun showControls() { controlsShownAt = System.currentTimeMillis() }
    fun togglePlay() { if (player.isPlaying) player.pause() else player.play(); showControls() }
    fun seek(by: Long) { player.seekTo((player.currentPosition + by).coerceIn(0, player.duration.coerceAtLeast(0))); showControls() }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focus)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.MediaPlayPause -> { togglePlay(); true }
                    Key.DirectionLeft, Key.MediaRewind -> { seek(-SEEK_MS); true }
                    Key.DirectionRight, Key.MediaFastForward -> { seek(SEEK_MS); true }
                    Key.DirectionUp, Key.DirectionDown -> { showControls(); true }
                    else -> false
                }
            }
            .focusable()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (isTv) togglePlay() else showControls()
            },
        contentAlignment = Alignment.Center,
    ) {
        PlayerSurface(player = player, surfaceType = SURFACE_TYPE_SURFACE_VIEW, modifier = if (aspect > 0f) Modifier.aspectRatio(aspect) else Modifier)
        if (failed) Text(stringResource(Res.string.client_player_error), color = Color.White)
        if (cues.isNotEmpty()) {
            Text(
                cues, color = Color.White, style = MaterialTheme.typography.headlineSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (isTv) 96.dp else 72.dp, start = 48.dp, end = 48.dp)
                    .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        val visible = !playing || System.currentTimeMillis() - controlsShownAt < CONTROLS_HIDE_MS || tick < 0
        if (visible && !failed) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = if (isTv) 48.dp else 16.dp, vertical = if (isTv) 27.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(request.title, color = Color.White, style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(
                    progress = { if (duration > 0) position.toFloat() / duration else 0f },
                    modifier = Modifier.fillMaxWidth(), color = Color.White, trackColor = Color.White.copy(alpha = 0.25f),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text((if (playing) "▶ " else "❚❚ ") + formatTime(position), color = Color.White)
                    Text(formatTime(duration), color = Color.White)
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
