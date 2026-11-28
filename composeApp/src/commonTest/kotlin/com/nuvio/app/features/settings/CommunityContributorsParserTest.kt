package com.nuvio.app.features.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommunityContributorsParserTest {

    @Test
    fun `parse reads nuvio community contributor payload`() {
        val contributors = parseCommunityContributors(
            """
            {
              "contributors": [
                {
                  "login": "ada",
                  "avatarUrl": "https://example.com/ada.png",
                  "profileUrl": "https://github.com/ada",
                  "contributions": 12,
                  "repos": ["NuvioMobile"]
                },
                {
                  "login": "blank",
                  "contributions": 0
                }
              ]
            }
            """.trimIndent(),
        )

        val contributor = contributors.single()
        assertEquals("ada", contributor.login)
        assertEquals("https://example.com/ada.png", contributor.avatarUrl)
        assertEquals("https://github.com/ada", contributor.profileUrl)
        assertEquals(12, contributor.totalContributions)
    }

    @Test
    fun `parse keeps the older gitserver field names`() {
        val contributor = parseCommunityContributors(
            """
            {
              "contributors": [
                {
                  "name": "grace",
                  "avatar": "https://example.com/grace.png",
                  "profile": "https://github.com/grace",
                  "total": 4
                }
              ]
            }
            """.trimIndent(),
        ).single()

        assertEquals("grace", contributor.login)
        assertEquals("https://example.com/grace.png", contributor.avatarUrl)
        assertEquals("https://github.com/grace", contributor.profileUrl)
        assertEquals(4, contributor.totalContributions)
        assertNull(contributor.avatarUrl?.takeIf { it.isBlank() })
    }
}
