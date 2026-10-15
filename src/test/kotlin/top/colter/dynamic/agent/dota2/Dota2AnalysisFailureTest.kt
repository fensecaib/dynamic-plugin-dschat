package top.colter.dynamic.agent.dota2

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jetbrains.skia.Color
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import top.colter.dynamic.agent.ds.DeepSeekClient
import top.colter.dynamic.agent.util.CacheType
import top.colter.dynamic.agent.util.CacheUtils
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class Dota2AnalysisFailureTest {
    private val secret = "upstream-private-response"
    private val validAnalysis = "[战犯]\n主宰(B): 本场发挥欠佳。\n[MVP]\n影魔(A): 本场发挥出色。"

    private fun match(): JsonObject = buildJsonObject {
        put("match_id", 9044880093L)
        put("radiant_win", true)
        putJsonArray("players") {
            for (id in 1..2) add(buildJsonObject {
                put("account_id", id)
                put("hero_id", if (id == 1) 11 else 8)
                put("personaname", if (id == 1) "A" else "B")
                put("isRadiant", id == 1)
                put("kills", 10)
                put("deaths", 2)
                put("assists", 5)
                put("item_0", 2)
                put("backpack_2", 2)
            })
        }
    }

    private inner class Fixture(status: Int, content: String) : AutoCloseable {
        val dir: Path = Files.createTempDirectory("dota-analysis-failure-")
        val cache = CacheUtils(dir)
        val service = Dota2Service(dir.toFile(), cache)
        val calls = AtomicInteger()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private var globalIcon: Image? = null
        val client: DeepSeekClient

        init {
            setField("heroConstantsLoaded", true)
            setField("itemConstantsLoaded", true)
            server.createContext("/chat") { exchange ->
                calls.incrementAndGet()
                exchange.requestBody.readBytes()
                val body = if (status == 200) buildJsonObject {
                    putJsonArray("choices") {
                        add(buildJsonObject {
                            put("finish_reason", "stop")
                            putJsonObject("message") {
                                put("role", "assistant")
                                put("content", content)
                            }
                        })
                    }
                }.toString() else secret
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            server.start()
            client = DeepSeekClient("local-test-key", "http://127.0.0.1:${server.address.port}/chat", maxRetries = 0)
        }

        private fun setField(name: String, value: Any) {
            Dota2Service::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        }

        fun installCachedIcons(): Image {
            val icon = Surface.makeRasterN32Premul(48, 48).use {
                it.canvas.clear(Color.BLUE)
                it.makeImageSnapshot()
            }
            globalIcon = icon
            val bytes = icon.encodeToData()!!.use { it.bytes }
            cache.cacheFile(CacheType.ICON_HERO, "hero.png").writeBytes(bytes)
            cache.cacheFile(CacheType.ICON_ITEM, "item.png").writeBytes(bytes)
            setField("heroIconPaths", mutableMapOf(11 to "/hero.png", 8 to "/hero.png"))
            setField("itemIconPaths", mutableMapOf(2 to "/item.png"))
            listOf("aghsScepterYes", "aghsScepterNo", "aghsShardYes", "aghsShardNo").forEach { setField(it, icon) }
            return icon
        }

        override fun close() {
            server.stop(0)
            globalIcon?.close()
            val resolved = dir.toAbsolutePath().normalize()
            check(resolved.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
            check(dir.fileName.toString().startsWith("dota-analysis-failure-"))
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `authentication and provider failures produce safe analysis unavailable errors`() = runBlocking<Unit> {
        for ((status, category) in listOf(401 to "unauthorized", 503 to "server_error")) {
            Fixture(status, "").use { fixture ->
                val error = assertFailsWith<DotaAnalysisUnavailableException> {
                    fixture.service.analyzeMatch(1, match(), fixture.client, "local-model")
                }
                assertEquals(category, error.category)
                assertTrue(error.userMessage.contains("AI 分析暂不可用"))
                assertFalse(error.userMessage.contains(secret))
                assertNull(error.cause)
                assertEquals(1, fixture.calls.get())
            }
        }
    }

    @Test fun `missing input and malformed analysis never silently become successful reports`() = runBlocking<Unit> {
        Fixture(200, "not a team analysis").use { fixture ->
            val unavailable = assertFailsWith<DotaAnalysisUnavailableException> {
                fixture.service.analyzeMatch(1, match(), fixture.client, "local-model")
            }
            assertEquals("analysis_format", unavailable.category)
            val invalidData = assertFailsWith<DotaAnalysisUnavailableException> {
                fixture.service.analyzeMatch(1, buildJsonObject {}, fixture.client, "local-model")
            }
            assertEquals("analysis_data", invalidData.category)
            assertEquals(1, fixture.calls.get())
        }
    }

    @Test fun `overview first request failure stops without producing a statistics-only success`() = runBlocking<Unit> {
        Fixture(503, "").use { fixture ->
            val snapshot = OverviewSnapshot(1, "A", listOf(
                OverviewMatch(1, 11, "影魔", 1, 2400, true, emptyMap(), emptyMap(), emptyList(), false),
            ))
            val error = assertFailsWith<DotaAnalysisUnavailableException> {
                analyzeOverview(snapshot, OverviewMode.NORMAL, fixture.client, "local-model")
            }
            assertEquals("server_error", error.category)
            assertTrue(error.userMessage.contains("AI 分析暂不可用"))
            assertEquals(1, fixture.calls.get())
        }
    }

    @Test fun `full report shares and closes its own images while retaining global icons`() = runBlocking<Unit> {
        Fixture(200, validAnalysis).use { fixture ->
            val global = fixture.installCachedIcons()
            val generated = fixture.service.generateFullReport(match(), fixture.client, "local-model")
            val hero = assertNotNull(generated.report.players.first().heroIcon)
            val item = assertNotNull(generated.report.players.first().items.first())
            generated.use {
                assertEquals(2, fixture.calls.get())
                assertSame(item, it.report.players.last().items.first())
                assertSame(item, it.report.players.first().backpackItems[2])
                assertFalse(hero.isClosed)
                assertFalse(item.isClosed)
            }
            assertTrue(hero.isClosed)
            assertTrue(item.isClosed)
            assertFalse(global.isClosed)
        }
    }

    @Test fun `full analysis aborts when either side has no usable AI answer`() = runBlocking<Unit> {
        Fixture(401, "").use { fixture ->
            val error = assertFailsWith<DotaAnalysisUnavailableException> {
                fixture.service.analyzeFullMatch(match(), fixture.client, "local-model")
            }
            assertEquals("unauthorized", error.category)
            assertTrue(error.userMessage.contains("AI 分析暂不可用"))
        }
    }

    @Test fun `full report cards use player slots when explicit sides are absent`() = runBlocking<Unit> {
        Fixture(200, validAnalysis).use { fixture ->
            fixture.installCachedIcons()
            val source = match()
            val slotOnly = JsonObject(source + ("players" to JsonArray(source["players"]!!.jsonArray.map { row ->
                val player = row.jsonObject
                val slot = if (player["isRadiant"]!!.jsonPrimitive.boolean) 0 else 128
                JsonObject((player - "isRadiant") + ("player_slot" to JsonPrimitive(slot)))
            })))
            fixture.service.generateFullReport(slotOnly, fixture.client, "local-model").use { generated ->
                assertEquals(listOf(true, false), generated.report.players.map { it.isRadiant })
                assertEquals(2, fixture.calls.get())
            }
        }
    }

    @Test fun `analysis rejects conflicting or missing team sides before calling AI`() = runBlocking<Unit> {
        Fixture(200, validAnalysis).use { fixture ->
            for (conflicting in listOf(true, false)) {
                val source = match()
                val invalid = JsonObject(source + ("players" to JsonArray(source["players"]!!.jsonArray.map { row ->
                    val player = row.jsonObject
                    if (conflicting) JsonObject(player + ("player_slot" to JsonPrimitive(128)))
                    else JsonObject(player - "isRadiant")
                })))
                val error = assertFailsWith<DotaAnalysisUnavailableException> {
                    fixture.service.analyzeMatch(1, invalid, fixture.client, "local-model")
                }
                assertEquals("analysis_data", error.category)
            }
            assertEquals(0, fixture.calls.get())
        }
    }
}
