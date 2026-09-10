package top.colter.dynamic.agent.dota2

import kotlinx.serialization.Serializable
import org.jetbrains.skia.Image

@Serializable
data class DsAnalysisResult(
    val mvpOrSvp: String,
    val mvpOrSvpText: String,
    val criminal: String,
    val criminalText: String,
    val rawResponse: String
)

data class DualAnalyzeResult(
    val radiant: DsAnalysisResult?,
    val dire: DsAnalysisResult?
)

data class Dota2MatchReport(
    val matchId: Long = 0,
    val duration: Int = 0,
    val startTime: Long = 0,
    val gameMode: String = "",
    val radiantScore: Int = 0,
    val direScore: Int = 0,
    val radiantWin: Boolean = false,
    val players: List<Dota2PlayerCard> = emptyList(),
    val mvpName: String = "N/A",
    val mvpReason: String = "N/A",
    val svpName: String = "N/A",
    val svpReason: String = "N/A",
    val criminalName: String = "N/A",
    val criminalReason: String = "N/A",
    val radiantMvp: String = "N/A",
    val radiantMvpReason: String = "N/A",
    val radiantCriminal: String = "N/A",
    val radiantCriminalReason: String = "N/A",
    val direMvp: String = "N/A",
    val direMvpReason: String = "N/A",
    val direCriminal: String = "N/A",
    val direCriminalReason: String = "N/A",
)

data class Dota2PlayerCard(
    val name: String,
    val heroName: String,
    val heroId: Int = 0,
    val heroIcon: Image? = null,
    val isRadiant: Boolean = true,
    val kills: Int = 0,
    val deaths: Int = 0,
    val assists: Int = 0,
    val kda: Double = 0.0,
    val gpm: Int = 0,
    val xpm: Int = 0,
    val heroDamage: Int = 0,
    val towerDamage: Int = 0,
    val level: Int = 0,
    val lastHits: Int = 0,
    val items: List<Image?> = emptyList(),
    val denies: Int = 0,
    val netWorth: Int = 0,
    val heroHealing: Int = 0,
    val backpackItems: List<Image?> = emptyList(),
    val hasAghsScepter: Boolean = false,
    val hasAghsShard: Boolean = false,
    val aghsScepterIcon: Image? = null,
    val aghsShardIcon: Image? = null,
    val rankTier: Int = 0,
    val rankName: String = "",
    val isMvp: Boolean = false,
    val isSvp: Boolean = false,
    val isCriminal: Boolean = false,
)
