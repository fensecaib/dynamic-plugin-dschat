package top.colter.dynamic.agent.dota2

const val ANONYMOUS_PLAYER_NAME = "匿名玩家"

/**
 * Valve Dota 2 简体中文英雄名称（按 hero_id）。
 *
 * OpenDota 的 constants/heroes 当前只返回英文 localized_name，因此中文名需要在
 * 插件内统一维护。未知的新英雄仍会回退到 OpenDota 英文名，不会显示为空。
 */
internal val DOTA2_HERO_NAMES_ZH_CN = mapOf(
    1 to "敌法师", 2 to "斧王", 3 to "祸乱之源", 4 to "血魔", 5 to "水晶室女", 6 to "卓尔游侠",
    7 to "撼地者", 8 to "主宰", 9 to "米拉娜", 10 to "变体精灵", 11 to "影魔", 12 to "幻影长矛手",
    13 to "帕克", 14 to "帕吉", 15 to "雷泽", 16 to "沙王", 17 to "风暴之灵", 18 to "斯温",
    19 to "小小", 20 to "复仇之魂", 21 to "风行者", 22 to "宙斯", 23 to "昆卡", 25 to "莉娜",
    26 to "莱恩", 27 to "暗影萨满", 28 to "斯拉达", 29 to "潮汐猎人", 30 to "巫医", 31 to "巫妖",
    32 to "力丸", 33 to "谜团", 34 to "修补匠", 35 to "狙击手", 36 to "瘟疫法师", 37 to "术士",
    38 to "兽王", 39 to "痛苦女王", 40 to "剧毒术士", 41 to "虚空假面", 42 to "冥魂大帝",
    43 to "死亡先知", 44 to "幻影刺客", 45 to "帕格纳", 46 to "圣堂刺客", 47 to "冥界亚龙",
    48 to "露娜", 49 to "龙骑士", 50 to "戴泽", 51 to "发条技师", 52 to "拉席克", 53 to "自然先知",
    54 to "噬魂鬼", 55 to "黑暗贤者", 56 to "克林克兹", 57 to "全能骑士", 58 to "魅惑魔女",
    59 to "哈斯卡", 60 to "暗夜魔王", 61 to "育母蜘蛛", 62 to "赏金猎人", 63 to "编织者",
    64 to "杰奇洛", 65 to "蝙蝠骑士", 66 to "陈", 67 to "幽鬼", 68 to "远古冰魄", 69 to "末日使者",
    70 to "熊战士", 71 to "裂魂人", 72 to "矮人直升机", 73 to "炼金术士", 74 to "祈求者",
    75 to "沉默术士", 76 to "殁境神蚀者", 77 to "狼人", 78 to "酒仙", 79 to "暗影恶魔",
    80 to "独行德鲁伊", 81 to "混沌骑士", 82 to "米波", 83 to "树精卫士", 84 to "食人魔魔法师",
    85 to "不朽尸王", 86 to "拉比克", 87 to "干扰者", 88 to "司夜刺客", 89 to "娜迦海妖",
    90 to "光之守卫", 91 to "艾欧", 92 to "维萨吉", 93 to "斯拉克", 94 to "美杜莎",
    95 to "巨魔战将", 96 to "半人马战行者", 97 to "马格纳斯", 98 to "伐木机", 99 to "钢背兽",
    100 to "巨牙海民", 101 to "天怒法师", 102 to "亚巴顿", 103 to "上古巨神", 104 to "军团指挥官",
    105 to "工程师", 106 to "灰烬之灵", 107 to "大地之灵", 108 to "孽主", 109 to "恐怖利刃",
    110 to "凤凰", 111 to "神谕者", 112 to "寒冬飞龙", 113 to "天穹守望者", 114 to "齐天大圣",
    119 to "邪影芳灵", 120 to "石鳞剑士", 121 to "天涯墨客", 123 to "森海飞霞",
    126 to "虚无之灵", 128 to "电炎绝手", 129 to "玛尔斯", 131 to "百戏大王",
    135 to "破晓辰星", 136 to "玛西", 137 to "獸", 138 to "琼英碧灵", 145 to "凯", 155 to "朗戈",
)

internal fun localizedDota2HeroName(heroId: Int, englishFallback: String? = null): String =
    DOTA2_HERO_NAMES_ZH_CN[heroId]
        ?: englishFallback?.trim()?.takeIf { it.isNotEmpty() }
        ?: "Hero_$heroId"

internal fun isAnonymousDota2PlayerName(name: String?): Boolean {
    val normalized = name?.trim().orEmpty()
    return normalized.isEmpty() || normalized == "?" || normalized == "null" || normalized == ANONYMOUS_PLAYER_NAME
}

internal fun normalizeDota2PlayerName(name: String?): String =
    name?.trim()?.takeUnless(::isAnonymousDota2PlayerName) ?: ANONYMOUS_PLAYER_NAME

private val DOTA2_IDENTITY_SEPARATORS = setOf(
    ' ', '\t', '\n', '\r',
    '(', ')', '（', '）', '[', ']', '【', '】',
    ':', '：', '/', '|', ',', '，', '·',
)

/**
 * 判断分析文本中是否出现完整的玩家名或英雄名。
 *
 * AI 通常会返回“英雄(玩家)”或“英雄：玩家”。要求左右至少是文本边界或常见
 * 分隔符，可以避免“凯”“陈”“獸”等单字英雄名误命中“凯旋”“陈述”等普通词。
 */
internal fun matchesDota2AnalysisIdentity(candidate: String?, identity: String?): Boolean {
    val text = candidate?.trim().orEmpty()
    val token = identity?.trim().orEmpty()
    if (text.isEmpty() || token.isEmpty()) return false

    var start = text.indexOf(token)
    while (start >= 0) {
        val end = start + token.length
        val hasLeftBoundary = start == 0 || text[start - 1] in DOTA2_IDENTITY_SEPARATORS
        val hasRightBoundary = end == text.length || text[end] in DOTA2_IDENTITY_SEPARATORS
        if (hasLeftBoundary && hasRightBoundary) return true
        start = text.indexOf(token, start + 1)
    }
    return false
}
