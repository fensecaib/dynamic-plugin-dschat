package top.colter.dynamic.agent.dota2

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.Image
import top.colter.dynamic.agent.config.ImageConfig
import top.colter.dynamic.agent.draw.dota2OverviewDraw
import top.colter.dynamic.agent.ds.DeepSeekClient
import top.colter.dynamic.agent.util.CacheUtils
import top.colter.dynamic.agent.util.HttpUtils
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class Dota2OverviewTest {
    private fun row(id: Long, death: Int? = 0) = buildJsonObject {
        put("match_id",id);put("hero_id",15);put("player_slot",0);put("radiant_win",true);put("start_time",1_780_000_000L+id)
        put("kills",4);death?.let { put("deaths",it) };put("assists",6);put("duration",2400)
    }
    private fun detail(id: Long, pct: Double = .5, account: Long = 1, teamKills: Int = 20) = buildJsonObject {
        put("match_id",id);put("radiant_win",true);put("radiant_score",teamKills)
        putJsonArray("players") { add(buildJsonObject {
            put("account_id",account);put("hero_id",15);put("player_slot",0);put("kills",4);put("deaths",0);put("assists",6)
            put("gold_per_min",400);put("xp_per_min",600);put("hero_damage",18000);put("tower_damage",0)
            for (i in 0..5) put("item_$i",0)
            putJsonObject("benchmarks") { overviewMetrics.keys.forEach { key -> putJsonObject(key) { put("pct",pct) } } }
        }) }
    }
    private fun fixture(count: Int = 10): OverviewSnapshot {
        val ids=(101L until 101+count).toList()
        return buildOverviewSnapshot(1,null,JsonArray(ids.reversed().map { row(it) }), ids.associateWith { detail(it,(it-100)/12.0) }, ::localizedDota2HeroName)
    }
    private val prose = "这批数据呈现了不同比赛之间的表现差异，需要先把英雄和实际职责对应起来，再考虑哪些记录适合放在同一个尺度下对照。赛后统计能够指出值得关注的方向，但不能代替具体过程的复盘，也不能由单个指标断定整体贡献。建议先挑选同英雄的可比样本，分别记录资源获取、阵亡前后的收益与目标选择，再检查这些差异是否在更多场次中反复出现。判断应该跟着证据走，既不要把一次优势表现当成稳定能力，也不要用一场受挫记录否定全部发挥。"
    private fun validRaw(s: OverviewSnapshot): String = buildJsonObject { putJsonArray("sections") {
        s.sectionTitles().keys.forEach { id -> add(buildJsonObject { put("id",id);put("body",if (id=="overview") prose.take(180)+"。" else prose+prose) }) }
    } }.toString()

    @Test fun `recent snapshot preserves zeros partial coverage order and stable representative identities`() {
        val recent=JsonArray(listOf(row(3),row(1),row(2),row(2)))
        val s=buildOverviewSnapshot(1,null,recent,mapOf(1L to detail(1,.9),2L to detail(2,.1),3L to detail(999)),::localizedDota2HeroName)
        assertEquals(listOf(1L,2L,3L),s.matches.map { it.id })
        assertEquals(2,s.details);assertEquals(0.0,s.average("deaths").value)
        assertEquals(2,s.average("gold_per_min").count);assertEquals(400.0,s.average("gold_per_min").value)
        assertEquals(0.0,s.average("tower_damage").value)
        assertEquals(50.0,s.average("kill_participation").value)
        assertEquals(listOf(1L,2L),s.representatives.map { it.match.id })
        assertNull(s.matches.last().selectionScore)
    }

    @Test fun `missing target zero team kills unknown outcome and missing benchmarks never become zeros`() {
        val recent=JsonArray(listOf(buildJsonObject { put("match_id",1);put("hero_id",15) },row(2,null)))
        val s=buildOverviewSnapshot(1,null,recent,mapOf(1L to detail(1,account=8),2L to detail(2,teamKills=0)),::localizedDota2HeroName)
        assertNull(s.matches.first().won);assertNull(s.matches.first().values["deaths"])
        assertNull(s.average("kill_participation").value)
        assertEquals(1,s.average("deaths").count)
        assertEquals("参考样本",s.representatives.single().label)
        val empty=buildOverviewSnapshot(1,null,JsonArray(emptyList()),emptyMap(),::localizedDota2HeroName)
        assertTrue(empty.representatives.isEmpty());assertNull(empty.average("deaths").value)
    }

    @Test fun `overview modes change only thinking and arguments cannot crash on invalid ids`() {
        val s=fixture()
        val normal=overviewRequest(s,OverviewMode.NORMAL,"model");val deep=overviewRequest(s,OverviewMode.DEEP,"model")
        assertEquals(normal.copy(thinking=deep.thinking),deep)
        assertTrue(Dota2Service.dota2SystemPrompt.contains(dota2RoastStyle))
        assertTrue(normal.messages.first().content!!.contains(dota2RoastStyle))
        assertEquals("disabled",normal.thinking?.type);assertEquals("enabled",deep.thinking?.type)
        assertEquals(123L,overviewAccount(listOf("个人详情"),123))
        assertEquals(456L,overviewAccount(listOf("深度个人详情","456"),null))
        listOf(listOf("个人详情","abc"),listOf("个人详情","-1"),listOf("个人详情","9999999999999"),listOf("个人详情","123","10"),listOf("个人详情")).forEach {
            assertFailsWith<IllegalArgumentException> { overviewAccount(it,null) }
        }
    }

    @Test fun `analysis validates pairing rejects numbers and compacts at complete sentences`() {
        val s=fixture();val raw=validRaw(s);val a=parseOverviewAnalysis(raw,s)
        assertEquals(s.sectionTitles().keys.toList(),a.sections.map { it.id })
        assertTrue(a.sections.all { it.body.endsWith("。") && it.body.length<=420 })
        assertFails { parseOverviewAnalysis(raw.replace("match_110","match_999"),s) }
        assertFails { parseOverviewAnalysis(raw.replace("这批数据","GPM 7816"),s) }
        assertFails { parseOverviewAnalysis("{\"sections\":[]}",s) }
        assertFails { parseOverviewAnalysis("broken JSON",s) }
        assertFails { parseOverviewAnalysis(raw.replace(prose,"短文"),s) }
        assertEquals(raw,validRaw(s)) // Parsing/compaction never mutates the source report.
        val ending="\n— 输出部门忙着开会，拆迁进度还得查账。"
        val compact=compactOverviewBody("这段点评保留数据与具体复盘问题。".repeat(40)+ending,420)
        assertTrue(compact.length<=420)
        assertTrue(compact.endsWith(ending))
        assertTrue(compact.removeSuffix(ending).endsWith("。"))
    }

    @Test fun `decimal facts and names survive normalization without weakening number validation`() {
        val base=fixture(1)
        val s=base.copy(matches=base.matches.map { it.copy(values=it.values + ("gold_per_min" to 430.7)) })
        assertNull(parseOverviewAnalysis(validRaw(s).replace("这批数据", "GPM 430.7，这批数据"),s).notice)
        assertFails { parseOverviewAnalysis(validRaw(s).replace("这批数据", "GPM 430.8，这批数据"),s) }
        assertNull(parseOverviewAnalysis(validRaw(s).replace("这批数据", "英雄伤害18,000，这批数据"),s).notice)
        val profile=buildJsonObject { putJsonObject("profile") { put("personaname","Player Ctrl\n\t高松灯") } }
        val snapshot=buildOverviewSnapshot(1,profile,JsonArray(listOf(row(1))),emptyMap(),::localizedDota2HeroName)
        assertEquals("Player Ctrl  高松灯",snapshot.player)
        val req=overviewRequest(s,OverviewMode.NORMAL,"model")
        fun encoded(r:top.colter.dynamic.agent.ds.ChatRequest)=Json.parseToJsonElement(HttpUtils.json.encodeToString(top.colter.dynamic.agent.ds.ChatRequest.serializer(),r)).jsonObject
        assertFalse("response_format" in encoded(req.copy(responseFormat=null)))
        assertEquals("json_object",encoded(req)["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun `repair failures preserve prior valid sections and never accept truncated responses`() = runBlocking<Unit> {
        val s=fixture(1)
        fun rawWithBad(vararg bad:String):String {
            val root=Json.parseToJsonElement(validRaw(s)).jsonObject
            return buildJsonObject { putJsonArray("sections") { root["sections"]!!.jsonArray.forEach { section ->
                val obj=section.jsonObject
                add(if(obj["id"]!!.jsonPrimitive.content in bad) JsonObject(obj+("body" to JsonPrimitive("短文"))) else obj)
            } } }.toString()
        }
        suspend fun run(first:String, second:String, secondStatus:Int=200, reason:String="stop"):OverviewAnalysis {
            val calls=AtomicInteger();val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
            server.createContext("/chat") { ex ->
                ex.requestBody.readBytes();val n=calls.getAndIncrement();val status=if(n==0)200 else secondStatus
                val payload=buildJsonObject { putJsonArray("choices") { add(buildJsonObject {
                    put("finish_reason",reason);putJsonObject("message") { put("role","assistant");put("content",if(n==0)first else second) }
                }) } }.toString().toByteArray()
                ex.sendResponseHeaders(status,payload.size.toLong());ex.responseBody.use { it.write(payload) }
            }
            server.start()
            try {
                val result=analyzeOverview(s,OverviewMode.NORMAL,DeepSeekClient("test","http://127.0.0.1:${server.address.port}/chat"),"model")
                assertEquals(2,calls.get());return result
            } finally { server.stop(0) }
        }
        val failed=run(rawWithBad("economy"),"{}",500)
        assertEquals(listOf("economy"),failed.sections.filter { it.isFallback }.map { it.id })
        assertNotNull(failed.notice)
        val merged=run(rawWithBad("economy"),rawWithBad("output"))
        assertNull(merged.notice);assertTrue(merged.sections.none { it.isFallback })
        val truncated=run(validRaw(s),validRaw(s),reason="length")
        assertTrue(truncated.sections.all { it.isFallback })
        assertNotNull(truncated.notice)
    }

    @Test fun `pipeline bounds detail requests caches success repairs once and renders both modes`() = runBlocking<Unit> {
        val dir=Files.createTempDirectory("overview-test-");val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val pool=Executors.newCachedThreadPool();server.executor=pool
        val active=AtomicInteger();val peak=AtomicInteger();val detailCalls=AtomicInteger();val calls=AtomicInteger()
        val requested=java.util.concurrent.CopyOnWriteArrayList<JsonObject>();val s=fixture()
        server.createContext("/") { exchange ->
            val path=exchange.requestURI.path
            val body=when {
                path=="/chat" -> {
                    val req=Json.parseToJsonElement(exchange.requestBody.readBytes().toString(Charsets.UTF_8)).jsonObject;requested+=req
                    val text=if(calls.getAndIncrement()==0) "{\"sections\":[]}" else validRaw(s)
                    buildJsonObject { putJsonArray("choices") { add(buildJsonObject { put("finish_reason","stop");putJsonObject("message") { put("role","assistant");put("content",text) } }) } }.toString()
                }
                path=="/players/1/recentMatches" -> JsonArray((110L downTo 101).map { row(it) }).toString()
                path=="/players/1" -> "{\"profile\":{\"personaname\":\"测试玩家\"}}"
                path.startsWith("/matches/") -> {
                    detailCalls.incrementAndGet();val n=active.incrementAndGet();peak.accumulateAndGet(n,::maxOf)
                    try { Thread.sleep(30);val id=path.substringAfterLast('/').toLong();detail(id,(id-100)/12.0).toString() } finally { active.decrementAndGet() }
                }
                path.startsWith("/constants/") -> "{}"
                else -> error("Unexpected endpoint $path")
            }.toByteArray()
            exchange.sendResponseHeaders(200,body.size.toLong());exchange.responseBody.use { it.write(body) };exchange.close()
        }
        server.start()
        try {
            val base="http://127.0.0.1:${server.address.port}";val service=Dota2Service(dir.toFile(),CacheUtils(dir),base);val client=DeepSeekClient("test", "$base/chat")
            for (mode in OverviewMode.entries) service.generateOverview(1,mode,client,"fixture-model").use { generated ->
                assertEquals(10,generated.snapshot.details);assertNull(generated.analysis.notice)
                dota2OverviewDraw(generated,ImageConfig()).use { image ->assertEquals(1600,image.width);assertTrue(image.height in 1800..8000);assertNotNull(image.encodeToData()?.use { it.bytes }) }
            }
            assertEquals(2,peak.get());assertEquals(10,detailCalls.get());assertEquals(3,calls.get())
            assertEquals(listOf("disabled","disabled","enabled"),requested.map { it["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content })
            assertEquals(2,requested[0]["messages"]!!.jsonArray.size);assertEquals(4,requested[1]["messages"]!!.jsonArray.size)
        } finally { server.stop(0);pool.shutdownNow();assertTrue(dir.fileName.toString().startsWith("overview-test-"));dir.toFile().deleteRecursively() }
    }

    @Test fun `invalid AI responses produce a visibly labelled statistics fallback`() = runBlocking<Unit> {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0);val calls=AtomicInteger()
        server.createContext("/chat") { ex -> calls.incrementAndGet();ex.requestBody.readBytes();val data="{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"role\":\"assistant\",\"content\":\"{}\"}}]}".toByteArray();ex.sendResponseHeaders(200,data.size.toLong());ex.responseBody.use { it.write(data) } }
        server.start()
        try {
            val s=fixture(1);val a=analyzeOverview(s,OverviewMode.NORMAL,DeepSeekClient("test","http://127.0.0.1:${server.address.port}/chat"),"model")
            assertNotNull(a.notice);assertEquals(2,calls.get());assertTrue(overviewText(s,a).contains("数据摘要"))
            GeneratedOverview(s,a,OverviewMode.NORMAL,Dota2ReportAssets(emptyMap(),emptyMap())).use { g ->
                for(factor in listOf(1f,2f,Float.NaN)) dota2OverviewDraw(g,ImageConfig(factor=factor)).use { image ->assertTrue(image.width.toLong()*image.height<=16_020_000) }
            }
        } finally { server.stop(0) }
    }

    @Test fun `optional real-data production renderer preview`() = runBlocking<Unit> {
        if(System.getenv("DOTA_OVERVIEW_PREVIEW")!="true")return@runBlocking
        val p=Path.of(".codex/tmp/overview-techies");val out=p.resolve("production");Files.createDirectories(out)
        fun obj(path:Path)=Json.parseToJsonElement(Files.readString(path)).jsonObject
        val recent=Json.parseToJsonElement(Files.readString(p.resolve("selected-recent.json"))).jsonArray
        val details=recent.associate { val id=it.jsonObject["match_id"]!!.jsonPrimitive.long;id to obj(p.resolve("$id.json")) }
        val s=buildOverviewSnapshot(176496411,obj(p.resolve("profile.json")),recent,details,::localizedDota2HeroName)
        val request=overviewRequest(s,OverviewMode.NORMAL,"deepseek-v4-flash")
        Files.writeString(out.resolve("request.json"),HttpUtils.json.encodeToString(top.colter.dynamic.agent.ds.ChatRequest.serializer(),request))
        val rawPath=System.getenv("DOTA_OVERVIEW_RAW")?.let { Path.of(it) }
        val start=System.nanoTime()
        val a=if(System.getenv("DOTA_OVERVIEW_LIVE")=="true") {
            analyzeOverview(s,OverviewMode.NORMAL,DeepSeekClient(requireNotNull(System.getenv("DOTA_OVERVIEW_TEST_KEY")),"https://api.deepseek.com/chat/completions"),"deepseek-v4-flash")
        } else if(rawPath!=null) try { parseOverviewAnalysis(Files.readString(rawPath),s,allowPartial=true) } catch(e:Exception) { overviewFallback(s,"实测输出未通过校验：${e.message}") }
            else parseOverviewAnalysis(validRaw(s),s).copy(notice="排版测试夹具：真实十场数据，正文为离线测试文案")
        Files.writeString(out.resolve("render-notice.txt"),a.notice ?: "API 正文通过当前结构和硬规则检查；不是完整语义校验。")
        a.rawResponse?.let { Files.writeString(out.resolve("api-raw.txt"),it) }
        Files.writeString(out.resolve("validation-error.txt"),a.validationError.orEmpty())
        Files.writeString(out.resolve("timing.txt"),"analysis_seconds="+(System.nanoTime()-start)/1_000_000_000.0)
        Files.writeString(out.resolve("analysis.json"),buildJsonObject { putJsonArray("sections") { a.sections.forEach { add(buildJsonObject { put("id",it.id);put("body",it.body) }) } } }.toString())
        Files.writeString(out.resolve("report.txt"),overviewText(s,a))
        fun image(kind:String,id:Int)=p.resolve("full-assets/$kind-$id.png").takeIf { Files.exists(it) }?.let { Image.makeFromEncoded(Files.readAllBytes(it)) }
        val assets=Dota2ReportAssets(s.matches.map { it.heroId }.distinct().associateWith { image("hero",it) },s.representatives.flatMap { it.match.items }.distinct().associateWith { image("item",it) },p.resolve("full-assets/avatar.png").takeIf { Files.exists(it) }?.let { Image.makeFromEncoded(Files.readAllBytes(it)) })
        GeneratedOverview(s,a,OverviewMode.NORMAL,assets).use { g ->dota2OverviewDraw(g,ImageConfig()).use { img -> img.encodeToData()!!.use { Files.write(out.resolve("overview.png"),it.bytes) } } }
    }
}
