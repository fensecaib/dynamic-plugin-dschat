package top.colter.dynamic.agent.dota2

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.jetbrains.skia.Image
import top.colter.dynamic.agent.ds.ChatMessage
import top.colter.dynamic.agent.ds.ChatRequest
import top.colter.dynamic.agent.ds.DeepSeekClient
import top.colter.dynamic.agent.ds.ThinkingConfig
import top.colter.dynamic.agent.util.CacheType
import top.colter.dynamic.agent.util.CacheUtils
import top.colter.dynamic.agent.util.HttpUtils
import top.colter.dynamic.agent.util.AgentDiagnostics
import top.colter.dynamic.agent.ds.ApiCallException
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.cancellation.CancellationException

class Dota2Service(
    private val dataDir: File,
    private val cache: CacheUtils,
    internal val apiBase: String = "https://api.opendota.com/api",
) {
    internal val reportTasks = Dota2ReportTaskGate()
    internal val overviewDetails = OverviewDetailCache()

    private fun Exception.rethrowIfCancellation() {
        if (this is CancellationException) throw this
    }

    companion object {
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

$dota2RoastStyle

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
    private val bindingsLock = Any()
    private val bindings = mutableMapOf<String, Long>() // senderId -> accountId
    private var bindingsRequireBackup = false // guarded by bindingsLock

    private val heroNames = mutableMapOf<Int, String>()
    private val heroIconPaths = mutableMapOf<Int, String>()
    private val itemIconPaths = mutableMapOf<Int, String>()
    private val constantsMutex = Mutex()
    private val reportAssetPermits = Semaphore(6)
    @Volatile
    private var heroConstantsLoaded = false
    @Volatile
    private var itemConstantsLoaded = false

    private val aghsIconsMutex = Mutex()
    private var aghsScepterYes: Image? = null
    private var aghsScepterNo: Image? = null
    private var aghsShardYes: Image? = null
    private var aghsShardNo: Image? = null

    fun init() = synchronized(bindingsLock) {
        if (bindFile.exists()) {
            try {
                val raw = HttpUtils.json.decodeFromString(JsonObject.serializer(), bindFile.readText())
                // Parse into a temporary map: a bad entry must not install a partially loaded file.
                val loaded = raw.mapValues { (_, v) -> v.jsonPrimitive.content.toLong() }
                bindings.clear()
                bindings.putAll(loaded)
                bindingsRequireBackup = false
            } catch (_: Exception) {
                // Preserve the file for recovery and keep any already loaded bindings.
                bindingsRequireBackup = true
            }
        }
    }

    private fun saveBindings(values: Map<String, Long>) {
        val obj = buildJsonObject { values.forEach { (k, v) -> put(k, v) } }
        val destination = bindFile.toPath().toAbsolutePath()
        Files.createDirectories(destination.parent)
        val pending = Files.createTempFile(destination.parent, "dota2-bindings-", ".tmp")
        try {
            Files.writeString(pending, HttpUtils.json.encodeToString(JsonObject.serializer(), obj))
            if (bindingsRequireBackup && Files.exists(destination)) {
                if (!Files.isRegularFile(destination)) throw IOException("绑定数据文件无法备份，请检查宿主数据目录。")
                val backup = Files.createTempFile(destination.parent, "dota2-bindings-corrupt-", ".json")
                try {
                    Files.copy(destination, backup, StandardCopyOption.REPLACE_EXISTING)
                } catch (e: Exception) {
                    runCatching { Files.deleteIfExists(backup) }
                    throw e
                }
            }
            try {
                Files.move(pending, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(pending, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(pending)
        }
    }

    fun getBinding(senderId: String): Long? = synchronized(bindingsLock) { bindings[senderId] }

    fun setBinding(senderId: String, accountId: Long) = synchronized(bindingsLock) {
        val updated = bindings + (senderId to accountId)
        saveBindings(updated)
        bindings[senderId] = accountId
        bindingsRequireBackup = false
    }

    // ── API calls ────────────────────────────────

    private suspend fun requestData(path: String, params: Map<String, String> = emptyMap()): JsonElement? {
        val operation = when {
            path.endsWith("/recentMatches") -> "recent_matches"
            path.startsWith("/players/") -> "player_profile"
            else -> "match_detail"
        }
        return AgentDiagnostics.stage("opendota_$operation") {
            val response = try {
                HttpUtils.httpGetAsync("$apiBase$path", params = params)
            } catch (e: java.io.IOException) {
                AgentDiagnostics.event("agent_data", "failed", mapOf("operation" to operation,
                    "category" to if (e is java.net.http.HttpTimeoutException) "network_timeout" else "network_error"), warning = true)
                throw openDotaNetworkFailure(e)
            }
            AgentDiagnostics.event("agent_data", if (response.statusCode() in 200..299) "received" else "failed",
                mapOf("operation" to operation, "http_status" to response.statusCode()), warning = response.statusCode() !in 200..299)
            if (response.statusCode() == 404) return@stage null
            if (response.statusCode() !in 200..299) throw openDotaHttpFailure(response.statusCode())
            try {
                HttpUtils.json.parseToJsonElement(response.body()).also {
                    if (it is JsonObject && it["error"]?.let { error -> error !is JsonNull } == true) invalidData()
                }
            } catch (e: kotlinx.serialization.SerializationException) {
                AgentDiagnostics.event("agent_data", "failed", mapOf("operation" to operation, "category" to "response_json"), warning = true)
                throw OpenDotaApiException("当前 OpenDota 服务返回的数据格式异常，请稍后重试。", e)
            }
        }
    }

    private fun invalidData(): Nothing = throw OpenDotaApiException("当前 OpenDota 服务返回的数据格式异常，请稍后重试。")

    suspend fun validatePlayer(accountId: Long): String? {
        val data = requestData("/players/$accountId") ?: return null
        val player = data as? JsonObject ?: invalidData()
        val profile = player["profile"]
        if (profile == null || profile is JsonNull) return null
        val name = (profile as? JsonObject ?: invalidData())["personaname"]
        if (name == null || name is JsonNull) return null
        return (name as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalidData()
    }

    suspend fun getRecentMatches(accountId: Long, limit: Int = 1): JsonArray? {
        val data = requestData("/players/$accountId/recentMatches", mapOf("limit" to limit.toString())) ?: return null
        val matches = data as? JsonArray ?: invalidData()
        matches.forEach { row ->
            val match = row as? JsonObject ?: invalidData()
            if ((match["match_id"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } == null) invalidData()
            val start = match["start_time"]
            if (start != null && start !is JsonNull && (start as? JsonPrimitive)?.longOrNull == null) invalidData()
        }
        return orderedRecentMatches(matches, limit)
    }

    suspend fun getMatchDetail(matchId: Long): JsonObject? {
        val data = requestData("/matches/$matchId") ?: return null
        val match = data as? JsonObject ?: invalidData()
        if ((match["match_id"] as? JsonPrimitive)?.longOrNull != matchId) invalidData()
        return match
    }

    /**
     * 初始化 OpenDota 英雄与物品常量。
     *
     * 两类数据分别记录完成状态：并发调用只会执行一次初始化，任一接口暂时失败时，
     * 后续请求只重试失败的部分，不会清空已经可用的名称和图标路径。
     */
    suspend fun ensureConstants() {
        if (heroConstantsLoaded && itemConstantsLoaded) return
        constantsMutex.withLock {
            if (!heroConstantsLoaded) {
                try {
                    val heroResp = HttpUtils.httpGetAsync("$apiBase/constants/heroes")
                    AgentDiagnostics.event("agent_data", if (heroResp.statusCode() in 200..299) "received" else "failed",
                        mapOf("operation" to "hero_constants", "http_status" to heroResp.statusCode()), warning = heroResp.statusCode() !in 200..299)
                    if (heroResp.statusCode() in 200..299) {
                        val heroObj = HttpUtils.json.decodeFromString(JsonObject.serializer(), heroResp.body())
                        if (heroObj.isNotEmpty()) {
                            heroObj.forEach { (_, v) ->
                                val hero = v.jsonObject
                                val id = hero["id"]?.jsonPrimitive?.intOrNull ?: return@forEach
                                heroNames[id] = hero["localized_name"]?.jsonPrimitive?.content ?: "Hero_$id"
                                val img = hero["img"]?.jsonPrimitive?.content?.substringBefore("?") ?: ""
                                if (img.isNotEmpty()) heroIconPaths[id] = img
                            }
                            heroConstantsLoaded = true
                        }
                    }
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    AgentDiagnostics.event("agent_data", "fallback", mapOf("operation" to "hero_constants", "error_type" to e.javaClass.simpleName), warning = true)
                    // 保留已加载的数据，下次请求时仅重试尚未成功的常量类型。
                }
            }

            if (!itemConstantsLoaded) {
                try {
                    val itemResp = HttpUtils.httpGetAsync("$apiBase/constants/items")
                    AgentDiagnostics.event("agent_data", if (itemResp.statusCode() in 200..299) "received" else "failed",
                        mapOf("operation" to "item_constants", "http_status" to itemResp.statusCode()), warning = itemResp.statusCode() !in 200..299)
                    if (itemResp.statusCode() in 200..299) {
                        val itemObj = HttpUtils.json.decodeFromString(JsonObject.serializer(), itemResp.body())
                        if (itemObj.isNotEmpty()) {
                            itemObj.forEach { (_, v) ->
                                val item = v.jsonObject
                                val id = item["id"]?.jsonPrimitive?.intOrNull ?: return@forEach
                                val img = item["img"]?.jsonPrimitive?.content?.substringBefore("?") ?: ""
                                if (img.isNotEmpty()) itemIconPaths[id] = img
                            }
                            itemConstantsLoaded = true
                        }
                    }
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    AgentDiagnostics.event("agent_data", "fallback", mapOf("operation" to "item_constants", "error_type" to e.javaClass.simpleName), warning = true)
                    // 英雄和物品常量独立重试，避免一个接口失败阻断另一个接口。
                }
            }
        }
    }

    fun heroName(id: Int) = localizedDota2HeroName(id, heroNames[id])

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
            val resp = HttpUtils.httpGetBytesAsync(url)
            if (resp.statusCode() in 200..299 && resp.body().size > 512) resp.body() else null
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            null
        }
    }

    // ── Aghanim icons ────────────────────────────

    /**
     * 并行加载神杖、魔晶的已购买与未购买图标。
     *
     * 四项全部成功后直接复用；若只有部分资源失败，后续调用仅补齐缺失项。
     */
    suspend fun ensureAghsIcons() = ensureAghsIcons(::downloadDirect)

    internal suspend fun ensureAghsIcons(download: suspend (String) -> Image?) {
        aghsIconsMutex.withLock {
            if (aghsScepterYes != null && aghsScepterNo != null && aghsShardYes != null && aghsShardNo != null) {
                return@withLock
            }
            val base = "https://www.opendota.com/assets/images/dota2"
            coroutineScope {
                // Transfer ownership inside each child. A later child may finish before an
                // earlier download, so deferring storage until ordered awaits loses its image on cancellation.
                listOf(
                    async { aghsScepterYes = aghsScepterYes ?: download("$base/scepter_1.png") },
                    async { aghsScepterNo = aghsScepterNo ?: download("$base/scepter_0.png") },
                    async { aghsShardYes = aghsShardYes ?: download("$base/shard_1.png") },
                    async { aghsShardNo = aghsShardNo ?: download("$base/shard_0.png") },
                ).awaitAll()
            }
        }
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
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            return null
        }
    }

    // ── Match Analysis ───────────────────────────

    suspend fun analyzeMatch(
        myAccountId: Long, matchJson: JsonObject,
        dsClient: DeepSeekClient, model: String, mode: Dota2ReportMode = Dota2ReportMode.NORMAL
    ): DsAnalysisResult? {
        val players = analysisPlayers(matchJson)
        val radiantWin = (matchJson["radiant_win"] as? JsonPrimitive)?.booleanOrNull
            ?: throw analysisUnavailable(category = "analysis_data")

        val me = players.find {
            (it["account_id"] as? JsonPrimitive)?.longOrNull == myAccountId
        } ?: throw analysisUnavailable(category = "analysis_data")
        val myTeam = analysisTeamSide(me)
        val weWon = (myTeam == 0) == radiantWin

        val myTeamPlayers = players.filter { analysisTeamSide(it) == myTeam }
        ensureConstants()

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
            sb.appendLine("${playerDisplayName(obj)} | " +
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
            thinking = ThinkingConfig(type = mode.thinkingType), maxTokens = 16384)

        return requestAnalysis(dsClient, request, matchJson)
    }

    /** 两个阵营并行分析；任一方失败时及时取消另一方，避免发送不完整诊断。 */
    suspend fun analyzeFullMatch(
        matchJson: JsonObject, dsClient: DeepSeekClient, model: String
    ): DualAnalyzeResult = withDotaAnalysisBudget {
        coroutineScope {
            val radiantResult = async { analyzeTeamSide(0, matchJson, dsClient, model) }
            val direResult = async { analyzeTeamSide(1, matchJson, dsClient, model) }
            DualAnalyzeResult(radiantResult.await(), direResult.await())
        }
    }

    private suspend fun analyzeTeamSide(
        side: Int, matchJson: JsonObject,
        dsClient: DeepSeekClient, model: String
    ): DsAnalysisResult? {
        val players = analysisPlayers(matchJson)
        val radiantWin = (matchJson["radiant_win"] as? JsonPrimitive)?.booleanOrNull
            ?: throw analysisUnavailable(category = "analysis_data")
        val weWon = (side == 0) == radiantWin

        val sidePlayers = players.filter { analysisTeamSide(it) == side }
        if (sidePlayers.isEmpty()) throw analysisUnavailable(category = "analysis_data")
        ensureConstants()

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
            sb.appendLine("${playerDisplayName(obj)} | " +
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

        return requestAnalysis(dsClient, request, matchJson, side)
    }

    private fun analysisPlayers(matchJson: JsonObject): List<JsonObject> =
        (matchJson["players"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.map {
            it as? JsonObject ?: throw analysisUnavailable(category = "analysis_data")
        } ?: throw analysisUnavailable(category = "analysis_data")

    private fun analysisTeamSide(player: JsonObject): Int {
        val explicit = (player["isRadiant"] as? JsonPrimitive)?.booleanOrNull
        val slot = (player["player_slot"] as? JsonPrimitive)?.intOrNull
        val fromSlot = when (slot) {
            in 0..4 -> true
            in 128..132 -> false
            else -> null
        }
        if (explicit != null && fromSlot != null && explicit != fromSlot) {
            throw analysisUnavailable(category = "analysis_data")
        }
        val radiant = explicit ?: fromSlot ?: throw analysisUnavailable(category = "analysis_data")
        return if (radiant) 0 else 1
    }

    private suspend fun requestAnalysis(
        client: DeepSeekClient, request: ChatRequest, matchJson: JsonObject, side: Int? = null,
    ): DsAnalysisResult {
        val result = client.chat(request)
        val failure = result.exceptionOrNull()
        val analysis = result.getOrNull()?.choices?.firstOrNull()?.message?.content?.let(::parseAnalysisResult)
        if (analysis != null) return analysis
        val unavailable = analysisUnavailable(failure)
        AgentDiagnostics.event("agent_analysis", "unavailable", mapOf(
            "match_id" to (matchJson["match_id"] as? JsonPrimitive)?.longOrNull,
            "side" to side, "category" to unavailable.category,
            "http_status" to (failure as? ApiCallException)?.statusCode,
        ), warning = true)
        throw unavailable
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

    /** 两种模式共用提示词和绘图；资源与分析并行准备，同一资源每份报告只下载/解码一次。 */
    internal suspend fun generateMatchReport(
        matchJson: JsonObject, myAccountId: Long, dsClient: DeepSeekClient, model: String, won: Boolean,
        mode: Dota2ReportMode = Dota2ReportMode.NORMAL,
    ): GeneratedDota2Report {
        val matchId = matchJson["match_id"]?.jsonPrimitive?.longOrNull ?: 0
        var resources: Dota2ReportAssets? = null
        var transferred = false
        try {
            return withDotaAnalysisBudget {
                measureDotaStage(matchId, "analysis_and_resources", mode) {
                    // 常量先准备好，保证原来的英雄名称及模型输入不因资源并发而发生变化。
                    measureDotaStage(matchId, "constants", mode) { ensureConstants() }
                    coroutineScope {
                        val pendingAssets = async {
                            measureDotaStage(matchId, "assets", mode) {
                                prepareReportAssets(matchJson).also { resources = it }
                            }
                        }
                        val analysis = measureDotaStage(matchId, "ai", mode) { analyzeMatch(myAccountId, matchJson, dsClient, model, mode) }
                        val assets = pendingAssets.await()
                        val report = measureDotaStage(matchId, "assemble", mode) { buildReport(matchJson, myAccountId, analysis, won, assets) }
                        GeneratedDota2Report(analysis, report, assets)
                    }
                }
            }.also { transferred = true }
        } finally {
            if (!transferred) resources?.close()
        }
    }

    /** 全场分析与普通战报共用资源所有权，取消或绘图完成时均释放每份报告的图片。 */
    internal suspend fun generateFullReport(
        matchJson: JsonObject, dsClient: DeepSeekClient, model: String,
    ): GeneratedDota2Report {
        val matchId = (matchJson["match_id"] as? JsonPrimitive)?.longOrNull ?: 0
        var resources: Dota2ReportAssets? = null
        var transferred = false
        try {
            return withDotaAnalysisBudget {
                measureDotaStage(matchId, "full_analysis_and_resources") {
                    measureDotaStage(matchId, "constants") { ensureConstants() }
                    coroutineScope {
                        val pendingAssets = async {
                            measureDotaStage(matchId, "assets") {
                                prepareReportAssets(matchJson).also { resources = it }
                            }
                        }
                        val analysis = measureDotaStage(matchId, "full_ai") { analyzeFullMatch(matchJson, dsClient, model) }
                        val assets = pendingAssets.await()
                        val report = measureDotaStage(matchId, "assemble") { buildFullReport(matchJson, analysis, assets) }
                        GeneratedDota2Report(null, report, assets)
                    }
                }
            }.also { transferred = true }
        } finally {
            if (!transferred) resources?.close()
        }
    }

    private suspend fun prepareReportAssets(matchJson: JsonObject): Dota2ReportAssets {
        val players = matchJson["players"]?.jsonArray.orEmpty().map { it.jsonObject }
        val keys = buildList {
            players.forEach { p ->
                add(true to (p["hero_id"]?.jsonPrimitive?.intOrNull ?: 0))
                ((0..5).map { "item_$it" } + (0..2).map { "backpack_$it" } + "item_neutral").forEach { field ->
                    add(false to (p[field]?.jsonPrimitive?.intOrNull ?: 0))
                }
            }
        }.filter { it.second > 0 }.distinct()
        val allocated = java.util.concurrent.ConcurrentLinkedQueue<Image>()
        var completed = false
        try {
            return coroutineScope {
                val aghs = async { ensureAghsIcons() }
                val loaded = keys.map { key -> async {
                    reportAssetPermits.withPermit {
                        val image = try {
                            if (key.first) loadHeroIcon(key.second) else loadItemIcon(key.second)
                        } catch (e: Exception) { e.rethrowIfCancellation(); null }
                        if (image != null) allocated.add(image)
                        key to image
                    }
                } }.awaitAll()
                aghs.await()
                Dota2ReportAssets(
                    loaded.filter { it.first.first }.associate { it.first.second to it.second },
                    loaded.filter { !it.first.first }.associate { it.first.second to it.second },
                )
            }.also { completed = true }
        } finally { if (!completed) allocated.forEach { it.close() } }
    }

    internal suspend fun buildReport(
        matchJson: JsonObject, myAccountId: Long,
        dsResult: DsAnalysisResult?, won: Boolean, assets: Dota2ReportAssets? = null,
    ): Dota2MatchReport {
        if (assets == null) { ensureConstants(); ensureAghsIcons() }
        val players = matchJson["players"]?.jsonArray ?: JsonArray(emptyList())
        val radiantWin = matchJson["radiant_win"]?.jsonPrimitive?.boolean ?: false

        val cards = players.map { p -> buildPlayerCard(p.jsonObject, won, dsResult, assets) }
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

    suspend fun buildFullReport(matchJson: JsonObject, dual: DualAnalyzeResult?): Dota2MatchReport =
        buildFullReport(matchJson, dual, null)

    private suspend fun buildFullReport(
        matchJson: JsonObject, dual: DualAnalyzeResult?, assets: Dota2ReportAssets?,
    ): Dota2MatchReport {
        if (assets == null) { ensureConstants(); ensureAghsIcons() }
        val players = matchJson["players"]?.jsonArray ?: JsonArray(emptyList())
        val radiantWin = matchJson["radiant_win"]?.jsonPrimitive?.boolean ?: false
        val r = dual?.radiant; val d = dual?.dire

        val cards = players.map { buildPlayerCard(it.jsonObject, false, null, assets) }
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
        obj: JsonObject, won: Boolean, dsResult: DsAnalysisResult?, assets: Dota2ReportAssets? = null,
    ): Dota2PlayerCard {
        val heroId = obj["hero_id"]?.jsonPrimitive?.intOrNull ?: 0
        val itemIds = (0..5).map { obj["item_$it"]?.jsonPrimitive?.intOrNull ?: 0 }
        val bpIds = (0..2).map { obj["backpack_$it"]?.jsonPrimitive?.intOrNull ?: 0 }
        val neutralIds = listOf(obj["item_neutral"]?.jsonPrimitive?.intOrNull ?: 0)
        val isRadiant = analysisTeamSide(obj) == 0
        suspend fun item(id: Int): Image? = if (assets == null) loadItemIcon(id) else assets.items[id]

        return Dota2PlayerCard(
            name = playerDisplayName(obj),
            heroName = heroName(heroId), heroId = heroId, heroIcon = if (assets == null) loadHeroIcon(heroId) else assets.heroes[heroId],
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
            items = itemIds.map { item(it) } + neutralIds.mapNotNull { item(it) },
            denies = obj["denies"]?.jsonPrimitive?.intOrNull ?: 0,
            netWorth = obj["net_worth"]?.jsonPrimitive?.intOrNull ?: 0,
            heroHealing = obj["hero_healing"]?.jsonPrimitive?.intOrNull ?: 0,
            // 背包必须保留三个固定槽位，避免中间空槽导致后续物品向前错位。
            backpackItems = bpIds.map { item(it) },
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
            val playerName = card.name.takeUnless(::isAnonymousDota2PlayerName)
            val heroName = card.heroName.takeIf { it.isNotBlank() }
            val englishHeroName = heroNames[card.heroId]?.takeIf { it.isNotBlank() }
            matchesDota2AnalysisIdentity(name, playerName) ||
                matchesDota2AnalysisIdentity(name, heroName) ||
                matchesDota2AnalysisIdentity(name, englishHeroName)
        }
    }

    private fun analysisDisplayName(card: Dota2PlayerCard?, fallback: String?): String {
        if (card != null) {
            return "${card.heroName}(${normalizeDota2PlayerName(card.name)})"
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
        1, 22 -> "全英雄选择"; 2 -> "队长模式"; 3 -> "随机征召"; 4 -> "个别征召"
        5 -> "全阵营随机"; 16 -> "队长征召"; 18 -> "技能征召"; 23 -> "加速模式"
        else -> "模式$mode"
    }

    private fun nameContains(name: String?, obj: JsonObject): Boolean {
        if (name.isNullOrBlank()) return false
        val heroId = obj["hero_id"]?.jsonPrimitive?.intOrNull ?: 0
        val personaname = playerDisplayName(obj).takeUnless(::isAnonymousDota2PlayerName)
        val localizedHeroName = heroName(heroId)
        val englishHeroName = heroNames[heroId]
        return matchesDota2AnalysisIdentity(name, personaname) ||
            matchesDota2AnalysisIdentity(name, localizedHeroName) ||
            matchesDota2AnalysisIdentity(name, englishHeroName)
    }

    private fun playerDisplayName(obj: JsonObject): String = normalizeDota2PlayerName(
        obj["personaname"]?.jsonPrimitive?.contentOrNull
    )
}
