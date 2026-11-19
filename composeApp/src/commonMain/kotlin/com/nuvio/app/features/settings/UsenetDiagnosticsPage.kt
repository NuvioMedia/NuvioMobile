package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.usenet.UsenetPlatform
import com.nuvio.app.features.usenet.UsenetPlayback
import com.nuvio.app.features.usenet.UsenetStartupReport
import com.nuvio.app.features.usenet.UsenetStartupReport.NzbLookup
import com.nuvio.app.features.usenet.UsenetStartupReport.NzbWrite
import com.nuvio.app.features.usenet.UsenetStartupReport.Prefetch
import kotlin.math.roundToLong
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.usenet_diag_archive
import nuvio.composeapp.generated.resources.usenet_diag_bind_time
import nuvio.composeapp.generated.resources.usenet_diag_body_time
import nuvio.composeapp.generated.resources.usenet_diag_boot
import nuvio.composeapp.generated.resources.usenet_diag_cache
import nuvio.composeapp.generated.resources.usenet_diag_cancelled
import nuvio.composeapp.generated.resources.usenet_diag_decoder
import nuvio.composeapp.generated.resources.usenet_diag_downloads
import nuvio.composeapp.generated.resources.usenet_diag_empty
import nuvio.composeapp.generated.resources.usenet_diag_engine
import nuvio.composeapp.generated.resources.usenet_diag_first_bytes
import nuvio.composeapp.generated.resources.usenet_diag_handoff
import nuvio.composeapp.generated.resources.usenet_diag_head_ready
import nuvio.composeapp.generated.resources.usenet_diag_head_tail_ready
import nuvio.composeapp.generated.resources.usenet_diag_headers_time
import nuvio.composeapp.generated.resources.usenet_diag_hits
import nuvio.composeapp.generated.resources.usenet_diag_index_time
import nuvio.composeapp.generated.resources.usenet_diag_io_time
import nuvio.composeapp.generated.resources.usenet_diag_joins
import nuvio.composeapp.generated.resources.usenet_diag_limited_connections
import nuvio.composeapp.generated.resources.usenet_diag_lookup_time
import nuvio.composeapp.generated.resources.usenet_diag_memory
import nuvio.composeapp.generated.resources.usenet_diag_not_applicable
import nuvio.composeapp.generated.resources.usenet_diag_nzb
import nuvio.composeapp.generated.resources.usenet_diag_nzb_busy
import nuvio.composeapp.generated.resources.usenet_diag_nzb_cache
import nuvio.composeapp.generated.resources.usenet_diag_nzb_cancelled
import nuvio.composeapp.generated.resources.usenet_diag_nzb_disk_error
import nuvio.composeapp.generated.resources.usenet_diag_nzb_expired
import nuvio.composeapp.generated.resources.usenet_diag_nzb_hit
import nuvio.composeapp.generated.resources.usenet_diag_nzb_invalid
import nuvio.composeapp.generated.resources.usenet_diag_nzb_miss
import nuvio.composeapp.generated.resources.usenet_diag_nzb_not_saved
import nuvio.composeapp.generated.resources.usenet_diag_nzb_notes
import nuvio.composeapp.generated.resources.usenet_diag_nzb_oversized
import nuvio.composeapp.generated.resources.usenet_diag_nzb_saved
import nuvio.composeapp.generated.resources.usenet_diag_nzb_size
import nuvio.composeapp.generated.resources.usenet_diag_nzb_stages
import nuvio.composeapp.generated.resources.usenet_diag_nzb_write
import nuvio.composeapp.generated.resources.usenet_diag_off
import nuvio.composeapp.generated.resources.usenet_diag_on
import nuvio.composeapp.generated.resources.usenet_diag_overlap
import nuvio.composeapp.generated.resources.usenet_diag_parse_time
import nuvio.composeapp.generated.resources.usenet_diag_player
import nuvio.composeapp.generated.resources.usenet_diag_prefetch
import nuvio.composeapp.generated.resources.usenet_diag_prefetch_partial
import nuvio.composeapp.generated.resources.usenet_diag_provider_first
import nuvio.composeapp.generated.resources.usenet_diag_provider_setup
import nuvio.composeapp.generated.resources.usenet_diag_range_wait
import nuvio.composeapp.generated.resources.usenet_diag_reused
import nuvio.composeapp.generated.resources.usenet_diag_selection
import nuvio.composeapp.generated.resources.usenet_diag_session
import nuvio.composeapp.generated.resources.usenet_diag_tail_ready
import nuvio.composeapp.generated.resources.usenet_diag_total
import nuvio.composeapp.generated.resources.usenet_diag_when
import nuvio.composeapp.generated.resources.usenet_fast_mkv
import nuvio.composeapp.generated.resources.usenet_fast_nzb
import nuvio.composeapp.generated.resources.usenet_last_startup
import org.jetbrains.compose.resources.stringResource

/**
 * The last Usenet startup's timings, as NuvioTV's diagnostics cards show them: a summary, the
 * engine's stages, the NZB's stages, and the caches. Nothing in it names a release or provider.
 */
internal fun LazyListScope.usenetDiagnosticsContent(isTablet: Boolean) {
    item {
        val latest by UsenetPlayback.lastStartupReport.collectAsStateWithLifecycle()
        val report = remember(latest) { UsenetStartupReport.parse(latest) }
        if (report == null) {
            SettingsSection(title = stringResource(Res.string.usenet_last_startup), isTablet = isTablet) {
                SettingsGroup(isTablet = isTablet) {
                    DiagnosticsColumn(isTablet) { Note(stringResource(Res.string.usenet_diag_empty)) }
                }
            }
            return@item
        }
        val on = stringResource(Res.string.usenet_diag_on)
        val off = stringResource(Res.string.usenet_diag_off)

        SettingsSection(title = stringResource(Res.string.usenet_last_startup), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                DiagnosticsColumn(isTablet) {
                    DiagnosticRow(stringResource(Res.string.usenet_diag_when), report.recordedAtMs?.let(UsenetPlatform::dateTime) ?: NOT_MEASURED)
                    DiagnosticRow(stringResource(Res.string.usenet_fast_mkv), if (report.fastMkvStartup) on else off)
                    DiagnosticRow(stringResource(Res.string.usenet_fast_nzb), if (report.fastNzbFetch) on else off)
                    DiagnosticRow(stringResource(Res.string.usenet_diag_total), ms(report.totalMs), MaterialTheme.nuvio.colors.accent)
                    DiagnosticRow(stringResource(Res.string.usenet_diag_boot), ms(report.engineBootMs))
                    DiagnosticRow(stringResource(Res.string.usenet_diag_reused), if (report.engineReused) on else off)
                    DiagnosticRow(stringResource(Res.string.usenet_diag_session), ms(report.sessionMs))
                    DiagnosticRow(stringResource(Res.string.usenet_diag_handoff), ms(report.handoffMs))
                    DiagnosticRow(stringResource(Res.string.usenet_diag_player), ms(report.playerMs))
                    DiagnosticRow(stringResource(Res.string.usenet_diag_decoder), ms(report.decoderSetupMs))
                }
            }
        }

        Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
        SettingsSection(title = stringResource(Res.string.usenet_diag_engine), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                DiagnosticsColumn(isTablet) {
                    DiagnosticRow(
                        stringResource(Res.string.usenet_diag_prefetch),
                        when (report.prefetch) {
                            Prefetch.SKIPPED_FEW_CONNECTIONS -> stringResource(Res.string.usenet_diag_limited_connections)
                            Prefetch.HEAD_AND_TAIL_READY -> stringResource(Res.string.usenet_diag_head_tail_ready)
                            Prefetch.PARTIAL -> stringResource(Res.string.usenet_diag_prefetch_partial)
                            Prefetch.OFF -> off
                            Prefetch.NOT_APPLICABLE -> stringResource(Res.string.usenet_diag_not_applicable)
                        },
                    )
                    DiagnosticRow(stringResource(Res.string.usenet_diag_nzb), ms(report.nzbLoadMs))
                    DiagnosticRow(stringResource(Res.string.usenet_diag_selection), ms(report.selectionMs))
                    DiagnosticRow(stringResource(Res.string.usenet_diag_archive), ms(report.archiveDiscoveryMs))
                    DiagnosticRow(stringResource(Res.string.usenet_diag_range_wait), ms(report.longestRequestWaitMs))
                    DiagnosticRow(stringResource(Res.string.usenet_diag_head_ready), ms(report.headReadyMs))
                    DiagnosticRow(stringResource(Res.string.usenet_diag_tail_ready), ms(report.tailReadyMs))
                    DiagnosticRow(stringResource(Res.string.usenet_diag_first_bytes), ms(report.firstMediaBytesMs))
                    Note(stringResource(Res.string.usenet_diag_overlap))
                }
            }
        }

        Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
        SettingsSection(title = stringResource(Res.string.usenet_diag_nzb_stages), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                DiagnosticsColumn(isTablet) {
                    DiagnosticRow(stringResource(Res.string.usenet_diag_provider_setup), ms(report.providerSetupMs))
                    DiagnosticRow(stringResource(Res.string.usenet_diag_provider_first), ms(report.firstNntpMetadataMs))
                    DiagnosticRow(stringResource(Res.string.usenet_diag_lookup_time), ms(report.nzbCacheLookupMs))
                    DiagnosticRow(stringResource(Res.string.usenet_diag_headers_time), ms(report.nzbHeadersMs))
                    DiagnosticRow(stringResource(Res.string.usenet_diag_body_time), ms(report.nzbBodyReadMs))
                    DiagnosticRow(stringResource(Res.string.usenet_diag_parse_time), ms(report.nzbParseMs))
                    DiagnosticRow(stringResource(Res.string.usenet_diag_index_time), ms(report.nzbIndexMs))
                    DiagnosticRow(stringResource(Res.string.usenet_diag_io_time), ms(report.nzbCacheIoMs))
                    DiagnosticRow(stringResource(Res.string.usenet_diag_bind_time), ms(report.nzbCacheBindMs))
                    Note(stringResource(Res.string.usenet_diag_nzb_notes))
                }
            }
        }

        Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
        SettingsSection(title = stringResource(Res.string.usenet_diag_cache), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                DiagnosticsColumn(isTablet) {
                    DiagnosticRow(
                        stringResource(Res.string.usenet_diag_nzb_cache),
                        when (report.nzbLookup) {
                            NzbLookup.HIT -> stringResource(Res.string.usenet_diag_nzb_hit)
                            NzbLookup.DISABLED -> off
                            NzbLookup.MISS -> stringResource(Res.string.usenet_diag_nzb_miss)
                            NzbLookup.EXPIRED -> stringResource(Res.string.usenet_diag_nzb_expired)
                            NzbLookup.INVALID -> stringResource(Res.string.usenet_diag_nzb_invalid)
                            NzbLookup.DISK_ERROR -> stringResource(Res.string.usenet_diag_nzb_disk_error)
                            null -> NOT_MEASURED
                        },
                    )
                    DiagnosticRow(
                        stringResource(Res.string.usenet_diag_nzb_write),
                        when (report.nzbWrite) {
                            NzbWrite.SAVED -> stringResource(Res.string.usenet_diag_nzb_saved)
                            NzbWrite.OVERSIZED -> stringResource(Res.string.usenet_diag_nzb_oversized)
                            NzbWrite.CANCELLED -> stringResource(Res.string.usenet_diag_nzb_cancelled)
                            NzbWrite.INVALID -> stringResource(Res.string.usenet_diag_nzb_not_saved)
                            NzbWrite.DISK_ERROR -> stringResource(Res.string.usenet_diag_nzb_disk_error)
                            NzbWrite.BUSY -> stringResource(Res.string.usenet_diag_nzb_busy)
                            null -> NOT_MEASURED
                        },
                    )
                    DiagnosticRow(stringResource(Res.string.usenet_diag_nzb_size), mebibytes(report.nzbBytes?.toDouble()))
                    DiagnosticRow(stringResource(Res.string.usenet_diag_hits), count(report.articleCacheHits))
                    DiagnosticRow(stringResource(Res.string.usenet_diag_joins), count(report.sharedDownloads))
                    DiagnosticRow(stringResource(Res.string.usenet_diag_downloads), count(report.articleDownloads))
                    DiagnosticRow(stringResource(Res.string.usenet_diag_cancelled), count(report.cancelledDownloads))
                    DiagnosticRow(stringResource(Res.string.usenet_diag_memory), mebibytes(report.articleBufferBytes))
                }
            }
        }
    }
}

/** Shown for a value that was not measured or does not apply, as on NuvioTV. */
private const val NOT_MEASURED = "—"

internal fun ms(value: Double?): String = value?.takeIf { it.isFinite() && it >= 0 }?.let { "${it.roundToLong()} ms" } ?: NOT_MEASURED

private fun count(value: Long?): String = value?.toString() ?: NOT_MEASURED

private fun mebibytes(bytes: Double?): String {
    val mib = bytes?.takeIf { it.isFinite() && it >= 0 }?.div(1_048_576.0) ?: return NOT_MEASURED
    val tenths = (mib * 10).roundToLong()
    return "${tenths / 10}.${tenths % 10} MiB"
}

@Composable
private fun DiagnosticsColumn(isTablet: Boolean, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun DiagnosticRow(label: String, value: String, valueColor: Color = MaterialTheme.nuvio.colors.textPrimary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.nuvio.colors.textMuted,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.nuvio.colors.textMuted)
}
