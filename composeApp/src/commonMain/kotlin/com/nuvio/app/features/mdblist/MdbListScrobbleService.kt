package com.nuvio.app.features.mdblist

import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleEvent

class MdbListScrobbleService(
    private val api: MdbListApiClient,
    private val sync: MdbListSyncRepository
) {
    suspend fun scrobble(scope: MdbListAuthScope, action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        if (!event.progressPercent.isFinite() || event.media.ids.toMdbListIds() == null) return
        sync.write(scope, setOf(MdbListSyncBucket.WATCHED, MdbListSyncBucket.PLAYBACK)) { snapshot ->
            val target = snapshot.mutationTarget(event.media) ?: return@write snapshot to Unit
            if (target.type == MdbListItemType.SHOW || !target.scrobbleCoordinatesResolved) return@write snapshot to Unit
            val response = api.post("/scrobble/${action.wireValue}", target.scrobbleBody(event.progressPercent.coerceIn(0.0, 100.0)).toString(), scope)
            val receipt = decodeMdbListScrobbleReceipt(response, target, action, kotlin.time.Clock.System.now().toEpochMilliseconds())
            snapshot.applyScrobble(receipt) to Unit
        }
    }

    suspend fun clear(scope: MdbListAuthScope, contentId: String, season: Int?, episode: Int?) {
        sync.ensureLoaded()
        val sessions = sync.currentSnapshot()?.playback.orEmpty().filter { session ->
            contentId in session.media.ids.aliases() &&
                (season == null || episode == null || session.season == season && session.episode == episode)
        }
        for (session in sessions) {
            sync.write(scope, setOf(MdbListSyncBucket.PLAYBACK)) { snapshot ->
                val target = MdbListMutationTarget(session.type, session.media, session.season, session.episode)
                try {
                    val response = api.post("/scrobble/clear", target.scrobbleBody().toString(), scope)
                    val body = mdbListResponseElement(response.body).objectValue()
                    if (body.text("action") != "clear" || body.flag("deleted") == null) throw MdbListDecodingException()
                } catch (error: MdbListApiException) {
                    if (error.status != 404) throw error
                }
                snapshot.copy(playback = snapshot.playback.filterNot(target::matches)) to Unit
            }
        }
    }
}
