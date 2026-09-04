package top.colter.dynamic.agent.dota2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Dota2LocalizationTest {
    @Test
    fun `uses Chinese hero name and English fallback`() {
        assertEquals("影魔", localizedDota2HeroName(11, "Shadow Fiend"))
        assertEquals("复仇之魂", localizedDota2HeroName(20, "Vengeful Spirit"))
        assertEquals("百戏大王", localizedDota2HeroName(131, "Ring Master"))
        assertEquals("獸", localizedDota2HeroName(137, "Primal Beast"))
        assertEquals("凯", localizedDota2HeroName(145, "Kez"))
        assertEquals("朗戈", localizedDota2HeroName(155, "Largo"))
        assertEquals("Unknown Hero", localizedDota2HeroName(999, "Unknown Hero"))
        assertEquals("Hero_999", localizedDota2HeroName(999))
    }

    @Test
    fun `normalizes missing player name`() {
        assertEquals(ANONYMOUS_PLAYER_NAME, normalizeDota2PlayerName(null))
        assertEquals(ANONYMOUS_PLAYER_NAME, normalizeDota2PlayerName(""))
        assertEquals(ANONYMOUS_PLAYER_NAME, normalizeDota2PlayerName("?"))
        assertEquals("真实玩家", normalizeDota2PlayerName("  真实玩家  "))
    }

    @Test
    fun `matches complete analysis identities without one-character false positives`() {
        assertTrue(matchesDota2AnalysisIdentity("影魔(匿名玩家)", "影魔"))
        assertTrue(matchesDota2AnalysisIdentity("凯：匿名玩家", "凯"))
        assertTrue(matchesDota2AnalysisIdentity("Vengeful Spirit(Test)", "Vengeful Spirit"))
        assertTrue(matchesDota2AnalysisIdentity("MVP / 真实玩家", "真实玩家"))

        assertFalse(matchesDota2AnalysisIdentity("凯旋归来", "凯"))
        assertFalse(matchesDota2AnalysisIdentity("陈述问题", "陈"))
        assertFalse(matchesDota2AnalysisIdentity("野兽般的表现", "獸"))
        assertFalse(matchesDota2AnalysisIdentity(null, "影魔"))
    }
}
