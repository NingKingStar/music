package com.muses.player.core.model.lyrics

/**
 * 歌词在线搜索领域模型（任务 08-25-native-lyrics-online / L0）。
 *
 * 规格书逐字段翻译：
 * - src/features/lyrics/types.ts（AmllIndexEntry / AmllMatchQuery / AmllMatchResult / AmllRawIndexLine）
 * - src/features/lyrics/providers/types.ts（OnlineLyricsQuery / OnlineLyricsFormat /
 *   OnlineLyricsSource / LyricsProvider 命中与编排结果）
 */

// ── 在线歌词查询与命中（providers/types.ts）────────────────

/** 在线歌词查询（编排层 / 各 provider 共用） */
data class OnlineLyricsQuery(
    val songId: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    /** 秒；LRCLIB 等可用 */
    val durationSec: Double? = null,
    /** 正在播放的实际平台曲目；平台 ID 不得用于其他平台。 */
    val trackRef: com.muses.player.core.model.online.OnlineTrackRef? = null,
)

/** ttml=amll；lrc=行级；yrc/qrc=平台逐字（AMLL lyric 解析器可解析）；wire 与 Web 一致 */
enum class OnlineLyricsFormat(val wire: String) {
    TTML("ttml"),
    LRC("lrc"),
    YRC("yrc"),
    QRC("qrc"),
}

/** 在线歌词来源 */
enum class OnlineLyricsSource(val wire: String) {
    AMLL("amll"),
    KW("kw"),
    TX("tx"),
    WY("wy"),
    KG("kg"),
    MG("mg"),
    LRCLIB("lrclib"),
}

/** 可插拔歌词源命中；translationText 为 timed LRC 译文（如网易 tlyric） */
data class OnlineLyricsProviderHit(
    val text: String,
    val format: OnlineLyricsFormat,
    val translationText: String? = null,
    val romanizationText: String? = null,
    /** 网易逐字正文缺音译时，保留普通LRC原文用于安全对齐romalrc。 */
    val romanizationBaseText: String? = null,
    val romanizationFallbackText: String? = null,
)

/**
 * 可插拔回退源（平台 / LRCLIB）；amll 在编排层单独调用（providers/types.ts LyricsProvider）。
 * id 不应为 AMLL（Web 类型层面排除，此处运行时约束）。
 */
interface LyricsProvider {
    val id: OnlineLyricsSource

    suspend fun searchLyrics(query: OnlineLyricsQuery): OnlineLyricsProviderHit?
}

/** 匹配失败原因 */
enum class OnlineLyricsFailReason {
    NO_MATCH,
    NETWORK,
    PARSE,
}

/** matchOnlineLyrics 编排结果（Ok | Fail 密封建模） */
sealed interface OnlineLyricsMatchResult {
    data class Ok(
        val text: String,
        val format: OnlineLyricsFormat,
        val source: OnlineLyricsSource,
        val translationText: String? = null,
        /**
         * 命中置信度（child4 R4-3）：amll 路径由 findBestMatch 产出；平台 LRC 缺省
         * 视为 HIGH（向后兼容）。低置信命中仅供刮削页候选排序参考。
         */
        val confidence: com.muses.player.core.model.scrape.MatchConfidence? = null,
        val romanizationText: String? = null,
        val romanizationBaseText: String? = null,
        val romanizationFallbackText: String? = null,
    ) : OnlineLyricsMatchResult

    data class Fail(val reason: OnlineLyricsFailReason) : OnlineLyricsMatchResult
}

// ── AMLL TTML DB（types.ts）───────────────────────────────

/** amll-ttml-db 索引行（解析后的结构化字段） */
data class AmllIndexEntry(
    val musicName: String,
    val artists: List<String>,
    val album: String? = null,
    /** 曲目时长（秒）；索引行可选携带，用于匹配质量时长约束（child4） */
    val durationSec: Double? = null,
    val rawLyricFile: String,
)

/** 匹配查询 */
data class AmllMatchQuery(
    val songId: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    /** 查询曲目时长（秒）；可选，参与匹配质量时长约束（child4） */
    val durationSec: Double? = null,
    /** 人工刮削查询跳过失败缓存的读写，允许立即重试；播放查询默认保留缓存策略。 */
    val bypassNegativeCache: Boolean = false,
)

enum class AmllFailReason {
    NO_MATCH,
    NETWORK,
    PARSE,
    ABORTED,
}

/** AMLL TTML 查询结果（Ok | Fail 密封建模） */
sealed interface AmllMatchResult {
    data class Ok(
        val ttml: String,
        val rawLyricFile: String,
        val score: Int,
        /** 来自 findBestMatch；自动写库路径应校验为 HIGH */
        val confidence: com.muses.player.core.model.scrape.MatchConfidence? = null,
        val romanizationText: String? = null,
    ) : AmllMatchResult

    data class Fail(val reason: AmllFailReason) : AmllMatchResult
}
