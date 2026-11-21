package com.nuvio.app.features.usenet

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidRegexCompatibilityTest {
    /**
     * Android's regex engine (ICU) rejects Java's script and block property syntax, such as
     * \p{IsLatin} or \p{InGreek}, while the JVM running these tests accepts it. A regex using it
     * passes every unit test and then crashes on a device, so the sources are checked instead.
     */
    @Test fun `main sources use no regex property syntax that Android rejects`() {
        val roots = listOf(File("src/androidMain/kotlin"), File("src/commonMain/kotlin"))
        assertTrue("run from the composeApp module", roots.all { it.isDirectory })
        val property = Regex("""\\p\{(?:Is|In)[A-Z]""")
        val offenders = roots.asSequence().flatMap { it.walk() }.filter { it.extension == "kt" || it.extension == "java" }.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                "${file.invariantSeparatorsPath}:${i + 1}".takeIf { property.containsMatchIn(line) }
            }
        }.toList()
        assertEquals(emptyList<String>(), offenders)
    }

    @Test fun `latin letters and their marks are recognised without a regex`() {
        assertTrue(NewznabProtocol.hasLatinLetter("La casa de papel"))
        assertTrue(NewznabProtocol.hasLatinLetter("Amélie"))
        assertFalse(NewznabProtocol.hasLatinLetter("ペーパー・ハウス"))
        assertFalse(NewznabProtocol.hasLatinLetter("2049"))
        // Marks are dropped from Latin letters only: kana voicing marks belong to the letter.
        assertEquals("Amelie", NewznabProtocol.releaseSpelling("Amélie"))
        assertEquals("ガンダム", NewznabProtocol.releaseSpelling("ガンダム"))
    }
}
