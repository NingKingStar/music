package com.muses.player.core.media.scanner

import com.muses.player.core.data.db.SongTags
import com.muses.player.core.model.Song
import com.muses.player.core.model.SourceType
import com.muses.player.core.model.scrape.MetaFieldSource
import com.muses.player.core.model.scrape.MetaSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PlaybackLazyScan 编排单测（U26 上收自安卓 PlaybackService 懒扫描逻辑，双端共用）。
 * 覆盖：版本已齐跳过 / 标签缺失跳过 / 正常补齐 / 已刮削字段保护 / 无更新仍抬升版本。
 */
class PlaybackLazyScanTest {

    private fun filenameSong() = Song(
        id = "s1",
        sourceId = "src-1",
        path = "https://nas/music/夜曲.mp3",
        title = "夜曲",
        sourceType = SourceType.WEBDAV,
        tagsVersion = 0,
    )

    private fun fullTags() = PlaybackLazyScan.FileTags(
        title = "夜曲",
        artist = "周杰伦",
        album = "十一月的萧邦",
        durationMs = 211000L,
    )

    @Test
    fun `解析器版本已齐仍刷新文件标签`() {
        val song = filenameSong().copy(tagsVersion = SongTags.TAGS_VERSION)
        assertEquals("周杰伦", PlaybackLazyScan.merge(song, fullTags())?.artist)
    }

    @Test
    fun skips_when_tags_null() {
        assertNull(PlaybackLazyScan.merge(filenameSong(), null))
    }

    @Test
    fun merges_tags_and_bumps_version() {
        val merged = PlaybackLazyScan.merge(filenameSong(), fullTags())!!
        assertEquals("周杰伦", merged.artist)
        assertEquals("十一月的萧邦", merged.album)
        assertEquals(211000L, merged.durationMs)
        assertEquals(211L, merged.durationSec)
        assertEquals(SongTags.TAGS_VERSION, merged.tagsVersion)
    }

    @Test
    fun `旧刮削数据库值不能覆盖文件真实标签`() {
        val song = filenameSong().copy(
            title = "刮削标题",
            artist = "刮削歌手",
            metaSources = MetaSources(title = MetaFieldSource.SCRAPE, artist = MetaFieldSource.SCRAPE),
        )
        val merged = PlaybackLazyScan.merge(song, fullTags())!!
        // 已刮削字段不受文件旧标签覆盖
        assertEquals("夜曲", merged.title)
        assertEquals("周杰伦", merged.artist)
        // 未标记字段仍补齐
        assertEquals("十一月的萧邦", merged.album)
    }

    @Test
    fun bumps_version_even_without_update() {
        // 标签全空：无可补字段，仍抬升版本避免重复探测
        val merged = PlaybackLazyScan.merge(filenameSong(), PlaybackLazyScan.FileTags())!!
        assertEquals("夜曲", merged.title)
        assertEquals(SongTags.TAGS_VERSION, merged.tagsVersion)
    }

    @Test
    fun keeps_existing_when_tag_blank() {
        val song = filenameSong().copy(title = "库标题")
        val merged = PlaybackLazyScan.merge(song, PlaybackLazyScan.FileTags(title = "  "))!!
        assertEquals("夜曲", merged.title)
    }

    @Test
    fun windows_path_falls_back_to_file_name() {
        val song = filenameSong().copy(
            path = "C:\\Users\\me\\Music\\晴天.mp3",
            title = "晴天",
            sourceType = SourceType.LOCAL,
        )
        val merged = PlaybackLazyScan.merge(song, PlaybackLazyScan.FileTags())!!
        assertEquals("晴天", merged.title)
    }

    // ── coverBackfill：封面回填脱离版本门禁 ──

    @Test
    fun backfills_cover_when_version_ready_but_missing() {
        // 版本已齐（merge 必返回 null），库内无封面 → 文件封面回填
        val song = filenameSong().copy(tagsVersion = SongTags.TAGS_VERSION)
        assertEquals("file:///covers/x.jpg", PlaybackLazyScan.merge(song, fullTags().copy(coverUri = "file:///covers/x.jpg"))?.coverUri)
        val backfilled = PlaybackLazyScan.coverBackfill(song, "file:///covers/x.jpg")!!
        assertEquals("file:///covers/x.jpg", backfilled.coverUri)
        // 只动封面：其余字段与版本原样保留
        assertEquals(song.title, backfilled.title)
        assertEquals(SongTags.TAGS_VERSION, backfilled.tagsVersion)
    }

    @Test
    fun skips_backfill_when_cover_present() {
        val song = filenameSong().copy(coverUri = "file:///covers/old.jpg")
        assertNull(PlaybackLazyScan.coverBackfill(song, "file:///covers/new.jpg"))
    }

    @Test
    fun skips_backfill_when_scraped_cover_decision() {
        // 刮削封面决策（选中/显式清空）不受文件图覆盖
        val song = filenameSong().copy(
            tagsVersion = SongTags.TAGS_VERSION,
            metaSources = MetaSources(cover = MetaFieldSource.SCRAPE),
        )
        assertNull(PlaybackLazyScan.coverBackfill(song, "file:///covers/x.jpg"))
    }

    @Test
    fun skips_backfill_when_file_has_no_cover() {
        val song = filenameSong().copy(tagsVersion = SongTags.TAGS_VERSION)
        assertNull(PlaybackLazyScan.coverBackfill(song, null))
        assertNull(PlaybackLazyScan.coverBackfill(song, "  "))
    }
}
