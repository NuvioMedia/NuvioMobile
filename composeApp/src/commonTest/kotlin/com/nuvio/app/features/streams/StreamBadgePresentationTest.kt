package com.nuvio.app.features.streams

import com.nuvio.app.features.debrid.DebridProviders
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamBadgePresentationTest {
    @Test
    fun `parses fusion badge url payload shape`() {
        val importedRules = StreamBadgeRulesParser.parse(
            sourceUrl = "https://example.test/fusion-tags-ume.json",
            payload = """
                {
                  "filters": [
                    {
                      "borderColor": "#27C04F",
                      "groupId": "media",
                      "id": "remux",
                      "imageURL": "https://example.test/remux.png",
                      "isEnabled": true,
                      "name": "REMUX",
                      "pattern": "(?i)\\bremux\\b",
                      "tagColor": "#27C04F",
                      "tagStyle": "filled",
                      "textColor": "#FFFFFF",
                      "type": "filter"
                    }
                  ],
                  "groups": [
                    {
                      "color": "#96CEB4",
                      "id": "media",
                      "isExpanded": true,
                      "name": "Media Source"
                    }
                  ]
                }
            """.trimIndent(),
        )

        assertEquals("https://example.test/fusion-tags-ume.json", importedRules.sourceUrl)
        assertEquals(1, importedRules.filters.size)
        assertEquals("REMUX", importedRules.filters.single().name)
        assertEquals("(?i)\\bremux\\b", importedRules.filters.single().pattern)
        assertEquals("https://example.test/remux.png", importedRules.filters.single().imageURL)
        assertEquals("Media Source", importedRules.groups.single().name)
    }

    @Test
    fun `attaches badges to every supported stream shape`() {
        val rules = StreamBadgeRules(
            imports = listOf(
                StreamBadgeImport(
                    sourceUrl = "https://example.test/badges.json",
                    filters = listOf(
                        StreamBadgeFilter(
                            name = "WEB",
                            pattern = "(?i)web-dl",
                            imageURL = "https://example.test/web.png",
                        ),
                        StreamBadgeFilter(
                            name = "REMUX",
                            pattern = "(?i)\\bremux\\b",
                            imageURL = "https://example.test/remux.png",
                        ),
                        StreamBadgeFilter(
                            name = "PLUGIN",
                            pattern = "(?i)plugin-source",
                            imageURL = "https://example.test/plugin.png",
                        ),
                        StreamBadgeFilter(
                            name = "TRUEHD",
                            pattern = "(?i)truehd",
                            imageURL = "https://example.test/truehd.png",
                        ),
                    ),
                ),
            ),
        )
        val normalUrlStream = StreamItem(
            name = "Movie.2026.1080p.WEB-DL-GRP",
            url = "https://example.test/movie.mp4",
            addonName = "Direct",
            addonId = "direct",
            badges = listOf(StreamBadge(name = "EXISTING", imageURL = "https://example.test/existing.png")),
        )
        val addonTorrentStream = StreamItem(
            infoHash = "abcdef1234567890abcdef1234567890abcdef12",
            addonName = "Addon",
            addonId = "addon:test",
            behaviorHints = StreamBehaviorHints(filename = "Movie.2026.2160p.REMUX-GRP.mkv"),
        )
        val pluginStream = StreamItem(
            title = "Plugin result",
            externalUrl = "https://example.test/plugin.m3u8",
            sourceName = "plugin-source",
            addonName = "Plugin Source",
            addonId = "plugin:test",
        )
        val debridStream = StreamItem(
            name = "Cached",
            infoHash = "1234567890abcdef1234567890abcdef12345678",
            addonName = "Addon",
            addonId = "addon:test",
            debridCacheStatus = StreamDebridCacheStatus(
                providerId = DebridProviders.TORBOX_ID,
                providerName = DebridProviders.Torbox.displayName,
                state = StreamDebridCacheState.CACHED,
                cachedName = "Movie.2026.TrueHD.7.1-GRP.mkv",
            ),
        )

        val presented = StreamBadgePresentation.apply(
            groups = listOf(
                AddonStreamGroup(
                    addonName = "Mixed",
                    addonId = "mixed",
                    streams = listOf(normalUrlStream, addonTorrentStream, pluginStream, debridStream),
                ),
            ),
            rules = rules,
        ).single().streams

        assertEquals(listOf("EXISTING", "WEB"), presented[0].badges.map { it.name })
        assertEquals(listOf("REMUX"), presented[1].badges.map { it.name })
        assertEquals(listOf("PLUGIN"), presented[2].badges.map { it.name })
        assertEquals(listOf("TRUEHD"), presented[3].badges.map { it.name })
    }

    @Test
    fun `uses only active badge import`() {
        val rules = StreamBadgeRules(
            imports = listOf(
                StreamBadgeImport(
                    sourceUrl = "https://example.test/inactive.json",
                    isActive = false,
                    filters = listOf(
                        StreamBadgeFilter(
                            name = "INACTIVE",
                            pattern = "(?i)web-dl",
                            imageURL = "https://example.test/inactive.png",
                        ),
                    ),
                ),
                StreamBadgeImport(
                    sourceUrl = "https://example.test/active.json",
                    isActive = true,
                    filters = listOf(
                        StreamBadgeFilter(
                            name = "ACTIVE",
                            pattern = "(?i)web-dl",
                            imageURL = "https://example.test/active.png",
                        ),
                    ),
                ),
            ),
        )

        val presented = StreamBadgePresentation.apply(
            groups = listOf(
                AddonStreamGroup(
                    addonName = "Direct",
                    addonId = "direct",
                    streams = listOf(
                        StreamItem(
                            name = "Movie.2026.1080p.WEB-DL-GRP",
                            url = "https://example.test/movie.mp4",
                            addonName = "Direct",
                            addonId = "direct",
                        ),
                    ),
                ),
            ),
            rules = rules,
        ).single().streams.single()

        assertEquals(listOf("ACTIVE"), presented.badges.map { it.name })
    }

    @Test
    fun `applies new rules after reusing the previous ones`() {
        fun rules(name: String, pattern: String) = StreamBadgeRules(
            imports = listOf(
                StreamBadgeImport(
                    sourceUrl = "https://example.test/badges.json",
                    filters = listOf(
                        StreamBadgeFilter(name = name, pattern = pattern, imageURL = "https://example.test/$name.png"),
                    ),
                ),
            ),
        )
        val group = AddonStreamGroup(
            addonName = "Direct",
            addonId = "direct",
            streams = listOf(
                StreamItem(
                    name = "Movie.2026.2160p.WEB-DL.REMUX-GRP",
                    url = "https://example.test/movie.mp4",
                    addonName = "Direct",
                    addonId = "direct",
                ),
            ),
        )
        fun badgeNames(rules: StreamBadgeRules) =
            StreamBadgePresentation.apply(listOf(group), rules).single().streams.single().badges.map { it.name }

        val webRules = rules("WEB", "(?i)web-dl")
        assertEquals(listOf("WEB"), badgeNames(webRules))
        assertEquals(listOf("WEB"), badgeNames(webRules))
        assertEquals(listOf("WEB"), badgeNames(rules("WEB", "(?i)web-dl")))
        assertEquals(listOf("REMUX"), badgeNames(rules("REMUX", "(?i)remux")))
        assertEquals(emptyList(), badgeNames(StreamBadgeRules()))
        assertEquals(listOf("WEB"), badgeNames(webRules))
    }
}
