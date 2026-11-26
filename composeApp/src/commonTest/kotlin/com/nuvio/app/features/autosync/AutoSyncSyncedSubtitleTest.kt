package com.nuvio.app.features.autosync

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class AutoSyncSyncedSubtitleTest {

    @AfterTest
    fun reset() {
        AutoSyncSyncedSubtitle.clear()
    }

    @Test
    fun `a run that ends late leaves the run that replaced it in progress`() {
        val first = AutoSyncSyncedSubtitle.start("https://subs/a.srt")
        val second = AutoSyncSyncedSubtitle.start("https://subs/a.srt")

        AutoSyncSyncedSubtitle.end(first)
        assertSame(second, AutoSyncSyncedSubtitle.status.value)

        AutoSyncSyncedSubtitle.end(second)
        assertNull(AutoSyncSyncedSubtitle.status.value)
    }

    @Test
    fun `ending a run keeps the result it reached`() {
        val run = AutoSyncSyncedSubtitle.start("https://subs/b.srt")
        AutoSyncSyncedSubtitle.mark("https://subs/b.srt", AutoSyncState.NOT_NEEDED)

        AutoSyncSyncedSubtitle.end(run)

        val status = AutoSyncSyncedSubtitle.status.value
        assertEquals("https://subs/b.srt", status?.url)
        assertEquals(AutoSyncState.NOT_NEEDED, status?.state)
    }

    @Test
    fun `a stale run cannot fail the subtitle that replaced it`() {
        val stale = AutoSyncSyncedSubtitle.start("https://subs/c.srt")
        val current = AutoSyncSyncedSubtitle.start("https://subs/d.srt")

        AutoSyncSyncedSubtitle.fail(stale)
        assertSame(current, AutoSyncSyncedSubtitle.status.value)

        AutoSyncSyncedSubtitle.fail(current)
        val status = AutoSyncSyncedSubtitle.status.value
        assertEquals("https://subs/d.srt", status?.url)
        assertEquals(AutoSyncState.FAILED, status?.state)
    }

    @Test
    fun `a cleared run stays cleared`() {
        val run = AutoSyncSyncedSubtitle.start("https://subs/e.srt")
        AutoSyncSyncedSubtitle.clear()

        AutoSyncSyncedSubtitle.fail(run)

        assertNull(AutoSyncSyncedSubtitle.status.value)
    }
}
