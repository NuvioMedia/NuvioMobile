package com.nuvio.app.features.livetv.sports

import com.nuvio.app.features.livetv.record.RecordingPlatform
import com.nuvio.app.features.livetv.sync.LiveTvDriveHttp
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Game Alerts (game starting, scores for your teams): asking for notifications when they're
 * needed, and the one-time "Get alerts for …?" offer after you follow your first team.
 */
internal object GameAlerts {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Remembered on this phone only: the offer is shown once. */
    private const val OFFERED_KEY = "game_alerts_offered"

    private val _offer = MutableStateFlow<String?>(null)
    /** The team or league just followed, while the offer is waiting to be answered. */
    val offer: StateFlow<String?> = _offer.asStateFlow()

    /** Whether this phone can show alerts at all (and so whether asking makes sense). */
    val supported: Boolean get() = SportsLivePlatform.supported

    fun notificationsAllowed(): Boolean = !supported || RecordingPlatform.notificationsAllowed()

    /** Shows Android's "Allow notifications?" box when they're off; [onResult] gets whether they're on. */
    fun askForNotifications(onResult: (Boolean) -> Unit = {}) {
        if (notificationsAllowed()) { onResult(true); return }
        scope.launch {
            val ok = runCatching { EpisodeReleaseNotificationPlatform.requestAuthorization() }.getOrDefault(false)
            onResult(ok || notificationsAllowed())
        }
    }

    /** Asks first; when Android won't ask again (turned down before), opens the app's notification settings. */
    fun turnOnNotifications(onResult: (Boolean) -> Unit = {}) {
        askForNotifications { ok ->
            if (!ok) SportsLivePlatform.openNotificationSettings()
            onResult(ok)
        }
    }

    /** A team or league was just followed: offer alerts once, unless they're already all set. */
    fun onFollowed(name: String) {
        if (!supported) return
        if (runCatching { LiveTvDriveHttp.getString(OFFERED_KEY) }.getOrNull() == "1") return
        val s = SportsRepository.settings.value
        if (s.remindMyTeams && s.scoringAlerts && notificationsAllowed()) { markOffered(); return }
        _offer.value = name
    }

    /** "Turn on": both alerts on, then notifications. */
    fun acceptOffer() {
        markOffered()
        _offer.value = null
        SportsRepository.updateSettings { it.copy(remindMyTeams = true, scoringAlerts = true) }
        turnOnNotifications()
    }

    /** "Not now". */
    fun declineOffer() {
        markOffered()
        _offer.value = null
    }

    private fun markOffered() {
        runCatching { LiveTvDriveHttp.putString(OFFERED_KEY, "1") }
    }
}
