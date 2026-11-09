package com.muses.player.core.lyrics

import com.muses.player.core.lyrics.model.LyricsDocument
import com.muses.player.core.lyrics.model.NeteaseLyricParser
import com.muses.player.core.lyrics.parser.LrcLyricsParser
import com.muses.player.core.lyrics.parser.QQMusicQrcLyricsParser
import com.muses.player.core.model.lyrics.OnlineLyricsMatchResult
import com.muses.player.core.model.lyrics.OnlineLyricsFormat
import com.muses.player.core.model.lyrics.OnlineLyricsQuery
import com.muses.player.feature.player.lyric.LyricsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「Muses 在线歌词匹配 → [LyricsDocument]」的共用落地。
 *
 * 播放页（`PlayerViewModel`）与迷你条歌词模式（`MainViewModel`）共用同一份口径，
 * 避免两处各写一遍查询构造与格式分流。
 *
 * ## 何时用
 * 在线音源曲目**先问洛雪脚本**（`lyric` 动作），取不到时回退到这里。
 * 兜底必要性：多数洛雪自定义源脚本只声明 `musicUrl`（歌词/封面在洛雪桌面端由主程序
 * 自己的接口取，不归脚本），所以「只问脚本」会让绝大多数在线曲目没有歌词。
 *
 * ## 格式分流
 * - 无 timed 译文：走通用解析链（TTML / YRC / QRC / LRC），AMLL 命中即 TTML；
 * - 有 timed 译文（平台源的 tlyric）：按原文格式合并，保留逐字时间轴。
 */
suspend fun LyricsMatcher.matchDocument(
    songId: String,
    title: String,
    artist: String? = null,
    album: String? = null,
    durationMs: Long = 0L,
    durationSec: Long = 0L,
    songPath: String? = null,
): LyricsDocument? {
    val result = match(
        OnlineLyricsQuery(
            songId = songId,
            title = title,
            artist = artist,
            album = album,
            trackRef = com.muses.player.core.model.online.OnlineTrackRef.parse(songPath)?.let {
                com.muses.player.core.model.online.OnlineTrackSession.resolvedReference(it)
            },
            durationSec = durationSec.takeIf { it > 0 }?.toDouble()
                ?: durationMs.takeIf { it > 0 }?.let { it / 1000.0 },
        ),
    )
    if (result !is OnlineLyricsMatchResult.Ok) return null
    return parseLyricsMatch(result)
}

internal suspend fun parseLyricsMatch(result: OnlineLyricsMatchResult.Ok): LyricsDocument? =
    withContext(Dispatchers.Default) {
        val translation = result.translationText
        val primary = if (translation.isNullOrBlank() && result.romanizationText.isNullOrBlank()) {
            LyricsParser.parseDocument(result.text)
        } else {
            when (result.format) {
                OnlineLyricsFormat.YRC -> NeteaseLyricParser.parse(yrc = result.text, lrc = "", translatedYrc = translation.orEmpty(), translatedLrc = translation.orEmpty(), romanizedYrc = result.romanizationText.orEmpty(), romanizedLrc = result.romanizationText.orEmpty())
                OnlineLyricsFormat.QRC -> QQMusicQrcLyricsParser.parse(primary = result.text, translation = translation.orEmpty(), romanization = result.romanizationText.orEmpty())
                else -> LrcLyricsParser.parse(result.text, translation = translation.orEmpty(), romanization = result.romanizationText.orEmpty())
            }
        } ?: return@withContext null
        val fallback = result.romanizationBaseText?.let { base ->
            LrcLyricsParser.parse(base, romanization = result.romanizationFallbackText.orEmpty())
        }
        if (fallback != null) LyricRomanizationPolicy.enrich(primary, fallback, result.source.wire, result.source.wire).document else primary
    }

/** 当前曲的副歌区间；没有网易匹配或接口失败时返回空表（不做文本推断）。 */
suspend fun LyricsMatcher.chorusRangesFor(song: com.muses.player.core.model.Song): List<com.muses.player.core.model.lyrics.ChorusRange> =
    runCatching {
        com.muses.player.core.lyrics.provider.fetchWyChorusRanges(
            OnlineLyricsQuery(
                songId = song.id, title = song.title, artist = song.artist, album = song.album,
                durationSec = song.durationMs.takeIf { it > 0 }?.let { it / 1000.0 }
                    ?: song.durationSec.takeIf { it > 0 }?.toDouble(),
                trackRef = com.muses.player.core.model.online.OnlineTrackRef.parse(song.path),
            ),
        )
    }.getOrDefault(emptyList())

/** 当前歌词是否还缺译文/音译（策略类内部使用，对外只暴露这个判定）。 */
fun needsAnnotationEnrichment(document: LyricsDocument, translationSource: String?): Boolean =
    LyricTranslationPolicy.needsEnrichment(document, translationSource) ||
        LyricRomanizationPolicy.needsEnrichment(document)

/** 用网易 donor 补全译文与音译；没变化时返回原对象。 */
fun enrichWithWyDonor(
    base: LyricsDocument,
    donor: LyricsDocument?,
    translationSource: String?,
): Pair<LyricsDocument, String?> {
    if (donor == null) return base to translationSource
    val translated = LyricTranslationPolicy.enrich(base, donor, translationSource, "wy")
    val read = LyricRomanizationPolicy.enrich(translated.document, donor, translated.translationSource, "wy")
    return read.document to translated.translationSource
}

/** 挑一行够长、能当“歌词搜索”关键字的原文（优先外文，中文断句也能搜）。 */
private fun lyricProbeOf(base: LyricsDocument): String? = base.lines.asSequence()
    .map { it.text.trim() }
    .filter { it.length >= 6 }
    .filterNot { it.startsWith("[") || it.contains("http") }
    .sortedByDescending { it.length }
    .firstOrNull()
    ?.take(40)

/** 用网易候选逐个尝试，选合得最多的那一版（不按“第一个能用”）。 */
suspend fun enrichLyricsWithWy(
    base: LyricsDocument,
    query: OnlineLyricsQuery,
    translationSource: String?,
    onDebug: (String) -> Unit = {},
): Pair<LyricsDocument, String?>? {
    var best: LyricsDocument? = null
    var bestFilled = 0
    for (donor in com.muses.player.core.lyrics.provider.wyAnnotationDonors(query, onDebug, lyricProbeOf(base))) {
        val (merged, filled) = mergeAnnotations(base, donor)
        onDebug("merge donor=${donor.lines.size} filled=$filled coverage=${(annotationCoverage(merged) * 100).toInt()}%")
        if (filled > bestFilled) {
            bestFilled = filled
            best = merged
        }
        // 已经合满就不必再试后面的版本。
        if (annotationCoverage(merged) >= 0.95) break
    }
    return best?.takeIf { bestFilled > 0 }?.let { it to (translationSource ?: "wy") }
}

/** 兼容：单版本合并（保留旧行为）。 */
/** 用同一首曲的网易 id 取译文/音译 donor；失败返回 null。 */
suspend fun LyricsMatcher.annotationDonorFor(song: com.muses.player.core.model.Song): LyricsDocument? =
    runCatching {
        com.muses.player.core.lyrics.provider.fetchWyAnnotationDonor(
            OnlineLyricsQuery(
                songId = song.id, title = song.title, artist = song.artist, album = song.album,
                durationSec = song.durationMs.takeIf { it > 0 }?.let { it / 1000.0 }
                    ?: song.durationSec.takeIf { it > 0 }?.toDouble(),
                trackRef = com.muses.player.core.model.online.OnlineTrackRef.parse(song.path),
            ),
        )
    }.getOrNull()

/** 播放专用渐进通道；脚本与平台互不等待，所有播放 UI 共享缓存及进行中的查询。 */
fun LyricsMatcher.observeDocuments(
    song: com.muses.player.core.model.Song,
    metadata: com.muses.player.core.model.online.OnlineTrackMetadataResolver? = null,
): kotlinx.coroutines.flow.Flow<PlaybackLyricsUpdate> = observePlayback(
    OnlineLyricsQuery(
        songId = song.id, title = song.title, artist = song.artist, album = song.album,
        durationSec = song.durationMs.takeIf { it > 0 }?.let { it / 1000.0 }
            ?: song.durationSec.takeIf { it > 0 }?.toDouble(),
        // 原始平台 ID 对应用户选择的版本，不能因音频换源而丢失该版本的直接歌词入口。
        trackRef = com.muses.player.core.model.online.OnlineTrackRef.parse(song.path),
    ), metadata, song.lyrics,
)
