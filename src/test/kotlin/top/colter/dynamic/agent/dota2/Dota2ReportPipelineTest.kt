package top.colter.dynamic.agent.dota2

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.json.*
import org.jetbrains.skia.*
import top.colter.dynamic.agent.config.ImageConfig
import top.colter.dynamic.agent.draw.dota2MatchDraw
import top.colter.dynamic.agent.ds.DeepSeekClient
import top.colter.dynamic.agent.util.CacheType
import top.colter.dynamic.agent.util.CacheUtils
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.*

class Dota2ReportPipelineTest {
    @Test fun `review cards resolve within the reviewed team and leave ambiguous names unmatched`(
        @org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path,
    ) = runBlocking<Unit> {
        val service = Dota2Service(dir.toFile(), CacheUtils(dir))
        val match = buildJsonObject {
            put("radiant_win", false)
            putJsonArray("players") {
                for ((i, hero) in listOf(11, 8, 21, 26).withIndex()) add(buildJsonObject {
                    put("account_id", i + 1)
                    put("hero_id", hero)
                    put("isRadiant", i < 2)
                    put("personaname", if (i == 3) ANONYMOUS_PLAYER_NAME else "同名")
                })
            }
        }
        val radiant = DsAnalysisResult("主宰(同名)", "输出", "同名", "承伤", "")
        val dire = DsAnalysisResult("风行者(同名)", "推进", "莱恩", "控制", "")
        Dota2ReportAssets(emptyMap(), emptyMap()).use { assets ->
            val normal = service.buildReport(match, radiant, false, assets)
            assertEquals(8, normal.players.single { it.isSvp }.heroId)
            assertTrue(normal.players.none { it.isCriminal })
            assertEquals("同名", normal.criminalName)
            val full = service.buildFullReport(match, DualAnalyzeResult(radiant, dire), assets)
            assertEquals(21, full.players.single { it.isMvp }.heroId)
            assertEquals(8, full.players.single { it.isSvp }.heroId)
            assertEquals(26, full.players.single { it.isCriminal }.heroId)

            // 两位队友互用对方的英雄名作昵称；模型仍按「英雄(玩家)」明确标出了人选。
            val aliases = listOf("主宰", "影魔", "同名", ANONYMOUS_PLAYER_NAME)
            val swapped = JsonObject(match + ("players" to JsonArray(match.getValue("players").jsonArray.mapIndexed { i, row ->
                JsonObject(row.jsonObject + ("personaname" to JsonPrimitive(aliases[i])))
            })))
            val explicit = radiant.copy(mvpOrSvp = "主宰（影魔）", criminal = "影魔(主宰)")
            val resolved = service.buildReport(swapped, explicit, false, assets)
            assertEquals(8, resolved.players.singleOrNull { it.isSvp }?.heroId)
            assertEquals(11, resolved.players.singleOrNull { it.isCriminal }?.heroId)
        }
    }

    @Test fun `parallel pipeline preserves model request text and rendered report`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("dota-pipeline-test")
        val service = Dota2Service(dir.toFile(), CacheUtils(dir))
        // 仅测试夹具预置常量和资源，所有外部请求都由本地模拟模型接口替代。
        fun setField(name: String, value: Any) { Dota2Service::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value) }
        val icon = Surface.makeRasterN32Premul(64, 40).use { s -> s.canvas.clear(Color.BLUE); s.makeImageSnapshot() }
        val bytes = icon.encodeToData()!!.use { it.bytes }
        val cache = CacheUtils(dir)
        cache.cacheFile(CacheType.ICON_HERO, "hero.png").writeBytes(bytes)
        cache.cacheFile(CacheType.ICON_ITEM, "item.png").writeBytes(bytes)
        setField("heroConstantsLoaded", true); setField("itemConstantsLoaded", true)
        setField("heroIconPaths", mutableMapOf(11 to "/hero.png", 8 to "/hero.png"))
        setField("itemIconPaths", mutableMapOf(2 to "/item.png"))
        listOf("aghsScepterYes", "aghsScepterNo", "aghsShardYes", "aghsShardNo").forEach { setField(it, icon) }
        val match = buildJsonObject {
            put("match_id", 2001L); put("radiant_win", true); put("duration", 2400)
            putJsonArray("players") {
                for (i in 1..2) add(buildJsonObject {
                    put("account_id", i); put("hero_id", if (i == 1) 11 else 8); put("personaname", if (i == 1) "A" else "B")
                    put("isRadiant", true); put("kills", 10); put("deaths", 2); put("assists", 15)
                    put("gold_per_min", 600); put("item_0", 2); put("item_1", 2); put("backpack_0", 2); put("backpack_2", 2)
                    put("item_5", 999); put("item_neutral", 998)
                })
            }
        }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val analysis = "[战犯]\n主宰(B): 本场发挥欠佳。\n[MVP]\n影魔(A): 本场发挥出色。"
        server.createContext("/chat") { exchange ->
            requests.add(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            val response = buildJsonObject { putJsonArray("choices") { add(buildJsonObject {
                putJsonObject("message") { put("role", "assistant"); put("content", analysis) }
            }) } }.toString().toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }; exchange.close()
        }
        server.start()
        try {
            val client = DeepSeekClient("test", "http://127.0.0.1:${server.address.port}/chat")
            val originalAnalysis = service.analyzeMatch(1, match, client, "same-model")
            val original = service.buildReport(match, originalAnalysis, true)
            try {
                service.generateMatchReport(match, 1, client, "same-model", true).use { generated ->
                    assertEquals(originalAnalysis, generated.analysis)
                    assertEquals(requests[0], requests[1])
                    assertSame(generated.report.players[0].items[0], generated.report.players[1].items[0])
                    val player = generated.report.players.first()
                    assertEquals(listOf(2, 2, 0, 0, 0, 999, 998), player.itemIds)
                    assertEquals(listOf(2, 0, 2), player.backpackIds)
                    assertNull(player.items[5])
                    assertNull(player.items[6])
                    assertNull(generated.report.players[0].backpackItems[1])
                    assertNotNull(generated.report.players[0].backpackItems[2])
                    fun png(image: Image): ByteArray = image.use { it.encodeToData()!!.use { encoded -> encoded.bytes } }
                    val expected = png(assertNotNull(dota2MatchDraw(original, ImageConfig())))
                    val actual = png(assertNotNull(dota2MatchDraw(generated.report, ImageConfig())))
                    assertContentEquals(expected, actual)
                }
                // 两种模式同时准备请求也不会修改共享客户端；只有 thinking 字段不同。
                listOf(Dota2ReportMode.DEEP, Dota2ReportMode.NORMAL).map { mode ->
                    async {
                        service.generateMatchReport(match, 1, client, "same-model", true, mode).use {
                            assertEquals(originalAnalysis, it.analysis)
                        }
                    }
                }.awaitAll()
                val bodies = requests.map { Json.parseToJsonElement(it).jsonObject }
                assertEquals("disabled", bodies[0]["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
                assertEquals(setOf("enabled", "disabled"), bodies.drop(2).map {
                    it["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content
                }.toSet())
                bodies.forEach { assertEquals(bodies[0] - "thinking", it - "thinking") }
            } finally {
                original.players.flatMap { listOfNotNull(it.heroIcon) + it.items.filterNotNull() + it.backpackItems.filterNotNull() }.forEach { it.close() }
            }
        } finally {
            server.stop(0); icon.close()
            check(dir.toAbsolutePath().normalize().startsWith(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
            check(dir.fileName.toString().startsWith("dota-pipeline-test"))
            dir.toFile().deleteRecursively()
        }
    }
}
