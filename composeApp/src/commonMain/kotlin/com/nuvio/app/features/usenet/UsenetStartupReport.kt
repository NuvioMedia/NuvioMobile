package com.nuvio.app.features.usenet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * The last Usenet startup, read from the report the engine sidecar keeps: what the diagnostics
 * page shows, as NuvioTV's startup cards compute it. Durations are milliseconds; null means not
 * measured or not applicable. Overlapping stages are not summed.
 */
data class UsenetStartupReport(
    val recordedAtMs: Long? = null,
    val fastMkvStartup: Boolean = false,
    val fastNzbFetch: Boolean = true,
    /** From the engine lock to the first frame on screen. */
    val totalMs: Double? = null,
    val engineBootMs: Double? = null,
    val engineReused: Boolean = false,
    val sessionMs: Double? = null,
    val handoffMs: Double? = null,
    val playerMs: Double? = null,
    val decoderSetupMs: Double? = null,
    val prefetch: Prefetch = Prefetch.NOT_APPLICABLE,
    val nzbLoadMs: Double? = null,
    val selectionMs: Double? = null,
    val archiveDiscoveryMs: Double? = null,
    val longestRequestWaitMs: Double? = null,
    val headReadyMs: Double? = null,
    val tailReadyMs: Double? = null,
    val firstMediaBytesMs: Double? = null,
    val providerSetupMs: Double? = null,
    val firstNntpMetadataMs: Double? = null,
    val nzbCacheLookupMs: Double? = null,
    val nzbHeadersMs: Double? = null,
    val nzbBodyReadMs: Double? = null,
    val nzbParseMs: Double? = null,
    val nzbIndexMs: Double? = null,
    val nzbCacheIoMs: Double? = null,
    val nzbCacheBindMs: Double? = null,
    val nzbLookup: NzbLookup? = null,
    val nzbWrite: NzbWrite? = null,
    /** Shown when the NZB came from the cache or was saved to it. */
    val nzbBytes: Long? = null,
    val articleCacheHits: Long? = null,
    val sharedDownloads: Long? = null,
    val articleDownloads: Long? = null,
    val cancelledDownloads: Long? = null,
    val articleBufferBytes: Double? = null,
) {
    enum class Prefetch { SKIPPED_FEW_CONNECTIONS, HEAD_AND_TAIL_READY, PARTIAL, OFF, NOT_APPLICABLE }
    enum class NzbLookup { HIT, DISABLED, MISS, EXPIRED, INVALID, DISK_ERROR }
    enum class NzbWrite { SAVED, OVERSIZED, CANCELLED, INVALID, DISK_ERROR, BUSY }

    companion object {
        /** Null for an empty or unreadable report. */
        fun parse(report: String): UsenetStartupReport? {
            if (report.isBlank()) return null
            val root = runCatching { Json.parseToJsonElement(report).jsonObject }.getOrNull() ?: return null
            val marks = root.obj("marksMs")
            val engine = root.obj("engine")
            val engineMarks = engine.obj("marksMs")
            val durations = engine.obj("durationsMs")
            val fast = root.bool("fastMkvStartup") ?: false
            val nzb = engine["nzbCache"] as? JsonObject
            val lookup = when (nzb?.text("lookup")) {
                "hit" -> NzbLookup.HIT
                "disabled" -> NzbLookup.DISABLED
                "miss" -> when (nzb.text("reason")) {
                    "expired" -> NzbLookup.EXPIRED
                    "invalid" -> NzbLookup.INVALID
                    "disk_error" -> NzbLookup.DISK_ERROR
                    else -> NzbLookup.MISS
                }
                else -> null
            }
            val write = when (nzb?.text("write")) {
                "saved" -> NzbWrite.SAVED
                "oversized" -> NzbWrite.OVERSIZED
                "cancelled" -> NzbWrite.CANCELLED
                "invalid" -> NzbWrite.INVALID
                "disk_error" -> NzbWrite.DISK_ERROR
                "busy" -> NzbWrite.BUSY
                else -> null
            }
            val ranges = engine["ranges"] as? JsonArray
            val waits = ranges.orEmpty().mapNotNull { element ->
                val range = element as? JsonObject ?: return@mapNotNull null
                val first = range.number("firstByteMs")?.takeIf { it >= 0 } ?: return@mapNotNull null
                first - (range.number("startMs") ?: return@mapNotNull null)
            }
            val store = engine.obj("store")
            return UsenetStartupReport(
                recordedAtMs = root.long("recordedAtMs")?.takeIf { it > 0 },
                fastMkvStartup = fast,
                fastNzbFetch = root.bool("fastNzbFetch") ?: true,
                totalMs = marks.span("first_frame"),
                engineBootMs = marks.span("engine_ready", "resolve_lock_acquired"),
                engineReused = "sidecar_reused" in marks || "prepared_session_reused" in marks,
                sessionMs = marks.span("session_response", "session_request"),
                handoffMs = marks.span("prepare", "resolved"),
                playerMs = marks.span("first_frame", "prepare"),
                decoderSetupMs = marks.span("decoder_init_duration"),
                prefetch = when {
                    "warmup_skipped_connection_limit" in engineMarks -> Prefetch.SKIPPED_FEW_CONNECTIONS
                    "head_article_ready" in engineMarks && "tail_article_ready" in engineMarks -> Prefetch.HEAD_AND_TAIL_READY
                    "warmup_started" in engineMarks -> Prefetch.PARTIAL
                    !fast -> Prefetch.OFF
                    else -> Prefetch.NOT_APPLICABLE
                },
                nzbLoadMs = engineMarks.span("nzb_loaded", "pool_created"),
                selectionMs = engineMarks.span("content_selected", "nzb_loaded"),
                archiveDiscoveryMs = engine.number("archiveDiscoveryMs")?.takeIf { it >= 0 },
                longestRequestWaitMs = waits.maxOrNull(),
                headReadyMs = engineMarks.span("head_article_ready", "warmup_started"),
                tailReadyMs = engineMarks.span("tail_article_ready", "warmup_started"),
                firstMediaBytesMs = engineMarks.span("first_media_bytes"),
                providerSetupMs = engineMarks.span("pool_created", "provider_setup_started"),
                firstNntpMetadataMs = engineMarks.span("first_nntp_metadata", "first_nntp_request"),
                nzbCacheLookupMs = engineMarks.span("nzb_cache_lookup_finished", "nzb_cache_lookup_started"),
                nzbHeadersMs = engineMarks.span("nzb_response_headers", "nzb_fetch_started"),
                nzbBodyReadMs = durations.number("nzb_body_read")?.takeIf { it >= 0 },
                nzbParseMs = durations.number("nzb_parse_work")?.takeIf { it >= 0 },
                nzbIndexMs = engineMarks.span("nzb_index_finished", "nzb_index_started"),
                nzbCacheIoMs = durations.number("nzb_cache_io")?.takeIf { it >= 0 },
                nzbCacheBindMs = engineMarks.span("nzb_cache_bound", "nzb_index_finished"),
                nzbLookup = lookup,
                nzbWrite = write,
                nzbBytes = nzb?.takeIf { lookup == NzbLookup.HIT || write == NzbWrite.SAVED }?.long("bytes"),
                articleCacheHits = store.long("cacheHits"),
                sharedDownloads = store.long("sharedJoins"),
                articleDownloads = store.long("downloads"),
                cancelledDownloads = store.long("cancellations"),
                articleBufferBytes = engine.number("articleSlabsBytes"),
            )
        }

        private fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())

        private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

        private fun JsonObject.number(key: String): Double? = primitive(key)?.doubleOrNull?.takeIf { it.isFinite() }

        private fun JsonObject.long(key: String): Long? = primitive(key)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

        private fun JsonObject.bool(key: String): Boolean? = primitive(key)?.booleanOrNull

        private fun JsonObject.text(key: String): String? = primitive(key)?.contentOrNull

        /** [end] − [start], or [end] alone without a start; null when a mark is missing or the span is negative. */
        private fun JsonObject.span(end: String, start: String? = null): Double? {
            val endMs = number(end) ?: return null
            val startMs = if (start == null) 0.0 else number(start) ?: return null
            return (endMs - startMs).takeIf { it >= 0 }
        }

        private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
    }
}
