package com.ks.streamflix.client.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ks.streamflix.api.Item
import com.ks.streamflix.api.Library
import com.ks.streamflix.client.FoundServer
import com.ks.streamflix.client.PlatformHost
import com.ks.streamflix.client.PlayRequest
import com.ks.streamflix.client.net.ApiException
import com.ks.streamflix.client.net.StreamflixClient
import com.ks.streamflix.client.net.normaliseServer
import com.ks.streamflix.client.plugin.ClientPlugin
import com.ks.streamflix.client.plugin.InstalledPlugins
import com.ks.streamflix.client.settings.LanguageChoice
import com.ks.streamflix.client.settings.SettingKeys
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Loading : Screen
    data object Connect : Screen
    data object SignIn : Screen
    data object Home : Screen
    data class LibraryPage(val library: Library) : Screen
    data class Detail(val item: Item) : Screen
    data class Playing(val item: Item, val request: PlayRequest) : Screen
    data object Settings : Screen
}

data class LibraryRow(val library: Library, val items: List<Item>)

data class UiState(
    val screen: Screen = Screen.Loading,
    val server: String? = null,
    val serverName: String? = null,
    val user: String? = null,
    val found: List<FoundServer> = emptyList(),
    val searching: Boolean = false,
    val busy: Boolean = false,
    /** A language-file key: a server error code or `client.*`. */
    val error: String? = null,
    val rows: List<LibraryRow> = emptyList(),
    val libraryItems: List<Item> = emptyList(),
    val resumeMs: Long? = null,
    val language: LanguageChoice = LanguageChoice.System,
    /** The item that had focus on each screen, so going back restores it (D-pad). */
    val lastFocused: Map<String, Long> = emptyMap(),
    /** Bumped when the home screen is refreshed, so plugin sections reload. */
    val homeVersion: Int = 0,
)

/** The apps' state and navigation: one-way data flow, the screens only read [state] and call these functions. */
class AppViewModel(val host: PlatformHost, plugins: List<ClientPlugin>) : ViewModel() {
    val plugins = InstalledPlugins.install(plugins, host)
    private val settings = host.settings
    private val _state = MutableStateFlow(UiState(language = LanguageChoice.parse(settings.getString(SettingKeys.LANGUAGE))))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val backStack = ArrayDeque<Screen>()
    var client: StreamflixClient? = null
        private set
    private var job: Job? = null

    init {
        val server = settings.getString(SettingKeys.SERVER)
        val token = settings.getString(SettingKeys.TOKEN)
        if (server == null) {
            show(Screen.Connect, root = true)
            discover()
        } else {
            val c = StreamflixClient(host.transport, server, token)
            client = c
            _state.update { it.copy(server = server, user = settings.getString(SettingKeys.USER)) }
            work {
                val info = c.info()
                _state.update { it.copy(serverName = info.name) }
                if (token != null && runCatching { c.libraries() }.isSuccess) openHome() else show(Screen.SignIn, root = true)
            }.also { j ->
                j.invokeOnCompletion {
                    if (_state.value.screen == Screen.Loading) {
                        val error = _state.value.error
                        show(Screen.SignIn, root = true)
                        _state.update { it.copy(error = error) }
                    }
                }
            }
        }
    }

    // ---- Navigation -------------------------------------------------------------------------------

    private fun show(screen: Screen, root: Boolean = false) {
        if (root) backStack.clear() else backStack.addLast(_state.value.screen)
        _state.update { it.copy(screen = screen, error = null) }
    }

    /** Goes back one screen; false at the first screen (the app then closes or does nothing). */
    fun back(): Boolean {
        val previous = backStack.removeLastOrNull() ?: return false
        _state.update { it.copy(screen = previous, error = null) }
        if (previous == Screen.Home) refreshHome()
        return true
    }

    fun rememberFocus(screenKey: String, itemId: Long) = _state.update { it.copy(lastFocused = it.lastFocused + (screenKey to itemId)) }

    // ---- Connecting and signing in ----------------------------------------------------------------

    fun discover() {
        _state.update { it.copy(searching = true) }
        viewModelScope.launch {
            val found = runCatching { host.discover() }.getOrDefault(emptyList())
            _state.update { it.copy(found = found, searching = false) }
        }
    }

    fun connect(address: String) {
        val server = normaliseServer(address) ?: return _state.update { it.copy(error = "client.error.address") }
        val c = StreamflixClient(host.transport, server)
        work {
            val info = c.info()
            if (info.apiVersion != com.ks.streamflix.api.ApiV1.VERSION) throw ApiException(0, "client.error.version")
            if (info.setupNeeded) throw ApiException(0, "client.error.setupNeeded")
            client = c
            settings.putString(SettingKeys.SERVER, server)
            settings.putString(SettingKeys.TOKEN, null)
            _state.update { it.copy(server = server, serverName = info.name) }
            show(Screen.SignIn)
        }
    }

    fun signIn(name: String, password: String) {
        val c = client ?: return
        work {
            val session = c.signIn(name, password)
            settings.putString(SettingKeys.TOKEN, session.token)
            settings.putString(SettingKeys.USER, session.user.name)
            _state.update { it.copy(user = session.user.name) }
            openHome()
        }
    }

    fun signOut() {
        val c = client
        viewModelScope.launch { c?.signOut() }
        settings.putString(SettingKeys.TOKEN, null)
        _state.update { it.copy(user = null, rows = emptyList()) }
        show(Screen.SignIn, root = true)
    }

    fun changeServer() {
        signOut()
        settings.putString(SettingKeys.SERVER, null)
        client = null
        _state.update { it.copy(server = null, serverName = null) }
        show(Screen.Connect, root = true)
        discover()
    }

    fun chooseLanguage(choice: LanguageChoice) {
        settings.putString(SettingKeys.LANGUAGE, choice.saved)
        _state.update { it.copy(language = choice) }
    }

    fun openSettings() = show(Screen.Settings)

    // ---- Browsing ---------------------------------------------------------------------------------

    private suspend fun openHome() {
        loadRows()
        show(Screen.Home, root = true)
        _state.update { it.copy(homeVersion = it.homeVersion + 1) }
    }

    fun refreshHome() {
        work { loadRows(); _state.update { it.copy(homeVersion = it.homeVersion + 1) } }
    }

    private suspend fun loadRows() {
        val c = client ?: return
        val rows = c.libraries().map { LibraryRow(it, c.items(it.id)) }
        _state.update { it.copy(rows = rows) }
    }

    fun openLibrary(library: Library) {
        val c = client ?: return
        _state.update { it.copy(libraryItems = emptyList()) }
        show(Screen.LibraryPage(library))
        work { _state.update { it.copy(libraryItems = c.items(library.id)) } }
    }

    fun openItem(item: Item) {
        val c = client ?: return
        _state.update { it.copy(resumeMs = null) }
        show(Screen.Detail(item))
        viewModelScope.launch { loadResume(c, item) }
        plugins.detailSections.forEach { section -> viewModelScope.launch { runCatching { section.load(c, item) } } }
    }

    private suspend fun loadResume(c: StreamflixClient, item: Item) {
        val resume = plugins.resumeProviders.firstNotNullOfOrNull { runCatching { it(c, item) }.getOrNull() }
        if ((_state.value.screen as? Screen.Detail)?.item?.id == item.id) _state.update { it.copy(resumeMs = resume?.takeIf { ms -> ms > 0 }) }
    }

    /** Plays [item] from [startMs]: in the app's own player, or in another player through a stream link. */
    fun play(item: Item, startMs: Long = 0) {
        val c = client ?: return
        if (host.hasPlayer) {
            viewModelScope.launch {
                var request = PlayRequest(c.streamUrl(item.id), c.authHeaders, item.title, startMs)
                plugins.playDecorators.forEach { d -> request = runCatching { d(c, item, request) }.getOrDefault(request) }
                show(Screen.Playing(item, request))
            }
        } else {
            work { if (!host.openExternally(c.streamLink(item.id))) throw ApiException(0, "client.error.noPlayer") }
        }
    }

    fun runAction(index: Int, item: Item) {
        val c = client ?: return
        val action = plugins.itemActions.getOrNull(index) ?: return
        work { action.run(c, item) }
    }

    fun playbackProgress(item: Item, positionMs: Long, durationMs: Long) {
        val c = client ?: return
        viewModelScope.launch { plugins.playbackListeners.forEach { runCatching { it.progress(c, item, positionMs, durationMs) } } }
    }

    fun playbackStopped(item: Item, positionMs: Long, durationMs: Long) {
        val c = client ?: return
        back()
        // Saved first, then the item's page asks again, so Resume shows where playback stopped.
        viewModelScope.launch {
            plugins.playbackListeners.forEach { runCatching { it.stopped(c, item, positionMs, durationMs) } }
            loadResume(c, item)
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /** Runs [block] with the busy flag; a failure becomes the error shown on the current screen. */
    private fun work(block: suspend () -> Unit): Job {
        job?.cancel()
        _state.update { it.copy(busy = true, error = null) }
        return viewModelScope.launch {
            try {
                block()
            } catch (e: ApiException) {
                if (e.status == 401 && _state.value.screen != Screen.SignIn && client?.token != null) {
                    settings.putString(SettingKeys.TOKEN, null)
                    show(Screen.SignIn, root = true)
                }
                _state.update { it.copy(error = e.code) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }.also { job = it }
    }
}
