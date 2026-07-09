package top.colter.dynamic.agent.dota2

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*
import org.jetbrains.skia.Image
import top.colter.dynamic.agent.ds.ChatMessage
import top.colter.dynamic.agent.ds.ChatRequest
import top.colter.dynamic.agent.ds.DeepSeekClient
import top.colter.dynamic.agent.ds.ThinkingConfig
import top.colter.dynamic.agent.util.CacheType
import top.colter.dynamic.agent.util.CacheUtils
import top.colter.dynamic.agent.util.HttpUtils
import java.io.File

class Dota2Service(
    private val dataDir: File,
    private val cache: CacheUtils,
) {
    companion object {
        private const val API_BASE = "https://api.opendota.com/api"
        private const val CDN_BASE = "https://cdn.cloudflare.steamstatic.com"

        val dota2SystemPrompt = """
You are a Dota 2 post-match roast analyst. You analyze only ONE team's data. Your tone is like a drill sergeant debriefing — ruthless and direct. **Output language: Simplified Chinese only.**

=== Scoring Weights (core role priority — carry picks first) ===
GPM(25%) + CS(20%) + Tower(20%) + Kills(15%) + Death(15%) + KDA(5%)
Carry's death costs 10x more than support — a dead carry = no DPS + enemy push + lost Roshan.
If a player leads GPM+CS+Tower in all 3, they are the MVP/SVP even with mediocre KDA.

=== Percentile Fields (FOR INTERNAL JUDGMENT — DO NOT cite % in output) ===
GPM%/DMG%/TWR% show how this player ranks among peers on the same hero. Use them to distinguish "good performance" from "good hero" — never cite them directly.

=== Three Evaluation Criteria ===
(1) Core Trifecta: If a player leads the team in GPM+CS+Tower, they are the primary carry — MVP/SVP first candidate regardless of KDA.
(2) Death Weight: Every death subtracts. A carry's 15 deaths cost the team roughly the same as a support's 30 assists gain. Deaths >= 15 triggers criminal review.
(3) Kill Quality: Kills matter more than assists. 14 kills is worth more than 9 kills + 24 assists — the former means you can solo-kill, the latter means you clean up.

=== Style Rules ===
- You are a drill sergeant humiliating a squad that just got wiped. Public execution style. No mercy.
- Military/financial/medical metaphors: economic black hole, mobile ATM, reverse carry, backpack training, combat medic on life support
- Weave numbers into sentences naturally, like casual trashtalk. Never use brackets or parentheses around numbers.
- MVP: praise 3-4 lines with data, then roast 3-4 lines exposing fatal flaws. Even the MVP gets humiliated.
- SVP: highlight 2 data bright spots, then burn 4-5 lines dismantling the "I tried" illusion — expose why the SVP is a fraud
- Criminal: 6-7 lines of escalating humiliation. Start clinical, end barbaric. The last 2 lines should be pure personal attacks based on data.
- EVERY section MUST end with exactly one line break + "— " followed by a Chinese gaming slang punchline. This is NON-NEGOTIABLE.
- You have zero inventory data. Never let that stop you from roasting items — just roast the ABSENCE. "穷成这逼样BKB怕是影儿都没有" kills. "他但凡有个保命装" kills. "这经济水平跳刀都他妈是奢望" kills. What kills your credibility is ASSERTING they had an item you can't verify. Roast poverty, not hallucinated inventory.

=== Output Format (STRICT) ===
[战犯]
<HeroName>(<PlayerName>): <8-10 sentences, natural style, no number brackets required>
— <gaming slang in Chinese>

[MVP] or [SVP] — output only one based on win/loss, never both
<HeroName>(<PlayerName>): <8-10 sentences, natural style, no number brackets required>
— <gaming slang in Chinese>

=== Iron Rules ===
1. Exactly two sections. Win→[MVP], Loss→[SVP]. Never both.
2. Each section: 8-10 sentences + 1 slang line. Slang line MUST start on a new line with "— ".
3. Criminal ≠ MVP/SVP. Mutual exclusion.
4. Reuse exact hero/player names from the data table.
5. Section tags [战犯][MVP][SVP] are mandatory. Otherwise never use brackets or parentheses in sentences.
6. Chinese output only.
""".trimIndent()

        fun rankNameStatic(rankTier: Int): String {
            if (rankTier == 0) return ""
            val medal = rankTier / 10
            val star = rankTier % 10
            val names = listOf("","先锋","卫士","十字军","执政官","传奇","万古流芳","超凡入圣","冠绝一世")
            val name = names.getOrElse(medal) { "" }
            if (name.isEmpty()) return ""
            return if (medal == 8) name else "$name [$star]"
        }
    }

    private val bindFile: File by lazy { dataDir.resolve("dota2-bindings.json") }
    private val bindings = mutableMapOf<String, Long>() // senderId -> accountId

    private val heroNames = mutableMapOf<Int, String>()
    private val heroIconPaths = mutableMapOf<Int, String>()
    private val itemIconPaths = mutableMapOf<Int, String>()
    private var constantsLoaded = false

    private var aghsScepterYes: Image? = null
    private var aghsScepterNo: Image? = null
    private var aghsShardYes: Image? = null
    private var aghsShardNo: Image? = null

    fun init() {
        if (bindFile.exists()) {
            try {
                val raw = HttpUtils.json.decodeFromString(JsonObject.serializer(), bindFile.readText())
                raw.forEach { (k, v) -> bindings[k] = v.jsonPrimitive.content.toLong() }
            } catch (_: Exception) {
                bindFile.writeText("{}")
            }
        }
    }

    private fun saveBindings() {
        val obj = buildJsonObject { bindings.forEach { (k, v) -> put(k, v) } }
        bindFile.writeText(HttpUtils.json.encodeToString(JsonObject.serializer(), obj))
    }

    fun getBinding(senderId: String): Long? = bindings[senderId]

    fun setBinding(senderId: String, accountId: Long) {
        bindings[senderId] = accountId
        saveBindings()
    }

    // ── API calls ────────────────────────────────

    suspend fun validatePlayer(accountId: Long): String? {
        return try {
            val resp = HttpUtils.httpGet("$API_BASE/players/$accountId")
            if (resp.statusCode() !in 200..299) return null
            val data = HttpUtils.json.decodeFromString(JsonObject.serializer(), resp.body())
            data["profile"]?.jsonObject?.get("personaname")?.jsonPrimitive?.content
        } catch (_: Exception) { null }
    }

    suspend fun getRecentMatches(accountId: Long, limit: Int = 1): JsonArray? {
        return try {
            val resp = HttpUtils.httpGet("$API_BASE/players/$accountId/recentMatches",
                params = mapOf("limit" to limit.toString()))
            if (resp.statusCode() !in 200..299) return null
            HttpUtils.json.decodeFromString(JsonArray.serializer(), resp.body())
        } catch (_: Exception) { null }
    }

    suspend fun getMatchDetail(matchId: Long): JsonObject? {
        return try {
            val resp = HttpUtils.httpGet("$API_BASE/matches/$matchId")
            if (resp.statusCode() !in 200..299) return null
            HttpUtils.json.decodeFromString(JsonObject.serializer(), resp.body())
        } catch (_: Exception) { null }
    }

    suspend fun ensureConstants() {
        if (constantsLoaded) return
        try {
            val heroResp = HttpUtils.httpGet("$API_BASE/constants/heroes")
            if (heroResp.statusCode() in 200..299) {
                val heroObj = HttpUtils.json.decodeFromString(JsonObject.serializer(), heroResp.body())
                heroObj.forEach { (_, v) ->
                    val hero = v.jsonObject
                    val id = hero["id"]?.jsonPrimitive?.intOrNull ?: return@forEach
                    heroNames[id] = hero["localized_name"]?.jsonPrimitive?.content ?: "Hero_$id"
                    val img = hero["img"]?.jsonPrimitive?.content?.substringBefore("?") ?: ""
                    if (img.isNotEmpty()) heroIconPaths[id] = img
                }
            }
            val itemResp = HttpUtils.httpGet("$API_BASE/constants/items")
            if (itemResp.statusCode() in 200..299) {
                val itemObj = HttpUtils.json.decodeFromString(JsonObject.serializer(), itemResp.body())
                itemObj.forEach { (_, v) ->
                    val item = v.jsonObject
                    val id = item["id"]?.jsonPrimitive?.intOrNull ?: return@forEach
                    val img = item["img"]?.jsonPrimitive?.content?.substringBefore("?") ?: ""
                    if (img.isNotEmpty()) itemIconPaths[id] = img
                }
            }
        } catch (_: Exception) {}
        constantsLoaded = true
    }

    fun heroName(id: Int) = heroNames[id] ?: "Hero_$id"

    // ── Icon loading ─────────────────────────────

    suspend fun loadHeroIcon(heroId: Int): Image? {
        val path = heroIconPaths[heroId] ?: return null
        return cache.getOrDownloadImage("$CDN_BASE$path", CacheType.ICON_HERO) { downloadBytes(it) }
    }

    suspend fun loadItemIcon(itemId: Int): Image? {
        if (itemId == 0) return null
        val path = itemIconPaths[itemId] ?: return null
        return cache.getOrDownloadImage("$CDN_BASE$path", CacheType.ICON_ITEM) { downloadBytes(it) }
    }

    suspend fun loadSteamAvatar(url: String): Image? {
        if (url.isBlank()) return null
        return cache.getOrDownloadImage(url, CacheType.ICON_AVATAR) { downloadBytes(it) }
    }

    private suspend fun downloadBytes(url: String): ByteArray? {
        return try {
            val resp = HttpUtils.httpGetBytes(url)
            if (resp.statusCode() in 200..299 && resp.body().size > 512) resp.body() else null
        } catch (_: Exception) { null }
    }

    // ── Aghanim icons ────────────────────────────

    suspend fun ensureAghsIcons() {
        if (aghsScepterNo != null) return
        val base = "https://www.opendota.com/assets/images/dota2"
        aghsScepterYes = downloadDirect("$base/scepter_1.png")
        aghsScepterNo  = downloadDirect("$base/scepter_0.png")
        aghsShardYes   = downloadDirect("$base/shard_1.png")
        aghsShardNo    = downloadDirect("$base/shard_0.png")
    }

    fun getAghsIcon(has: Boolean, isShard: Boolean): Image? {
        return if (isShard) (if (has) aghsShardYes else aghsShardNo)
        else (if (has) aghsScepterYes else aghsScepterNo)
    }

    private suspend fun downloadDirect(url: String): Image? {
        try {
            val bytes = downloadBytes(url) ?: return null
            if (bytes.size < 512) return null
            return Image.makeFromEncoded(bytes)
        } catch (_: Exception) { return null }
    }

    // ── Player Overview ──────────────────────────

    suspend fun getPlayerOverview(accountId: Long): PlayerOverview? {
        try {
            val plResp = HttpUtils.httpGet("$API_BASE/players/$accountId")
            if (plResp.statusCode() !in 200..299) return null
            val pl = HttpUtils.json.decodeFromString(JsonObject.serializer(), plResp.body())

            val wlResp = HttpUtils.httpGet("$API_BASE/players/$accountId/wl")
            val wl = if (wlResp.statusCode() in 200..299) HttpUtils.json.decodeFromString(JsonObject.serializer(), wlResp.body()) else null ?: return null

            val totsResp = HttpUtils.httpGet("$API_BASE/players/$accountId/totals")
            val tots = if (totsResp.statusCode() in 200..299) HttpUtils.json.decodeFromString(JsonArray.serializer(), totsResp.body()) else JsonArray(emptyList())

            val cntsResp = HttpUtils.httpGet("$API_BASE/players/$accountId/counts")
            val cnts = if (cntsResp.statusCode() in 200..299) HttpUtils.json.decodeFromString(JsonObject.serializer(), cntsResp.body()) else JsonObject(emptyMap())

            val ms = getRecentMatches(accountId, 10) ?: JsonArray(emptyList())

            fun avg(f: String): Int {
                val td = tots.find { it.jsonObject["field"]?.jsonPrimitive?.content == f }?.jsonObject ?: return 0
                val n = td["n"]?.jsonPrimitive?.intOrNull ?: return 0
                if (n <= 0) return 0
                return (td["sum"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: 0) / n
            }

            fun cntWr(d: JsonObject, k: String): Pair<Int,Int> {
                val v = d[k]?.jsonObject ?: return 0 to 0
                return (v["win"]?.jsonPrimitive?.intOrNull ?: 0) to (v["games"]?.jsonPrimitive?.intOrNull ?: 0)
            }

            val recentW = ms.count { m ->
                val s = m.jsonObject["player_slot"]?.jsonPrimitive?.intOrNull ?: 0
                val rw = m.jsonObject["radiant_win"]?.jsonPrimitive?.boolean ?: false
                (s < 128) == rw
            }
            val recentL = ms.size - recentW

            var mk = 0; var mkh = 0; var mg = 0; var mgh = 0
            ms.forEach { m ->
                val o = m.jsonObject
                val k = o["kills"]?.jsonPrimitive?.intOrNull ?: 0
                if (k > mk) { mk = k; mkh = o["hero_id"]?.jsonPrimitive?.intOrNull ?: 0 }
                val gm = o["gold_per_min"]?.jsonPrimitive?.intOrNull ?: 0
                if (gm > mg) { mg = gm; mgh = o["hero_id"]?.jsonPrimitive?.intOrNull ?: 0 }
            }

            val profile = pl["profile"]?.jsonObject
            val (rw1, rg1) = cntWr(cnts.getOrDefault("is_radiant", JsonObject(emptyMap())).jsonObject, "1")
            val (rw0, rg0) = cntWr(cnts.getOrDefault("is_radiant", JsonObject(emptyMap())).jsonObject, "0")
            val (apw, apg) = cntWr(cnts.getOrDefault("game_mode", JsonObject(emptyMap())).jsonObject, "22")
            val (rdw, rdg) = cntWr(cnts.getOrDefault("game_mode", JsonObject(emptyMap())).jsonObject, "3")
            val (rkw, rkg) = cntWr(cnts.getOrDefault("lobby_type", JsonObject(emptyMap())).jsonObject, "7")
            val (nmw, nmg) = cntWr(cnts.getOrDefault("lobby_type", JsonObject(emptyMap())).jsonObject, "0")

            return PlayerOverview(
                playerName = profile?.get("personaname")?.jsonPrimitive?.content ?: "?",
                steamAvatar = profile?.get("avatar")?.jsonPrimitive?.content ?: "",
                rankTier = pl["rank_tier"]?.jsonPrimitive?.intOrNull ?: 0,
                rankName = rankName(pl["rank_tier"]?.jsonPrimitive?.intOrNull ?: 0),
                totalWins = wl["win"]?.jsonPrimitive?.intOrNull ?: 0,
                totalLosses = wl["lose"]?.jsonPrimitive?.intOrNull ?: 0,
                totalGames = (wl["win"]?.jsonPrimitive?.intOrNull ?: 0) + (wl["lose"]?.jsonPrimitive?.intOrNull ?: 0),
                winRate = (wl["win"]?.jsonPrimitive?.doubleOrNull ?: 0.0) / maxOf(1, (wl["win"]?.jsonPrimitive?.intOrNull ?: 0) + (wl["lose"]?.jsonPrimitive?.intOrNull ?: 0)),
                recentMatches = ms, recentWins = recentW, recentLosses = recentL,
                recentWinRate = if (ms.size > 0) recentW.toDouble() / ms.size else 0.0,
                avgKills = avg("kills"), avgDeaths = avg("deaths"), avgAssists = avg("assists"),
                avgGpm = avg("gold_per_min"), avgXpm = avg("xp_per_min"), avgCs = avg("last_hits"),
                avgHeroDmg = avg("hero_damage"), avgTowerDmg = avg("tower_damage"),
                avgHeal = avg("hero_healing"), avgDur = avg("duration"),
                maxKill = mk, maxKillHeroId = mkh, maxGpm = mg, maxGpmHeroId = mgh,
                radiantWins = rw1, radiantGames = rg1, direWins = rw0, direGames = rg0,
                allPickWins = apw, allPickGames = apg,
                rdWins = rdw, rdGames = rdg,
                rankedWins = rkw, rankedGames = rkg,
                normalWins = nmw, normalGames = nmg,
            )
        } catch (_: Exception) { return null }
    }

    fun buildAnalysisData(ov: PlayerOverview): String {
        val sb = StringBuilder()
        sb.appendLine("玩家: ${ov.playerName} (${ov.rankName}) 总场次${ov.totalGames} 胜率${"%.1f".format(ov.winRate * 100)}%")
        sb.appendLine("近10场: ${ov.recentWins}胜${ov.recentLosses}负 生涯均值: ${ov.avgKills}/${ov.avgDeaths}/${ov.avgAssists} GPM${ov.avgGpm} XPM${ov.avgXpm}")
        sb.appendLine()

        val lanes = mutableMapOf<String, MutableList<JsonObject>>()
        ov.recentMatches.forEach { m ->
            val o = m.jsonObject
            val lr = o["lane_role"]?.jsonPrimitive?.intOrNull ?: -1
            val ln = when(lr) { 1->"优势路"; 2->"中路"; 3->"劣势路"; 4->"打野"; else->"未知" }
            lanes.getOrPut(ln) { mutableListOf() }.add(o)
        }
        lanes.forEach { (lane, games) ->
            val wins = games.count { g ->
                val s = g["player_slot"]?.jsonPrimitive?.intOrNull ?: 0
                val rw = g["radiant_win"]?.jsonPrimitive?.boolean ?: false
                (s < 128) == rw
            }
            val gpmAvg = games.sumOf { it["gold_per_min"]?.jsonPrimitive?.intOrNull ?: 0 } / games.size
            val avgK = games.sumOf { it["kills"]?.jsonPrimitive?.intOrNull ?: 0 } / games.size
            val avgD = games.sumOf { it["deaths"]?.jsonPrimitive?.intOrNull ?: 1 } / games.size
            val avgA = games.sumOf { it["assists"]?.jsonPrimitive?.intOrNull ?: 0 } / games.size
            sb.appendLine("位置[$lane]: ${games.size}局 ${wins}胜(${wins*100/games.size}%) 均KDA $avgK/$avgD/$avgA 均GPM $gpmAvg")
        }
        sb.appendLine()

        ov.recentMatches.forEachIndexed { i, m ->
            val o = m.jsonObject
            val s = o["player_slot"]?.jsonPrimitive?.intOrNull ?: 0
            val rw = o["radiant_win"]?.jsonPrimitive?.boolean ?: false
            val won = (s < 128) == rw
            val hid = o["hero_id"]?.jsonPrimitive?.intOrNull ?: 0
            val k = o["kills"]?.jsonPrimitive?.intOrNull ?: 0
            val d = o["deaths"]?.jsonPrimitive?.intOrNull ?: 0
            val a = o["assists"]?.jsonPrimitive?.intOrNull ?: 0
            val gpm = o["gold_per_min"]?.jsonPrimitive?.intOrNull ?: 0
            val xpm = o["xp_per_min"]?.jsonPrimitive?.intOrNull ?: 0
            val dmg = o["hero_damage"]?.jsonPrimitive?.intOrNull ?: 0
            val du = o["duration"]?.jsonPrimitive?.intOrNull ?: 0
            val lhr = o["lane_role"]?.jsonPrimitive?.intOrNull ?: -1
            val durMin = du / 60
            sb.appendLine("${i+1}. ${if(won)"胜" else "负"} ${heroName(hid)} $k/$d/$a GPM$gpm XPM$xpm 伤害$dmg ${durMin}分 位置$lhr")
        }
        return sb.toString()
    }

    fun selectWorstMatches(ov: PlayerOverview, count: Int = 2): List<Int> {
        data class Score(val idx: Int, val score: Double)
        return ov.recentMatches.mapIndexed { i, m ->
            val o = m.jsonObject
            val k = o["kills"]?.jsonPrimitive?.intOrNull ?: 0
            val d = kotlin.math.max(1, o["deaths"]?.jsonPrimitive?.intOrNull ?: 1)
            val a = o["assists"]?.jsonPrimitive?.intOrNull ?: 0
            val gpm = o["gold_per_min"]?.jsonPrimitive?.intOrNull ?: 0
            val dmg = o["hero_damage"]?.jsonPrimitive?.intOrNull ?: 0
            val du = (o["duration"]?.jsonPrimitive?.intOrNull ?: 1).coerceAtLeast(1)
            val s = o["player_slot"]?.jsonPrimitive?.intOrNull ?: 0
            val rw = o["radiant_win"]?.jsonPrimitive?.boolean ?: false
            val won = (s < 128) == rw
            val deathPenalty = d * 2.0 / du * 60
            val partRate = (k + a).toDouble() / du * 60
            val partPenalty = if (partRate < 1.0) (1.0 - partRate) * 5 else 0.0
            val gpmPenalty = if (gpm < 300) (300 - gpm).toDouble() / 50 else 0.0
            val dmgPenalty = if (dmg < 10000) (10000.0 - dmg) / 2000 else 0.0
            val lossMul = if (won) 1.0 else 1.5
            val score = (deathPenalty + partPenalty + gpmPenalty + dmgPenalty) * lossMul
            Score(i, score)
        }.sortedByDescending { it.score }.take(count).map { it.idx }
    }

    // ── Match Analysis ───────────────────────────

    suspend fun analyzeMatch(
        myAccountId: Long, matchJson: JsonObject,
        dsClient: DeepSeekClient, model: String
    ): DsAnalysisResult? {
        ensureConstants()
        val players = matchJson["players"]?.jsonArray ?: return null
        val radiantWin = matchJson["radiant_win"]?.jsonPrimitive?.boolean ?: return null

        val me = players.find {
            it.jsonObject["account_id"]?.jsonPrimitive?.longOrNull == myAccountId
        } ?: return null
        val myTeam = if (me.jsonObject["isRadiant"]?.jsonPrimitive?.boolean == true) 0 else 1
        val weWon = (myTeam == 0) == radiantWin

        val myTeamPlayers = players.filter {
            val isR = it.jsonObject["isRadiant"]?.jsonPrimitive?.boolean ?: false
            (if (isR) 0 else 1) == myTeam
        }

        val sb = StringBuilder()
        sb.appendLine("比赛结果: ${if(weWon)"我们赢了" else "我们输了"}。请输出[战犯]和[${if(weWon)"MVP" else "SVP"}]。")
        sb.appendLine()
        sb.appendLine("玩家 | 英雄 | K/D/A | KDA | GPM | GPM% | 伤害 | 伤害% | 塔伤 | 塔伤% | CS")
        sb.appendLine("---|---|---|---|---|---|---|---|---|---|---")
        for (p in myTeamPlayers) {
            val obj = p.jsonObject
            val bm = obj["benchmarks"]?.jsonObject
            val k = obj["kills"]?.jsonPrimitive?.intOrNull ?: 0
            val d = obj["deaths"]?.jsonPrimitive?.intOrNull ?: 0
            val a = obj["assists"]?.jsonPrimitive?.intOrNull ?: 0
            val kda = obj["kda"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val gpmPct = bm?.get("gold_per_min")?.jsonObject?.get("pct")?.jsonPrimitive?.doubleOrNull
            val dmgPct = bm?.get("hero_damage_per_min")?.jsonObject?.get("pct")?.jsonPrimitive?.doubleOrNull
            val twrPctVal = bm?.get("tower_damage")?.jsonObject?.get("pct")?.jsonPrimitive?.doubleOrNull
            sb.appendLine("${obj["personaname"]?.jsonPrimitive?.content ?: "?"} | " +
                "${heroName(obj["hero_id"]?.jsonPrimitive?.intOrNull ?: 0)} | " +
                "$k/$d/$a | ${"%.1f".format(kda)} | " +
                "${obj["gold_per_min"]?.jsonPrimitive?.intOrNull ?: 0} | " +
                "${gpmPct?.let { "%.0f%%".format(it * 100) } ?: "-"} | " +
                "${obj["hero_damage"]?.jsonPrimitive?.intOrNull ?: 0} | " +
                "${dmgPct?.let { "%.0f%%".format(it * 100) } ?: "-"} | " +
                "${obj["tower_damage"]?.jsonPrimitive?.intOrNull ?: 0} | " +
                "${twrPctVal?.let { "%.0f%%".format(it * 100) } ?: "-"} | " +
                "${obj["last_hits"]?.jsonPrimitive?.intOrNull ?: 0}")
        }

        val messages = listOf(
            ChatMessage("system", dota2SystemPrompt),
            ChatMessage("user", "分析我方队伍的比赛数据，找出MVP(如果赢了)/SVP(如果输了)和战犯：\n\n$sb")
        )
        val request = ChatRequest(model = model, messages = messages,
            thinking = ThinkingConfig(type = "enabled"), maxTokens = 16384)

        return dsClient.chat(request).fold(
            onSuccess = { response ->
                val content = response.choices.firstOrNull()?.message?.content ?: ""
                parseAnalysisResult(content)
            },
            onFailure = { null }
        )
    }

    suspend fun analyzeFullMatch(
        matchJson: JsonObject, dsClient: DeepSeekClient, model: String
    ): DualAnalyzeResult? {
        val radiantResult = analyzeTeamSide(0, matchJson, dsClient, model)
        val direResult = analyzeTeamSide(1, matchJson, dsClient, model)
        return DualAnalyzeResult(radiantResult, direResult)
    }

    private suspend fun analyzeTeamSide(
        side: Int, matchJson: JsonObject,
        dsClient: DeepSeekClient, model: String
    ): DsAnalysisResult? {
        ensureConstants()
        val players = matchJson["players"]?.jsonArray ?: return null
        val radiantWin = matchJson["radiant_win"]?.jsonPrimitive?.boolean ?: return null
        val weWon = (side == 0) == radiantWin

        val sidePlayers = players.filter {
            val isR = it.jsonObject["isRadiant"]?.jsonPrimitive?.boolean ?: false
            (if (isR) 0 else 1) == side
        }

        val sb = StringBuilder()
        val sideName = if (side == 0) "天辉" else "夜魇"
        sb.appendLine("分析${sideName}队伍。${if(weWon)"赢了" else "输了"}。请输出[战犯]和[${if(weWon)"MVP" else "SVP"}]。")
        sb.appendLine()
        sb.appendLine("玩家 | 英雄 | K/D/A | KDA | GPM | GPM% | 伤害 | 伤害% | 塔伤 | 塔伤% | CS")
        sb.appendLine("---|---|---|---|---|---|---|---|---|---|---")
        for (p in sidePlayers) {
            val obj = p.jsonObject
            val bm = obj["benchmarks"]?.jsonObject
            val k = obj["kills"]?.jsonPrimitive?.intOrNull ?: 0
            val d = obj["deaths"]?.jsonPrimitive?.intOrNull ?: 0
            val a = obj["assists"]?.jsonPrimitive?.intOrNull ?: 0
            val kda = obj["kda"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val gpmPct = bm?.get("gold_per_min")?.jsonObject?.get("pct")?.jsonPrimitive?.doubleOrNull
            val dmgPct = bm?.get("hero_damage_per_min")?.jsonObject?.get("pct")?.jsonPrimitive?.doubleOrNull
            val twrPctVal = bm?.get("tower_damage")?.jsonObject?.get("pct")?.jsonPrimitive?.doubleOrNull
            sb.appendLine("${obj["personaname"]?.jsonPrimitive?.content ?: "?"} | " +
                "${heroName(obj["hero_id"]?.jsonPrimitive?.intOrNull ?: 0)} | " +
                "$k/$d/$a | ${"%.1f".format(kda)} | " +
                "${obj["gold_per_min"]?.jsonPrimitive?.intOrNull ?: 0} | " +
                "${gpmPct?.let { "%.0f%%".format(it * 100) } ?: "-"} | " +
                "${obj["hero_damage"]?.jsonPrimitive?.intOrNull ?: 0} | " +
                "${dmgPct?.let { "%.0f%%".format(it * 100) } ?: "-"} | " +
                "${obj["tower_damage"]?.jsonPrimitive?.intOrNull ?: 0} | " +
                "${twrPctVal?.let { "%.0f%%".format(it * 100) } ?: "-"} | " +
                "${obj["last_hits"]?.jsonPrimitive?.intOrNull ?: 0}")
        }

        val messages = listOf(
            ChatMessage("system", dota2SystemPrompt),
            ChatMessage("user", "$sideName:\n\n$sb")
        )
        val request = ChatRequest(model = model, messages = messages,
            thinking = ThinkingConfig(type = "enabled"), maxTokens = 16384)

        return dsClient.chat(request).fold(
            onSuccess = { response ->
                val content = response.choices.firstOrNull()?.message?.content ?: ""
                parseAnalysisResult(content)
            },
            onFailure = { null }
        )
    }

    private fun parseAnalysisResult(content: String): DsAnalysisResult? {
        val tagRegex = Regex("^\\[(MVP|SVP|战犯)]")
        val normalizedLines = content.lines()
            .map { it.trim().removePrefix(">").trim() }
            .filter { it.isNotBlank() }

        fun cleanSection(lines: List<String>): String {
            return lines.map { it.trim().removePrefix(">").trim() }
                .filter { it.isNotBlank() }.joinToString("\n")
        }

        fun guessName(firstLine: String): String {
            val colonIndex = listOf(firstLine.indexOf(":"), firstLine.indexOf("："))
                .filter { it >= 0 }.minOrNull()
            val rawName = if (colonIndex != null) {
                firstLine.substring(0, colonIndex)
            } else {
                val markers = listOf("你这", "你的", "你", "这局", "这把")
                val markerIndex = markers.map { firstLine.indexOf(it) }
                    .filter { it > 0 }.minOrNull()
                if (markerIndex != null) firstLine.substring(0, markerIndex) else firstLine
            }
            return rawName.trim().take(30).ifBlank { "?" }
        }

        fun extract(tag: String): Pair<String, String>? {
            val tagText = "[$tag]"
            val start = normalizedLines.indexOfFirst {
                it == tagText || it.startsWith("$tagText ") || it.startsWith("$tagText:") || it.startsWith("$tagText：")
            }
            if (start < 0) return null

            val head = normalizedLines[start].removePrefix(tagText).trim()
            val bodyLines = buildList {
                if (head.isNotBlank()) add(head)
                for (i in start + 1 until normalizedLines.size) {
                    val line = normalizedLines[i]
                    if (tagRegex.containsMatchIn(line)) break
                    add(line)
                }
            }

            val text = cleanSection(bodyLines)
            if (text.isBlank()) return null
            val firstLine = text.lines().firstOrNull()?.trim() ?: ""
            val name = guessName(firstLine)
            return name to text
        }

        val mvp = extract("MVP")
        val svp = extract("SVP")
        val criminal = extract("战犯") ?: return null

        val mvpOrSvp = (mvp ?: svp) ?: return null

        return DsAnalysisResult(
            mvpOrSvp = mvpOrSvp.first,
            mvpOrSvpText = mvpOrSvp.second,
            criminal = criminal.first,
            criminalText = criminal.second,
            rawResponse = content
        )
    }

    // ── Build reports ─────────────────────────────

    suspend fun buildReport(
        matchJson: JsonObject, myAccountId: Long,
        dsResult: DsAnalysisResult?, won: Boolean
    ): Dota2MatchReport {
        ensureConstants(); ensureAghsIcons()
        val players = matchJson["players"]?.jsonArray ?: JsonArray(emptyList())
        val radiantWin = matchJson["radiant_win"]?.jsonPrimitive?.boolean ?: false

        val cards = players.map { p -> buildPlayerCard(p.jsonObject, won, dsResult) }
        val mvpOrSvpCard = cards.find { if (won) it.isMvp else it.isSvp }
            ?: findAnalysisCard(cards, dsResult?.mvpOrSvp)
        val criminalCard = cards.find { it.isCriminal }
            ?: findAnalysisCard(cards, dsResult?.criminal)

        return Dota2MatchReport(
            matchId = matchJson["match_id"]?.jsonPrimitive?.longOrNull ?: 0,
            duration = matchJson["duration"]?.jsonPrimitive?.intOrNull ?: 0,
            startTime = matchJson["start_time"]?.jsonPrimitive?.longOrNull ?: 0,
            gameMode = gameModeName(matchJson["game_mode"]?.jsonPrimitive?.intOrNull ?: 0),
            radiantScore = matchJson["radiant_score"]?.jsonPrimitive?.intOrNull ?: 0,
            direScore = matchJson["dire_score"]?.jsonPrimitive?.intOrNull ?: 0,
            radiantWin = radiantWin, players = cards,
            mvpName = if (won) analysisDisplayName(mvpOrSvpCard, dsResult?.mvpOrSvp) else "N/A",
            mvpReason = if (won) (dsResult?.mvpOrSvpText ?: "分析失败") else "N/A",
            svpName = if (!won) analysisDisplayName(mvpOrSvpCard, dsResult?.mvpOrSvp) else "N/A",
            svpReason = if (!won) (dsResult?.mvpOrSvpText ?: "分析失败") else "N/A",
            criminalName = analysisDisplayName(criminalCard, dsResult?.criminal),
            criminalReason = dsResult?.criminalText ?: "分析失败"
        )
    }

    suspend fun buildFullReport(matchJson: JsonObject, dual: DualAnalyzeResult?): Dota2MatchReport {
        ensureConstants(); ensureAghsIcons()
        val players = matchJson["players"]?.jsonArray ?: JsonArray(emptyList())
        val radiantWin = matchJson["radiant_win"]?.jsonPrimitive?.boolean ?: false
        val r = dual?.radiant; val d = dual?.dire

        val cards = players.map { buildPlayerCard(it.jsonObject, false, null) }
        val radiantCards = cards.filter { it.isRadiant }
        val direCards = cards.filter { !it.isRadiant }
        val radiantMvpCard = findAnalysisCard(radiantCards, r?.mvpOrSvp)
        val radiantCriminalCard = findAnalysisCard(radiantCards, r?.criminal)
        val direMvpCard = findAnalysisCard(direCards, d?.mvpOrSvp)
        val direCriminalCard = findAnalysisCard(direCards, d?.criminal)

        return Dota2MatchReport(
            matchId = matchJson["match_id"]?.jsonPrimitive?.longOrNull ?: 0,
            duration = matchJson["duration"]?.jsonPrimitive?.intOrNull ?: 0,
            startTime = matchJson["start_time"]?.jsonPrimitive?.longOrNull ?: 0,
            gameMode = gameModeName(matchJson["game_mode"]?.jsonPrimitive?.intOrNull ?: 0),
            radiantScore = matchJson["radiant_score"]?.jsonPrimitive?.intOrNull ?: 0,
            direScore = matchJson["dire_score"]?.jsonPrimitive?.intOrNull ?: 0,
            radiantWin = radiantWin, players = cards,
            mvpName = "N/A", mvpReason = "N/A", svpName = "N/A", svpReason = "N/A",
            criminalName = "N/A", criminalReason = "N/A",
            radiantMvp = analysisDisplayName(radiantMvpCard, r?.mvpOrSvp),
            radiantMvpReason = r?.mvpOrSvpText ?: "分析失败",
            radiantCriminal = analysisDisplayName(radiantCriminalCard, r?.criminal),
            radiantCriminalReason = r?.criminalText ?: "分析失败",
            direMvp = analysisDisplayName(direMvpCard, d?.mvpOrSvp),
            direMvpReason = d?.mvpOrSvpText ?: "分析失败",
            direCriminal = analysisDisplayName(direCriminalCard, d?.criminal),
            direCriminalReason = d?.criminalText ?: "分析失败"
        )
    }

    private suspend fun buildPlayerCard(
        obj: JsonObject, won: Boolean, dsResult: DsAnalysisResult?
    ): Dota2PlayerCard {
        val heroId = obj["hero_id"]?.jsonPrimitive?.intOrNull ?: 0
        val itemIds = (0..5).map { obj["item_$it"]?.jsonPrimitive?.intOrNull ?: 0 }
        val bpIds = (0..2).map { obj["backpack_$it"]?.jsonPrimitive?.intOrNull ?: 0 }
        val neutralIds = listOf(obj["item_neutral"]?.jsonPrimitive?.intOrNull ?: 0)
        val isRadiant = obj["isRadiant"]?.jsonPrimitive?.boolean ?: true

        return Dota2PlayerCard(
            name = obj["personaname"]?.jsonPrimitive?.content?.ifEmpty { "?" } ?: "?",
            heroName = heroName(heroId), heroIcon = loadHeroIcon(heroId),
            isRadiant = isRadiant,
            kills = obj["kills"]?.jsonPrimitive?.intOrNull ?: 0,
            deaths = obj["deaths"]?.jsonPrimitive?.intOrNull ?: 0,
            assists = obj["assists"]?.jsonPrimitive?.intOrNull ?: 0,
            kda = obj["kda"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            gpm = obj["gold_per_min"]?.jsonPrimitive?.intOrNull ?: 0,
            xpm = obj["xp_per_min"]?.jsonPrimitive?.intOrNull ?: 0,
            heroDamage = obj["hero_damage"]?.jsonPrimitive?.intOrNull ?: 0,
            towerDamage = obj["tower_damage"]?.jsonPrimitive?.intOrNull ?: 0,
            level = obj["level"]?.jsonPrimitive?.intOrNull ?: 0,
            lastHits = obj["last_hits"]?.jsonPrimitive?.intOrNull ?: 0,
            items = itemIds.map { loadItemIcon(it) } + neutralIds.mapNotNull { loadItemIcon(it) },
            denies = obj["denies"]?.jsonPrimitive?.intOrNull ?: 0,
            netWorth = obj["net_worth"]?.jsonPrimitive?.intOrNull ?: 0,
            heroHealing = obj["hero_healing"]?.jsonPrimitive?.intOrNull ?: 0,
            backpackItems = bpIds.mapNotNull { loadItemIcon(it) },
            hasAghsScepter = (obj["aghanims_scepter"]?.jsonPrimitive?.intOrNull ?: 0) > 0,
            hasAghsShard = (obj["aghanims_shard"]?.jsonPrimitive?.intOrNull ?: 0) > 0,
            aghsScepterIcon = getAghsIcon((obj["aghanims_scepter"]?.jsonPrimitive?.intOrNull ?: 0) > 0, false),
            aghsShardIcon = getAghsIcon((obj["aghanims_shard"]?.jsonPrimitive?.intOrNull ?: 0) > 0, true),
            rankTier = obj["rank_tier"]?.jsonPrimitive?.intOrNull ?: 0,
            rankName = rankName(obj["rank_tier"]?.jsonPrimitive?.intOrNull ?: 0),
            isMvp = won && nameContains(dsResult?.mvpOrSvp, obj),
            isSvp = !won && nameContains(dsResult?.mvpOrSvp, obj),
            isCriminal = nameContains(dsResult?.criminal, obj)
        )
    }

    private fun findAnalysisCard(cards: List<Dota2PlayerCard>, name: String?): Dota2PlayerCard? {
        if (name.isNullOrBlank()) return null
        return cards.firstOrNull { card ->
            val playerName = card.name.takeIf { it.isNotBlank() && it != "?" }
            val heroName = card.heroName.takeIf { it.isNotBlank() }
            (playerName != null && (playerName in name || name in playerName)) ||
                (heroName != null && (heroName in name || name in heroName))
        }
    }

    private fun analysisDisplayName(card: Dota2PlayerCard?, fallback: String?): String {
        if (card != null) {
            val playerName = card.name.takeIf { it.isNotBlank() && it != "?" }
            return if (playerName != null) "${card.heroName}($playerName)" else card.heroName
        }
        return fallback?.take(30)?.ifBlank { "?" } ?: "?"
    }

    fun rankName(rankTier: Int): String {
        if (rankTier == 0) return ""
        val medal = rankTier / 10
        val star = rankTier % 10
        val names = listOf("","先锋","卫士","十字军","执政官","传奇","万古流芳","超凡入圣","冠绝一世")
        val name = names.getOrElse(medal) { "" }
        if (name.isEmpty()) return ""
        return if (medal == 8) name else "$name [$star]"
    }

    private fun gameModeName(mode: Int): String = when (mode) {
        22 -> "全英雄选择"; 2 -> "队长模式"; 3 -> "随机征召"; 4 -> "个别征召"
        5 -> "全阵营随机"; 16 -> "队长征召"; 1 -> "加速模式"; 23 -> "技能征召"
        else -> "模式$mode"
    }

    private fun nameContains(name: String?, obj: JsonObject): Boolean {
        if (name.isNullOrBlank()) return false
        val personaname = obj["personaname"]?.jsonPrimitive?.content ?: ""
        val hn = heroName(obj["hero_id"]?.jsonPrimitive?.intOrNull ?: 0)
        return personaname.isNotEmpty() && personaname in name
            || hn.isNotEmpty() && hn in name
    }
}
