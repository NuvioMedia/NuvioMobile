package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.sports.SportsCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FightSidesTest {
    @Test
    fun readsBothFightersFromACommonTitle() {
        val sides = assertNotNull(SportsCatalog.fightSides("Boxing : Canelo Alvarez vs. Christian Mbilli"))
        assertTrue("canelo" in sides.first && "alvarez" in sides.first)
        assertTrue("mbilli" in sides.second && "christian" in sides.second)
    }

    @Test
    fun cardAndPromoterWordsAreLeftOut() {
        val sides = assertNotNull(SportsCatalog.fightSides("UFC 310: Pantoja vs Asakura"))
        assertEquals(listOf("pantoja"), sides.first.filter { it == "pantoja" })
        assertEquals(listOf("asakura"), sides.second)
    }

    @Test
    fun aTitleWithoutTwoSidesIsNotAFight() {
        assertNull(SportsCatalog.fightSides("Boxing Night Live"))
        assertNull(SportsCatalog.fightSides("A vs B vs C"))
    }
}
