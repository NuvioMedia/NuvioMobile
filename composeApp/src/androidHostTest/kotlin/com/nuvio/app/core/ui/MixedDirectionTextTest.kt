package com.nuvio.app.core.ui

import android.app.Application
import android.content.ClipboardManager
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.party.MessageRow
import com.nuvio.app.features.party.PartyChat
import com.nuvio.app.features.party.PartyChatContent
import com.nuvio.app.features.party.PartyChatMessage
import com.nuvio.app.features.party.PartyChatRow
import com.nuvio.app.features.search.SearchRecentRow
import com.nuvio.app.features.search.SearchScreen
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MixedDirectionTextTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var inputView: View
    private val direction = mutableStateOf(LayoutDirection.Ltr)
    private val sample = mutableStateOf(samples.first())
    private val mine = mutableStateOf(false)

    @After fun clearDraft() { PartyChat.draft.value = "" }

    @Test fun searchContentAndEditing() {
        show { SearchScreen() }
        checkInput()
    }

    @Test fun chatContentEditingAndParagraphs() {
        PartyChat.draft.value = ""
        show { PartyChatContent(members = emptyList()) }
        checkInput()
        val field = compose.onNode(hasSetTextAction())
        field.performTextReplacement("مرحبا")
        assertTrue(layout(field).size.width < field.fetchSemanticsNode().boundsInRoot.width)
        val longMessage = "مرحبا " + "One Piece 1125 ".repeat(40)
        field.performTextReplacement(longMessage)
        assertEquals(longMessage.take(PartyChat.MAX_LENGTH), layout(field).layoutInput.text.text)
        field.performTextReplacement(paragraphs)
        assertText(field, paragraphs, ResolvedTextDirection.Rtl)
        assertEquals(paragraphs, PartyChat.draft.value)
    }

    @Test fun recentSearchDirection() {
        show { SearchRecentRow(sample.value.first, {}, {}) }
        for (root in LayoutDirection.entries) for (entry in samples) {
            compose.runOnIdle { direction.value = root; sample.value = entry }
            assertText(compose.onNodeWithText(entry.first), entry.first, entry.second)
        }
    }

    @Test fun messagesAndNamesHaveIndependentDirections() {
        fun name() = if (sample.value.second == ResolvedTextDirection.Rtl) "Alice שלום" else "דוד Alice"
        show {
            MessageRow(
                PartyChatRow(
                    PartyChatMessage("test", 1, "sender", name(), null, null, sample.value.first, 0, mine.value),
                    startsGroup = true, endsGroup = true, showTime = true,
                ),
                members = emptyList(), bubbleWidth = 180.dp,
            )
        }
        for (root in LayoutDirection.entries) for (own in listOf(false, true)) {
            for (entry in samples + (paragraphs to ResolvedTextDirection.Rtl)) {
                compose.runOnIdle { direction.value = root; mine.value = own; sample.value = entry }
                val body = compose.onNodeWithText(entry.first)
                assertText(body, entry.first, entry.second)
                if (entry.first == arabic) assertTrue(layout(body).lineCount > 1, "Narrow bubbles must wrap")
                if (!own) assertText(compose.onNodeWithText(name()), name(), if (entry.second == ResolvedTextDirection.Rtl) {
                    ResolvedTextDirection.Ltr
                } else ResolvedTextDirection.Rtl)
            }
        }
    }

    @Test fun defaultInputKeepsAppStyle() {
        lateinit var expected: TextStyle
        show {
            expected = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.nuvio.colors.textPrimary)
            NuvioInputField("مرحبا Hello", {}, "Search")
        }
        val result = layout(compose.onNode(hasSetTextAction()))
        val actual = result.layoutInput.style
        assertEquals(expected.fontSize, actual.fontSize)
        assertEquals(expected.lineHeight, actual.lineHeight)
        assertEquals(expected.fontFamily, actual.fontFamily)
        assertEquals(expected.fontWeight, actual.fontWeight)
        assertEquals(expected.letterSpacing, actual.letterSpacing)
        assertEquals(expected.color, actual.color)
        assertEquals(ResolvedTextDirection.Ltr, result.getParagraphDirection(0), "Unmodified callers retain their default direction")
    }

    private fun checkInput() {
        val field = compose.onNode(hasSetTextAction())
        for (root in LayoutDirection.entries) {
            compose.runOnIdle { direction.value = root }
            for ((text, expected) in samples) {
                field.performTextReplacement(text)
                assertText(field, text, expected)
            }
            for (text in listOf(arabic, hebrew)) checkEditing(field, text)
        }
        compose.runOnIdle { direction.value = LayoutDirection.Ltr }
        field.performTextReplacement("")
        inputConnection {
            assertTrue(it.setComposingText("مرح", 1))
            assertTrue(it.setComposingText("مرحبا", 1))
            assertTrue(it.finishComposingText())
            assertTrue(it.commitText(" One Piece 1125", 1))
        }
        assertText(field, "مرحبا One Piece 1125", ResolvedTextDirection.Rtl)
        field.performTextReplacement(arabic)
        val english = arabic.indexOf("one piece")
        select(field, TextRange(english, english + 9))
        field.performSemanticsAction(SemanticsActions.CopyText) { it() }
        awaitClipboard("one piece")
        select(field, TextRange(0, arabic.length))
        field.performSemanticsAction(SemanticsActions.CutText) { it() }
        compose.waitUntil(5_000) { layout(field).layoutInput.text.text.isEmpty() }
        awaitClipboard(arabic)
        field.performSemanticsAction(SemanticsActions.PasteText) { it() }
        compose.waitUntil(5_000) { layout(field).layoutInput.text.text == arabic }
        assertEquals(arabic, layout(field).layoutInput.text.text)
    }

    private fun checkEditing(field: SemanticsNodeInteraction, text: String) {
        field.performTextReplacement(text)
        val number = text.indexOf("1125")
        select(field, TextRange(number + 2))
        field.performKeyInput { pressKey(Key.DirectionRight) }
        val cursor = field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        // This seed is inside ASCII digits, not a grapheme or bidi-run boundary.
        assertTrue(cursor.collapsed && cursor.start in listOf(number + 1, number + 3), "Arrow: $text; $cursor")
        field.performTextInput("0")
        assertEquals(text.take(cursor.start) + "0" + text.drop(cursor.start), layout(field).layoutInput.text.text)
        field.performTextReplacement(text)
        select(field, TextRange(number + 4))
        field.performKeyInput { pressKey(Key.Backspace) }
        assertEquals(text.replace("1125", "112"), layout(field).layoutInput.text.text)
        field.performTextReplacement(text)
        val latin = if (text == arabic) "one piece" else "One Piece"
        val start = text.indexOf(latin)
        select(field, TextRange(start, start + latin.length))
        field.performTextInput("Breaking Bad")
        assertEquals(text.replace(latin, "Breaking Bad"), layout(field).layoutInput.text.text)
    }

    private fun select(field: SemanticsNodeInteraction, range: TextRange) {
        // IME commands must share EditProcessor state; Semantics alone can be overwritten by composing updates.
        inputConnection { assertTrue(it.setSelection(range.start, range.end)) }
        compose.waitUntil(5_000) { field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange] == range }
        assertEquals(range, field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
    }

    private fun inputConnection(action: (InputConnection) -> Unit) = compose.runOnIdle {
        val connection = checkNotNull(inputView.onCreateInputConnection(EditorInfo()))
        try { action(connection) } finally { connection.closeConnection() }
    }

    private fun awaitClipboard(expected: String) {
        val clipboard = inputView.context.getSystemService(ClipboardManager::class.java)
        compose.waitUntil(5_000) { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == expected }
        assertEquals(expected, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    private fun assertText(node: SemanticsNodeInteraction, text: String, expected: ResolvedTextDirection) {
        val result = layout(node)
        assertEquals(text, result.layoutInput.text.text, "Logical text")
        assertEquals(expected, result.getParagraphDirection(0), "Direction in ${direction.value}: $text")
        if (text == paragraphs) {
            assertEquals(ResolvedTextDirection.Ltr, result.getParagraphDirection(6))
            assertEquals(ResolvedTextDirection.Rtl, result.getParagraphDirection(12))
        }
        if (!result.isLineEllipsized(0)) for (token in listOf("1125", "one piece", "One Piece")) {
            val start = text.indexOf(token)
            if (start < 0) continue
            for (i in start until start + token.length) {
                if (text[i].isWhitespace()) continue // A trailing space at a soft wrap takes the paragraph level.
                assertEquals(ResolvedTextDirection.Ltr, result.getBidiRunDirection(i), token)
                if (i > start && result.getLineForOffset(i) == result.getLineForOffset(i - 1)) {
                    assertTrue(result.getBoundingBox(i).left >= result.getBoundingBox(i - 1).left, token)
                }
            }
        }
        if ((text == arabic || text == hebrew) && !result.isLineEllipsized(0)) {
            fun precedes(a: Int, b: Int) = if (result.getLineForOffset(a) == result.getLineForOffset(b)) {
                result.getBoundingBox(a).left > result.getBoundingBox(b).left
            } else result.getLineForOffset(a) < result.getLineForOffset(b)
            val (before, latin, after) = if (text == arabic) listOf("انمي", "one piece", "صدرت")
                else listOf("האנימה", "One Piece", "כבר")
            assertTrue(precedes(text.indexOf(before), text.indexOf(latin)))
            assertTrue(precedes(text.indexOf(latin), text.indexOf(after)))
        }
    }

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            inputView = LocalView.current
            CompositionLocalProvider(LocalLayoutDirection provides direction.value) {
                NuvioTheme { Box(Modifier.width(320.dp)) { content() } }
            }
        }
    }

    private companion object {
        const val arabic = "الحلقه 1125 من انمي one piece صدرت بالفعل"
        const val hebrew = "פרק 1125 של האנימה One Piece כבר יצא"
        const val paragraphs = "مرحبا\nHello\nשלום"
        val samples = listOf(
            arabic to ResolvedTextDirection.Rtl,
            hebrew to ResolvedTextDirection.Rtl,
            "Episode 1125 من One Piece متوفرة الآن!" to ResolvedTextDirection.Ltr,
            "🎉 مرحبا (One Piece) 1125!" to ResolvedTextDirection.Rtl,
        )
    }
}
