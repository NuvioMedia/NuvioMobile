package com.nuvio.app.features.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.data.LiveTvRepository
import com.nuvio.app.features.livetv.data.LogoChoice
import com.nuvio.app.features.livetv.data.LogoFinder
import com.nuvio.app.features.livetv.model.LiveChannel
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.livetv_action_change_logo
import nuvio.composeapp.generated.resources.livetv_logo_finding
import nuvio.composeapp.generated.resources.livetv_logo_group_guides
import nuvio.composeapp.generated.resources.livetv_logo_group_library
import nuvio.composeapp.generated.resources.livetv_logo_group_playlists
import nuvio.composeapp.generated.resources.livetv_logo_group_tmdb
import nuvio.composeapp.generated.resources.livetv_logo_its_own
import nuvio.composeapp.generated.resources.livetv_logo_link
import nuvio.composeapp.generated.resources.livetv_logo_link_description
import nuvio.composeapp.generated.resources.livetv_logo_link_hint
import nuvio.composeapp.generated.resources.livetv_logo_looking
import nuvio.composeapp.generated.resources.livetv_logo_none
import nuvio.composeapp.generated.resources.livetv_logo_none_query
import nuvio.composeapp.generated.resources.livetv_logo_search_hint
import nuvio.composeapp.generated.resources.livetv_logo_source_guides
import nuvio.composeapp.generated.resources.livetv_logo_source_iptv_org
import nuvio.composeapp.generated.resources.livetv_logo_source_playlists
import nuvio.composeapp.generated.resources.livetv_logo_source_tmdb
import nuvio.composeapp.generated.resources.livetv_logo_source_tv_logos
import nuvio.composeapp.generated.resources.livetv_logo_sources
import nuvio.composeapp.generated.resources.livetv_logo_sources_description
import nuvio.composeapp.generated.resources.livetv_logo_using_own
import nuvio.composeapp.generated.resources.livetv_logo_using_pick
import org.jetbrains.compose.resources.stringResource

/**
 * Change logo: pick a different logo for one channel from your other playlists, your guides, the
 * two logo libraries and TMDB, or paste a link. The pick is saved against the channel (and synced
 * with the rest of your Live TV settings); "Use its own logo" puts the playlist's back.
 */
@Composable
internal fun ChangeLogoDialog(channel: LiveChannel, onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val settings by LiveTvPreferences.settings.collectAsStateWithLifecycle()
    val user by LiveTvPreferences.userState.collectAsStateWithLifecycle()
    val off = remember(settings.logoSourcesOff) { LogoFinder.sourcesOff(settings.logoSourcesOff) }
    val picked = channel.key in user.channelLogos

    var query by remember(channel.key) { mutableStateOf("") }
    var choices by remember(channel.key) { mutableStateOf<List<LogoChoice>?>(null) }
    var libraryLoading by remember(channel.key) { mutableStateOf(false) }
    var sourcesOpen by remember { mutableStateOf(false) }
    var linkOpen by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }

    // The guides' channel lists (for the logos they carry) are read once, when first needed.
    LaunchedEffect(Unit) { LiveTvRepository.ensureSourceLists() }
    val guideLists by LiveTvRepository.epgSources.collectAsStateWithLifecycle()

    LaunchedEffect(channel.key, query.trim(), settings.logoSourcesOff, guideLists.sumOf { it.channels.size }) {
        // A short pause while typing; your playlists and guides first, then the libraries (their
        // lists may need fetching the first time).
        if (query.isNotBlank()) delay(400)
        choices = LogoFinder.choices(channel, query.trim(), withLibrary = false)
        libraryLoading = true
        choices = LogoFinder.choices(channel, query.trim(), withLibrary = true)
        libraryLoading = false
    }

    fun use(url: String?) {
        LiveTvPreferences.setChannelLogo(channel.key, url)
        onDismiss()
    }

    DialogSurface(onDismissRequest = onDismiss, title = stringResource(Res.string.livetv_action_change_logo)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12)) {
            ChannelLogo(channel, size = 44.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    channel.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(if (picked) Res.string.livetv_logo_using_pick else Res.string.livetv_logo_using_own),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
        }
        NuvioInputField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(Res.string.livetv_logo_search_hint),
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
        ) {
            if (picked) item(key = "own") {
                DialogOption(
                    text = stringResource(Res.string.livetv_logo_its_own),
                    selected = false,
                    role = Role.Button,
                    onClick = { use(null) },
                )
            }
            item(key = "link") {
                DialogOption(
                    text = stringResource(Res.string.livetv_logo_link),
                    description = stringResource(Res.string.livetv_logo_link_description),
                    selected = linkOpen,
                    role = Role.Button,
                    onClick = { linkOpen = !linkOpen },
                )
            }
            if (linkOpen) item(key = "linkField") {
                Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
                    NuvioInputField(
                        value = link,
                        onValueChange = { link = it },
                        placeholder = stringResource(Res.string.livetv_logo_link_hint),
                    )
                    DialogButtons {
                        DialogButton(
                            text = stringResource(Res.string.action_save),
                            style = DialogButtonStyle.Primary,
                            enabled = link.trim().startsWith("http", ignoreCase = true),
                            onClick = { use(link.trim()) },
                        )
                    }
                }
            }
            item(key = "sources") {
                DialogOption(
                    text = stringResource(Res.string.livetv_logo_sources),
                    description = stringResource(Res.string.livetv_logo_sources_description),
                    selected = sourcesOpen,
                    role = Role.Button,
                    onClick = { sourcesOpen = !sourcesOpen },
                )
            }
            if (sourcesOpen) {
                val sources = listOf(
                    "tv-logos" to Res.string.livetv_logo_source_tv_logos,
                    "iptv-org" to Res.string.livetv_logo_source_iptv_org,
                    "tmdb" to Res.string.livetv_logo_source_tmdb,
                    "playlists" to Res.string.livetv_logo_source_playlists,
                    "guides" to Res.string.livetv_logo_source_guides,
                )
                items(sources, key = { "src-" + it.first }) { (key, label) ->
                    DialogOption(
                        text = stringResource(label),
                        selected = key !in off,
                        role = Role.Checkbox,
                        onClick = {
                            LiveTvPreferences.updateSettings { s ->
                                val now = LogoFinder.sourcesOff(s.logoSourcesOff)
                                s.copy(logoSourcesOff = (if (key in now) now - key else now + key).sorted().joinToString(","))
                            }
                        },
                    )
                }
            }

            val list = choices
            if (list == null) {
                item(key = "finding") { Hint(stringResource(Res.string.livetv_logo_finding)) }
            } else {
                if (list.isEmpty() && !libraryLoading) item(key = "none") {
                    Hint(
                        if (query.isBlank()) stringResource(Res.string.livetv_logo_none)
                        else stringResource(Res.string.livetv_logo_none_query, query.trim()),
                    )
                }
                var lastGroup = -1
                list.forEach { choice ->
                    if (choice.group != lastGroup) {
                        lastGroup = choice.group
                        val group = choice.group
                        item(key = "h$group") {
                            Text(
                                text = when (group) {
                                    LogoChoice.PLAYLISTS -> stringResource(Res.string.livetv_logo_group_playlists)
                                    LogoChoice.GUIDES -> stringResource(Res.string.livetv_logo_group_guides)
                                    LogoChoice.TMDB -> stringResource(Res.string.livetv_logo_group_tmdb)
                                    else -> stringResource(Res.string.livetv_logo_group_library)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = tokens.colors.textMuted,
                                modifier = Modifier.padding(start = NuvioTokens.Space.s4, top = NuvioTokens.Space.s8),
                            )
                        }
                    }
                    item(key = "l" + choice.url) {
                        DialogOption(
                            text = choice.name,
                            description = choice.from,
                            selected = choice.url == channel.logo,
                            role = Role.Button,
                            onClick = { use(choice.url) },
                            leading = { LogoPreview(choice.url) },
                        )
                    }
                }
            }
            if (libraryLoading) item(key = "loading") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(NuvioTokens.Space.s8),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
                ) {
                    NuvioLoadingIndicator(size = 20.dp)
                    Text(
                        stringResource(Res.string.livetv_logo_looking),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                    )
                }
            }
        }
        DialogButtons {
            DialogButton(text = stringResource(Res.string.action_close), onClick = onDismiss)
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.nuvio.colors.textMuted,
        modifier = Modifier.fillMaxWidth().padding(NuvioTokens.Space.s8),
    )
}

/** A logo as the channel list shows it: on the card colour, fitted in a box (loads as you scroll). */
@Composable
private fun LogoPreview(url: String) {
    Box(
        modifier = Modifier
            .size(width = 64.dp, height = 40.dp)
            .clip(RoundedCornerShape(NuvioTokens.Radius.md))
            .background(MaterialTheme.nuvio.colors.surfaceCard),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().padding(NuvioTokens.Space.s4),
            contentScale = ContentScale.Fit,
        )
    }
}
