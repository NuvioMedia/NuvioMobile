package com.nuvio.app.features.party

import kotlin.math.abs

internal sealed interface PartyCorrection {
    data object InSync : PartyCorrection
    data class Seek(val positionMs: Long) : PartyCorrection
    data class Nudge(val rate: Float) : PartyCorrection
}

internal sealed interface OwnSeek {
    data class Behind(val positionMs: Long) : OwnSeek
    data object CatchUp : OwnSeek
    data object Blocked : OwnSeek
}

internal object PartySync {
    const val NUDGE_START_MS = 250L
    const val NUDGE_STOP_MS = 80L
    const val SEEK_THRESHOLD_MS = 1_500L
    const val PAUSED_TOLERANCE_MS = 500L
    const val NUDGE_FACTOR = 0.05f
    const val SEEK_NOTICE_MS = 2_000L
    const val REJOIN_GAP_MS = 2_000L
    const val BEHIND_STEP_MS = 5_000L

    fun expectedPositionMs(playback: PartyPlayback, nowMs: Long, durationMs: Long = 0L): Long {
        val position = if (playback.paused) {
            playback.positionMs
        } else {
            val elapsed = (nowMs - playback.at).coerceAtLeast(0L)
            playback.positionMs + (elapsed * playback.rate).toLong()
        }
        val clamped = position.coerceAtLeast(0L)
        return if (durationMs > 0L) clamped.coerceAtMost(durationMs) else clamped
    }

    fun isNewer(candidate: PartyPlayback, current: PartyPlayback?): Boolean {
        if (current == null) return true
        if (candidate.seq != current.seq) return candidate.seq > current.seq
        return candidate.memberId.orEmpty() > current.memberId.orEmpty()
    }

    fun correction(
        localPositionMs: Long,
        expectedPositionMs: Long,
        playback: PartyPlayback,
        nudging: Boolean,
    ): PartyCorrection {
        val drift = localPositionMs - expectedPositionMs
        val distance = abs(drift)
        if (playback.paused) {
            return if (distance > PAUSED_TOLERANCE_MS) PartyCorrection.Seek(expectedPositionMs) else PartyCorrection.InSync
        }
        val threshold = if (nudging) NUDGE_STOP_MS else NUDGE_START_MS
        return when {
            distance > SEEK_THRESHOLD_MS -> PartyCorrection.Seek(expectedPositionMs)
            distance > threshold -> {
                val factor = if (drift > 0L) 1f - NUDGE_FACTOR else 1f + NUDGE_FACTOR
                PartyCorrection.Nudge(playback.rate * factor)
            }
            else -> PartyCorrection.InSync
        }
    }

    fun coordinatorId(party: ActiveParty): String? {
        val online = party.presence.keys + party.memberId
        val host = party.hostMemberId
        if (host != null && host in online) return host
        if (party.hostOnly) return host
        return party.members.firstOrNull { it.id in online }?.id ?: party.memberId
    }

    fun actionOf(previous: PartyPlayback?, next: PartyPlayback): PartyAction? {
        if (previous == null || previous.waiting || next.waiting) return null
        if (previous.mediaKey != next.mediaKey) return null
        return when {
            previous.paused != next.paused -> if (next.paused) PartyAction.Paused else PartyAction.Resumed
            abs(previous.rate - next.rate) > 0.01f -> PartyAction.Speed
            abs(next.positionMs - expectedPositionMs(previous, next.at)) > SEEK_NOTICE_MS -> PartyAction.Seeked
            else -> null
        }
    }

    fun ownSeek(targetMs: Long, partyPositionMs: Long, behind: Boolean): OwnSeek = when {
        targetMs < partyPositionMs - REJOIN_GAP_MS -> OwnSeek.Behind(targetMs)
        behind -> OwnSeek.CatchUp
        else -> OwnSeek.Blocked
    }

    fun shouldRejoin(gapMs: Long, playing: Boolean): Boolean = playing && gapMs <= REJOIN_GAP_MS

    fun reportedGapMs(gapMs: Long): Long =
        ((gapMs.coerceAtLeast(0L) + BEHIND_STEP_MS / 2) / BEHIND_STEP_MS * BEHIND_STEP_MS).coerceAtLeast(BEHIND_STEP_MS)

    fun waitingMemberIds(party: ActiveParty, ignored: Set<String>): List<String> =
        party.presence.values
            .filter { it.mediaKey == party.media.key }
            .filter { it.status == PartyMemberStatus.Loading || it.status == PartyMemberStatus.Buffering }
            .map { it.memberId }
            .filterNot { it in ignored }
}
