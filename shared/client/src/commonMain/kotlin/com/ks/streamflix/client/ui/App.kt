package com.ks.streamflix.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ks.streamflix.api.Item
import com.ks.streamflix.api.ItemKind
import com.ks.streamflix.api.LibraryKind
import com.ks.streamflix.client.ProvideLanguage
import com.ks.streamflix.client.resources.Res
import com.ks.streamflix.client.resources.allStringResources
import com.ks.streamflix.client.resources.client_connect_address
import com.ks.streamflix.client.resources.client_connect_address_hint
import com.ks.streamflix.client.resources.client_connect_found
import com.ks.streamflix.client.resources.client_connect_heading
import com.ks.streamflix.client.resources.client_connect_none
import com.ks.streamflix.client.resources.client_connect_search_again
import com.ks.streamflix.client.resources.client_connect_searching
import com.ks.streamflix.client.resources.client_connect_submit
import com.ks.streamflix.client.resources.client_field_name
import com.ks.streamflix.client.resources.client_field_password
import com.ks.streamflix.client.resources.client_home_empty
import com.ks.streamflix.client.resources.client_home_see_all
import com.ks.streamflix.client.resources.client_item_episode
import com.ks.streamflix.client.resources.client_item_play
import com.ks.streamflix.client.resources.client_item_resume
import com.ks.streamflix.client.resources.client_item_start_over
import com.ks.streamflix.client.resources.client_library_empty
import com.ks.streamflix.client.resources.client_settings_change_server
import com.ks.streamflix.client.resources.client_settings_server
import com.ks.streamflix.client.resources.client_settings_sign_out
import com.ks.streamflix.client.resources.client_settings_user
import com.ks.streamflix.client.resources.client_settings_version
import com.ks.streamflix.client.resources.client_sign_in_heading
import com.ks.streamflix.client.resources.client_sign_in_submit
import com.ks.streamflix.client.resources.common_back
import com.ks.streamflix.client.resources.common_language
import com.ks.streamflix.client.resources.common_language_english
import com.ks.streamflix.client.resources.common_language_hindi
import com.ks.streamflix.client.resources.common_language_system
import com.ks.streamflix.client.resources.common_settings
import com.ks.streamflix.client.settings.LanguageChoice
import com.ks.streamflix.client.settings.resolveLanguage
import org.jetbrains.compose.resources.stringResource

/** Loads the picture for an item's card at a width in pixels (from the plugins); null shows the initial. */
val LocalCardImage = staticCompositionLocalOf<suspend (Item, Int) -> ImageBitmap?> { { _, _ -> null } }

/** The whole app, for every platform. [header] is shown above the screens (the desktop's server card). */
@Composable
fun StreamflixApp(vm: AppViewModel, header: @Composable () -> Unit = {}) {
    val state by vm.state.collectAsState()
    val host = vm.host
    ProvideLanguage(resolveLanguage(state.language, host.preferredLanguages)) {
        val cardImage: suspend (Item, Int) -> ImageBitmap? = { item, width ->
            val c = vm.client
            if (c == null) null else vm.plugins.cardImages.firstNotNullOfOrNull { runCatching { it(c, item, width) }.getOrNull() }
        }
        CompositionLocalProvider(LocalCardImage provides cardImage) {
        StreamflixTheme(host.isTv) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                val screen = state.screen
                if (screen is Screen.Playing) {
                    host.Player(
                        screen.request,
                        onProgress = { p, d -> vm.playbackProgress(screen.item, p, d) },
                        onExit = { p, d -> vm.playbackStopped(screen.item, p, d) },
                    )
                    return@Surface
                }
                // Overscan-safe margins on a TV: 48 dp sideways, 27 dp top and bottom.
                val padding = if (host.isTv) PaddingValues(horizontal = 48.dp, vertical = 27.dp) else PaddingValues(16.dp)
                Column(Modifier.fillMaxSize().padding(padding)) {
                    header()
                    when (screen) {
                        Screen.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        Screen.Connect -> ConnectScreen(vm, state)
                        Screen.SignIn -> SignInScreen(vm, state)
                        Screen.Home -> HomeScreen(vm, state)
                        is Screen.LibraryPage -> LibraryScreen(vm, state, screen)
                        is Screen.Detail -> DetailScreen(vm, state, screen.item)
                        Screen.Settings -> SettingsScreen(vm, state)
                        is Screen.Playing -> Unit
                    }
                }
            }
        }
        }
    }
}

// Screens move the focus only on a TV, where the remote needs a starting point.

// ---- Connect and sign in ------------------------------------------------------------------------

@Composable
private fun ConnectScreen(vm: AppViewModel, state: UiState) {
    var address by rememberSaveable { mutableStateOf("") }
    val first = remember { FocusRequester() }
    Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Heading(stringResource(Res.string.client_connect_heading))
        Muted(stringResource(Res.string.client_connect_found))
        when {
            state.searching -> Muted(stringResource(Res.string.client_connect_searching))
            state.found.isEmpty() -> Muted(stringResource(Res.string.client_connect_none))
        }
        state.found.forEachIndexed { i, server ->
            ListRow(server.name, server.address, Modifier.then(if (i == 0) Modifier.focusRequester(first) else Modifier)) { vm.connect(server.address) }
        }
        OutlinedButton(onClick = vm::discover, modifier = Modifier.focusRing(), enabled = !state.searching) {
            Text(stringResource(Res.string.client_connect_search_again))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = address, onValueChange = { address = it },
            label = { Text(stringResource(Res.string.client_connect_address)) },
            placeholder = { Text(stringResource(Res.string.client_connect_address_hint, "192.168.1.20:8742")) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { vm.connect(address) }),
            modifier = Modifier.fillMaxWidth().then(if (state.found.isEmpty()) Modifier.focusRequester(first) else Modifier),
        )
        ErrorText(state.error)
        PrimaryButton(stringResource(Res.string.client_connect_submit), busy = state.busy) { vm.connect(address) }
    }
    LaunchedEffect(state.found.isEmpty()) { if (vm.host.isTv) runCatching { first.requestFocus() } }
}

@Composable
private fun SignInScreen(vm: AppViewModel, state: UiState) {
    var name by rememberSaveable { mutableStateOf(state.user.orEmpty()) }
    var password by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Heading(stringResource(Res.string.client_sign_in_heading, state.serverName ?: state.server.orEmpty()))
        OutlinedTextField(
            name, { name = it }, label = { Text(stringResource(Res.string.client_field_name)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().focusRequester(first),
        )
        OutlinedTextField(
            password, { password = it }, label = { Text(stringResource(Res.string.client_field_password)) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { vm.signIn(name, password) }),
            modifier = Modifier.fillMaxWidth(),
        )
        ErrorText(state.error)
        PrimaryButton(stringResource(Res.string.client_sign_in_submit), busy = state.busy) { vm.signIn(name, password) }
        TextButton(onClick = vm::changeServer, modifier = Modifier.focusRing()) { Text(stringResource(Res.string.client_settings_change_server)) }
    }
    LaunchedEffect(Unit) { if (vm.host.isTv) runCatching { first.requestFocus() } }
}

// ---- Browsing -----------------------------------------------------------------------------------

@Composable
private fun HomeScreen(vm: AppViewModel, state: UiState) {
    // Focus returns to the last opened item; the first time, it starts on the first card on screen
    // (the plugins' sections come first, then the libraries). Once a card has the focus, the screen
    // never moves it by itself: rows that load later must not take it, or OK opens another film
    // than the one shown focused. (Only with a remote: on a phone, focus would only show rings.)
    // The first card is known only once every section has loaded (the libraries often arrive first).
    var focusTaken by remember { mutableStateOf(false) }
    var sectionsLoaded by remember(state.homeVersion) { mutableStateOf(0) }
    val firstCard = if (sectionsLoaded < vm.plugins.homeSections.size) null
        else vm.plugins.homeSections.firstNotNullOfOrNull { it.items.firstOrNull() }?.id
            ?: state.rows.firstOrNull { it.items.isNotEmpty() }?.items?.first()?.id
    // An item can be in two rows (Continue watching and its library), so the row is remembered too.
    val lastRow = state.lastFocused[HOME_ROW]
    fun restoreIn(row: Long): Long? = when {
        !vm.host.isTv || focusTaken -> null
        lastRow != null -> if (lastRow == row) state.lastFocused["home"] else null
        else -> firstCard
    }
    fun open(row: Long, item: Item) {
        vm.rememberFocus("home", item.id)
        vm.rememberFocus(HOME_ROW, row)
        vm.openItem(item)
    }
    val onFocused = { focusTaken = true }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Heading(state.serverName ?: "KS StreamFlix", Modifier.weight(1f))
                OutlinedButton(onClick = vm::openSettings, modifier = Modifier.focusRing()) { Text(stringResource(Res.string.common_settings)) }
            }
        }
        items(vm.plugins.homeSections.size) { i ->
            val section = vm.plugins.homeSections[i]
            LaunchedEffect(state.homeVersion) {
                vm.client?.let { runCatching { section.refresh(it) } }
                sectionsLoaded++
            }
            section.Content(open = { open(i.toLong(), it) }, focusId = restoreIn(i.toLong()), onFocused = onFocused)
        }
        if (state.rows.isEmpty() && !state.busy) item { Muted(stringResource(Res.string.client_home_empty)) }
        items(state.rows, key = { it.library.id }) { row ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.library.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.openLibrary(row.library) }, modifier = Modifier.focusRing()) {
                        Text(stringResource(Res.string.client_home_see_all))
                    }
                }
                if (row.items.isEmpty()) Muted(stringResource(Res.string.client_library_empty))
                ItemRow(row.items.take(30), restoreIn(LIBRARY_ROWS + row.library.id), onFocused) { open(LIBRARY_ROWS + row.library.id, it) }
            }
        }
        item { ErrorText(state.error) }
    }
}

/** Which home row a card was opened from: the plugins' sections by index, libraries after [LIBRARY_ROWS]. */
private const val HOME_ROW = "home-row"
private const val LIBRARY_ROWS = 1_000_000L

/**
 * A horizontal row of item cards; [restoreId] gets the focus (back after returning to this screen),
 * and [onFocused] is called whenever one of its cards gets the focus.
 */
@Composable
fun ItemRow(items: List<Item>, restoreId: Long?, onFocused: () -> Unit = {}, open: (Item) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(2.dp)) {
        items(items, key = { it.id }) { item -> ItemCard(item, Modifier.width(180.dp), focus = item.id == restoreId, onFocused = onFocused) { open(item) } }
    }
}

@Composable
private fun LibraryScreen(vm: AppViewModel, state: UiState, screen: Screen.LibraryPage) {
    val key = "library-${screen.library.id}"
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BackRow(vm, screen.library.name)
        if (state.libraryItems.isEmpty() && !state.busy) Muted(stringResource(Res.string.client_library_empty))
        val items = if (screen.library.kind == LibraryKind.SHOWS) state.libraryItems.sortedWith(compareBy({ it.series }, { it.season }, { it.episode })) else state.libraryItems
        LazyVerticalGrid(GridCells.Adaptive(180.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(2.dp)) {
            items(items, key = { it.id }) { item ->
                ItemCard(item, focus = vm.host.isTv && item.id == state.lastFocused[key]) { vm.rememberFocus(key, item.id); vm.openItem(item) }
            }
        }
        ErrorText(state.error)
    }
}

@Composable
private fun DetailScreen(vm: AppViewModel, state: UiState, item: Item) {
    val play = remember { FocusRequester() }
    Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BackRow(vm, item.series ?: item.title)
        Text(displayTitle(item), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        when {
            item.kind == ItemKind.EPISODE -> Muted(stringResource(Res.string.client_item_episode, (item.season ?: 0).toString(), (item.episode ?: 0).toString()))
            item.year != null -> Muted(item.year.toString())
        }
        Muted("${item.container.uppercase()} · ${formatSize(item.size)}")
        vm.plugins.detailSections.forEach { it.Content(item) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val resume = state.resumeMs
            if (resume != null) {
                PrimaryButton(stringResource(Res.string.client_item_resume, formatTime(resume)), Modifier.focusRequester(play)) { vm.play(item, resume) }
                OutlinedButton(onClick = { vm.play(item, 0) }, modifier = Modifier.focusRing()) { Text(stringResource(Res.string.client_item_start_over)) }
            } else {
                PrimaryButton(stringResource(Res.string.client_item_play), Modifier.focusRequester(play)) { vm.play(item, 0) }
            }
            vm.plugins.itemActions.forEachIndexed { i, action ->
                if (action.isAvailable()) OutlinedButton(onClick = { vm.runAction(i, item) }, modifier = Modifier.focusRing()) { Text(action.label()) }
            }
        }
        ErrorText(state.error)
    }
    LaunchedEffect(state.resumeMs) { if (vm.host.isTv) runCatching { play.requestFocus() } }
}

@Composable
private fun SettingsScreen(vm: AppViewModel, state: UiState) {
    // The D-pad focus starts on the chosen language. Choosing one rebuilds the screen in the new
    // language (ProvideLanguage), so this also keeps the focus on the button just pressed.
    val chosen = remember { FocusRequester() }
    Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BackRow(vm, stringResource(Res.string.common_settings))
        Text(stringResource(Res.string.common_language), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                LanguageChoice.System to Res.string.common_language_system,
                LanguageChoice.English to Res.string.common_language_english,
                LanguageChoice.Hindi to Res.string.common_language_hindi,
            ).forEach { (choice, label) ->
                ChoiceButton(stringResource(label), state.language == choice, if (state.language == choice) Modifier.focusRequester(chosen) else Modifier) {
                    vm.chooseLanguage(choice)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Muted(stringResource(Res.string.client_settings_server, state.serverName?.let { "$it (${state.server})" } ?: state.server.orEmpty()))
        state.user?.let { Muted(stringResource(Res.string.client_settings_user, it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::signOut, modifier = Modifier.focusRing()) { Text(stringResource(Res.string.client_settings_sign_out)) }
            OutlinedButton(onClick = vm::changeServer, modifier = Modifier.focusRing()) { Text(stringResource(Res.string.client_settings_change_server)) }
        }
        Muted(stringResource(Res.string.client_settings_version, vm.host.appVersion))
        vm.plugins.settingsSections.forEach { it() }
    }
    LaunchedEffect(Unit) { if (vm.host.isTv) runCatching { chosen.requestFocus() } }
}

// ---- Parts --------------------------------------------------------------------------------------

/**
 * One of several choices (language, subtitles). The same button whether chosen or not, only its
 * colours change, so the D-pad focus stays on it when it is chosen.
 */
@Composable
fun ChoiceButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = if (selected) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground,
    )
    Button(
        onClick = onClick, colors = colors, modifier = modifier.focusRing(),
        border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) { Text(label) }
}

@Composable
fun ItemCard(item: Item, modifier: Modifier = Modifier, focus: Boolean = false, onFocused: () -> Unit = {}, onClick: () -> Unit) {
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .border(if (focused) 2.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CardShape)
            .background(MaterialTheme.colorScheme.surfaceVariant, CardShape)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // The poster from the metadata plugins, loaded at the card's size; the initial until then, or without one.
        val load = LocalCardImage.current
        val widthPx = with(LocalDensity.current) { 156.dp.roundToPx() }
        val poster by produceState<ImageBitmap?>(null, item.id) { value = load(item, widthPx) }
        Box(Modifier.fillMaxWidth().height(if (poster != null) 234.dp else 96.dp).clip(CardShape).background(MaterialTheme.colorScheme.secondary), contentAlignment = Alignment.Center) {
            val image = poster
            if (image != null) Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text((item.series ?: item.title).take(1).uppercase(), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(displayTitle(item), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(subtitle(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
    LaunchedEffect(focus) { if (focus) runCatching { requester.requestFocus() } }
}

@Composable
private fun ListRow(title: String, detail: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.fillMaxWidth().focusRing().border(1.dp, MaterialTheme.colorScheme.outline, CardShape).clickable(onClick = onClick).padding(12.dp),
    ) {
        Text(title, fontWeight = FontWeight.Medium)
        Muted(detail)
    }
}

@Composable
private fun BackRow(vm: AppViewModel, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { vm.back() }, modifier = Modifier.focusRing()) { Text("← " + stringResource(Res.string.common_back)) }
        Heading(title)
    }
}

@Composable
private fun Heading(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

@Composable
private fun Muted(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun PrimaryButton(text: String, modifier: Modifier = Modifier, busy: Boolean = false, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !busy, modifier = modifier.focusRing(), colors = ButtonDefaults.buttonColors()) {
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text(text)
    }
}

/** An error from a language-file key (a server error code or `client.*`). */
@Composable
fun ErrorText(code: String?) {
    if (code == null) return
    val resource = Res.allStringResources[resourceName(code)] ?: Res.allStringResources["common_error_generic"]
    Text(resource?.let { stringResource(it) } ?: code, color = MaterialTheme.colorScheme.error)
}

/** `client.error.noPlayer` → `client_error_no_player`, as the language tool names Android resources. */
fun resourceName(key: String): String = buildString {
    key.forEach { c ->
        when {
            c == '.' -> append('_')
            c.isUpperCase() -> append('_').append(c.lowercaseChar())
            else -> append(c)
        }
    }
}

fun displayTitle(item: Item): String = when (item.kind) {
    ItemKind.MOVIE -> item.title
    ItemKind.EPISODE -> "S${item.season ?: 0} E${item.episode ?: 0} · ${item.title}"
}

private fun subtitle(item: Item): String = when (item.kind) {
    ItemKind.MOVIE -> item.year?.toString() ?: item.container.uppercase()
    ItemKind.EPISODE -> item.series.orEmpty()
}

/** `1:02:03` or `2:03`. */
fun formatTime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}" else "$m:${s.toString().padStart(2, '0')}"
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "${(bytes * 10 / (1L shl 30)) / 10.0} GB"
    bytes >= 1L shl 20 -> "${bytes / (1L shl 20)} MB"
    else -> "${bytes / 1024} KB"
}
