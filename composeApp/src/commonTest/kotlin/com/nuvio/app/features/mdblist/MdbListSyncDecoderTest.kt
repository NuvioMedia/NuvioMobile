package com.nuvio.app.features.mdblist

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class MdbListSyncDecoderTest {
    @Test
    fun `watched episodes preserve show identity and separate episode IDs`() {
        val result = decodeMdbListWatched("""{
          "episodes":[{"last_watched_at":"2026-09-01T12:00:00Z","episode":{
            "season":2,"number":3,"name":"Third","ids":{"tmdb":500,"tvdb":600},
            "show":{"title":"Series","ids":{"imdb":"tt1234","tmdb":50,"mdblist":"abc"}}
          }}],"pagination":{"next_cursor":"opaque+/="}
        }""")

        val episode = result.items.single()
        assertEquals(50L, episode.media.ids.tmdb)
        assertEquals("tt1234", episode.media.ids.contentId)
        assertEquals(500L, episode.episodeTmdbId)
        assertEquals(600L, episode.episodeTvdbId)
        assertEquals(2, episode.season)
        assertEquals(3, episode.episode)
        assertEquals("opaque+/=", result.nextCursor)
    }

    @Test
    fun `all watched types and explicit nested episodes decode without inventing history`() {
        val result = decodeMdbListWatched("""{
          "movies":[{"watched_at":"2026-09-01T12:00:00+00:00","movie":{"ids":{"tmdb":1}}}],
          "shows":[{"watched_at":"2026-09-01T12:00:00Z","show":{"ids":{"tmdb":2},"total_aired_episodes":100},
            "seasons":[{"number":1,"episodes":[{"number":4,"watched_at":"2026-09-01T12:00:00Z"},{"number":5}]}]}],
          "seasons":[{"watched_at":"2026-09-01T12:00:00Z","season":{"number":2,"ids":{"tmdb":200},"show":{"ids":{"tmdb":2}}}}]
        }""")

        assertEquals(4, result.items.size)
        assertEquals(listOf(4), result.items.filter { it.type == MdbListItemType.EPISODE }.map { it.episode })
        assertEquals(2L, result.items.single { it.type == MdbListItemType.SEASON }.media.ids.tmdb)
    }

    @Test
    fun `incomplete identity coordinates or dates fail instead of publishing partial history`() {
        val bodies = listOf(
            "{}", "[]", "{broken}",
            """{"movies":[{"watched_at":"today","movie":{"ids":{"tmdb":1}}}]}""",
            """{"movies":[{"watched_at":"2026-09-01T12:00:00Z","movie":{"ids":{"tmdb":0}}}]}""",
            """{"episodes":[{"watched_at":"2026-09-01T12:00:00Z","episode":{"number":1,"ids":{"tmdb":500},"show":{"ids":{"tmdb":5}}}}]}"""
        )
        bodies.forEach { body -> assertDecodingFails { decodeMdbListWatched(body) } }
    }

    @Test
    fun `paused episodes accept legacy identifier names and unknown runtime`() {
        val result = decodeMdbListPlayback("""[{
          "id":7,"type":"episode","progress":42.25,"runtime":0,"updated_at":"2026-09-01T12:00:00Z",
          "show":{"title":"Series","ids":{"imdbid":"tt1234","tmdbid":50}},
          "episode":{"season":0,"number":1,"title":"Special","ids":{"tmdbid":500}}
        }]""").single()

        assertEquals(42.25f, result.progress, 0f)
        assertEquals(50L, result.media.ids.tmdb)
        assertEquals(500L, result.episodeTmdbId)
        assertEquals(0, result.season)
        assertNull(result.runtimeMinutes)
    }

    @Test
    fun `invalid playback percentages and missing coordinates are rejected`() {
        for (progress in listOf("NaN", "Infinity", "-1", "101")) {
            assertDecodingFails {
                decodeMdbListPlayback("""[{"id":1,"type":"movie","progress":"$progress","paused_at":"2026-09-01T12:00:00Z","movie":{"ids":{"tmdb":1}}}]""")
            }
        }
        assertDecodingFails {
            decodeMdbListPlayback("""[{"id":1,"type":"episode","progress":10,"paused_at":"2026-09-01T12:00:00Z","show":{"ids":{"tmdb":1}},"episode":{"number":1}}]""")
        }
    }

    @Test
    fun `dropped seasons use parent identifiers and retain season zero`() {
        val show = decodeMdbListDropped("""{"shows":[{"show":{"ids":{"tmdb":5}}}]}""", false).items.single()
        val season = decodeMdbListDropped("""{"seasons":[{"season":{"number":0,"ids":{"tmdb":500},"show":{"ids":{"tmdb":5}}}}]}""", true).items.single()
        assertEquals(5L, show.ids.tmdb)
        assertNull(show.season)
        assertEquals(5L, season.ids.tmdb)
        assertEquals(0, season.season)
    }

    @Test
    fun `journal separates server action from backdated watch time and ignores ratings`() {
        val result = decodeMdbListJournal("""{"journal":[
          {"category":"rated"},
          {"category":"watched","item_type":"episode","ids":{"mdblist":"abc","tmdb":5},"status":"added","action_at":"2026-09-01T12:00:00Z","value_at":"2020-01-01T00:00:00Z","season":1,"episode":3,"episode_tmdb_id":500},
          {"category":"watched","item_type":"movie","ids":{"tmdb":6},"status":"removed","action_at":"2026-09-01T12:00:01Z","value_at":null}
        ],"server_time":"2026-09-01T12:00:02Z","pagination":{"has_more":false}}""")

        assertEquals(2, result.items.size)
        assertEquals("2020-01-01T00:00:00Z", result.items.first().watchedAt)
        assertEquals(5L, result.items.first().ids.tmdb)
        assertEquals(500L, result.items.first().episodeTmdbId)
        assertTrue(result.items.last().removed)
    }

    @Test
    fun `empty filtered journal page still preserves its continuation`() {
        val page = decodeMdbListJournal("""{"journal":[{"category":"rated"}],"pagination":{"has_more":true,"next_cursor":"next"}}""")
        assertTrue(page.items.isEmpty())
        assertEquals("next", page.nextCursor)
    }

    @Test
    fun `expired journals request full sync and malformed journals fail`() {
        assertTrue(decodeMdbListJournal("""{"requires_full_sync":true,"reason":"sync_window_expired"}""").requiresFullSync)
        listOf("{}", """{"journal":null}""", """{"journal":[],"pagination":{"has_more":true}}""").forEach { body ->
            assertDecodingFails { decodeMdbListJournal(body) }
        }
    }

    @Test
    fun `activity comparison includes removals and tolerates unrelated metadata`() {
        val previous = decodeMdbListActivities("""{"server_time":"2026-09-01T12:00:00Z","watched_at":null,"journal_at":null,"paused_at":null,"dropped_at":null,"version":2}""")
        val next = decodeMdbListActivities("""{"server_time":"2026-09-01T12:00:10Z","watched_at":null,"journal_at":"2026-09-01T12:00:05Z","paused_at":null,"dropped_at":null,"version":3}""")
        assertTrue(next.watchedChanged(previous))
        assertEquals(false, next.playbackChanged(previous))
        assertEquals(false, next.droppedChanged(previous))
        assertEquals(false, next.values.containsKey("version"))
    }

    @Test
    fun `legacy pagination continues by total and cursor pagination takes priority`() {
        val offset = decodeMdbListWatched("""{"movies":[],"pagination":{"total":3000,"offset":0,"limit":1000}}""")
        val cursor = decodeMdbListWatched("""{"movies":[],"pagination":{"total":3000,"offset":0,"limit":1000,"next_cursor":"next"}}""")
        assertEquals(1000, offset.nextOffset)
        assertNull(cursor.nextOffset)
        assertEquals("next", cursor.nextCursor)
    }

    private fun assertDecodingFails(block: () -> Any) {
        try {
            block()
            throw AssertionError("Expected incomplete response to fail")
        } catch (_: MdbListDecodingException) {
        }
    }
}
