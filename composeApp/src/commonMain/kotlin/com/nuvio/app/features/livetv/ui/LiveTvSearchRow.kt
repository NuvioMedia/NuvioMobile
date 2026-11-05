package com.nuvio.app.features.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.features.livetv.LiveTvDeepLinks
import com.nuvio.app.features.livetv.data.LiveTvRepository
import com.nuvio.app.features.livetv.model.EpgProgram
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvClock
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.livetv_search_row_title
import nuvio.composeapp.generated.resources.livetv_sports_live_now
import nuvio.composeapp.generated.resources.livetv_sports_upcoming
import org.jetbrains.compose.resources.stringResource

/** One Live TV search result: a channel, plus the show that matched (or is on now). */
internal data class LiveSearchHit(
    val channel: LiveChannel,
    val program: EpgProgram?,
    val live: Boolean,
)

private const val MAX_HITS = 40
private const val AHEAD_MS = 6L * 60 * 60 * 1000

/** Channels whose name/number match first, then channels with a matching show in the next 6 hours. */
internal fun liveTvSearchHits(
    visible: List<LiveChannel>,
    programs: Map<String, List<EpgProgram>>,
    query: String,
    now: Long,
): List<LiveSearchHit> {
    val q = query.trim()
    if (q.length < 2) return emptyList()
    fun current(c: LiveChannel): EpgProgram? =
        guideFor(c, programs).firstOrNull { it.startMs <= now && it.stopMs > now }

    val out = ArrayList<LiveSearchHit>()
    val seen = HashSet<String>()
    for (c in LiveTvViewState.search(visible, q)) {
        if (!seen.add(c.key)) continue
        val p = current(c)
        out += LiveSearchHit(c, p, p != null)
        if (out.size >= MAX_HITS) return out
    }
    for (c in visible) {
        if (c.key in seen) continue
        val p = guideFor(c, programs).firstOrNull {
            it.stopMs > now && it.startMs < now + AHEAD_MS && it.title.contains(q, ignoreCase = true)
        } ?: continue
        seen += c.key
        out += LiveSearchHit(c, p, p.startMs <= now)
        if (out.size >= MAX_HITS) break
    }
    return out
}

private fun guideFor(c: LiveChannel, programs: Map<String, List<EpgProgram>>): List<EpgProgram> =
    programs[c.key] ?: c.tvgId?.let { programs[it] } ?: emptyList()

/** The "Live TV" row shown above the catalog rows in Search. Nothing shows when there are no matches. */
@Composable
internal fun LiveTvSearchRow(
    query: String,
    modifier: Modifier = Modifier,
) {
    val list by LiveTvViewState.list.collectAsStateWithLifecycle()
    val programs by LiveTvRepository.programs.collectAsStateWithLifecycle()
    var hits by remember { mutableStateOf(emptyList<LiveSearchHit>()) }

    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            hits = emptyList()
            return@LaunchedEffect
        }
        LiveTvRepository.ensureLoaded()
    }
    LaunchedEffect(query, list.visible, programs) {
        if (query.trim().length < 2) return@LaunchedEffect
        delay(300)
        hits = liveTvSearchHits(list.visible, programs, query, LiveTvClock.nowMs())
    }
    if (hits.isEmpty() || query.trim().length < 2) return

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.livetv_search_row_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(hits, key = { it.channel.key }) { hit ->
                LiveSearchCard(hit)
            }
        }
    }
}

@Composable
private fun LiveSearchCard(hit: LiveSearchHit) {
    val now = LiveTvClock.nowMs()
    val p = hit.program
    val image = p?.icon ?: hit.channel.logo
    Column(
        modifier = Modifier
            .width(200.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { LiveTvDeepLinks.handle(LiveTvDeepLinks.channelUrl(hit.channel.key)) },
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(112.dp).background(MaterialTheme.colorScheme.surface),
        ) {
            if (image != null) {
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = if (p?.icon != null) ContentScale.Crop else ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(112.dp).padding(if (p?.icon != null) 0.dp else 16.dp),
                )
            }
            Text(
                text = stringResource(if (hit.live) Res.string.livetv_sports_live_now else Res.string.livetv_sports_upcoming),
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (hit.live) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            if (p != null && hit.live && p.stopMs > p.startMs) {
                val frac = ((now - p.startMs).toFloat() / (p.stopMs - p.startMs)).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)),
                )
                Box(
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(frac).height(3.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = p?.title ?: hit.channel.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row {
                Text(
                    text = "${hit.channel.number} · ${hit.channel.name}" + if (hit.channel.sourceName.isNotBlank()) " · ${hit.channel.sourceName}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
