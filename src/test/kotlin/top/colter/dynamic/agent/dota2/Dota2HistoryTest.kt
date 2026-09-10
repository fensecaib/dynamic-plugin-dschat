package top.colter.dynamic.agent.dota2

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import top.colter.dynamic.agent.draw.historyKda
import top.colter.dynamic.agent.draw.historyWon
import kotlin.test.*

class Dota2HistoryTest {
    @Test fun `explicit player queries use selected account and preserve legacy match IDs`() = runBlocking<Unit> {
        val recent=JsonArray((1L..10L).map { match(1000+it,it) })
        for(mode in Dota2ReportMode.entries) for(n in 1..10) {
            val result=resolveDotaReportTarget(listOf(mode.command,"176496411",n.toString()),999) { account ->
                assertEquals(176496411L,account);recent
            }
            assertEquals(176496411L to (1011L-n),result)
        }
        assertEquals(176496411L to 1010L,resolveDotaReportTarget(listOf("战报","176496411","1"),null) { recent })
        assertEquals(999L to 1010L,resolveDotaReportTarget(listOf("战报"),999) { assertEquals(999L,it);recent })
        assertEquals(999L to 8980854337L,resolveDotaReportTarget(listOf("战报","8980854337"),999) { error("must not fetch") })
        assertEquals(176496411L,dotaPlayerAccount("176496411",999))
        assertTrue(historyCommandHint(10,176496411).contains("/dota 战报 176496411 N"))
        for(args in listOf(listOf("战报","176496411","0"),listOf("战报","176496411","11"),listOf("战报","abc","1"),listOf("战报","4294967296","1"),listOf("战报","176496411","1","2"))) {
            assertFailsWith<IllegalArgumentException> { resolveDotaReportTarget(args,999) { error("must not fetch") } }
        }
        assertFailsWith<IllegalArgumentException> { resolveDotaReportTarget(listOf("战报","176496411","10"),null) { JsonArray(listOf(match(11))) } }
        assertFailsWith<IllegalArgumentException> { resolveDotaReportTarget(listOf("战报"),null) { error("must not fetch") } }
    }

    @Test fun `target match validates identity and normalizes both teams before analysis`() {
        fun player(id:Long,slot:Int)=buildJsonObject { put("account_id",id);put("player_slot",slot) }
        val raw=buildJsonObject { put("match_id",100);put("radiant_win",true);putJsonArray("players") { add(player(1,0));add(player(2,128)) } }
        val radiant=prepareDotaTargetMatch(raw,100,1)
        val dire=prepareDotaTargetMatch(raw,100,2)
        assertTrue(radiant.won);assertFalse(dire.won)
        assertEquals(listOf(true,false),radiant.detail["players"]!!.jsonArray.map { it.jsonObject["isRadiant"]!!.jsonPrimitive.boolean })
        assertEquals(raw["players"]!!.jsonArray[0].jsonObject["isRadiant"],null) // Original response remains immutable.
        assertEquals(radiant,prepareDotaTargetMatch(radiant.detail,100,1))
        assertFailsWith<IllegalArgumentException> { prepareDotaTargetMatch(raw,101,1) }
        assertFailsWith<IllegalArgumentException> { prepareDotaTargetMatch(raw,100,3) }
        assertFailsWith<IllegalArgumentException> { prepareDotaTargetMatch(JsonObject(raw-"radiant_win"),100,1) }
        for(bad in listOf(player(1,77),JsonObject(player(1,0)+("isRadiant" to JsonPrimitive(false))))) {
            assertFailsWith<IllegalArgumentException> { prepareDotaTargetMatch(JsonObject(raw+("players" to JsonArray(listOf(bad)))),100,1) }
        }
        val explicit=JsonObject(raw+("players" to JsonArray(listOf(buildJsonObject { put("account_id",1);put("isRadiant",true) }))))
        assertTrue(prepareDotaTargetMatch(explicit,100,1).won)
    }

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
