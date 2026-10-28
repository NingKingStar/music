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
        if (translation.isNullOrBlank()) {
            LyricsParser.parseDocument(result.text)
        } else {
            when (result.format) {
                OnlineLyricsFormat.YRC -> NeteaseLyricParser.parse(yrc = result.text, lrc = "", translatedLrc = translation)
                OnlineLyricsFormat.QRC -> QQMusicQrcLyricsParser.parse(primary = result.text, translation = translation)
                else -> LrcLyricsParser.parse(result.text, translation = translation)
            }
        }
    }

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
