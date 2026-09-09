package top.colter.dynamic.agent.dota2

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import top.colter.dynamic.agent.draw.historyKda
import top.colter.dynamic.agent.draw.historyWon
import kotlin.test.*

class Dota2HistoryTest {
    private fun match(id: Long, time: Long? = null) = buildJsonObject {
        put("match_id", id); if (time != null) put("start_time", time)
    }

    @Test fun `all ten positions match history ordering and truncate excess records`() = runBlocking {
        val raw = JsonArray((1L..20L).map { match(1000 + it, it) }.reversed().shuffled())
        val history = orderedRecentMatches(raw)
        assertEquals(10, history.size)
        for (index in 1..10) assertEquals(history[index - 1].jsonObject["match_id"]!!.jsonPrimitive.long,
            resolveDotaReportMatchId(index.toString()) { raw })
        assertEquals(1020, resolveDotaReportMatchId(null) { raw })
        assertEquals(1020, resolveDotaReportMatchId("01") { raw })
    }

    @Test fun `fresh queries move with new matches and explicit IDs bypass recent API`() = runBlocking {
        var recent = JsonArray(listOf(match(100, 1), match(200, 2)))
        assertEquals(100, resolveDotaReportMatchId("2") { recent })
        recent = JsonArray(recent + match(300, 3))
        assertEquals(200, resolveDotaReportMatchId("2") { recent })
        assertEquals(8980854337, resolveDotaReportMatchId("8980854337") { error("must not fetch") })
        assertEquals(11, resolveDotaReportMatchId("11") { error("11 is an ID, not an index") })
    }

    @Test fun `invalid parameters never silently fetch latest`() = runBlocking<Unit> {
        for (arg in listOf("abc", "0", "-1", "1.5", "", "+2", "9223372036854775808")) {
            assertFailsWith<IllegalArgumentException> { resolveDotaReportMatchId(arg) { error("must not fetch") } }
        }
        assertFailsWith<IllegalArgumentException> { resolveDotaReportMatchId("1") { null } }
        assertFailsWith<IllegalArgumentException> { resolveDotaReportMatchId("1") { JsonArray(emptyList()) } }
        val failure = assertFailsWith<IllegalArgumentException> { resolveDotaReportMatchId("3") { JsonArray(listOf(match(100, 1))) } }
        assertTrue(failure.message!!.contains("仅有1场"))
        assertFailsWith<IllegalArgumentException> { resolveDotaReportMatchId("1") { JsonArray(listOf(buildJsonObject { put("start_time", 1) })) } }
    }

    @Test fun `tie and missing timestamps have deterministic order`() {
        val ordered = orderedRecentMatches(JsonArray(listOf(match(300), match(100, 1), match(200, 1))))
        assertEquals(listOf(200L, 100L, 300L), ordered.map { it.jsonObject["match_id"]!!.jsonPrimitive.long })
    }

    @Test fun `zero deaths and unknown data do not become false losses or zero KDA`() {
        assertNull(historyKda(buildJsonObject {}))
        assertEquals(30.0, historyKda(buildJsonObject { put("kills", 12); put("deaths", 0); put("assists", 18) }))
        assertNull(historyWon(buildJsonObject { put("player_slot", 128) }))
        assertNull(historyWon(buildJsonObject { put("player_slot", 77); put("radiant_win", true) }))
        assertEquals(true, historyWon(buildJsonObject { put("player_slot", 128); put("radiant_win", false) }))
        assertEquals(false, historyWon(buildJsonObject { put("player_slot", 0); put("radiant_win", false) }))
    }
}
