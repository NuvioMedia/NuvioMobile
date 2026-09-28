package com.nuvio.app.features.simkl

import com.nuvio.app.features.tracking.TrackingEpisode
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingHistoryItem
import com.nuvio.app.features.tracking.TrackingListStatus
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimklMutationReconciliationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `list response commits the server rewritten status without advancing watermark`() {
        val request = movie()
        val receipt = response(
            """
            {
              "added": {
                "movies": [
                  {
                    "to": "completed",
                    "ids": {"simkl": 472214, "imdb": "tt1375666"},
                    "type": "movie"
                  }
                ],
                "shows": []
              },
              "not_found": {"movies": [], "shows": []}
            }
            """,
        ).toListMutationReceipt(listOf(request), json)

        val updated = SimklSyncSnapshot(
            isInitialized = true,
            watermark = "v1",
        ).applyMutationReceipt(receipt, 1_700_000_000_000L)

        assertEquals("v1", updated.watermark)
        assertEquals(SimklListStatus.COMPLETED, updated.entries.single().status)
        assertEquals("472214", updated.entries.single().media?.ids?.simklIdValue())
        assertFalse(receipt.requiresReconciliation)
        assertEquals(listOf(TrackingListStatus.COMPLETED), receipt.result.resolvedListStatuses)
    }

    @Test
    fun `list response keeps catalog poster as a local fallback`() {
        val localPoster = "https://catalog.example/poster.webp"
        val request = movie().copy(posterUrl = localPoster)
        val receipt = response(
            """
            {
              "added": {
                "movies": [
                  {
                    "to": "plantowatch",
                    "ids": {"simkl": 472214, "imdb": "tt1375666"},
                    "type": "movie"
                  }
                ],
                "shows": []
              },
              "not_found": {"movies": [], "shows": []}
            }
            """,
        ).toListMutationReceipt(listOf(request), json)

        val updated = SimklSyncSnapshot()
            .applyMutationReceipt(receipt, 1_700_000_000_000L)

        assertEquals(localPoster, updated.entries.single().localPosterUrl)
        assertEquals(localPoster, updated.toSimklLibraryProjection().items.single().poster)
        assertFalse(receipt.requiresReconciliation)
    }

    @Test
    fun `partial list response commits only items present in added`() {
        val accepted = movie()
        val missing = movie().copy(
            title = "Missing",
            ids = TrackingExternalIds(imdb = "tt0000000"),
        )
        val receipt = response(
            """
            {
              "added": {
                "movies": [
                  {
                    "to": "plantowatch",
                    "ids": {"simkl": 472214, "imdb": "tt1375666"},
                    "type": "movie"
                  }
                ],
                "shows": []
              },
              "not_found": {
                "movies": [{"title": "Missing", "ids": {"imdb": "tt0000000"}}],
                "shows": []
              }
            }
            """,
        ).toListMutationReceipt(listOf(accepted, missing), json)

        val updated = SimklSyncSnapshot().applyMutationReceipt(receipt, 1_700_000_000_000L)

        assertEquals(1, updated.entries.size)
        assertEquals(SimklListStatus.PLAN_TO_WATCH, updated.entries.single().status)
        assertEquals(1, receipt.result.notFoundCount)
        assertFalse(receipt.result.isComplete)
    }

    @Test
    fun `newly watched movie has a poster before the next library refresh`() {
        val posterUrl = "https://catalog.example/poster.webp"
        val receipt = movieHistoryReceipt(posterUrl)
        val snapshot = SimklSyncSnapshot(
            isInitialized = true,
            watermark = "v1",
            lastCheckedAtEpochMs = 1_700_000_000_000L,
        )

        val updated = snapshot.applyMutationReceipt(receipt, 1_700_000_100_000L)
        val item = updated.toSimklLibraryProjection().items.single()

        assertEquals("tt1375666", item.id)
        assertEquals(setOf("simkl:status:completed"), item.listKeys)
        assertEquals(posterUrl, item.poster)
        assertNull(updated.entries.single().media?.poster)
        assertEquals("v1", updated.watermark)
        assertEquals(snapshot.lastCheckedAtEpochMs, updated.lastCheckedAtEpochMs)
        assertFalse(receipt.requiresReconciliation)
    }

    @Test
    fun `marking a movie watched preserves existing library artwork`() {
        val existingPoster = "https://catalog.example/existing.webp"
        val receipt = movieHistoryReceipt("https://catalog.example/new.webp")
        listOf(null, "movie-poster").forEach { simklPoster ->
            val snapshot = SimklSyncSnapshot(
                entries = listOf(
                    SimklLibraryEntry(
                        mediaType = SimklMediaType.MOVIES,
                        status = SimklListStatus.PLAN_TO_WATCH,
                        localPosterUrl = existingPoster,
                        movie = movieMedia().copy(poster = simklPoster),
                    ),
                ),
            )

            val updated = snapshot.applyMutationReceipt(receipt, 1_700_000_100_000L)

            assertEquals(existingPoster, updated.entries.single().localPosterUrl)
            assertEquals(
                simklPosterUrl(simklPoster) ?: existingPoster,
                updated.toSimklLibraryProjection().items.single().poster,
            )
        }
    }

    @Test
    fun `history response records episode and resolved anime classification locally`() {
        val request = TrackingHistoryItem(
            media = anime(TrackingEpisode(number = 3)),
            watchedAtEpochMs = 1_700_000_000_000L,
        )
        val receipt = response(
            """
            {
              "added": {
                "movies": 0,
                "shows": 1,
                "episodes": 1,
                "statuses": [
                  {
                    "request": {
                      "ids": {"simkl": 39687, "mal": 16498},
                      "type": "show"
                    },
                    "response": {
                      "status": "watching",
                      "simkl_type": "anime",
                      "anime_type": "tv"
                    }
                  }
                ]
              },
              "not_found": {"movies": [], "shows": [], "episodes": []}
            }
            """,
        ).toHistoryMutationReceipt(listOf(request), json)

        val updated = SimklSyncSnapshot(
            isInitialized = true,
            watermark = "v1",
        ).applyMutationReceipt(receipt, 1_700_000_100_000L)

        val entry = updated.entries.single()
        assertEquals("v1", updated.watermark)
        assertEquals(SimklMediaType.ANIME, entry.mediaType)
        assertEquals(SimklListStatus.WATCHING, entry.status)
        assertEquals("tv", entry.animeType)
        assertEquals("2023-11-14T22:13:20Z", entry.seasons.single().episodes.single().watchedAt)
        assertFalse(receipt.requiresReconciliation)
    }

    @Test
    fun `whole show history response keeps reconciliation because server expands episodes`() {
        val request = TrackingHistoryItem(media = show())
        val receipt = response(
            """
            {
              "added": {
                "movies": 0,
                "shows": 1,
                "episodes": 6,
                "statuses": [
                  {
                    "request": {"ids": {"simkl": 2090}, "type": "show"},
                    "response": {"status": "watching", "simkl_type": "tv"}
                  }
                ]
              },
              "not_found": {"movies": [], "shows": [], "episodes": []}
            }
            """,
        ).toHistoryMutationReceipt(listOf(request), json)

        assertTrue(receipt.requiresReconciliation)
    }

    @Test
    fun `episode history removal clears watched state and retains reconciliation`() {
        val request = show(TrackingEpisode(season = 1, number = 2))
        val receipt = response(
            """
            {
              "deleted": {"movies": 0, "shows": 0, "episodes": 1},
              "not_found": {"movies": [], "shows": []}
            }
            """,
        ).toHistoryRemovalReceipt(listOf(request), json)
        val media = showMedia()
        val updated = SimklSyncSnapshot(
            isInitialized = true,
            watermark = "v1",
            entries = listOf(
                SimklLibraryEntry(
                    mediaType = SimklMediaType.SHOWS,
                    status = SimklListStatus.WATCHING,
                    lastWatchedAt = "2023-11-14T22:13:20Z",
                    watchedEpisodesCount = 1,
                    show = media,
                    seasons = listOf(
                        SimklSeason(
                            number = 1,
                            episodes = listOf(
                                SimklEpisode(number = 2, watchedAt = "2023-11-14T22:13:20Z"),
                            ),
                        ),
                    ),
                ),
            ),
        ).applyMutationReceipt(receipt, 1_700_000_100_000L)

        val entry = updated.entries.single()
        assertEquals("v1", updated.watermark)
        assertNull(entry.seasons.single().episodes.single().watchedAt)
        assertEquals(0, entry.watchedEpisodesCount)
        assertNull(entry.lastWatchedAt)
        assertTrue(receipt.requiresReconciliation)
        assertEquals(1, (receipt.mutation as SimklCommittedMutation.RemoveFromHistory).deleted.episodes)
    }

    @Test
    fun `whole title removal deletes local entry without reconciliation`() {
        val request = movie()
        val receipt = response(
            """
            {
              "deleted": {"movies": 1, "shows": 0, "episodes": 0},
              "not_found": {"movies": [], "shows": []}
            }
            """,
        ).toHistoryRemovalReceipt(listOf(request), json)
        val snapshot = SimklSyncSnapshot(
            isInitialized = true,
            watermark = "v1",
            entries = listOf(
                SimklLibraryEntry(
                    mediaType = SimklMediaType.MOVIES,
                    status = SimklListStatus.COMPLETED,
                    movie = movieMedia(),
                ),
            ),
        )

        val updated = snapshot.applyMutationReceipt(receipt, 1_700_000_000_000L)

        assertTrue(updated.entries.isEmpty())
        assertEquals("v1", updated.watermark)
        assertFalse(receipt.requiresReconciliation)
    }

    private fun movieHistoryReceipt(posterUrl: String): SimklMutationReceipt = response(
        """
        {
          "added": {
            "movies": 1,
            "shows": 0,
            "episodes": 0,
            "statuses": [
              {
                "request": {"ids": {"simkl": 472214, "imdb": "tt1375666"}, "type": "movie"},
                "response": {"status": "completed", "simkl_type": "movie"}
              }
            ]
          },
          "not_found": {"movies": [], "shows": [], "episodes": []}
        }
        """,
    ).toHistoryMutationReceipt(
        listOf(TrackingHistoryItem(media = movie().copy(posterUrl = posterUrl))),
        json,
    )

    private fun response(body: String) = SimklApiResponse(
        status = 201,
        body = body,
        headers = emptyMap(),
    )

    private fun movie() = TrackingMediaReference(
        kind = TrackingMediaKind.MOVIE,
        title = "Inception",
        year = 2010,
        ids = TrackingExternalIds(simkl = 472214, imdb = "tt1375666"),
    )

    private fun show(episode: TrackingEpisode? = null) = TrackingMediaReference(
        kind = TrackingMediaKind.SHOW,
        title = "The Walking Dead",
        year = 2010,
        ids = TrackingExternalIds(simkl = 2090, imdb = "tt1520211"),
        episode = episode,
    )

    private fun anime(episode: TrackingEpisode? = null) = TrackingMediaReference(
        kind = TrackingMediaKind.ANIME,
        title = "Attack on Titan",
        year = 2013,
        ids = TrackingExternalIds(simkl = 39687, mal = 16498),
        episode = episode,
    )

    private fun movieMedia() = SimklMedia(
        title = "Inception",
        year = 2010,
        ids = mapOf(
            "simkl" to JsonPrimitive(472214L),
            "imdb" to JsonPrimitive("tt1375666"),
        ),
    )

    private fun showMedia() = SimklMedia(
        title = "The Walking Dead",
        year = 2010,
        ids = mapOf(
            "simkl" to JsonPrimitive(2090L),
            "imdb" to JsonPrimitive("tt1520211"),
        ),
    )
}
