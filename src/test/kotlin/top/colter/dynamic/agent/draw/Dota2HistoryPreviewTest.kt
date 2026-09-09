package top.colter.dynamic.agent.draw

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jetbrains.skia.Image
import top.colter.dynamic.agent.config.ImageConfig
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class Dota2HistoryPreviewTest {
    @Test fun renderHistory() = runBlocking {
        if (!System.getenv("DOTA_HISTORY_PREVIEW").equals("true", true)) return@runBlocking
        val heroIds = listOf(11, 8, 13, 101, 2, 21, 26, 97, 20, 14)
        val heroKeys = listOf("nevermore", "juggernaut", "puck", "skywrath_mage", "axe", "windrunner", "lion", "magnataur", "vengefulspirit", "pudge")
        val icons = heroIds.zip(heroKeys).associate { (id, key) -> id to runCatching {
            val conn = URI("https://cdn.cloudflare.steamstatic.com/apps/dota2/images/dota_react/heroes/$key.png").toURL().openConnection()
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            conn.getInputStream().use { Image.makeFromEncoded(it.readBytes()) }
        }.getOrNull() }
        val rows = JsonArray(heroIds.mapIndexed { i, hero -> buildJsonObject {
            put("hero_id", hero); put("match_id", 8980854337L - i * 17341); put("start_time", 1788913080L - i * 4800)
            put("player_slot", 0); if (i != 9) put("radiant_win", i in listOf(0,1,3,5,7,8))
            put("kills", listOf(13,8,10,20,3,12,2,10,7,9)[i]); put("deaths", if (i == 5) 0 else i + 2); put("assists", 14 + i)
            put("duration", 2263 + i * 31)
            if (i != 6) { put("gold_per_min", 687 - i * 23); put("xp_per_min", 979 - i * 32); put("hero_damage", 32700 + i * 1800) }
        } })
        try {
            for (factor in listOf(1f, 2f)) {
                val rendered = assertNotNull(dota2HistoryDraw(123456789, rows, ImageConfig(factor=factor), previewIcons=icons))
                rendered.use { image ->
                    assertEquals((1060 * factor).toInt(), image.width)
                    assertEquals((1018 * factor).toInt(), image.height)
                    val path = Path.of(".codex", "dota-history-${factor.toInt()}x.png")
                    Files.createDirectories(path.parent)
                    image.encodeToData()!!.use { Files.write(path, it.bytes) }
                }
            }
        } finally { icons.values.filterNotNull().forEach { it.close() } }
    }
}
