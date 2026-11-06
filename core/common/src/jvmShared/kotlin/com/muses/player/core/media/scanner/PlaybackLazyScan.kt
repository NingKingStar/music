package com.muses.player.core.media.scanner

import com.muses.player.core.data.db.SongTags
import com.muses.player.core.data.mapper.toDomain
import com.muses.player.core.model.Song

/**
 * 播放时懒扫描编排（U26 上收自安卓 PlaybackService.onEvents，android/desktop 双端共用）。
 *
 * 契约（与安卓侧逐字对齐）：
 * - 调用方核对缓存有效性后提供文件快照，版本已齐也允许更新；
 * - 数据库只缓存文件实际标签，旧刮削标记不能阻挡刷新；
 * - 空字段表示文件中没有该标签，读取失败则传 null 并保留数据库；
 * - 入库走 SongRepository.upsert 唯一路径（同步重建派生索引）。
 *
 * @param tags 文件标签快照（调用方负责读取：安卓经 AudioTagReader Range 探测，
 *   桌面经 JaudiotaggerTagPort 读本地缓存文件）；null = 读取失败，本次跳过（下次重试）。
 * @return 更新后的 Song（需入库），或 null（快照未变化 / 标签读取失败）。
 */
object PlaybackLazyScan {

    /** 文件标签快照（平台无关输入；两端各自读取后喂入） */
    data class FileTags(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val lyrics: String? = null,
        val coverUri: String? = null,
        val durationMs: Long = 0L,
        val audioQuality: String? = null,
    )

    /**
     * 封面回填（独立于 [merge] 的版本门禁）：
     *
     * 根因：[merge] 仅在 `tagsVersion < TAGS_VERSION` 时执行，且无更新分支也会抬升版本；
     * 封面在首次扫描/懒扫描时错过（内嵌图解析失败、文件当时无封面后补、缓存封面被清理），
     * 此后永久不再补——「有内嵌封面但不展示」的系统性缺口。
     *
     * 触发条件（缺一不可）：库内无封面（coverUri 空）+ 无刮削封面决策
     * （`metaSources.cover` 非空表示用户已选封面/显式清空，不得用文件图覆盖）+
     * 本次读到文件封面。只动 coverUri，其余字段与 tagsVersion 原样保留。
     *
     * @return 需入库的 Song，或 null（无需回填）。调用方在 [merge] 返回 null
     *  （版本已齐/标签读取失败）时顺手调用一次即可，两者互斥不重复写库。
     */
    fun coverBackfill(song: Song, coverUri: String?): Song? {
        if (song.metaSources?.cover != null) return null
        if (!song.coverUri.isNullOrBlank()) return null
        if (coverUri.isNullOrBlank()) return null
        return song.copy(coverUri = coverUri)
    }

    fun merge(song: Song, tags: FileTags?): Song? {
        if (tags == null) return null
        // 输入是成功读取的文件快照；版本只表示解析器版本，不能阻止刷新。
        // 旧 SCRAPE 标记曾表示仅库内修改，不可永久盖住文件真实值。
        val resolved = song.copy(
            title = tags.title?.takeIf { it.isNotBlank() }
                ?: song.path.substringBefore('?')
                    .substringAfterLast('/')
                    .substringAfterLast('\\')
                    .substringBeforeLast('.')
                    .let {
                        if (song.path.startsWith("content://") || it.isBlank()) song.title else
                            runCatching { java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrDefault(it)
                    },
            artist = tags.artist?.takeIf { it.isNotBlank() },
            album = tags.album?.takeIf { it.isNotBlank() },
            lyrics = tags.lyrics?.takeIf { it.isNotBlank() },
            lyricsFormat = if (tags.lyrics == song.lyrics) song.lyricsFormat else null,
            lyricsSource = tags.lyrics?.takeIf { it.isNotBlank() }?.let {
                com.muses.player.core.model.scrape.LyricsSource.EMBEDDED
            },
            metaSources = null,
            coverUri = tags.coverUri,
            durationMs = tags.durationMs.takeIf { it > 0 } ?: song.durationMs,
            durationSec = tags.durationMs.takeIf { it > 0 }?.div(1000) ?: song.durationSec,
            tagsVersion = SongTags.TAGS_VERSION,
            audioQuality = tags.audioQuality ?: song.audioQuality,
        )
        return resolved.takeIf { it != song }
    }
}

/** SongEntity 便捷入口（安卓 PlaybackService 侧用；桌面直接用 domain 版） */
fun com.muses.player.core.data.db.SongEntity.mergeLazyTags(tags: PlaybackLazyScan.FileTags?): Song? =
    PlaybackLazyScan.merge(toDomain(), tags)
