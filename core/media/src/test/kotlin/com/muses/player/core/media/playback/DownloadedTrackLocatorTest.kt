package com.muses.player.core.media.playback

import com.muses.player.core.model.download.DownloadTarget
import com.muses.player.core.model.download.DownloadTargetKind
import com.muses.player.core.model.download.DownloadedSong
import com.muses.player.core.model.online.OnlineTrackRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 已下载优先：命中要准、不可读要回退、索引刷新后新下载立即可见。 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class DownloadedTrackLocatorTest {
    private fun ref(
        json: String = """{"songmid":"m1","name":"曲目"}""",
        platform: String = "wy",
        sourceId: String = "source-a",
        quality: String? = "320k",
    ) = OnlineTrackRef(platform, json, sourceId, quality)

    private fun entry(
        reference: OnlineTrackRef,
        location: String = "/storage/downloads/a.mp3",
        kind: DownloadTargetKind = DownloadTargetKind.DEVICE,
    ) = DownloadedSong(
        taskId = "task-$location",
        track = com.muses.player.core.model.download.DownloadTrack(
            id = "song", sourceId = reference.sourceId, reference = reference.encode(), title = "曲目",
        ),
        location = location,
        target = DownloadTarget(kind = kind),
        quality = reference.quality,
    )

    private fun locator(vararg entries: DownloadedSong, readable: (String) -> Boolean = { true }): DownloadedTrackLocator {
        val index = DownloadedTrackIndex()
        index.update(entries.toList())
        return DownloadedTrackLocator(index, readable)
    }

    @Test
    fun `稳定ID命中并不因封面字段变化失配`() {
        val saved = entry(ref("""{"songmid":"m1","name":"曲目","cover":"https://a/1.jpg"}""", quality = "flac"))
        val playing = ref("""{"name":"曲目","cover":"https://b/2.jpg","songmid":"m1"}""", quality = "320k")
        assertEquals("/storage/downloads/a.mp3", locator(saved).find(playing))
    }

    @Test
    fun `没有稳定ID时字段顺序不影响命中`() {
        val saved = entry(ref("""{"a":1,"b":2}""", platform = "local"))
        val playing = ref("""{"b":2,"a":1}""", platform = "local")
        assertEquals("/storage/downloads/a.mp3", locator(saved).find(playing))
    }

    @Test
    fun `文件缺失或不可读时回退在线`() {
        assertNull(locator(entry(ref()), readable = { false }).find(ref()))
    }

    @Test
    fun `不同平台或音源不混淆`() {
        val saved = entry(ref(platform = "kw", sourceId = "s1"))
        assertNull(locator(saved).find(ref(platform = "wy", sourceId = "s1")))
        assertNull(locator(saved).find(ref(platform = "kw", sourceId = "s2")))
    }

    @Test
    fun `WebDAV记录不参与本地优先`() {
        val saved = entry(ref(), kind = DownloadTargetKind.WEBDAV)
        assertNull(locator(saved).find(ref()))
    }

    @Test
    fun `索引刷新后新下载立即可见且删除后失效`() {
        val index = DownloadedTrackIndex()
        val locator = DownloadedTrackLocator(index) { true }
        assertNull(locator.find(ref()))
        index.update(listOf(entry(ref())))
        assertEquals("/storage/downloads/a.mp3", locator.find(ref()))
        index.update(emptyList())
        assertNull(locator.find(ref()))
    }
}
