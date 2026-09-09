package top.colter.dynamic.agent.draw

import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import top.colter.dynamic.agent.config.ImageConfig
import top.colter.dynamic.agent.dota2.ANONYMOUS_PLAYER_NAME
import top.colter.dynamic.agent.dota2.Dota2MatchReport
import top.colter.dynamic.agent.dota2.Dota2PlayerCard
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull

/** 手工视觉回归快照；默认测试不会联网，设置 DOTA_LAYOUT_PREVIEW=true 后才执行。 */
class Dota2LayoutPreviewTest {
    @Test
    fun renderCurrentLayout() {
        if (!System.getenv("DOTA_LAYOUT_PREVIEW").equals("true", ignoreCase = true)) return

        val imageCache = mutableMapOf<String, Image?>()
        fun image(url: String): Image? = imageCache.getOrPut(url) {
            runCatching { Image.makeFromEncoded(URI(url).toURL().readBytes()) }.getOrNull()
        }
        fun hero(name: String) = image("https://cdn.cloudflare.steamstatic.com/apps/dota2/images/dota_react/heroes/$name.png")
        fun item(name: String) = image("https://cdn.cloudflare.steamstatic.com/apps/dota2/images/dota_react/items/$name.png")

        val heroKeys = listOf("nevermore", "juggernaut", "puck", "lion", "skywrath_mage", "axe", "vengefulspirit", "magnataur", "windrunner", "pudge")
        val heroNames = listOf("影魔", "主宰", "帕克", "莱恩", "天怒法师", "斧王", "复仇之魂", "马格纳斯", "风行者", "帕吉")
        val playerNames = listOf(ANONYMOUS_PLAYER_NAME, "Yatoro", "山顶资本", "Maybe", "SUMMER", "跳跳蛙", "Melody西塞", "Magnus", "Wind", "面首")
        val itemKeys = listOf("blink", "power_treads", "black_king_bar", "daedalus", "butterfly", "satanic", "travel_boots", "manta", "skadi", "monkey_king_bar", "refresher", "octarine_core")
        val itemImages = itemKeys.map(::item)
        val scepter = item("ultimate_scepter")
        val shard = item("aghanims_shard")

        val players = (0 until 10).map { index ->
            val radiant = index < 5
            val kills = listOf(13, 8, 10, 2, 20, 3, 7, 10, 4, 9)[index]
            val deaths = listOf(2, 7, 9, 14, 4, 10, 11, 9, 12, 8)[index]
            val assists = listOf(14, 15, 10, 23, 10, 16, 9, 10, 18, 22)[index]
            val start = index % itemImages.size
            Dota2PlayerCard(
                name=playerNames[index], heroName=heroNames[index], heroId=index + 1,
                heroIcon=hero(heroKeys[index]), isRadiant=radiant,
                kills=kills, deaths=deaths, assists=assists,
                kda=(kills + assists).toDouble() / deaths.coerceAtLeast(1),
                gpm=listOf(687, 519, 405, 377, 602, 338, 504, 405, 344, 512)[index],
                xpm=listOf(979, 695, 706, 667, 877, 537, 588, 706, 542, 744)[index],
                heroDamage=listOf(32700, 22700, 17700, 18000, 63600, 16400, 13300, 17700, 10800, 28500)[index],
                towerDamage=listOf(9000, 2800, 0, 1000, 12600, 0, 0, 0, 3500, 6100)[index],
                level=listOf(25, 22, 22, 21, 24, 19, 20, 22, 19, 23)[index],
                lastHits=listOf(328, 215, 53, 159, 218, 77, 279, 53, 25, 241)[index],
                denies=listOf(11, 11, 1, 16, 4, 2, 7, 1, 0, 8)[index],
                netWorth=listOf(24000, 18000, 13400, 11400, 18800, 11300, 15700, 13400, 11400, 19600)[index],
                items=(0 until 7).map { itemImages[(start + it) % itemImages.size] },
                backpackItems=listOf(itemImages.getOrNull(start), null, itemImages.getOrNull((start + 1) % itemImages.size)),
                hasAghsScepter=index % 3 == 0, hasAghsShard=index % 2 == 0,
                aghsScepterIcon=if (index % 3 == 0) scepter else null,
                aghsShardIcon=if (index % 2 == 0) shard else null,
                rankName=if (index == 0) "" else "万古流芳",
                isMvp=index == 0, isCriminal=index == 5,
            )
        }

        val report = Dota2MatchReport(
            matchId=8980854337, duration=2263, startTime=1788450180,
            gameMode="全英雄选择", radiantScore=48, direScore=38, radiantWin=true,
            players=players,
            mvpName="影魔（匿名玩家）", mvpReason="13杀2死，9.0 的 KDA。经济与经验效率领先，关键团战持续输出，是本场胜利的核心。",
            criminalName="斧王（跳跳蛙）", criminalReason="承伤与先手时机不稳定，多次脱节使团队失去正面战场主动权。",
        )

        val rendered = runBlocking { dota2MatchDraw(report, ImageConfig()) }
        assertNotNull(rendered)
        val output = Path.of(".codex", "dota-card-layout-demo.png")
        Files.createDirectories(output.parent)
        rendered.encodeToData()!!.use { Files.write(output, it.bytes) }
    }
}
