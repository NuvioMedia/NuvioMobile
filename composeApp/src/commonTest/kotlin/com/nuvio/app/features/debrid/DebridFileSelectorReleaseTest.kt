package com.nuvio.app.features.debrid

import com.nuvio.app.features.streams.StreamClientResolve
import kotlin.test.Test
import kotlin.test.assertEquals

class DebridFileSelectorReleaseTest {
    @Test
    fun `recorded releases select the requested file across providers and file orders`() {
        for (release in releases) {
            val resolve = StreamClientResolve(
                filename = release.filename,
                torrentName = release.description,
                fileIdx = release.fileIdx,
                season = release.season,
                episode = release.episode,
            )
            val expected = release.files.single { it.id == release.expectedId }
            for (files in listOf(release.files, release.files.reversed())) {
                assertEquals(
                    expected.id,
                    TorboxFileSelector().selectFile(files, resolve, release.season, release.episode)?.id,
                    release.title,
                )
                assertEquals(
                    expected.id,
                    RealDebridFileSelector().selectFile(
                        files.map { RealDebridTorrentFileDto(id = it.id, path = it.name, bytes = it.size) },
                        resolve,
                        release.season,
                        release.episode,
                    )?.id,
                    release.title,
                )
                assertEquals(
                    expected.name,
                    PremiumizeDirectDownloadFileSelector().selectFile(
                        files.map { PremiumizeDirectDownloadFileDto(path = it.name, size = it.size, link = "https://example.com/${it.id}") },
                        resolve,
                        release.season,
                        release.episode,
                    )?.path,
                    release.title,
                )
            }
        }
    }

    private data class Release(
        val title: String,
        val season: Int,
        val episode: Int,
        val fileIdx: Int,
        val filename: String,
        val description: String,
        val expectedId: Int,
        val files: List<TorboxTorrentFileDto>,
    )

    private val releases = listOf(
        Release(
            title = "The Office",
            season = 1,
            episode = 2,
            fileIdx = 5,
            filename = "The.Office.S01E02.720p.BluRay.x264.DUAL-WWW.BLUDV.TV.mkv",
            description = "Vida de Escritório (The Office) 2005 - 1ª Temporada Completa Acesse o ORIGINAL WWW.BLUDV.TV 720P\nThe.Office.S01E02.720p.BluRay.x264.DUAL-WWW.BLUDV.TV.mkv\n👤 1 💾 541.09 MB ⚙️ Comando\nDual Audio / 🇬🇧 / 🇵🇹",
            expectedId = 3,
            files = listOf(
                TorboxTorrentFileDto(id = 11, name = "Vida de Escritório (The Office) 2005 - 1ª Temporada Completa Acesse o ORIGINAL WWW.BLUDV.TV/BLUDV.TV.mp4", size = 53346313L),
                TorboxTorrentFileDto(id = 3, name = "Vida de Escritório (The Office) 2005 - 1ª Temporada Completa Acesse o ORIGINAL WWW.BLUDV.TV/The.Office.S01E02.720p.BluRay.x264.DUAL-WWW.BLUDV.TV.mkv", size = 567375172L),
            ),
        ),
        Release(
            title = "Breaking Bad",
            season = 1,
            episode = 2,
            fileIdx = 246,
            filename = "Breaking Bad (2008) - S01E02 - Cat's in the Bag... (1080p BluRay x265 Silence).mkv",
            description = "Breaking Bad (2008) Season 1-5 S01-S05 (1080p BluRay x265 HEVC 10bit AAC 5 1 Silence) [QxR]\nSeason 1/Breaking Bad (2008) - S01E02 - Cat's in the Bag... (1080p BluRay x265 Silence).mkv\n👤 391 💾 2.04 GB ⚙️ ThePirateBay",
            expectedId = 3,
            files = listOf(
                TorboxTorrentFileDto(id = 78, name = "Breaking Bad (2008) Season 1-5 S01-S05 (1080p BluRay x265 HEVC 10bit AAC 5.1 Silence)/Featurettes/Season 5/Featurettes 5.1/Deleted Scenes/1.mkv", size = 12515022L),
                TorboxTorrentFileDto(id = 3, name = "Breaking Bad (2008) Season 1-5 S01-S05 (1080p BluRay x265 HEVC 10bit AAC 5.1 Silence)/Season 1/Breaking Bad (2008) - S01E02 - Cat's in the Bag... (1080p BluRay x265 Silence).mkv", size = 2185130378L),
            ),
        ),
        Release(
            title = "Game of Thrones",
            season = 1,
            episode = 2,
            fileIdx = 6,
            filename = "Game.Of.Thrones.S01E02.BluRay.720p.DUAL.WWW.COMANDOTORRENTS.COM.mkv",
            description = "] Game of Thrones 1ª a 7ª Temporada Completa [720p] [BluRay] [DUAL\n[COMANDOTORRENTS.COM] Game of Thrones 1ª Temporada Completa 2011 [720p] [DUAL]/Game.Of.Thrones.S01E02.BluRay.720p.DUAL.WWW.COMANDOTORRENTS.COM.mkv\n👤 46 💾 750.52 MB ⚙️ Comando\nDual Audio / 🇬🇧 / 🇵🇹",
            expectedId = 42,
            files = listOf(
                TorboxTorrentFileDto(id = 5, name = "[ACESSE COMANDOTORRENTS.COM] Game of Thrones 1ª a 7ª Temporada Completa [720p] [BluRay] [DUAL]/[COMANDOTORRENTS.COM] Game of Thrones 7ª Temporada Completa 2017 [1080p] [DUAL]/COMANDOTORRENTS.COM.mp4", size = 7767348L),
                TorboxTorrentFileDto(id = 42, name = "[ACESSE COMANDOTORRENTS.COM] Game of Thrones 1ª a 7ª Temporada Completa [720p] [BluRay] [DUAL]/[COMANDOTORRENTS.COM] Game of Thrones 1ª Temporada Completa 2011 [720p] [DUAL]/Game.Of.Thrones.S01E02.BluRay.720p.DUAL.WWW.COMANDOTORRENTS.COM.mkv", size = 786979890L),
            ),
        ),
        Release(
            title = "Attack on Titan",
            season = 1,
            episode = 2,
            fileIdx = 3,
            filename = "[TatakaeFuniSubs] Attack on Titan - S01E02 (BD 1080p AV1) [Dual Audio] [7B6EA33D].mkv",
            description = "[TatakaeFuniSubs] Attack on Titan S01-04 (BD 1080p) [Dual Audio] | OAD (OVA) | Shingeki no Kyojin (Full Series Batch)\nSeason 1/[TatakaeFuniSubs] Attack on Titan - S01E02 (BD 1080p AV1) [Dual Audio] [7B6EA33D].mkv\n👤 195 💾 497.32 MB ⚙️ NyaaSi\nDubbed / Dual Audio",
            expectedId = 15,
            files = listOf(
                TorboxTorrentFileDto(id = 2, name = "Attack on Titan S01-04 (BD 1080p) [Dual Audio] [TatakaeFuniSubs]/Season 1/[TatakaeFuniSubs] Attack on Titan - S01E14 (BD 1080p AV1) [Dual Audio] [6981699D].mkv", size = 356036376L),
                TorboxTorrentFileDto(id = 15, name = "Attack on Titan S01-04 (BD 1080p) [Dual Audio] [TatakaeFuniSubs]/Season 1/[TatakaeFuniSubs] Attack on Titan - S01E02 (BD 1080p AV1) [Dual Audio] [7B6EA33D].mkv", size = 521477716L),
            ),
        ),
        Release(
            title = "Stranger Things",
            season = 2,
            episode = 3,
            fileIdx = 24,
            filename = "Stranger.Things.S02E03.720p.WEB-DL.6CH.x264.DUAL-WWW.BLUDV.COM.mkv",
            description = "Stranger Things 2017 - 2ª Temporada Completa [WEB-DL] WWW.BLUDV.COM\nStranger.Things.S02E03.720p.WEB-DL.6CH.x264.DUAL-WWW.BLUDV.COM.mkv\n👤 5 💾 879.54 MB ⚙️ Comando\nDual Audio / 🇬🇧 / 🇵🇹",
            expectedId = 6,
            files = listOf(
                TorboxTorrentFileDto(id = 2, name = "Stranger Things 2017 - 2ª Temporada Completa [WEB-DL] WWW.BLUDV.COM/BLUDV.mp4", size = 33957794L),
                TorboxTorrentFileDto(id = 6, name = "Stranger Things 2017 - 2ª Temporada Completa [WEB-DL] WWW.BLUDV.COM/Stranger.Things.S02E03.720p.WEB-DL.6CH.x264.DUAL-WWW.BLUDV.COM.mkv", size = 922263595L),
            ),
        ),
        Release(
            title = "Sentenced to Be a Hero",
            season = 1,
            episode = 2,
            fileIdx = 1,
            filename = "Sentenced to Be a Hero - S01E02 (BD 1080p x265 Opus) [Dual Audio] [PMR].mkv",
            description = "[PMR] Sentenced to Be a Hero Season 1 (S01) (BD 1080p x265 Opus) [Dual Audio] | Yuusha Kei ni Shosu: Choubatsu Yuusha 9004-tai Keimu Kiroku\nSentenced to Be a Hero - S01E02 (BD 1080p x265 Opus) [Dual Audio] [PMR].mkv\n👤 20 💾 4.49 GB ⚙️ NyaaSi\nDubbed / Dual Audio",
            expectedId = 4,
            files = listOf(
                TorboxTorrentFileDto(id = 3, name = "Sentenced to Be a Hero S01 (BD 1080p x265 Opus) [Dual Audio] [PMR]/Sentenced to Be a Hero - S01E01 (BD 1080p x265 Opus) [Dual Audio] [PMR].mkv", size = 12359794201L),
                TorboxTorrentFileDto(id = 4, name = "Sentenced to Be a Hero S01 (BD 1080p x265 Opus) [Dual Audio] [PMR]/Sentenced to Be a Hero - S01E02 (BD 1080p x265 Opus) [Dual Audio] [PMR].mkv", size = 4817912734L),
            ),
        ),
    )
}
