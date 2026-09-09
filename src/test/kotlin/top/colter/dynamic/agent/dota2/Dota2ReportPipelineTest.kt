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
            put("match_id", 8980854337L); put("radiant_win", true); put("duration", 2400)
            putJsonArray("players") {
                for (i in 1..2) add(buildJsonObject {
                    put("account_id", i); put("hero_id", if (i == 1) 11 else 8); put("personaname", if (i == 1) "A" else "B")
                    put("isRadiant", true); put("kills", 10); put("deaths", 2); put("assists", 15)
                    put("gold_per_min", 600); put("item_0", 2); put("item_1", 2); put("backpack_0", 2); put("backpack_2", 2)
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
            val original = service.buildReport(match, 1, originalAnalysis, true)
            try {
                service.generateMatchReport(match, 1, client, "same-model", true).use { generated ->
                    assertEquals(originalAnalysis, generated.analysis)
                    assertEquals(requests[0], requests[1])
                    assertSame(generated.report.players[0].items[0], generated.report.players[1].items[0])
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
