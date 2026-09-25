package com.nuvio.app.features.servers.jellyfin

import com.nuvio.app.features.servers.ServerItemRef
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JellyfinMapperTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val mapper = JellyfinMapper("https://media.example.com/jellyfin", "cabc")

    private val movie = json.decodeFromString(
        JellyfinItem.serializer(),
        """
        {
          "Id": "f1c9a0",
          "Name": "Director's Cut",
          "Type": "Movie",
          "ProductionYear": 2007,
          "CommunityRating": 7.84,
          "RunTimeTicks": 72000000000,
          "ProviderIds": {"Imdb": "tt0111161", "Tmdb": "278", "Tvdb": null},
          "ImageTags": {"Primary": "p1"},
          "BackdropImageTags": ["b1"],
          "MediaSources": [
            {"Id": "ms1", "Name": "4K Remux", "Path": "/media/movie.mkv", "Container": "mkv", "Size": 50000000000,
             "MediaStreams": [{"Type": "Video", "Width": 3840, "Height": 1600, "DisplayTitle": "4K HEVC HDR"}]},
            {"Id": "ms2", "Name": "1080p", "Path": "/media/movie-1080p.mp4", "Container": "mp4"}
          ]
        }
        """,
    )

    @Test
    fun mapsPreviewWithNativeIdentityAndUnauthenticatedArtwork() {
        val preview = mapper.preview(movie)!!
        assertEquals(ServerItemRef("cabc", "f1c9a0"), ServerItemRef.parse(preview.id))
        assertEquals("movie", preview.type)
        assertEquals("Director's Cut", preview.name)
        assertEquals("2007", preview.releaseInfo)
        assertEquals("7.8", preview.imdbRating)
        assertTrue(preview.poster!!.startsWith("https://media.example.com/jellyfin/Items/f1c9a0/Images/Primary?tag=p1"))
        assertFalse(preview.poster!!.contains("api_key"))
    }

    @Test
    fun keepsExternalIdsSeparateFromNativeId() {
        val ids = movie.externalIds()
        assertEquals("tt0111161", ids.imdb)
        assertEquals(278L, ids.tmdb)
        assertNull(ids.tvdb)
        assertEquals("tt0111161", mapper.details(movie, emptyList()).imdbId)
    }

    @Test
    fun listsEveryVersionAsCandidate() {
        val candidates = mapper.candidates(movie)
        assertEquals(listOf("ms1", "ms2"), candidates.map { it.target.mediaSourceId })
        assertEquals("4K Remux", candidates.first().title)
        assertEquals("movie.mkv", candidates.first().filename)
    }

    @Test
    fun missingItemsHaveNoCandidates() {
        assertTrue(mapper.candidates(movie.copy(locationType = "Virtual")).isEmpty())
    }

    @Test
    fun mapsEpisodesWithNativeIdsAndAvailability() {
        val episode = json.decodeFromString(
            JellyfinItem.serializer(),
            """{"Id": "e42", "Name": "Pilot", "Type": "Episode", "ParentIndexNumber": 0, "IndexNumber": 1, "LocationType": "Virtual"}""",
        )
        val video = mapper.video(episode)
        assertEquals(ServerItemRef("cabc", "e42").encode(), video.id)
        assertEquals(0, video.season)
        assertEquals(1, video.episode)
        assertFalse(video.available)
    }

    @Test
    fun mapsCollectionsAndCollectionLibraries() {
        assertEquals("collection", mapper.preview(movie.copy(type = "BoxSet"))!!.type)
        assertEquals("collection", mapper.preview(movie.copy(type = "Folder"))!!.type)
        assertEquals(com.nuvio.app.features.servers.ServerMediaKind.COLLECTION, libraryKind("boxsets"))
        assertNull(libraryKind("music"))
    }

    @Test
    fun readsEveryKnownProviderId() {
        val ids = movie.copy(
            providerIds = mapOf("Imdb" to "tt1", "AniList" to "21", "Kitsu" to "7442", "MyAnimeList" to "5114", "Zap2It" to "EP1"),
        ).externalIds()
        assertEquals("tt1", ids.imdb)
        assertEquals(21L, ids.anilist)
        assertEquals(7442L, ids.kitsu)
        assertEquals(5114L, ids.mal)
        assertNull(ids.trakt)
    }

    @Test
    fun ignoresUnsupportedItemTypes() {
        assertNull(mapper.preview(movie.copy(type = "MusicAlbum")))
    }
}
