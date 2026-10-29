package com.nuvio.app.features.party

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PartyChatTest {
    private fun sent(
        id: Long,
        memberId: String = "b",
        mine: Boolean = false,
        sentAt: Long = 1_000_000L + id * 1_000L,
        clientId: String? = null,
    ) = PartyChatMessage(
        key = "id:$id",
        id = id,
        memberId = memberId,
        name = memberId,
        avatarUrl = null,
        avatarColor = null,
        body = "message $id",
        sentAt = sentAt,
        mine = mine,
        clientId = clientId,
    )

    private fun pending(clientId: String, status: PartyChatStatus = PartyChatStatus.Sending) = PartyChatMessage(
        key = clientId,
        id = null,
        memberId = "a",
        name = "a",
        avatarUrl = null,
        avatarColor = null,
        body = "pending $clientId",
        sentAt = 2_000_000L,
        mine = true,
        clientId = clientId,
        status = status,
    )

    @Test
    fun newMessagesAreAddedInIdOrder() {
        val result = mergePartyChat(listOf(sent(1)), listOf(sent(3), sent(2)), replaceOlder = false, limit = 200)
        assertEquals(listOf(1L, 2L, 3L), result.messages.map { it.id })
        assertEquals(listOf(2L, 3L), result.added.map { it.id })
    }

    @Test
    fun duplicatesAreIgnored() {
        val result = mergePartyChat(listOf(sent(1), sent(2)), listOf(sent(2), sent(1)), replaceOlder = false, limit = 200)
        assertEquals(listOf(1L, 2L), result.messages.map { it.id })
        assertTrue(result.added.isEmpty())
    }

    @Test
    fun confirmedOwnMessageReplacesPendingAndKeepsItsKey() {
        val current = listOf(sent(1), pending("localAAAA0001"))
        val confirmed = sent(2, memberId = "a", mine = true, clientId = "localAAAA0001")
        val result = mergePartyChat(current, listOf(confirmed), replaceOlder = false, limit = 200)
        assertEquals(listOf("id:1", "localAAAA0001"), result.messages.map { it.key })
        assertEquals(2L, result.messages.last().id)
        assertEquals(PartyChatStatus.Sent, result.messages.last().status)
        assertTrue(result.added.isEmpty())
    }

    @Test
    fun someoneElseCannotTakeOverAPendingMessage() {
        val current = listOf(pending("localAAAA0001"))
        val spoof = sent(5, memberId = "b", mine = false, clientId = "localAAAA0001")
        val result = mergePartyChat(current, listOf(spoof), replaceOlder = false, limit = 200)
        assertEquals(listOf("id:5", "localAAAA0001"), result.messages.map { it.key })
        assertEquals(PartyChatStatus.Sending, result.messages.last().status)
        assertEquals(listOf(5L), result.added.map { it.id })
    }

    @Test
    fun pendingAndFailedMessagesStayAtTheBottom() {
        val current = listOf(sent(1), pending("localAAAA0001", PartyChatStatus.Failed))
        val result = mergePartyChat(current, listOf(sent(2)), replaceOlder = false, limit = 200)
        assertEquals(listOf("id:1", "id:2", "localAAAA0001"), result.messages.map { it.key })
    }

    @Test
    fun fullCatchUpPageDropsOlderMessagesToAvoidAGap() {
        val current = listOf(sent(1), sent(2), pending("localAAAA0001"))
        val page = (50L..149L).map { sent(it) }
        val result = mergePartyChat(current, page, replaceOlder = true, limit = 200)
        assertEquals(50L, result.messages.first().id)
        assertEquals("localAAAA0001", result.messages.last().key)
        assertEquals(101, result.messages.size)
    }

    @Test
    fun oldestMessagesAreDroppedPastTheLimit() {
        val current = (1L..5L).map { sent(it) }
        val result = mergePartyChat(current, listOf(sent(6)), replaceOlder = false, limit = 4)
        assertEquals(listOf(3L, 4L, 5L, 6L), result.messages.map { it.id })
    }

    @Test
    fun rowsGroupConsecutiveMessagesFromTheSameSender() {
        val messages = listOf(
            sent(1, memberId = "b"),
            sent(2, memberId = "b"),
            sent(3, memberId = "c"),
            sent(4, memberId = "a", mine = true),
            sent(5, memberId = "a2", mine = true),
        )
        val rows = partyChatRows(messages)
        assertEquals(listOf(true, false, true, true, false), rows.map { it.startsGroup })
        assertEquals(listOf(false, true, true, false, true), rows.map { it.endsGroup })
    }

    @Test
    fun timeLabelShowsOnFirstMessageAndAfterLongGaps() {
        val start = 5_000_000L
        val messages = listOf(
            sent(1, sentAt = start),
            sent(2, sentAt = start + 60_000L),
            sent(3, sentAt = start + 60_000L + 11 * 60_000L),
        )
        val rows = partyChatRows(messages)
        assertEquals(listOf(true, false, true), rows.map { it.showTime })
        assertTrue(rows[2].startsGroup)
        assertTrue(rows[1].endsGroup)
        assertFalse(rows[0].endsGroup)
    }
}
