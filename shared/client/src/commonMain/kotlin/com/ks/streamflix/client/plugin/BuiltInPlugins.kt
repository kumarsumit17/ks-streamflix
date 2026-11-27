package com.ks.streamflix.client.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.ks.streamflix.api.Item
import com.ks.streamflix.client.net.StreamflixClient
import com.ks.streamflix.client.resources.Res
import com.ks.streamflix.client.resources.client_action_jaiplayer
import com.ks.streamflix.client.resources.client_continue_heading
import com.ks.streamflix.client.ui.ItemRow
import org.jetbrains.compose.resources.stringResource

/**
 * Plugin `watch-state` (pairs with the server plugin of the same id): the "Continue watching" row,
 * Resume on an item's page, and the player's progress saved on the server. Quiet when the server
 * does not have the plugin.
 */
class WatchStatePlugin : ClientPlugin {
    override val id = "watch-state"

    override fun install(context: ClientPluginContext) {
        context.addHomeSection(object : HomeSection {
            override var items by mutableStateOf<List<Item>>(emptyList())

            override suspend fun refresh(client: StreamflixClient) {
                items = runCatching { client.continueWatching().map { it.item } }.getOrDefault(emptyList())
            }

            @Composable
            override fun Content(open: (Item) -> Unit, focusId: Long?, onFocused: () -> Unit) {
                if (items.isEmpty()) return
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.client_continue_heading), style = MaterialTheme.typography.titleMedium)
                    ItemRow(items, focusId, onFocused, open)
                }
            }
        })
        context.addResumePosition { client, item ->
            client.continueWatching().firstOrNull { it.item.id == item.id }?.progress?.positionMs
        }
        context.addPlaybackListener(object : PlaybackListener {
            override suspend fun progress(client: StreamflixClient, item: Item, positionMs: Long, durationMs: Long) {
                client.saveProgress(item.id, positionMs, durationMs)
            }

            override suspend fun stopped(client: StreamflixClient, item: Item, positionMs: Long, durationMs: Long) {
                if (durationMs > 0) client.saveProgress(item.id, positionMs, durationMs)
            }
        })
    }
}

/**
 * Plugin `jaiplayer`: "Open in KS JaiPlayer" on an item's page, with a stream link (the player
 * cannot send the sign-in header). [isInstalled] and [open] come from the platform.
 */
class JaiPlayerPlugin(private val isInstalled: () -> Boolean, private val open: (url: String, title: String) -> Boolean) : ClientPlugin {
    override val id = "jaiplayer"

    override fun install(context: ClientPluginContext) {
        context.addItemAction(
            ItemAction(
                id = "jaiplayer",
                label = { stringResource(Res.string.client_action_jaiplayer) },
                isAvailable = isInstalled,
                run = { client, item ->
                    if (!open(client.streamLink(item.id), item.title)) throw com.ks.streamflix.client.net.ApiException(0, "client.error.noPlayer")
                },
            ),
        )
    }
}
