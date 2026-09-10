package top.colter.dynamic.agent.dota2

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import top.colter.dynamic.agent.util.HttpUtils
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

internal enum class OverviewMode(val command: String, val thinking: String) {
    NORMAL("个人详情", "disabled"), DEEP("深度个人详情", "enabled");
    companion object { fun fromCommand(value: String?) = entries.find { it.command == value } }
}

internal fun overviewAccount(args: List<String>, binding: Long?): Long {
    require(args.size in 1..2 && OverviewMode.fromCommand(args.firstOrNull()) != null) { "用法：/dota 个人详情 [玩家ID] 或 /dota 深度个人详情 [玩家ID]" }
    return dotaPlayerAccount(args.getOrNull(1), binding)
}

internal data class OverviewValue(val value: Double?, val count: Int) {
    fun display(digits: Int = 1) = value?.let { String.format(Locale.ROOT, "%.${digits}f", it) } ?: "—"
}

internal data class OverviewMatch(
    val id: Long, val heroId: Int, val hero: String, val start: Long?, val duration: Int?, val won: Boolean?,
    val values: Map<String, Double>, val benchmarks: Map<String, Double>, val items: List<Int>, val detailAvailable: Boolean,
) {
    fun number(key: String) = values[key]?.let { String.format(Locale.ROOT, "%.0f", it) } ?: "—"
    val selectionScore: Double? get() = listOf("gold_per_min", "hero_damage_per_min", "tower_damage")
        .map { benchmarks[it] }.takeIf { it.all { v -> v != null } }?.filterNotNull()?.average()
}

internal data class OverviewRepresentative(val match: OverviewMatch, val label: String)
internal data class OverviewSnapshot(val accountId: Long, val player: String, val matches: List<OverviewMatch>, val avatarUrl: String = "") {
    val wins get() = matches.count { it.won == true }
    val losses get() = matches.count { it.won == false }
    val details get() = matches.count { it.detailAvailable }
    fun average(key: String, benchmark: Boolean = false): OverviewValue {
        val values = matches.mapNotNull { (if (benchmark) it.benchmarks else it.values)[key] }
        return OverviewValue(values.takeIf { it.isNotEmpty() }?.average(), values.size)
    }
    val representatives: List<OverviewRepresentative> get() {
        val eligible = matches.filter { it.selectionScore != null }.sortedWith(compareBy<OverviewMatch> { it.selectionScore }.thenBy { it.id })
        if (eligible.isEmpty()) return emptyList()
        if (eligible.size == 1) return listOf(OverviewRepresentative(eligible.single(), "参考样本"))
        return listOf(OverviewRepresentative(eligible.last(), "优势样本"), OverviewRepresentative(eligible.first(), "待复盘样本"))
    }
}

internal val overviewMetrics = linkedMapOf("gold_per_min" to "经济", "xp_per_min" to "经验", "last_hits_per_min" to "补刀", "hero_damage_per_min" to "伤害", "tower_damage" to "推进")
private val overviewFields = listOf("kills", "deaths", "assists", "gold_per_min", "xp_per_min", "last_hits", "hero_damage", "tower_damage")
private fun JsonObject.num(key: String) = (get(key) as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 }
private fun JsonObject.long(key: String) = (get(key) as? JsonPrimitive)?.longOrNull

internal fun buildOverviewSnapshot(accountId: Long, profile: JsonObject?, recent: JsonArray, details: Map<Long, JsonObject>, heroName: (Int) -> String): OverviewSnapshot {
    val rows = orderedRecentMatches(recent, 10).mapNotNull { it as? JsonObject }.distinctBy { it.long("match_id") }.mapNotNull { row ->
        val id = row.long("match_id")?.takeIf { it > 0 } ?: return@mapNotNull null
        val detail = details[id]?.takeIf { it.long("match_id") == id }
        val me = (detail?.get("players") as? JsonArray)?.mapNotNull { it as? JsonObject }?.find { it.long("account_id") == accountId }
        val source = me ?: row
        val slot = source.long("player_slot")?.takeIf { it in 0..4 || it in 128..132 }
        val side = (me?.get("isRadiant") as? JsonPrimitive)?.booleanOrNull ?: slot?.let { it < 128 }
        val rw = ((detail?.takeIf { me != null }?.get("radiant_win") ?: row["radiant_win"]) as? JsonPrimitive)?.booleanOrNull
        val values = overviewFields.mapNotNull { key -> (source.num(key) ?: row.num(key))?.let { key to it } }.toMap().toMutableMap()
        val teamKills = if (me != null && side != null) detail.num(if (side) "radiant_score" else "dire_score") else null
        val k = values["kills"]; val a = values["assists"]
        if (teamKills != null && k != null && a != null && teamKills > 0 && k + a <= teamKills) values["kill_participation"] = (k + a) / teamKills * 100
        val benchmarks = (me?.get("benchmarks") as? JsonObject)?.mapNotNull { (key, value) ->
            (value as? JsonObject)?.num("pct")?.takeIf { it <= 1 }?.let { key to it * 100 }
        }?.toMap().orEmpty()
        val hero = source.long("hero_id")?.toInt() ?: row.long("hero_id")?.toInt() ?: 0
        OverviewMatch(id, hero, heroName(hero), (detail?.long("start_time") ?: row.long("start_time"))?.takeIf { it > 0 },
            (detail?.num("duration") ?: row.num("duration"))?.takeIf { it <= Int.MAX_VALUE }?.toInt(),
            if (side != null && rw != null) side == rw else null, values, benchmarks,
            if (me == null) emptyList() else (0..5).map { me.long("item_$it")?.toInt()?.coerceAtLeast(0) ?: 0 }, me != null)
    }.sortedWith(compareBy<OverviewMatch> { it.start ?: 0L }.thenBy { it.id })
    val name = ((profile?.get("profile") as? JsonObject)?.get("personaname") as? JsonPrimitive)?.contentOrNull
        ?.replace(Regex("""[\p{Cntrl}]"""), " ")?.take(80)?.takeIf { it.isNotBlank() } ?: "玩家 $accountId"
    val avatar = ((profile?.get("profile") as? JsonObject)?.get("avatarfull") as? JsonPrimitive)?.contentOrNull.orEmpty()
    return OverviewSnapshot(accountId, name, rows, avatar)
}

/** Bounded, short-lived cache of successful immutable JSON. Never cache failures/private-player misses. */
internal class OverviewDetailCache {
    private val entries = LinkedHashMap<Long, Pair<Long, JsonObject>>()
    @Synchronized fun get(id: Long): JsonObject? {
        val value = entries[id] ?: return null
        if (System.nanoTime() - value.first > 300_000_000_000L) { entries.remove(id); return null }
        return value.second
    }
    @Synchronized fun put(id: Long, value: JsonObject) {
        entries[id] = System.nanoTime() to value
        while (entries.size > 32) entries.remove(entries.keys.first())
    }
}

internal suspend fun Dota2Service.getOverviewSnapshot(accountId: Long): OverviewSnapshot = coroutineScope {
    val profile = async { try {
        val response = HttpUtils.httpGetAsync("$apiBase/players/$accountId")
        if (response.statusCode() in 200..299) HttpUtils.json.parseToJsonElement(response.body()) as? JsonObject else null
    } catch (e: Exception) { if (e is CancellationException) throw e; null } }
    val recent = getRecentMatches(accountId, 10) ?: throw IllegalStateException("获取最近比赛失败，请稍后重试")
    require(recent.isNotEmpty()) { "没有可用的公开对局记录" }
    val semaphore = Semaphore(2)
    val details = recent.mapNotNull { (it as? JsonObject)?.long("match_id") }.distinct().map { id -> async {
        semaphore.withPermit {
            val detail = overviewDetails.get(id) ?: getMatchDetail(id)?.takeIf { it.long("match_id") == id }
                ?.also { if ((it["players"] as? JsonArray)?.any { p -> (p as? JsonObject)?.long("account_id") == accountId } == true) overviewDetails.put(id, it) }
            id to detail
        }
    } }.awaitAll().mapNotNull { (id, d) -> d?.let { id to it } }.toMap()
    buildOverviewSnapshot(accountId, profile.await(), recent, details, ::heroName).also { require(it.matches.isNotEmpty()) { "没有可用的公开对局记录" } }
}
