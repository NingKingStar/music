package com.muses.player.download

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.muses.player.core.data.repository.*
import com.muses.player.core.download.DownloadQueueStore
import com.muses.player.core.lxsdk.*
import com.muses.player.core.lxsdk.crypto.LxCryptoJvm
import com.muses.player.core.model.*
import com.muses.player.core.model.download.*
import com.muses.player.core.model.online.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.mockwebserver.*
import okio.Buffer
import kotlin.test.*

class DownloadManagerTest {
    @Test fun `删除下载只删除目标文件并保留歌单和喜欢`() = runBlocking {
        Harness().use { h ->
            val file = File(h.root, "song.mp3").apply { writeText("audio") }
            val other = File(h.root, "other.mp3").apply { writeText("other") }
            val task = DownloadTask("delete-1", DownloadTrack.from(h.song()), target = h.target,
                status = DownloadStatus.COMPLETED, savedLocation = file.absolutePath)
            h.store.update { listOf(task) }
            val entry = h.store.downloadedSongs.first().single()
            val favorites = FavoriteSongsStore(h.dataStore, h.songs, h.scope)
            val playlists = UserPlaylistStore(h.dataStore)
            favorites.setLiked(entry.toSong(), true)
            val playlistId = playlists.create("测试", entry.toSong())
            assertTrue(h.manager.deleteSaved(entry))
            assertFalse(file.exists())
            assertTrue(other.exists())
            assertTrue(h.store.downloadedSongs.first().isEmpty())
            assertTrue(h.store.load().isEmpty())
            assertEquals(h.song().id, playlists.snapshot().single { it.id == playlistId }.tracks.single().id)
            assertEquals(h.song().id, favorites.snapshot().selections.single().track.id)
        }
    }

    @Test fun `远端删除失败保留记录失效记录须单独确认`() = runBlocking {
        Harness().use { h ->
            val target = DownloadTarget(DownloadTargetKind.WEBDAV, h.source.id, "/Music", "网盘")
            val task = DownloadTask("delete-2", DownloadTrack.from(h.song()), target = target,
                status = DownloadStatus.COMPLETED, savedLocation = h.server.url("/dav/Music/song.mp3").toString())
            h.store.update { listOf(task) }
            val entry = h.store.downloadedSongs.first().single()
            h.server.enqueue(MockResponse().setResponseCode(403))
            assertTrue(runCatching { h.manager.deleteSaved(entry) }.isFailure)
            assertEquals(1, h.store.downloadedSongs.first().size)
            h.server.enqueue(MockResponse().setResponseCode(404))
            assertFalse(h.manager.deleteSaved(entry))
            assertEquals(1, h.store.downloadedSongs.first().size)
            h.server.enqueue(MockResponse().setResponseCode(404))
            assertTrue(h.manager.deleteSaved(entry, clearMissing = true))
            assertTrue(h.store.downloadedSongs.first().isEmpty())
            assertEquals("DELETE", h.server.takeRequest().method)
            assertEquals("DELETE", h.server.takeRequest().method)
            assertEquals("HEAD", h.server.takeRequest().method)
        }
    }

    @Test fun `远端删除仅发送一次指定文件请求`() = runBlocking {
        Harness().use { h ->
            val target = DownloadTarget(DownloadTargetKind.WEBDAV, h.source.id, "/Music", "网盘")
            val task = DownloadTask("delete-3", DownloadTrack.from(h.song()), target = target,
                status = DownloadStatus.COMPLETED, savedLocation = h.server.url("/dav/Music/song.mp3").toString())
            h.store.update { listOf(task) }
            val entry = h.store.downloadedSongs.first().single()
            h.server.enqueue(MockResponse().setResponseCode(204))
            assertTrue(h.manager.deleteSaved(entry))
            val request = h.server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/dav/Music/song.mp3", request.path)
            assertEquals(1, h.server.requestCount)
            assertTrue(h.store.downloadedSongs.first().isEmpty())
        }
    }

    @Test fun `远端下载记录不能指向目录删除`() = runBlocking {
        Harness().use { h ->
            val target = DownloadTarget(DownloadTargetKind.WEBDAV, h.source.id, "/Music", "网盘")
            h.store.update { listOf(DownloadTask("delete-dir", DownloadTrack.from(h.song()), target = target,
                status = DownloadStatus.COMPLETED, savedLocation = h.server.url("/dav/Music/").toString())) }
            val entry = h.store.downloadedSongs.first().single()
            assertTrue(runCatching { h.manager.deleteSaved(entry) }.isFailure)
            assertEquals(0, h.server.requestCount)
            assertEquals(1, h.store.downloadedSongs.first().size)
        }
    }
    @Test fun `点击下载立即开始且沿用用户保存位置`() = runBlocking {
        Harness().use { h ->
            h.store.setDefaultTarget(h.target)
            h.server.enqueue(MockResponse().setBody(Buffer().write(wav(16044))))
            h.manager.enqueue(h.song(), startImmediately = true).join()
            val completed = h.await(DownloadStatus.COMPLETED)
            assertEquals(h.target, completed.target)
            assertTrue(completed.downloadedBytes > 0)
            val saved = h.manager.downloadedSongs.first { it.isNotEmpty() }
            assertEquals(completed.savedLocation, saved.single().location)
            h.manager.remove(completed.id).join()
            assertTrue(h.store.load().isEmpty())
            assertEquals(saved.single().location, h.store.downloadedSongs.first().single().location)
        }
    }

    @Test fun `再次点击下载复用待下载任务而非重复排队`() = runBlocking {
        Harness().use { h ->
            h.store.setDefaultTarget(h.target)
            h.server.enqueue(MockResponse().setBody(Buffer().write(wav(16044))))
            h.manager.enqueue(h.song()).join()
            val original = h.store.load().single().id
            h.manager.enqueue(h.song(), startImmediately = true).join()
            assertEquals(original, h.await(DownloadStatus.COMPLETED).id)
            assertEquals(1, h.store.load().size)
        }
    }

    @Test fun `不断变化的试听签名地址不会让下载无限重试`() = runBlocking {
        for (crossPlatform in listOf(false, true)) {
            Harness(useDefaultResolver = true,
                candidateProvider = if (crossPlatform) OnlineTrackCandidateProvider { _, _ -> emptyList() } else null).use { h ->
                val url = h.server.url("/trial").toString()
                h.lx.register("变化地址", """
                    let serial = 0;
                    lx.on('request', () => Promise.resolve('$url?serial=' + (++serial)));
                    lx.send('inited', {sources: {kw: {actions: ['musicUrl'], qualitys: ['128k']}}});
                """.trimIndent())
                h.server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) = MockResponse()
                        .setBody(Buffer().write(wav(44 + 8000 * 2 * 60, 8000)))
                }
                h.settings.setDownloadPreferredQuality("128k")
                h.manager.enqueue(h.song().copy(durationMs = 120_000, durationSec = 120)).join()
                h.manager.start(setOf(h.store.load().single().id), h.target).join()
                val failed = h.await(DownloadStatus.FAILED)
                assertEquals(3, h.server.requestCount)
                assertTrue(failed.error.orEmpty().contains("试听或截短片段"))
                assertTrue(h.added.isEmpty())
            }
        }
    }
    @Test fun `完整文件核验失败后排除试听地址并尝试同平台同档其他脚本`() = runBlocking {
        Harness(useDefaultResolver = true).use { h ->
            for ((name, delay) in listOf("trial" to 0, "full" to 200)) {
                val url = h.server.url("/$name").toString()
                h.lx.register(name, """
                    lx.on('request', () => new Promise(resolve => setTimeout(() => resolve('$url'), $delay)));
                    lx.send('inited', {sources: {kw: {actions: ['musicUrl'], qualitys: ['320k']}}});
                """.trimIndent())
            }
            h.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse()
                    .setBody(Buffer().write(wav(44 + 8000 * 2 * if (request.path == "/trial") 60 else 120, 8000)))
            }
            h.manager.enqueue(h.song().copy(durationMs = 120_000, durationSec = 120)).join()
            h.manager.start(setOf(h.store.load().single().id), h.target).join()
            val done = h.await(DownloadStatus.COMPLETED)
            assertEquals(2, h.server.requestCount)
            assertEquals(120_000L, com.muses.player.core.media.scanner.DownloadAudioQuality.durationMs(File(done.savedLocation!!)))
            assertEquals("320k", done.requestedQuality)
        }
    }
    @Test fun `下载完成核验试听片段后同档换源且只保存完整歌曲`() = runBlocking {
        Harness(candidateProvider = OnlineTrackCandidateProvider { ref, _ -> listOf(ref.copy(platform = "tx")) }).use { h ->
            h.lx.register("测试多平台", """
                lx.send('inited', {sources: {tx: {actions: ['musicUrl'], qualitys: ['320k']}}});
            """.trimIndent())
            val requested = mutableListOf<Pair<String, LxQuality>>()
            h.resolveTrackOverride = { ref, quality ->
                requested += ref.platform to quality
                LxMusicUrl(h.server.url("/${ref.platform}/audio").toString(), quality)
            }
            h.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                    .setHeader("ETag", "\"test\"")
                    .setBody(Buffer().write(wav(44 + 8000 * 2 * if (request.path!!.startsWith("/kw/")) 60 else 120, 8000)))
            }
            h.manager.enqueue(h.song().copy(durationMs = 120_000, durationSec = 120)).join()
            h.manager.start(setOf(h.store.load().single().id), h.target).join()
            val done = h.await(DownloadStatus.COMPLETED)
            assertEquals(listOf("kw" to LxQuality.Q_320K, "tx" to LxQuality.Q_320K), requested)
            assertEquals(120_000L, com.muses.player.core.media.scanner.DownloadAudioQuality.durationMs(File(done.savedLocation!!)))
            assertEquals(1, h.added.size)
            assertTrue(h.root.walkTopDown().none { it.name == "audio.part" || it.name.startsWith("duration-probe.") })
        }
    }

    @Test fun `全部是试听片段时失败并且不会保存上传或入库`() = runBlocking {
        for (crossPlatform in listOf(false, true)) {
            Harness(candidateProvider = if (crossPlatform) OnlineTrackCandidateProvider { _, _ -> emptyList() } else null).use { h ->
                h.resolveTrackOverride = { _, quality -> LxMusicUrl(h.server.url("/audio/${quality.key}").toString(), quality) }
                h.server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) = MockResponse()
                        .setBody(Buffer().write(wav(44 + 8000 * 2 * 60, 8000)))
                }
                // 曲目对象缺少时长时，仍使用歌曲引用内的原曲时长核验。
                val song = h.song().copy(durationMs = 0, durationSec = 0,
                    path = OnlineTrackRef("kw", """{"interval":"02:00"}""", "online").encode())
                h.manager.enqueue(song).join()
                h.manager.start(setOf(h.store.load().single().id), h.target).join()
                val failed = h.await(DownloadStatus.FAILED)
                assertTrue(failed.error.orEmpty().contains("试听或截短片段"))
                assertNull(failed.savedLocation)
                assertNull(failed.localAudioPath)
                assertTrue(h.added.isEmpty())
                assertTrue(!File(h.target.directory).exists() || File(h.target.directory).listFiles()!!.isEmpty())
                assertTrue(h.root.walkTopDown().none { it.name == "audio.part" || it.name == "audio.fallback" || it.name.startsWith("duration-probe.") })
            }
        }
    }

    @Test fun `其他平台有无损时原平台不可用或假无损均不提前降档`() = runBlocking {
        for (mode in listOf("解析失败", "地址失效", "假无损")) {
            val metadataPlatforms = mutableListOf<String>()
            val resolver = object : OnlineTrackMetadataResolver by NoOpOnlineTrackMetadataResolver {
                override suspend fun resolveCover(ref: OnlineTrackRef): String? {
                    metadataPlatforms += ref.platform
                    return null
                }
            }
            Harness(candidateProvider = OnlineTrackCandidateProvider { ref, platforms ->
                assertTrue("tx" in platforms)
                listOf(ref.copy(platform = "tx", musicInfoJson = "{\"id\":2}"))
            }, metadataResolver = resolver).use { h ->
                h.lx.register("测试多平台", """
                    const { EVENT_NAMES, send } = globalThis.lx
                    send(EVENT_NAMES.inited, { sources: { tx: {
                        name: '测试', type: 'music', actions: ['musicUrl'], qualitys: ['flac','320k']
                    } } })
                """.trimIndent())
                val requests = mutableListOf<Pair<String, LxQuality>>()
                h.resolveTrackOverride = { ref, quality ->
                    requests += ref.platform to quality
                    if (ref.platform == "kw" && mode == "解析失败") throw LxResolveException("无损不可用")
                    LxMusicUrl(h.server.url("/${ref.platform}/${quality.key}").toString(), quality)
                }
                val flac = javaClass.getResourceAsStream("/audio/sine.flac")!!.use { it.readBytes() }
                val mp3 = javaClass.getResourceAsStream("/audio/sine.mp3")!!.use { it.readBytes() }
                h.server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when {
                        request.path!!.startsWith("/tx/") -> {
                            assertNull(request.getHeader("Range"))
                            assertNull(request.getHeader("If-Range"))
                            MockResponse().setBody(Buffer().write(flac))
                        }
                        mode == "地址失效" -> MockResponse().setResponseCode(403)
                        else -> MockResponse().setHeader("ETag", "\"123\"").setBody(Buffer().write(mp3))
                    }
                }
                h.settings.setDownloadPreferredQuality("flac")
                h.manager.enqueue(h.song()).join()
                h.manager.start(setOf(h.store.load().single().id), h.target).join()
                val done = h.await(DownloadStatus.COMPLETED)
                assertEquals(listOf("kw" to LxQuality.FLAC, "tx" to LxQuality.FLAC), requests)
                assertTrue(done.actualQualityLabel!!.startsWith("FLAC · 无损"))
                assertNull(done.qualityWarning)
                assertEquals(listOf("tx"), metadataPlatforms)
                assertEquals("flac", File(done.savedLocation!!).extension)
            }
        }
    }

    @Test fun `全部平台同档失败后才降档且不会升档`() = runBlocking {
        Harness(candidateProvider = OnlineTrackCandidateProvider { ref, _ -> listOf(ref.copy(platform = "tx")) }).use { h ->
            h.lx.register("测试多平台", """
                const { EVENT_NAMES, send } = globalThis.lx
                send(EVENT_NAMES.inited, { sources: { tx: {
                    name: '测试', type: 'music', actions: ['musicUrl'], qualitys: ['flac','320k']
                } } })
            """.trimIndent())
            val requests = mutableListOf<Pair<String, LxQuality>>()
            h.resolveTrackOverride = { ref, quality ->
                requests += ref.platform to quality
                if (quality == LxQuality.FLAC) throw LxResolveException("无损不可用")
                LxMusicUrl(h.server.url("/audio").toString(), quality)
            }
            h.settings.setDownloadPreferredQuality("flac")
            h.server.enqueue(MockResponse().setBody(Buffer().write(wav())))
            h.manager.enqueue(h.song()).join()
            h.manager.start(setOf(h.store.load().single().id), h.target).join()
            h.await(DownloadStatus.COMPLETED)
            assertEquals(listOf("kw" to LxQuality.FLAC, "tx" to LxQuality.FLAC, "kw" to LxQuality.Q_320K), requests)
        }
    }

    @Test fun `各平台实际文件均未达标时保留可用音频并显示真实音质`() = runBlocking {
        Harness(candidateProvider = OnlineTrackCandidateProvider { _, _ -> emptyList() }).use { h ->
            h.settings.setDownloadPreferredQuality("flac")
            h.resolveTrackOverride = { _, quality -> LxMusicUrl(h.server.url("/audio").toString(), quality) }
            val bytes = javaClass.getResourceAsStream("/audio/sine.mp3")!!.use { it.readBytes() }
            h.server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            h.manager.enqueue(h.song()).join()
            h.manager.start(setOf(h.store.load().single().id), h.target).join()
            val done = h.await(DownloadStatus.COMPLETED)
            assertEquals(1, h.server.requestCount)
            assertTrue(done.actualQualityLabel!!.startsWith("MP3 · 有损"))
            assertNotNull(done.qualityWarning)
            assertEquals("flac", done.requestedQuality)
            assertTrue(h.root.walkTopDown().none { it.name == "audio.fallback" || it.name.startsWith("quality-probe.") })
        }
    }

    @Test fun losslessRequestCannotHideActualMp3AndResultSurvivesReload() = runBlocking {
        Harness().use { h ->
            h.settings.setDownloadPreferredQuality("flac")
            h.resolveOverride = { LxMusicUrl(h.server.url("/audio").toString(), LxQuality.FLAC) }
            val bytes = javaClass.getResourceAsStream("/audio/sine.mp3")!!.use { it.readBytes() }
            h.server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            h.manager.enqueue(h.song()).join()
            h.manager.start(setOf(h.store.load().single().id), h.target).join()
            val completed = h.await(DownloadStatus.COMPLETED)
            assertEquals("flac", completed.requestedQuality)
            assertTrue(completed.actualQualityLabel!!.startsWith("MP3 · 有损"))
            assertTrue(completed.qualityWarning!!.contains("音质未达到所选「无损」"))
            assertTrue(completed.qualityWarning in completed.warnings)
            assertEquals("mp3", File(completed.savedLocation!!).extension)
            val restored = DownloadQueueStore(h.dataStore).load().single()
            assertEquals(completed.actualQualityLabel, restored.actualQualityLabel)
            assertEquals(completed.qualityWarning, restored.qualityWarning)
        }
    }

    @Test fun realFlacIsVerifiedWithoutLosslessWarning() = runBlocking {
        Harness().use { h ->
            h.settings.setDownloadPreferredQuality("flac")
            h.resolveOverride = { LxMusicUrl(h.server.url("/audio").toString(), LxQuality.FLAC) }
            val bytes = javaClass.getResourceAsStream("/audio/sine.flac")!!.use { it.readBytes() }
            h.server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            h.manager.enqueue(h.song()).join()
            h.manager.start(setOf(h.store.load().single().id), h.target).join()
            val completed = h.await(DownloadStatus.COMPLETED)
            assertTrue(completed.actualQualityLabel!!.startsWith("FLAC · 无损"))
            assertNull(completed.qualityWarning)
        }
    }

    @Test fun qualityReportDoesNotTreatUnknownOrSixteenBitAsRequestedQuality() {
        assertTrue(downloadQualityReport(LxQuality.FLAC, "flac", null).label.contains("未核实"))
        assertNotNull(downloadQualityReport(LxQuality.FLAC, "flac", null).warning)
        val sixteenBit = com.muses.player.core.media.scanner.DownloadAudioQuality(true, 16, 44100, 900, "flac")
        assertNotNull(downloadQualityReport(LxQuality.FLAC_24BIT, "flac", sixteenBit).warning)
        assertNull(downloadQualityReport(LxQuality.FLAC, "flac", sixteenBit).warning)
    }

    @Test fun masterQualityReportRejectsLossyButAcceptsLosslessWithoutRequiring192k() {
        val lossy320 = com.muses.player.core.media.scanner.DownloadAudioQuality(false, 0, 44100, 320_000, "mp3")
        val lossyWarning = downloadQualityReport(LxQuality.MASTER, "mp3", lossy320)
        assertNotNull(lossyWarning.warning)
        assertTrue(lossyWarning.warning!!.contains("Hi-Res"), lossyWarning.warning)

        val flac24192 = com.muses.player.core.media.scanner.DownloadAudioQuality(true, 24, 192_000, 4608, "flac")
        val high = downloadQualityReport(LxQuality.MASTER, "flac", flac24192)
        assertNull(high.warning)
        assertTrue(high.label.contains("24 bit"), high.label)
        assertTrue(high.label.contains("192000"), high.label)
        assertFalse(high.label.contains("母带"), high.label)

        // Hi-Res 统一档位必须核实位深；16bit 无损应提示回退。
        val flac16441 = com.muses.player.core.media.scanner.DownloadAudioQuality(true, 16, 44_100, 900, "flac")
        assertNotNull(downloadQualityReport(LxQuality.MASTER, "flac", flac16441).warning)

        // 采样率未知/异常低的无损也不能直接当达标。
        val unknownRate = com.muses.player.core.media.scanner.DownloadAudioQuality(true, 24, 0, 0, "flac")
        assertNotNull(downloadQualityReport(LxQuality.MASTER, "flac", unknownRate).warning)
    }

    @Test fun favoriteMarksRemainIndependentOfLibraryAcrossPlatforms() = runBlocking {
        Harness().use { h ->
            val online = h.song()
            val library = kotlinx.coroutines.flow.MutableStateFlow(emptyList<Song>())
            val repository = object : SongRepository by h.songs {
                override fun observeSongs() = library
            }
            val favorites = com.muses.player.core.data.repository.FavoriteSongsStore(h.dataStore, repository, h.scope)
            assertTrue(favorites.setLiked(online, false))
            assertFalse(favorites.snapshot().isLiked(online))
            library.value = listOf(online.copy(
                id = "local:1", path = "/music/test.mp3", sourceType = SourceType.LOCAL))
            assertFalse(favorites.snapshot().isLiked(online))
            assertTrue(favorites.snapshot().missingOnlineSongs.isEmpty())
            assertTrue(favorites.setLiked(online, false))
            val restored = com.muses.player.core.data.repository.FavoriteSongsStore(h.dataStore, repository, h.scope)
            assertFalse(restored.snapshot().isLiked(online))
            favorites.setLiked(online, true)
            assertTrue(restored.snapshot().isLiked(online.copy(id = "online:wy:2")))
            library.value = emptyList()
            assertEquals(online.path, restored.snapshot().missingOnlineSongs.single().path)
            favorites.setLiked(online.copy(id = "online:wy:2"), true)
            assertEquals(1, restored.snapshot().missingOnlineSongs.size)
            assertFalse(restored.snapshot().isLiked(online.copy(id = "online:wy:3", title = "测试歌曲 (Live)")))
            assertFalse(restored.snapshot().isLiked(online.copy(id = "online:wy:4", artist = "其他歌手")))
            assertTrue(favorites.setLiked(online, false))
            assertFalse(restored.snapshot().isLiked(online))
            assertTrue(restored.snapshot().missingOnlineSongs.isEmpty())
        }
    }

    @Test fun favoriteDownloadExcludesExistingLibraryAndReusesCrossPlatformQueueTask() = runBlocking {
        Harness().use { h ->
            val existing = h.song().copy(id = "online:wy:existing", title = "已入库歌曲")
            h.librarySongs += existing.copy(id = "local:existing", path = "/music/old.mp3", sourceType = SourceType.LOCAL)
            val favorites = com.muses.player.core.data.repository.FavoriteSongsStore(h.dataStore, h.songs, h.scope)
            favorites.setLiked(existing, true)
            favorites.setLiked(h.song().copy(id = "online:wy:liked"), true)
            h.manager.enqueue(h.song()).join()
            val queuedId = h.store.load().single().id
            h.store.setDefaultTarget(h.target)
            h.server.enqueue(MockResponse().setBody(Buffer().write(wav())))
            h.manager.downloadFavorites(favorites).join()
            val done = h.await(DownloadStatus.COMPLETED)
            assertEquals(queuedId, done.id)
            assertEquals(1, h.store.load().size)
            assertEquals(1, h.resolves)
            assertEquals(1, h.server.requestCount)
            assertNull(h.manager.libraryDuplicatePrompt.value)
            assertFalse(h.manager.preparingFavoriteDownloads.value)
        }
    }

    @Test fun favoriteDownloadCreatesOnlyMissingTasksAndRepeatedClickDoesNotDuplicateDownload() = runBlocking {
        Harness().use { h ->
            val favorites = com.muses.player.core.data.repository.FavoriteSongsStore(h.dataStore, h.songs, h.scope)
            favorites.setLiked(h.song(), true)
            favorites.setLiked(h.song().copy(id = "online:wy:2"), true)
            h.store.setDefaultTarget(h.target)
            h.server.enqueue(MockResponse().setBody(Buffer().write(wav())).setBodyDelay(250, TimeUnit.MILLISECONDS))
            h.manager.downloadFavorites(favorites).join()
            withTimeout(5_000) { h.manager.tasks.first { it.any { task -> task.status == DownloadStatus.DOWNLOADING } } }
            h.manager.downloadFavorites(favorites).join()
            val completed = h.await(DownloadStatus.COMPLETED)
            assertEquals(h.target, completed.target)
            assertEquals(1, h.store.load().size)
            assertEquals(1, h.resolves)
            assertEquals(1, h.server.requestCount)
        }
    }

    /** [resolveTimeoutMs] 与生产默认一致；「引擎卡死」用例传更小的值来压缩等待 */
    private class Harness(
        private val resolveTimeoutMs: Long = 120_000L,
        private val client: okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder().build(),
        private val uploadConfirmDelayMs: Long = 30_000L,
        private val uploadCommitTimeoutMs: Long = 120_000L,
        private val tagPort: com.muses.player.core.scrape.ports.TagPort = com.muses.player.core.scrape.ports.JaudiotaggerTagPort,
        private val candidateProvider: OnlineTrackCandidateProvider? = null,
        private val metadataResolver: OnlineTrackMetadataResolver = NoOpOnlineTrackMetadataResolver,
        private val useDefaultResolver: Boolean = false,
    ) : java.io.Closeable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val root = Files.createTempDirectory("muses-download-test").toFile()
        val server = MockWebServer().apply { start() }
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(root, "queue.preferences_pb") }
        val store = DownloadQueueStore(dataStore)
        val settings = DataStoreSettingsRepository(dataStore)
        val added = mutableListOf<Song>()
        val librarySongs = mutableListOf<Song>()
        val target = DownloadTarget(DownloadTargetKind.LOCAL, "local", File(root, "target").absolutePath, "测试目录")
        val source = Source("webdav", "测试 WebDAV", SourceType.WEBDAV, server.url("/dav/").toString(), "/Music", createdAt = 0, updatedAt = 0)
        val localSource = Source("local", "新增本地音源", SourceType.LOCAL, null, target.directory, createdAt = 0, updatedAt = 0)
        /** 保存位置失效判定依赖音源列表，测试里可增删后再 [DownloadManager.refresh] */
        val sourceList = mutableListOf(source, localSource)
        val sources = object : SourceRepository {
            override fun observeSources() = flow { emit(sourceList.toList()) }
            override suspend fun getSource(id: String) = sourceList.firstOrNull { it.id == id }
            override suspend fun upsert(source: Source) = Unit
            override suspend fun deleteById(id: String) = Unit
        }
        val songs = object : SongRepository {
            override fun observeSongs() = flowOf(librarySongs.toList())
            override suspend fun replaceSourceSongs(sourceId: String, songs: List<Song>): ScanMergeResult = error("不能全源替换")
            override suspend fun deleteSourceSongs(sourceId: String) = error("不能删除曲库")
            override suspend fun rebuildDerivedIndexes() = Unit
            override suspend fun getSong(id: String): Song? = null
            override suspend fun upsert(song: Song) { added += song }
        }
        val credentials = object : CredentialsRepository {
            override suspend fun savePassword(sourceId: String, password: String) = Unit
            override suspend fun getPassword(sourceId: String) = "测试密码"
            override suspend fun clearPassword(sourceId: String) = Unit
        }
        var resolves = 0
        val lx = LxScriptRepository(LxCryptoJvm())
        /** 覆盖直链解析行为（默认恒定成功），用于模拟音源后端超时/拒绝 */
        var resolveOverride: (suspend (LxQuality) -> LxMusicUrl)? = null
        var resolveTrackOverride: (suspend (OnlineTrackRef, LxQuality) -> LxMusicUrl)? = null
        val manager = DownloadManager(store, settings, sources, credentials, songs, lx,
            metadataResolver, null, object : DownloadStorage by createDownloadStorage(settings) {
                override val cacheDirectory = File(root, "cache")
                override val stagingDirectory = File(root, "cache")
            }, resolveTimeoutMs = resolveTimeoutMs, scope = scope, client = client, resolveAudio = if (useDefaultResolver) null else { ref, quality ->
                resolves++
                resolveTrackOverride?.invoke(ref, quality) ?: resolveOverride?.invoke(quality)
                    ?: LxMusicUrl(server.url("/audio").toString(), LxQuality.Q_128K)
            }, uploadConfirmDelayMs = uploadConfirmDelayMs, uploadCommitTimeoutMs = uploadCommitTimeoutMs,
                tagPort = tagPort, candidateProvider = candidateProvider)
        fun song(id: String = "online:kw:1") = Song(id, "online", OnlineTrackRef("kw", "{}", "online").encode(),
            "测试歌曲", "测试歌手", "测试专辑", 1000, 1, lyrics = "[00:00.00]测试歌词", sourceType = SourceType.ONLINE)
        suspend fun await(status: DownloadStatus): DownloadTask = withTimeout(15_000) {
            manager.tasks.first { list -> list.any { it.status == status } }.first { it.status == status }
        }
        override fun close() { scope.cancel(); server.close(); root.deleteRecursively() }
    }


    @Test fun playlistBatchReusesQueueAndAvoidsDownloadedDuplicates() = runBlocking {
        Harness().use { h ->
            val first = h.song()
            val second = h.song("online:kw:2").copy(title = "Other song")
            val local = first.copy(id = "local:1", path = "/local/track.mp3", sourceType = SourceType.LOCAL)
            h.manager.enqueue(first).join()
            val queuedId = h.store.load().single().id
            h.store.setDefaultTarget(h.target)
            repeat(2) { h.server.enqueue(MockResponse().setBody(Buffer().write(wav()))) }
            h.manager.downloadPlaylist(listOf(first, second, first, local), "flac").join()
            withTimeout(15_000) { h.manager.tasks.first { it.count { task -> task.status == DownloadStatus.COMPLETED } == 2 } }
            val done = h.store.load()
            assertEquals(2, done.size)
            assertEquals(queuedId, done.first { it.track.id == first.id }.id)
            assertEquals(setOf("flac"), done.map { it.quality }.toSet())
            assertEquals(2, h.resolves)
            h.manager.downloadPlaylist(listOf(first, second), "320k").join()
            assertEquals(2, h.store.load().size)
            assertEquals(2, h.resolves)
            assertFalse(h.manager.preparingPlaylistDownloads.value)
        }
    }

    @Test fun enqueueDoesNotResolveOrDownloadAndDeduplicates() = runBlocking {
        Harness().use { h ->
            h.manager.enqueue(h.song()).join(); h.manager.enqueue(h.song()).join()
            assertEquals(1, h.store.load().size)
            assertEquals(DownloadStatus.WAITING, h.store.load().single().status)
            assertEquals(0, h.resolves); assertEquals(0, h.server.requestCount)
            h.settings.setDownloadPreferredQuality("flac")
            h.manager.enqueue(h.song("online:kw:2")).join()
            assertEquals(listOf("320k", "flac"), h.store.load().map { it.quality })
        }
    }

    @Test fun existingMp3IsQueuedWithoutPromptButConfirmedBeforeDownloadingFlac() = runBlocking {
        Harness().use { h ->
            h.librarySongs += h.song().copy(id = "local:mp3", path = "/Music/song.mp3", sourceId = "local", sourceType = SourceType.LOCAL, audioQuality = "HQ")
            h.settings.setDownloadPreferredQuality("flac")
            h.manager.enqueue(h.song()).join()
            val task = h.store.load().single()
            assertNull(h.manager.libraryDuplicatePrompt.value)
            assertEquals(DownloadStatus.WAITING, task.status)
            h.manager.start(setOf(task.id), h.target).join()
            val prompt = withTimeout(5_000) { h.manager.libraryDuplicatePrompt.first { it != null }!! }
            assertEquals(0, h.resolves)
            assertEquals(0, h.server.requestCount)
            assertTrue(prompt.message(mapOf("local" to "本地音源")).contains("MP3"))
            assertTrue(prompt.message(emptyMap()).contains("FLAC"))
            h.manager.respondToLibraryDuplicate(prompt.id, false)
            h.await(DownloadStatus.PAUSED)
            assertEquals(1, h.store.load().size)
            assertNull(h.manager.libraryDuplicatePrompt.value)

            h.server.enqueue(MockResponse().setBody(Buffer().write(wav())))
            h.manager.start(setOf(task.id), h.target).join()
            val confirmed = withTimeout(5_000) { h.manager.libraryDuplicatePrompt.first { it != null }!! }
            h.settings.setDownloadPreferredQuality("128k")
            assertEquals("flac", confirmed.quality)
            h.manager.respondToLibraryDuplicate(confirmed.id, true)
            h.await(DownloadStatus.COMPLETED)
            assertEquals(1, h.resolves)
        }
    }

    @Test fun libraryDuplicateMatchingPreservesVersionsAndArtistsAcrossSources() {
        val incoming = Song("online:1", "online", "", "歌曲（Live）", "甲 / 乙", sourceType = SourceType.ONLINE)
        val same = incoming.copy(id = "webdav:1", path = "https://example.com/song.mp3", artist = "乙、甲", sourceType = SourceType.WEBDAV)
        assertEquals(listOf(same), matchingLibrarySongs(incoming, listOf(same,
            same.copy(id = "other", artist = "丙"), same.copy(id = "studio", title = "歌曲"), incoming)))
        assertTrue(matchingLibrarySongs(incoming.copy(artist = null), listOf(same)).isEmpty())
    }

    @Test fun cancelDownloadKeepsQueuedTaskAndOldLibraryFile() = runBlocking {
        Harness().use { h ->
            val oldFile = File(h.root, "old.mp3").apply { writeText("原音频") }
            h.manager.enqueue(h.song()).join()
            val task = h.store.load().single()
            // 入队之后曲库新增同曲，开始下载时仍能查到。
            h.librarySongs += h.song().copy(id = "local:old", path = oldFile.absolutePath, sourceType = SourceType.LOCAL)
            h.manager.start(setOf(task.id), h.target).join()
            val prompt = withTimeout(5_000) { h.manager.libraryDuplicatePrompt.first { it != null }!! }
            h.manager.respondToLibraryDuplicate(prompt.id, false)
            h.await(DownloadStatus.PAUSED)
            assertEquals(task.id, h.store.load().single().id)
            assertEquals("原音频", oldFile.readText())
            assertEquals(0, h.resolves)
        }
    }

    @Test fun startEmbedsMetadataAndSavesOnlyAudio() = runBlocking {
        Harness().use { h ->
            val coverBytes = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aWZ0AAAAASUVORK5CYII=")
            val cover = File(h.root, "cover.png").apply { writeBytes(coverBytes) }
            h.server.enqueue(MockResponse().setBody(Buffer().write(wav())))
            h.manager.enqueue(h.song().copy(coverUri = cover.toPath().toUri().toString())).join()
            h.manager.start(setOf(h.store.load().single().id), h.target).join()
            val completed = h.await(DownloadStatus.COMPLETED)
            assertEquals("128k", completed.requestedQuality)
            val directory = File(h.target.directory)
            val audio = directory.listFiles().orEmpty().single()
            assertEquals("wav", audio.extension)
            assertEquals("测试歌手 - 测试歌曲.wav", audio.name)
            val tags = com.muses.player.core.scrape.ports.JaudiotaggerTagPort.readTags(audio)!!
            assertEquals("测试歌曲", tags.title)
            assertEquals("测试歌手", tags.artist)
            assertEquals("测试专辑", tags.album)
            assertTrue(tags.lyrics!!.contains("测试歌词"))
            assertContentEquals(coverBytes, tags.cover)
            assertEquals(1, h.added.size)
            assertEquals(com.muses.player.core.media.scanner.WebDavLibraryScanner.stableSongId("local", completed.savedLocation!!), h.added.single().id)
            assertEquals(1, h.server.requestCount)
        }
    }

    @Test fun newWaitingSongIsNotStartedByExistingBatch() = runBlocking {
        Harness().use { h ->
            h.server.enqueue(MockResponse().setBody(Buffer().write(wav())).setBodyDelay(500, TimeUnit.MILLISECONDS))
            h.manager.enqueue(h.song()).join()
            val first = h.store.load().single()
            h.manager.start(setOf(first.id), h.target).join()
            h.manager.enqueue(h.song("online:kw:2")).join()
            h.await(DownloadStatus.COMPLETED)
            assertEquals(DownloadStatus.WAITING, h.store.load().last().status)
            assertEquals(1, h.resolves)
        }
    }

    @Test fun htmlResponseFailsWithoutSavingAndCanRetry() = runBlocking {
        Harness().use { h ->
            h.server.enqueue(MockResponse().setBody("<html>音源拒绝</html>"))
            h.manager.enqueue(h.song()).join()
            val id = h.store.load().single().id
            h.manager.start(setOf(id), h.target).join()
            h.await(DownloadStatus.FAILED)
            assertFalse(File(h.target.directory).exists()); assertTrue(h.added.isEmpty())
            h.server.enqueue(MockResponse().setBody(Buffer().write(wav())))
            h.manager.start(setOf(id), h.target).join()
            assertEquals(DownloadStatus.COMPLETED, h.await(DownloadStatus.COMPLETED).status)
        }
    }

    @Test fun pauseCancelsActiveNetworkAndContinueUsesValidatedRange() = runBlocking {
        Harness().use { h ->
            val payload = wav(512 * 1024)
            h.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val offset = request.getHeader("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
                    return if (offset == null) MockResponse().setHeader("ETag", "\"test-audio\"")
                        .setBody(Buffer().write(payload)).throttleBody(16384, 100, TimeUnit.MILLISECONDS)
                    else MockResponse().setResponseCode(206).setHeader("ETag", "\"test-audio\"")
                        .setHeader("Content-Range", "bytes $offset-${payload.lastIndex}/${payload.size}")
                        .setBody(Buffer().write(payload, offset, payload.size - offset))
                }
            }
            h.manager.enqueue(h.song()).join(); val id = h.store.load().single().id
            h.manager.start(setOf(id), h.target).join()
            withTimeout(5000) { h.manager.tasks.first { it.any { t -> t.downloadedBytes > 0 } } }
            h.manager.pause(id).join()
            assertEquals(DownloadStatus.PAUSED, h.store.load().single().status)
            assertFalse(File(h.target.directory).exists())
            h.manager.start(setOf(id), h.target).join(); h.await(DownloadStatus.COMPLETED)
            h.server.takeRequest(2, TimeUnit.SECONDS)
            val resumed = h.server.takeRequest(2, TimeUnit.SECONDS)!!
            assertNotNull(resumed.getHeader("Range")); assertEquals("\"test-audio\"", resumed.getHeader("If-Range"))
        }
    }

    @Test fun restartRecoveryNeverStartsQueueAndPreservesTarget() = runBlocking {
        Harness().use { h ->
            h.manager.enqueue(h.song()).join()
            h.store.setDefaultTarget(h.target)
            h.store.update { it.map { t -> t.copy(status = DownloadStatus.DOWNLOADING, downloadedBytes = 42) } }
            val recreated = DownloadQueueStore(h.dataStore)
            recreated.recoverInterrupted()
            assertEquals(DownloadStatus.PAUSED, recreated.load().single().status)
            assertEquals(42, recreated.load().single().downloadedBytes)
            assertEquals(h.target, recreated.defaultTarget.first())
            assertEquals(0, h.server.requestCount)
        }
    }

    @Test fun deletedSourceTargetFallsBackToConfiguredTarget() = runBlocking {
        Harness().use { h ->
            h.server.enqueue(MockResponse().setBody(Buffer().write(wav())))
            h.manager.enqueue(h.song()).join()
            val id = h.store.load().single().id
            // 队列里存着旧 WebDAV 音源，之后该音源被删；用户已在设置里把保存位置改成新加的本地音源
            h.manager.configure(id, target = DownloadTarget(DownloadTargetKind.WEBDAV, h.source.id, "/Music", "旧 WebDAV")).join()
            h.manager.setDefaultTarget(h.target).join()
            h.sourceList.removeAll { it.id == h.source.id }
            h.manager.refresh()
            h.manager.start(setOf(id), h.target).join()
            val done = h.await(DownloadStatus.COMPLETED)
            assertTrue(File(h.target.directory, "测试歌手 - 测试歌曲.wav").isFile, done.error.orEmpty())
            assertEquals(h.target, done.target)
            assertTrue(done.warnings.any { it.contains("原保存位置") }, done.warnings.toString())
            // 不应再请求已删的 WebDAV 音源（GET 音频一次，无上传）
            val requests = (1..h.server.requestCount).map { h.server.takeRequest(2, TimeUnit.SECONDS)!! }
            assertTrue(requests.none { it.method == "PUT" }, requests.map { it.method }.toString())
        }
    }

    @Test fun downloadFallsBackToDeviceDirectoryWhenEverySourceIsGone() = runBlocking {
        Harness().use { h ->
            val device = File(h.root, "device")
            h.settings.setDownloadDeviceDirectory(device.absolutePath)
            h.server.enqueue(MockResponse().setBody(Buffer().write(wav())))
            h.manager.enqueue(h.song()).join()
            val id = h.store.load().single().id
            h.manager.configure(id, target = DownloadTarget(DownloadTargetKind.WEBDAV, h.source.id, "/Music")).join()
            h.manager.setDefaultTarget(h.target).join()
            h.sourceList.clear()
            h.manager.refresh()
            h.manager.start(setOf(id), h.target).join()
            val done = h.await(DownloadStatus.COMPLETED)
            assertTrue(File(device, "测试歌手 - 测试歌曲.wav").isFile, done.error.orEmpty())
            assertEquals(DownloadTargetKind.DEVICE, done.target!!.kind)
        }
    }

    @Test fun uploadStreamsOnlyAudioWithoutOverwritingExistingFiles() = runBlocking {
        Harness().use { h ->
            h.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = when (request.method) {
                    "GET" -> MockResponse().setBody(Buffer().write(wav()))
                    "HEAD" -> MockResponse().setResponseCode(404)
                    "PUT" -> MockResponse().setResponseCode(201)
                    else -> MockResponse().setResponseCode(405)
                }
            }
            h.manager.enqueue(h.song()).join()
            h.manager.start(setOf(h.store.load().single().id), DownloadTarget(DownloadTargetKind.WEBDAV, "webdav", "/Music", "测试 WebDAV")).join()
            val done = h.await(DownloadStatus.COMPLETED)
            assertTrue(done.savedLocation!!.contains("/dav/Music/"))
            val requests = (1..h.server.requestCount).map { h.server.takeRequest(2, TimeUnit.SECONDS)!! }
            val puts = requests.filter { it.method == "PUT" }
            assertEquals(1, puts.size)
            assertTrue(puts.all { it.getHeader("If-None-Match") == "*" && it.bodySize > 0 })
            assertEquals(1, h.added.size)
        }
    }

    @Test fun uploadWaitsForGatewayCommitBeyondDownloadReadTimeout() = runBlocking {
        val client = okhttp3.OkHttpClient.Builder().readTimeout(50, TimeUnit.MILLISECONDS).build()
        Harness(client = client).use { h ->
            h.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = when (request.method) {
                    "GET" -> MockResponse().setBody(Buffer().write(wav()))
                    "HEAD" -> MockResponse().setResponseCode(404)
                    "PUT" -> MockResponse().setResponseCode(201).setHeadersDelay(300, TimeUnit.MILLISECONDS)
                    else -> MockResponse().setResponseCode(405)
                }
            }
            h.manager.enqueue(h.song()).join()
            h.manager.start(setOf(h.store.load().single().id),
                DownloadTarget(DownloadTargetKind.WEBDAV, "webdav", "/Music")).join()
            assertEquals(DownloadStatus.COMPLETED, h.await(DownloadStatus.COMPLETED).status)
            val requests = (1..h.server.requestCount).map { h.server.takeRequest(2, TimeUnit.SECONDS)!! }
            assertEquals(1, requests.count { it.method == "PUT" })
        }
    }

    @Test fun slowUploadResponseIsConfirmedWithoutRepeatingPut() = runBlocking {
        Harness(uploadConfirmDelayMs = 100).use { h ->
            val uploaded = java.util.concurrent.atomic.AtomicReference<ByteArray>()
            h.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.method == "HEAD" -> MockResponse().setResponseCode(404)
                    request.method == "GET" && request.path == "/audio" -> MockResponse().setBody(Buffer().write(wav()))
                    request.method == "GET" -> MockResponse().setBody(Buffer().write(uploaded.get()))
                    request.method == "PUT" -> {
                        uploaded.set(request.body.readByteArray())
                        MockResponse().setResponseCode(201).setHeadersDelay(2, TimeUnit.SECONDS)
                    }
                    else -> MockResponse().setResponseCode(405)
                }
            }
            h.manager.enqueue(h.song()).join()
            h.manager.start(setOf(h.store.load().single().id),
                DownloadTarget(DownloadTargetKind.WEBDAV, "webdav", "/Music")).join()
            val done = h.await(DownloadStatus.COMPLETED)
            assertTrue(done.warnings.any { it.contains("响应较慢") }, done.warnings.toString())
            assertNull(done.transferMessage)
            val requests = (1..h.server.requestCount).map { h.server.takeRequest(2, TimeUnit.SECONDS)!! }
            assertEquals(1, requests.count { it.method == "PUT" })
        }
    }

    @Test fun waitingForUploadCommitCanBePaused() = runBlocking {
        Harness().use { h ->
            h.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = when (request.method) {
                    "GET" -> MockResponse().setBody(Buffer().write(wav()))
                    "HEAD" -> MockResponse().setResponseCode(404)
                    "PUT" -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    else -> MockResponse().setResponseCode(405)
                }
            }
            h.manager.enqueue(h.song()).join()
            val id = h.store.load().single().id
            h.manager.start(setOf(id), DownloadTarget(DownloadTargetKind.WEBDAV, "webdav", "/Music")).join()
            withTimeout(5000) {
                h.manager.tasks.first { tasks -> tasks.any { it.transferMessage == "等待服务器保存" } }
                h.manager.pause(id).join()
            }
            assertEquals(DownloadStatus.PENDING_UPLOAD, h.store.load().single().status)
        }
    }

    @Test fun stalledUploadCommitFailsAndAllowsNextTaskToFinish() = runBlocking {
        Harness(uploadConfirmDelayMs = 30, uploadCommitTimeoutMs = 400).use { h ->
            val puts = java.util.concurrent.atomic.AtomicInteger()
            h.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = when {
                    request.method == "HEAD" -> MockResponse().setResponseCode(404)
                    request.method == "GET" && request.path == "/audio" -> MockResponse().setBody(Buffer().write(wav()))
                    request.method == "GET" -> MockResponse().setResponseCode(404)
                    request.method == "PUT" && puts.incrementAndGet() == 1 -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    request.method == "PUT" -> MockResponse().setResponseCode(201)
                    else -> MockResponse().setResponseCode(405)
                }
            }
            h.manager.enqueue(h.song("first")).join()
            h.manager.enqueue(h.song("second").copy(title = "下一首歌曲")).join()
            h.manager.start(h.store.load().map { it.id }.toSet(),
                DownloadTarget(DownloadTargetKind.WEBDAV, "webdav", "/Music")).join()
            val failed = h.await(DownloadStatus.PENDING_UPLOAD)
            assertEquals(DownloadStatus.UPLOADING, failed.failureStage)
            assertTrue(failed.error.orEmpty().contains("等待服务器保存超时"))
            assertEquals(failed.transferTotalBytes, failed.transferredBytes)
            assertEquals(DownloadStatus.COMPLETED, h.await(DownloadStatus.COMPLETED).status)
            assertEquals(2, puts.get())
            assertEquals(1, h.added.size)
        }
    }

    @Test fun pendingUploadSurvivesNewManagerAndDoesNotResolveAgain() = runBlocking {
        Harness().use { h ->
            h.settings.setDownloadPreferredQuality("flac")
            val payload = javaClass.getResourceAsStream("/audio/sine.mp3")!!.use { it.readBytes() }
            var uploadAllowed = false
            h.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = when (request.method) {
                    "GET" -> MockResponse().setBody(Buffer().write(payload))
                    "HEAD" -> MockResponse().setResponseCode(404)
                    "PUT" -> MockResponse().setResponseCode(if (uploadAllowed) 201 else 503)
                    else -> MockResponse().setResponseCode(405)
                }
            }
            h.manager.enqueue(h.song()).join()
            val target = DownloadTarget(DownloadTargetKind.WEBDAV, "webdav", "/Music")
            h.manager.start(h.store.load().map { it.id }.toSet(), target).join()
            val pending = h.await(DownloadStatus.PENDING_UPLOAD)
            assertNotNull(pending.qualityWarning)
            assertTrue(pending.actualQualityLabel!!.startsWith("MP3 · 有损"))
            uploadAllowed = true
            val restored = DownloadManager(h.store, h.settings, h.sources, h.credentials, h.songs, h.lx,
                NoOpOnlineTrackMetadataResolver, null, object : DownloadStorage by createDownloadStorage(h.settings) {
                    override val cacheDirectory = File(h.root, "cache")
                    override val stagingDirectory = File(h.root, "cache")
                }, scope = h.scope, resolveAudio = { _, _ -> error("补传不能重新解析或下载") })
            restored.start(setOf(pending.id), target).join()
            val completed = withTimeout(5000) {
                restored.tasks.first { it.singleOrNull()?.status == DownloadStatus.COMPLETED }.single()
            }
            assertNull(completed.localAudioPath)
            assertEquals(pending.actualQualityLabel, completed.actualQualityLabel)
            assertEquals(pending.qualityWarning, completed.qualityWarning)
            assertTrue(completed.qualityWarning in completed.warnings)
            assertEquals(1, h.resolves)
            assertEquals(1, h.added.size)
        }
    }

    @Test fun successfulScrapeRetryUpdatesEmbeddedMarkers() = runBlocking {
        Harness().use { h ->
            val original = File(h.root, "original.mp3").apply { writeText("原始音频") }
            val prepared = File(h.root, "prepared.mp3").apply { writeText("已内嵌的音频") }
            val queue = com.muses.player.core.scrape.writeback.PendingScrapeUploads(File(h.root, "scrape-uploads"))
            val task = queue.prepare("library-song", h.source.id, h.source.url!!, h.server.url("/dav/song.mp3").toString(),
                "新标题", prepared, com.muses.player.core.scrape.writeback.audioFileHash(original),
                com.muses.player.core.scrape.writeback.PendingScrapeMetadata(title = "新标题", lyrics = "内嵌歌词", lyricsFormat = "ttml"))
            var song = Song("library-song", h.source.id, task.url, "旧标题", lyrics = "旧歌词",
                sourceType = SourceType.WEBDAV,
                metaSources = com.muses.player.core.model.scrape.MetaSources(title = com.muses.player.core.model.scrape.MetaFieldSource.SCRAPE),
                lyricsSource = com.muses.player.core.model.scrape.LyricsSource.SCRAPE)
            val repository = object : SongRepository by h.songs {
                override suspend fun getSong(id: String) = song
                override suspend fun upsert(value: Song) { song = value }
            }
            val remote = object : com.muses.player.core.webdav.WebDavClient {
                var bytes = original.readBytes()
                override fun authenticate(username: String, password: String) = Unit
                override suspend fun probe(baseUrl: String) = true
                override suspend fun list(url: String) = emptyList<com.muses.player.core.webdav.WebDavItem>()
                override suspend fun get(url: String, dest: File) = dest.apply { writeBytes(bytes) }
                override suspend fun strongETag(url: String) = "\"original\""
                override suspend fun put(url: String, source: File) { bytes = source.readBytes() }
                override suspend fun putIfMatch(url: String, source: File, eTag: String?) {
                    assertEquals("\"original\"", eTag); put(url, source)
                }
                override suspend fun delete(url: String) = error("禁止删除")
                override suspend fun move(source: String, dest: String) = error("禁止移动")
                override suspend fun getString(url: String): String? = null
            }
            var invalidated: String? = null
            val manager = DownloadManager(h.store, h.settings, h.sources, h.credentials, repository, h.lx,
                NoOpOnlineTrackMetadataResolver, null, createDownloadStorage(h.settings), scope = h.scope,
                scrapeUploads = queue, scrapeUploadClient = remote,
                uploadedAudioInvalidator = com.muses.player.core.scrape.writeback.UploadedAudioInvalidator { invalidated = it })
            manager.retryScrapeUploads(setOf(task.id)).join()
            assertTrue(queue.tasks.value.isEmpty())
            assertEquals("新标题", song.title)
            assertEquals("内嵌歌词", song.lyrics)
            assertEquals(com.muses.player.core.model.scrape.LyricsFormat.TTML, song.lyricsFormat)
            assertEquals(com.muses.player.core.model.scrape.MetaFieldSource.EMBEDDED, song.metaSources?.title)
            assertEquals(com.muses.player.core.model.scrape.LyricsSource.EMBEDDED, song.lyricsSource)
            assertEquals(task.url, invalidated)
        }
    }

    @Test fun verifiedUploadCompletesBeforeCommitDeadlineWithoutAnotherPut() = runBlocking {
        Harness(uploadConfirmDelayMs = 30, uploadCommitTimeoutMs = 800).use { h ->
            val uploaded = java.util.concurrent.atomic.AtomicReference<ByteArray>()
            val puts = java.util.concurrent.atomic.AtomicInteger()
            h.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = when {
                    request.method == "HEAD" -> MockResponse().setResponseCode(404)
                    request.method == "GET" && request.path == "/audio" -> MockResponse().setBody(Buffer().write(wav()))
                    request.method == "GET" -> MockResponse().setBody(Buffer().write(uploaded.get()))
                    request.method == "PUT" -> {
                        puts.incrementAndGet()
                        uploaded.set(request.body.readByteArray())
                        MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    }
                    else -> MockResponse().setResponseCode(405)
                }
            }
            h.manager.enqueue(h.song()).join()
            h.manager.start(h.store.load().map { it.id }.toSet(),
                DownloadTarget(DownloadTargetKind.WEBDAV, "webdav", "/Music")).join()
            val completed = h.await(DownloadStatus.COMPLETED)
            assertTrue(completed.warnings.any { it.contains("已核验远端文件完整") })
            assertEquals(1, puts.get())
        }
    }

    @Test fun lockedUploadHasClearErrorWithoutRepeatingPut() = runBlocking {
        Harness().use { h ->
            h.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = when (request.method) {
                    "GET" -> MockResponse().setBody(Buffer().write(wav()))
                    "HEAD" -> MockResponse().setResponseCode(404)
                    "PUT" -> MockResponse().setResponseCode(423)
                    else -> MockResponse().setResponseCode(405)
                }
            }
            h.manager.enqueue(h.song()).join()
            h.manager.start(setOf(h.store.load().single().id),
                DownloadTarget(DownloadTargetKind.WEBDAV, "webdav", "/Music")).join()
            val failed = h.await(DownloadStatus.PENDING_UPLOAD)
            assertTrue(failed.error.orEmpty().contains("HTTP 423"))
            assertTrue(failed.error.orEmpty().contains("锁定"))
            assertEquals(DownloadStatus.UPLOADING, failed.failureStage)
            val requests = (1..h.server.requestCount).map { h.server.takeRequest(2, TimeUnit.SECONDS)!! }
            assertEquals(1, requests.count { it.method == "PUT" })
            assertTrue(h.added.isEmpty())
        }
    }

    @Test fun uploadResponseDisconnectConfirmsRemoteContentWithoutRepeatingPut() = runBlocking {
        Harness().use { h ->
            var uploaded: ByteArray? = null
            var audioPuts = 0
            var checks = 0
            h.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.method == "HEAD" -> MockResponse().setResponseCode(404)
                    request.method == "GET" && request.path == "/audio" -> MockResponse().setBody(Buffer().write(wav()))
                    request.method == "GET" -> if (++checks == 1) MockResponse().setResponseCode(404)
                        else MockResponse().setBody(Buffer().write(uploaded!!))
                    request.method == "PUT" && request.path!!.endsWith(".wav") -> {
                        audioPuts++
                        uploaded = request.body.readByteArray()
                        MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    }
                    else -> MockResponse().setResponseCode(201)
                }
            }
            h.manager.enqueue(h.song()).join()
            h.manager.start(setOf(h.store.load().single().id),
                DownloadTarget(DownloadTargetKind.WEBDAV, "webdav", "/Music")).join()
            val done = h.await(DownloadStatus.COMPLETED)
            assertEquals(1, audioPuts)
            assertEquals(2, checks)
            assertTrue(done.warnings.any { it.contains("已核验") })
            assertEquals(1, h.added.size)
        }
    }

    @Test fun uploadResponseDisconnectDoesNotAcceptDifferentContentOfSameSize() = runBlocking {
        Harness().use { h ->
            var uploaded: ByteArray? = null
            var recover = false
            h.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.method == "HEAD" && recover && request.path!!.endsWith(".wav") -> MockResponse().setResponseCode(200)
                    request.method == "HEAD" -> MockResponse().setResponseCode(404)
                    request.method == "GET" && request.path == "/audio" -> MockResponse().setBody(Buffer().write(wav()))
                    request.method == "GET" && recover -> MockResponse().setBody(Buffer().write(uploaded!!))
                    request.method == "GET" -> MockResponse().setBody(Buffer().write(uploaded!!.copyOf().apply { this[0] = 0 }))
                    request.method == "PUT" && recover -> MockResponse().setResponseCode(201)
                    else -> {
                        uploaded = request.body.readByteArray()
                        MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    }
                }
            }
            h.manager.enqueue(h.song()).join()
            h.manager.start(setOf(h.store.load().single().id),
                DownloadTarget(DownloadTargetKind.WEBDAV, "webdav", "/Music")).join()
            val failed = h.await(DownloadStatus.PENDING_UPLOAD)
            assertEquals(DownloadStatus.UPLOADING, failed.failureStage)
            assertTrue(h.added.isEmpty())
            recover = true
            h.manager.start(setOf(failed.id), failed.target!!).join()
            val done = h.await(DownloadStatus.COMPLETED)
            assertTrue(done.warnings.any { it.contains("上次上传") })
        }
    }

    @Test fun retryNeverTreatsIncompleteUploadAsSameQualityCompletedFile() = runBlocking {
        Harness(uploadConfirmDelayMs = 30, uploadCommitTimeoutMs = 400).use { h ->
            val uploaded = java.util.concurrent.atomic.AtomicReference<ByteArray>()
            val recovering = java.util.concurrent.atomic.AtomicBoolean()
            val puts = java.util.concurrent.atomic.AtomicInteger()
            h.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = when {
                    request.method == "HEAD" && recovering.get() -> MockResponse().setResponseCode(200).setHeader("ETag", "partial")
                    request.method == "HEAD" -> MockResponse().setResponseCode(404)
                    request.method == "GET" && request.path == "/audio" -> MockResponse().setBody(Buffer().write(wav()))
                    request.method == "GET" -> MockResponse().setBody(Buffer().write(uploaded.get()))
                    request.method == "PUT" -> {
                        puts.incrementAndGet()
                        val bytes = request.body.readByteArray()
                        uploaded.set(bytes.copyOf(bytes.size / 2))
                        MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    }
                    else -> MockResponse().setResponseCode(405)
                }
            }
            h.manager.enqueue(h.song()).join()
            h.manager.start(h.store.load().map { it.id }.toSet(), DownloadTarget(DownloadTargetKind.WEBDAV, "webdav", "/Music")).join()
            val first = h.await(DownloadStatus.PENDING_UPLOAD)
            recovering.set(true)
            h.manager.start(setOf(first.id), first.target!!).join()
            val retry = withTimeout(5000) {
                h.manager.tasks.first { tasks -> tasks.any { it.status == DownloadStatus.PENDING_UPLOAD && it.error.orEmpty().contains("未通过完整性核验") } }
                    .single()
            }
            assertFalse(retry.skippedExisting)
            assertTrue(h.added.isEmpty())
            assertEquals(1, puts.get())
        }
    }

    @Test fun uploadRedirectDoesNotCountRequestBodyTwice() = runBlocking {
        Harness().use { h ->
            h.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = when (request.method) {
                    "GET" -> MockResponse().setBody(Buffer().write(wav()))
                    "HEAD" -> MockResponse().setResponseCode(404)
                    "PUT" -> if (request.path!!.startsWith("/dav/")) {
                        MockResponse().setResponseCode(307)
                            .setHeader("Location", h.server.url("/received/${request.path!!.substringAfterLast('/')}"))
                    } else MockResponse().setResponseCode(201).setHeadersDelay(300, TimeUnit.MILLISECONDS)
                    else -> MockResponse().setResponseCode(405)
                }
            }
            val progress = java.util.concurrent.CopyOnWriteArrayList<DownloadTask>()
            val observer = h.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                h.manager.tasks.collect { tasks -> tasks.filter { it.status == DownloadStatus.UPLOADING }.forEach { progress += it } }
            }
            try {
                h.manager.enqueue(h.song()).join()
                h.manager.start(setOf(h.store.load().single().id),
                    DownloadTarget(DownloadTargetKind.WEBDAV, "webdav", "/Music")).join()
                val done = h.await(DownloadStatus.COMPLETED)
                val requests = (1..h.server.requestCount).map { h.server.takeRequest(2, TimeUnit.SECONDS)!! }
                val puts = requests.filter { it.method == "PUT" }
            assertEquals(2, puts.size)
                assertTrue(progress.isNotEmpty())
                assertTrue(progress.all { it.transferredBytes <= it.transferTotalBytes!! })
                assertEquals(puts.filter { it.path!!.startsWith("/received/") }.sumOf { it.bodySize }, done.transferTotalBytes)
                assertEquals(done.transferTotalBytes, done.transferredBytes)
            } finally { observer.cancelAndJoin() }
        }
    }

    @Test fun pendingUploadReusesPreparedAudioWithoutDownloadingAgain() = runBlocking {
        Harness().use { h ->
            val payload = wav()
            var uploadAllowed = false
            val seenAgent = java.util.concurrent.CopyOnWriteArrayList<String>()
            h.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "GET") seenAgent += request.getHeader("User-Agent").orEmpty()
                    return when (request.method) {
                        // 残留分片越界：续传请求会被拒绝，必须丢掉分片重下
                        "GET" -> if (request.getHeader("Range") != null) MockResponse().setResponseCode(416)
                        else MockResponse().setHeader("ETag", "\"part-1\"").setBody(Buffer().write(payload))
                        "HEAD" -> MockResponse().setResponseCode(404)
                        "PUT" -> if (uploadAllowed) MockResponse().setResponseCode(201) else MockResponse().setResponseCode(405)
                        else -> MockResponse().setResponseCode(405)
                    }
                }
            }
            val target = DownloadTarget(DownloadTargetKind.WEBDAV, "webdav", "/Music")
            h.manager.enqueue(h.song()).join()
            val id = h.store.load().single().id
            h.manager.start(setOf(id), target).join()
            val pending = h.await(DownloadStatus.PENDING_UPLOAD)
            assertNotNull(pending.localAudioPath)
            // 已完成的音频持久保留，不再留下重复的下载分片和续传校验器。
            val part = File(File(h.root, "cache"), id + File.separator + "audio.part")
            assertFalse(part.exists())
            assertTrue(File(pending.localAudioPath!!).isFile)
            // 音源直链必须带音源生态 UA：okhttp 默认 UA 会被网易 CDN 403
            assertTrue(seenAgent.any { it == "lx-music" } && seenAgent.none { it.startsWith("okhttp") })
            assertNull(h.store.load().single().resumeValidator)

            uploadAllowed = true
            h.manager.start(setOf(id), target).join()
            assertEquals(DownloadStatus.COMPLETED, h.await(DownloadStatus.COMPLETED).status)
            assertEquals(1, h.resolves)
        }
    }

    @Test fun filenameCannotEscapeDirectory() {
        val track = DownloadTrack("1", "online", "", "../../CON:*?", "../歌手")
        val name = downloadBaseName(track)
        assertFalse(name.contains('/')); assertFalse(name.contains('\\')); assertFalse(name.contains(':'))
        assertEquals("_CON", downloadBaseName(track.copy(title = "CON", artist = null)))
    }

    private fun wav(size: Int = 16384, sampleRate: Int = 44100): ByteArray {
        val bytes = ByteArray(size)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()); buffer.putInt(size - 8); buffer.put("WAVEfmt ".toByteArray())
        buffer.putInt(16); buffer.putShort(1); buffer.putShort(1); buffer.putInt(sampleRate); buffer.putInt(sampleRate * 2)
        buffer.putShort(2); buffer.putShort(16); buffer.put("data".toByteArray()); buffer.putInt(size - 44)
        return bytes
    }

    @Test fun localExistingAudioIsNeverOverwritten() = runBlocking {
        Harness().use { h ->
            h.manager.enqueue(h.song()).join(); val task = h.store.load().single()
            val directory = File(h.target.directory).apply { mkdirs() }
            val existing = File(directory, downloadBaseName(task.track) + ".wav").apply { writeText("用户已有文件") }
            h.server.enqueue(MockResponse().setBody(Buffer().write(wav())))
            h.manager.start(setOf(task.id), h.target).join(); h.await(DownloadStatus.FAILED)
            assertEquals("用户已有文件", existing.readText())
            assertTrue(h.added.isEmpty())
        }
    }

    @Test fun webdavSameQualityIsMarkedAndSkipped() = runBlocking {
        for (existingRate in listOf(44100, 48000)) Harness().use { h ->
            h.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = when {
                    request.method == "HEAD" -> MockResponse().setResponseCode(if (request.path!!.endsWith(".wav")) 200 else 404)
                    request.method == "GET" -> MockResponse().setBody(Buffer().write(wav(sampleRate = if (request.path == "/audio") 44100 else existingRate)))
                    else -> MockResponse().setResponseCode(405)
                }
            }
            h.manager.enqueue(h.song()).join()
            h.manager.start(setOf(h.store.load().single().id), DownloadTarget(DownloadTargetKind.WEBDAV, "webdav", "/Music")).join()
            val done = h.await(DownloadStatus.COMPLETED)
            assertTrue(done.skippedExisting)
            assertTrue(done.warnings.any { it.contains("同名") })
            val requests = (1..h.server.requestCount).map { h.server.takeRequest(2, TimeUnit.SECONDS)!! }
            assertTrue(requests.none { it.method == "PUT" || it.method == "DELETE" })
            assertTrue(h.added.isEmpty())
        }
    }

    @Test fun webdavHigherActualQualityOverwritesSameFile() = runBlocking {
        Harness().use { h ->
            h.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = when {
                    request.method == "HEAD" -> MockResponse().setResponseCode(if (request.path!!.endsWith(".wav")) 200 else 404).setHeader("ETag", "\"old\"")
                    request.method == "GET" -> MockResponse().setBody(Buffer().write(wav(sampleRate = if (request.path == "/audio") 44100 else 22050)))
                    request.method == "PUT" -> MockResponse().setResponseCode(201)
                    else -> MockResponse().setResponseCode(405)
                }
            }
            h.manager.enqueue(h.song()).join()
            h.manager.start(setOf(h.store.load().single().id), DownloadTarget(DownloadTargetKind.WEBDAV, "webdav", "/Music")).join()
            val done = h.await(DownloadStatus.COMPLETED)
            assertFalse(done.skippedExisting)
            val requests = (1..h.server.requestCount).map { h.server.takeRequest(2, TimeUnit.SECONDS)!! }
            val put = requests.single { it.method == "PUT" }
            assertEquals("\"old\"", put.getHeader("If-Match"))
            assertNull(put.getHeader("If-None-Match"))
            assertEquals(1, h.added.size)
        }
    }

    @Test fun completedSongCanBeRequeuedForHigherRequestedQuality() = runBlocking {
        Harness().use { h ->
            h.server.enqueue(MockResponse().setBody(Buffer().write(wav())))
            h.manager.enqueue(h.song()).join()
            h.manager.start(setOf(h.store.load().single().id), h.target).join()
            val completed = h.await(DownloadStatus.COMPLETED)
            h.settings.setDownloadPreferredQuality("flac")
            h.manager.enqueue(h.song()).join()
            val queued = h.store.load().single()
            assertEquals(DownloadStatus.WAITING, queued.status)
            assertEquals("flac", queued.quality)
            assertNotEquals(completed.id, queued.id)
            assertTrue(File(completed.savedLocation!!).isFile)
        }
    }

    @Test fun crossedLosslessMetricsAndDifferentLossyCodecsDoNotAutoUpgrade() {
        val lossless = com.muses.player.core.media.scanner.DownloadAudioQuality(true, 16, 96000, 1000, "flac")
        assertFalse(lossless.copy(bitDepth = 24, sampleRate = 44100).higherThan(lossless))
        assertFalse(lossless.copy(bitDepth = 0).higherThan(lossless))
        val lossy = lossless.copy(lossless = false, codec = "mp3", bitrate = 320)
        assertFalse(lossy.copy(codec = "aac", bitrate = 512).higherThan(lossy))
        assertTrue(lossless.higherThan(lossy))
    }

    @Test fun webdavUnknownExistingQualityIsSkipped() = runBlocking {
        Harness().use { h ->
            h.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = when {
                    request.method == "HEAD" -> MockResponse().setResponseCode(if (request.path!!.endsWith(".wav")) 200 else 404)
                    request.path == "/audio" -> MockResponse().setBody(Buffer().write(wav()))
                    request.method == "GET" -> MockResponse().setBody("无法读取的音频")
                    else -> MockResponse().setResponseCode(405)
                }
            }
            h.manager.enqueue(h.song()).join()
            h.manager.start(setOf(h.store.load().single().id), DownloadTarget(DownloadTargetKind.WEBDAV, "webdav", "/Music")).join()
            assertTrue(h.await(DownloadStatus.COMPLETED).skippedExisting)
            val requests = (1..h.server.requestCount).map { h.server.takeRequest(2, TimeUnit.SECONDS)!! }
            assertTrue(requests.none { it.method == "PUT" || it.method == "DELETE" })
        }
    }

    @Test fun crossFormatUpgradeRemovesOldFileOnlyAfterSuccessfulPut() = runBlocking {
        for (putSucceeds in listOf(false, true)) Harness().use { h ->
            var saved = false
            val old = ByteBuffer.allocate(128).order(ByteOrder.BIG_ENDIAN).apply {
                put("fLaC".toByteArray()); put(0x80.toByte()); put(0); put(0); put(34)
                putShort(4096); putShort(4096); put(ByteArray(6))
                putLong((22050L shl 44) or (15L shl 36) or 16384L)
                put(ByteArray(16))
            }.array()
            h.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = when {
                    request.method == "HEAD" -> MockResponse().setResponseCode(if (request.path!!.endsWith(".flac")) 200 else 404).setHeader("ETag", "\"old\"")
                    request.method == "GET" && request.path == "/audio" -> MockResponse().setBody(Buffer().write(wav()))
                    request.method == "GET" -> MockResponse().setBody(Buffer().write(old)).setHeader("ETag", "\"old\"")
                    request.method == "PUT" -> { saved = putSucceeds; MockResponse().setResponseCode(if (putSucceeds) 201 else 500) }
                    request.method == "DELETE" -> { assertTrue(saved); MockResponse().setResponseCode(204) }
                    else -> MockResponse().setResponseCode(405)
                }
            }
            h.manager.enqueue(h.song()).join()
            h.manager.start(setOf(h.store.load().single().id), DownloadTarget(DownloadTargetKind.WEBDAV, "webdav", "/Music")).join()
            val result = h.await(if (putSucceeds) DownloadStatus.COMPLETED else DownloadStatus.PENDING_UPLOAD)
            assertFalse(result.skippedExisting)
            val requests = (1..h.server.requestCount).map { h.server.takeRequest(2, TimeUnit.SECONDS)!! }
            assertEquals(if (putSucceeds) 1 else 0, requests.count { it.method == "DELETE" })
            if (putSucceeds) assertEquals("\"old\"", requests.single { it.method == "DELETE" }.getHeader("If-Match"))
        }
    }

    @Test fun webdavReceivesEmbeddedLyricsWithoutSidecars() = runBlocking {
        Harness().use { h ->
            h.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = when (request.method) {
                    "GET" -> MockResponse().setBody(Buffer().write(wav()))
                    "HEAD" -> MockResponse().setResponseCode(404)
                    "PUT" -> MockResponse().setResponseCode(if (request.path!!.endsWith(".wav")) 201 else 500)
                    else -> MockResponse().setResponseCode(405)
                }
            }
            h.manager.enqueue(h.song()).join()
            h.manager.start(setOf(h.store.load().single().id), DownloadTarget(DownloadTargetKind.WEBDAV, "webdav", "/Music")).join()
            val done = h.await(DownloadStatus.COMPLETED)
            val requests = (1..h.server.requestCount).map { h.server.takeRequest(2, TimeUnit.SECONDS)!! }
            val put = requests.filter { it.method == "PUT" }.single()
            val received = File(h.root, "received.wav").apply { writeBytes(put.body.readByteArray()) }
            val tags = com.muses.player.core.scrape.ports.JaudiotaggerTagPort.readTags(received)!!
            assertEquals("测试歌曲", tags.title)
            assertTrue(tags.lyrics!!.contains("测试歌词"))
            assertTrue(done.savedLocation!!.endsWith(".wav")); assertEquals(1, h.added.size)
        }
    }

    @Test fun failedMetadataEmbeddingRestoresOriginalAudioAndStillUploads() = runBlocking {
        for (throwException in listOf(false, true)) {
            val failingTags = object : com.muses.player.core.scrape.ports.TagPort by com.muses.player.core.scrape.ports.JaudiotaggerTagPort {
                override fun writeTags(file: File, changes: com.muses.player.core.model.scrape.ScrapeChanges, coverBytes: ByteArray?): com.muses.player.core.model.scrape.FileWriteResult {
                    file.writeText("模拟写标签时损坏临时副本")
                    if (throwException) error("标签写入异常")
                    return com.muses.player.core.model.scrape.FileWriteResult(ok = false)
                }
            }
            Harness(tagPort = failingTags).use { h ->
                val original = wav()
                h.server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) = when (request.method) {
                        "GET" -> MockResponse().setBody(Buffer().write(original))
                        "HEAD" -> MockResponse().setResponseCode(404)
                        "PUT" -> MockResponse().setResponseCode(201)
                        else -> MockResponse().setResponseCode(405)
                    }
                }
                h.manager.enqueue(h.song()).join()
                h.manager.start(h.store.load().map { it.id }.toSet(), DownloadTarget(DownloadTargetKind.WEBDAV, "webdav", "/Music")).join()
                val completed = h.await(DownloadStatus.COMPLETED)
                assertTrue(completed.warnings.any { it.contains("内嵌失败") && it.contains("稍后刮削") })
                val requests = (1..h.server.requestCount).map { h.server.takeRequest(2, TimeUnit.SECONDS)!! }
                val uploaded = requests.filter { it.method == "PUT" }.single()
                assertContentEquals(original, uploaded.body.readByteArray())
                assertNull(h.added.single().lyrics)
            }
        }
    }

    @Test fun completedSongIsNotEnqueuedAgainUntilRemoved() = runBlocking {
        Harness().use { h ->
            h.server.enqueue(MockResponse().setBody(Buffer().write(wav())))
            h.manager.enqueue(h.song()).join()
            val id = h.store.load().single().id
            h.manager.start(setOf(id), h.target).join()
            h.await(DownloadStatus.COMPLETED)
            // 已下载过：再次添加不产生新任务，也不重新发起解析
            h.manager.enqueue(h.song()).join()
            assertEquals(1, h.store.load().size)
            assertEquals(1, h.resolves)
            // 移出队列后可以重新添加（用户显式要求重下）
            h.manager.remove(id).join()
            h.manager.enqueue(h.song()).join()
            assertEquals(1, h.store.load().size)
            assertEquals(DownloadStatus.WAITING, h.store.load().single().status)
        }
    }

    @Test fun resolveFailureFallsBackToLowerQuality() = runBlocking {
        Harness().use { h ->
            // 模拟截图里的情形：脚本后端超时。档位回退由仓库内部完成，这里要求失败原因可直接读
            h.resolveOverride = { quality ->
                if (quality.rank >= LxQuality.FLAC.rank) {
                    throw IllegalStateException("后端聚合失败: Request timeout has expired [url=https://yy.example/lx/api/]")
                }
                LxMusicUrl(h.server.url("/audio").toString(), quality)
            }
            h.settings.setDownloadPreferredQuality(LxQuality.FLAC_24BIT.key)
            h.server.enqueue(MockResponse().setBody(Buffer().write(wav())))
            h.manager.enqueue(h.song()).join()
            h.manager.start(setOf(h.store.load().single().id), h.target).join()
            val failed = h.await(DownloadStatus.FAILED)
            assertEquals("下载失败：音源响应超时，请稍后重试或改用较低音质", failed.error)
            assertEquals(1, h.resolves)
            assertTrue(h.added.isEmpty())
        }
    }

    @Test fun wedgedResolverFailsInsteadOfHangingForever() = runBlocking {
        Harness(resolveTimeoutMs = 500).use { h ->
            // 脚本引擎卡住不再回调：任务要落到「失败 + 可重试」，不能永远停在准备下载
            h.resolveOverride = { kotlinx.coroutines.awaitCancellation() }
            h.manager.enqueue(h.song()).join()
            h.manager.start(setOf(h.store.load().single().id), h.target).join()
            val failed = h.await(DownloadStatus.FAILED)
            assertEquals("下载失败：音源响应超时，请稍后重试或改用较低音质", failed.error)
            assertEquals(0, h.server.requestCount)
        }
    }

    @Test fun customDeviceDirectoryIsUsedForDeviceTarget() = runBlocking {
        Harness().use { h ->
            val custom = File(h.root, "自定义下载目录")
            h.settings.setDownloadDeviceDirectory(custom.absolutePath)
            h.server.enqueue(MockResponse().setBody(Buffer().write(wav())))
            h.manager.enqueue(h.song()).join()
            h.manager.start(setOf(h.store.load().single().id), DownloadTarget()).join()
            h.await(DownloadStatus.COMPLETED)
            assertTrue(custom.listFiles().orEmpty().any { it.extension == "wav" })
            assertEquals("wav", custom.listFiles().orEmpty().single().extension)
            // 自定义目录不是音源，不写入曲库
            assertTrue(h.added.isEmpty())
        }
    }

    @Test fun deletedDownloadedFileAllowsReenqueue() = runBlocking {
        Harness().use { h ->
            val custom = File(h.root, "device-dir")
            h.settings.setDownloadDeviceDirectory(custom.absolutePath)
            h.server.enqueue(MockResponse().setBody(Buffer().write(wav())))
            h.manager.enqueue(h.song()).join()
            val first = h.store.load().single().id
            h.manager.start(setOf(first), DownloadTarget()).join()
            val done = h.await(DownloadStatus.COMPLETED)

            // 文件还在：再次添加被拒绝，队列不变
            h.manager.enqueue(h.song()).join()
            assertEquals(1, h.store.load().size)
            assertEquals(first, h.store.load().single().id)

            // 用户把文件删了：旧记录被替换，可以重新下载
            assertTrue(File(done.savedLocation!!).delete())
            h.server.enqueue(MockResponse().setBody(Buffer().write(wav())))
            h.manager.enqueue(h.song()).join()
            assertEquals(1, h.store.load().size)
            assertNotEquals(first, h.store.load().single().id)
            assertEquals(DownloadStatus.WAITING, h.store.load().single().status)
        }
    }

    @Test fun deletedWebdavFileAllowsReenqueue() = runBlocking {
        Harness().use { h ->
            h.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = when (request.method) {
                    "GET" -> MockResponse().setBody(Buffer().write(wav()))
                    "HEAD" -> MockResponse().setResponseCode(404)
                    "PUT" -> MockResponse().setResponseCode(201)
                    else -> MockResponse().setResponseCode(405)
                }
            }
            val target = DownloadTarget(DownloadTargetKind.WEBDAV, "webdav", "/Music", "测试 WebDAV")
            h.manager.enqueue(h.song()).join()
            val first = h.store.load().single().id
            h.manager.start(setOf(first), target).join()
            h.await(DownloadStatus.COMPLETED)

            // 远端查不到该文件（HEAD 404）时允许重新入队
            h.manager.enqueue(h.song()).join()
            assertEquals(1, h.store.load().size)
            assertNotEquals(first, h.store.load().single().id)
            assertEquals(DownloadStatus.WAITING, h.store.load().single().status)
        }
    }
}
