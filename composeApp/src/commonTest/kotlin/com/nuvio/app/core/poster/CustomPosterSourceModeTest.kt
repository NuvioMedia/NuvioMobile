package com.nuvio.app.core.poster

import kotlin.test.Test
import kotlin.test.assertEquals

class CustomPosterSourceModeTest {
    @Test
    fun legacyPatternDefaultsToPostersAndTags() {
        assertEquals(
            CustomPosterSourceMode.POSTERS_AND_TAGS,
            CustomPosterSourceMode.fromStorage(value = null, hasPattern = true),
        )
    }

    @Test
    fun emptyPatternCannotRestorePosterReplacementMode() {
        assertEquals(
            CustomPosterSourceMode.OFF,
            CustomPosterSourceMode.fromStorage(
                value = CustomPosterSourceMode.POSTERS_AND_TAGS.storageValue,
                hasPattern = false,
            ),
        )
    }

    @Test
    fun tagsOnlyModeSurvivesWithoutPosterPattern() {
        assertEquals(
            CustomPosterSourceMode.TAGS_ONLY,
            CustomPosterSourceMode.fromStorage(
                value = CustomPosterSourceMode.TAGS_ONLY.storageValue,
                hasPattern = false,
            ),
        )
    }
}
