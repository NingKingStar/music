package com.muses.player.core.media.playback

import android.net.Uri
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.muses.player.core.model.online.OnlinePlayableUrl
import com.muses.player.core.model.online.OnlinePlaybackHttp
import com.muses.player.core.model.online.OnlineTrackRef
import com.muses.player.core.model.online.OnlineTrackResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class OnlineResolvingDataSourceFactoryTest {
    @Test
    fun `同一歌曲连续打开复用直链而失败换资源时拒绝拼接`() {
        var calls = 0
        val upstream = RecordingDataSource()
        val source = factory(upstream, resolveTrack = {
            calls++
            OnlinePlayableUrl("https://cdn.test/audio$calls")
        }).createDataSource()
        source.open(onlineSpec()); source.close()
        source.open(onlineSpec()); source.close()
        assertEquals(1, calls)
        upstream.failNextOpen = true
        try { source.open(onlineSpec()); fail("应报告打开失败") } catch (_: IOException) { }
        source.close()
        try { source.open(onlineSpec()); fail("资源变化必须重置播放器") }
        catch (_: OnlineResourceChangedException) { }
        assertEquals(2, calls)
        assertEquals("https://cdn.test/audio1", upstream.spec.uri.toString())
    }

    @Test
    fun `缓存键完整区分实际地址和签名`() {
        val first = onlineCacheKey("https://cdn.test/audio?token=one")
        assertEquals(first, onlineCacheKey("https://cdn.test/audio?token=one"))
        assertFalse(first == onlineCacheKey("https://cdn.test/audio?token=two"))
        assertEquals(64, first.removePrefix("online-v2:").length)
    }

    @Test
    fun `实际缓存不会混用旧歌曲键和不同直链的片段`() {
        val directory = java.nio.file.Files.createTempDirectory("muses-cache-test").toFile()
        @Suppress("DEPRECATION")
        val cache = SimpleCache(directory, NoOpCacheEvictor())
        val requests = mutableListOf<String>()
        val upstream = DataSource.Factory {
            object : BaseDataSource(true) {
                var bytes = byteArrayOf()
                var position = 0
                var currentUri: Uri? = null
                override fun open(dataSpec: DataSpec): Long {
                    currentUri = dataSpec.uri
                    requests += dataSpec.uri.toString()
                    bytes = when (dataSpec.uri.toString()) {
                        "https://cdn.test/a" -> "abcdefgh".toByteArray()
                        "https://cdn.test/b" -> "ABCDEFGH".toByteArray()
                        else -> "XXXXXXXX".toByteArray()
                    }
                    position = dataSpec.position.toInt()
                    return (bytes.size - position).toLong().coerceAtLeast(0)
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (position >= bytes.size) return -1
                    val count = minOf(length, bytes.size - position)
                    bytes.copyInto(buffer, offset, position, position + count)
                    position += count
                    return count
                }
                override fun getUri(): Uri? = currentUri
                override fun close() { currentUri = null }
            }
        }
        val cached = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)
        fun read(source: DataSource, spec: DataSpec): String {
            source.open(spec)
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4)
            while (true) { val n = source.read(buffer, 0, buffer.size); if (n == -1) break; output.write(buffer, 0, n) }
            source.close()
            return output.toString("UTF-8")
        }
        try {
            assertEquals("XXXXXXXX", read(cached.createDataSource(), onlineSpec()))
            var url = "https://cdn.test/a"
            val resolving = OnlineResolvingDataSourceFactory(cached, object : OnlineTrackResolver {
                override suspend fun resolve(ref: OnlineTrackRef) = OnlinePlayableUrl(url)
            })
            assertEquals("abcdefgh", read(resolving.createDataSource(), onlineSpec()))
            url = "https://cdn.test/b"
            assertEquals("EFGH", read(resolving.createDataSource(), onlineSpec().buildUpon().setPosition(4).build()))
            assertTrue(requests.contains("https://cdn.test/a"))
            assertTrue(requests.contains("https://cdn.test/b"))
        } finally { cache.release(); directory.deleteRecursively() }
    }

    @Test
    fun `下载匹配忽略音质但不混淆来源和版本`() {
        val ref = OnlineTrackRef("wy", "{\"id\":1}", "online", "flac")
        assertTrue(matchesDownloadedRef(ref, ref.copy(quality = "320k")))
        assertFalse(matchesDownloadedRef(ref, ref.copy(musicInfoJson = "{\"id\":2}")))
        assertFalse(matchesDownloadedRef(ref, ref.copy(sourceId = "other")))
        assertFalse(matchesDownloadedRef(ref, ref.copy(platform = "kw")))
    }

    @Test
    fun `可读取下载文件优先并跳过在线解析`() {
        val upstream = RecordingDataSource()
        var calls = 0
        val source = factory(upstream, resolveTrack = { calls++; OnlinePlayableUrl("https://cdn.test/audio") },
            findDownloaded = { "content://downloads/audio" }).createDataSource()
        source.open(onlineSpec())
        assertEquals("content://downloads/audio", upstream.spec.uri.toString())
        assertEquals(0, calls)
        source.close()
    }

    @Test
    fun `找不到可读取下载文件时回退在线`() {
        val upstream = RecordingDataSource()
        val source = factory(upstream, findDownloaded = { null }).createDataSource()
        source.open(onlineSpec())
        assertEquals("https://cdn.test/audio", upstream.spec.uri.toString())
        source.close()
    }

    @Test
    fun `切换歌曲不会复用上一首直链`() {
        var calls = 0
        val upstream = RecordingDataSource()
        val source = factory(upstream, resolveTrack = { calls++; OnlinePlayableUrl("https://cdn.test/$calls") }).createDataSource()
        source.open(onlineSpec()); source.close()
        source.open(DataSpec.Builder().setUri(OnlineTrackRef("tx", "{}", "script").encode()).build()); source.close()
        assertEquals(2, calls)
    }

    @Test
    fun `解析超时会报错且不缓存直链`() {
        var errors = 0
        val upstream = RecordingDataSource()
        val source = OnlineResolvingDataSourceFactory(
            upstreamFactory = DataSource.Factory { upstream },
            resolver = object : OnlineTrackResolver {
                override suspend fun resolve(ref: OnlineTrackRef): OnlinePlayableUrl =
                    kotlinx.coroutines.awaitCancellation()
            },
            resolveTimeoutMs = 80L,
            onResolveError = { _, _ -> errors++ },
        ).createDataSource()
        try {
            source.open(onlineSpec())
            fail("解析超时应抛错")
        } catch (_: IOException) { }
        assertEquals(1, errors)
    }

    @Test
    fun `下载文件打不开时直接回退在线而不重建播放器`() {
        val upstream = RecordingDataSource()
        var calls = 0
        val source = factory(
            upstream,
            resolveTrack = { calls++; OnlinePlayableUrl("https://cdn.test/online") },
            findDownloaded = { "content://downloads/broken" },
        ).createDataSource()
        upstream.failNextOpen = true
        try {
            source.open(onlineSpec())
            fail("本地文件打不开应报错")
        } catch (_: IOException) { }
        source.close()
        // 本地文件刚失败：同一加载器内直接转在线，不应抛资源变化异常
        source.open(onlineSpec())
        assertEquals("https://cdn.test/online", upstream.spec.uri.toString())
        assertEquals(1, calls)
        source.close()
    }

    @Test
    fun `断网读失败后清空直链并重新解析`() {
        val upstream = RecordingDataSource()
        var calls = 0
        val source = factory(
            upstream,
            resolveTrack = { calls++; OnlinePlayableUrl("https://cdn.test/audio") },
        ).createDataSource()
        source.open(onlineSpec())
        source.close()
        assertEquals(1, calls)
        upstream.failReads = 1
        try {
            source.read(ByteArray(4), 0, 4)
            fail("断网读取应抛错")
        } catch (_: IOException) { }
        source.open(onlineSpec())
        assertEquals(2, calls)
        assertEquals("https://cdn.test/audio", upstream.spec.uri.toString())
        source.close()
    }

    @Test
    fun `在线解析后补齐播放标识并保留范围和原请求头`() {
        val upstream = RecordingDataSource()
        val source = factory(upstream).createDataSource()
        val uri = OnlineTrackRef("wy", "{}", "script").encode()
        source.open(DataSpec.Builder().setUri(uri).setPosition(1024)
            .setHttpRequestHeaders(mapOf("X-Test" to "keep")).build())
        assertEquals("https://cdn.test/audio", upstream.spec.uri.toString())
        assertEquals(onlineCacheKey("https://cdn.test/audio"), upstream.spec.key)
        assertEquals(1024L, upstream.spec.position)
        assertEquals(mapOf("X-Test" to "keep", "User-Agent" to OnlinePlaybackHttp.USER_AGENT),
            upstream.spec.httpRequestHeaders)
        source.close()
    }

    @Test
    fun `WebDAV地址不添加LX请求标识`() {
        val upstream = RecordingDataSource()
        val source = factory(upstream).createDataSource()
        val headers = mapOf("Authorization" to "Basic test")
        source.open(DataSpec.Builder().setUri("https://dav.test/audio")
            .setHttpRequestHeaders(headers).build())
        assertEquals("https://dav.test/audio", upstream.spec.uri.toString())
        assertEquals(headers, upstream.spec.httpRequestHeaders)
        source.close()
    }

    @Test
    fun `加载线程中断不记为解析失败且保留中断语义`() {
        val entered = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val interrupted = AtomicReference(false)
        val errors = mutableListOf<Throwable?>()
        val source = factory(RecordingDataSource(), resolveTrack = {
            entered.countDown()
            awaitCancellation()
        }, onError = { _, error -> errors += error }).createDataSource()
        val worker = Thread {
            try { source.open(onlineSpec()) }
            catch (e: Throwable) { failure.set(e) }
            finally { interrupted.set(Thread.interrupted()) }
        }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            worker.interrupt()
            worker.join(5000)
            assertFalse(worker.isAlive)
            assertTrue(failure.get() is InterruptedIOException)
            assertTrue(failure.get().cause is InterruptedException)
            assertTrue(interrupted.get())
            assertTrue(errors.isEmpty())
        } finally {
            worker.interrupt()
            worker.join(5000)
        }
    }

    @Test
    fun `协程取消不记为音源失败`() {
        val errors = mutableListOf<Throwable?>()
        val source = factory(RecordingDataSource(), resolveTrack = { throw CancellationException("取消") },
            onError = { _, error -> errors += error }).createDataSource()
        try {
            source.open(onlineSpec())
            fail("取消后应停止加载")
        } catch (e: InterruptedIOException) {
            assertTrue(e.cause is CancellationException)
        }
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `真实解析失败仍上报错误`() {
        val failure = IOException("网络失败")
        val errors = mutableListOf<Throwable?>()
        val source = factory(RecordingDataSource(), resolveTrack = { throw failure },
            onError = { _, error -> errors += error }).createDataSource()
        try {
            source.open(onlineSpec())
            fail("解析失败应抛出异常")
        } catch (e: IOException) {
            assertTrue(e.cause is IOException)
            assertEquals("网络失败", e.cause?.message)
        }
        assertEquals(1, errors.size)
        assertTrue(errors.single() is IOException)
        assertEquals("网络失败", errors.single()?.message)
    }

    @Test
    fun `本地已读出数据后再次失败必须要求重建播放器`() {
        val upstream = RecordingDataSource("abcdefgh".toByteArray())
        var calls = 0
        val source = factory(
            upstream,
            resolveTrack = { calls++; OnlinePlayableUrl("https://cdn.test/online") },
            findDownloaded = { "content://downloads/local" },
        ).createDataSource()
        source.open(onlineSpec())
        assertEquals(4, source.read(ByteArray(4), 0, 4))
        upstream.failNextOpen = true
        try {
            source.open(onlineSpec())
            fail("本地重开应失败")
        } catch (_: IOException) { }
        // 已经给解码器送过字节：必须重建播放器并重新定位，不能原地换源。
        try {
            source.open(onlineSpec())
            fail("必须要求重建播放器")
        } catch (_: OnlineResourceChangedException) { }
        assertEquals(1, calls)
    }

    @Test
    fun `读取失败会通知解析器丢弃该地址`() {
        val invalidated = AtomicReference<String>()
        val upstream = RecordingDataSource()
        val source = OnlineResolvingDataSourceFactory(
            upstreamFactory = DataSource.Factory { upstream },
            resolver = object : OnlineTrackResolver {
                override suspend fun resolve(ref: OnlineTrackRef) = OnlinePlayableUrl("https://cdn.test/audio")
                override fun invalidatePlayableUrl(url: String) { invalidated.set(url) }
            },
            findDownloaded = { null },
        ).createDataSource()
        source.open(onlineSpec())
        upstream.failReads = 1
        try {
            source.read(ByteArray(4), 0, 4)
            fail("读取失败应抛错")
        } catch (_: IOException) { }
        assertEquals("https://cdn.test/audio", invalidated.get())
        source.close()
    }

    @Test
    fun `资源变化重建后新数据源从原时间位置打开在线地址`() {
        val upstream = RecordingDataSource("abcdefgh".toByteArray())
        var calls = 0
        val factory = factory(
            upstream,
            resolveTrack = { calls++; OnlinePlayableUrl("https://cdn.test/online$calls") },
            findDownloaded = { "content://downloads/local" },
        )
        val source = factory.createDataSource()
        source.open(onlineSpec())
        assertEquals(4, source.read(ByteArray(4), 0, 4))
        upstream.failNextOpen = true
        try {
            source.open(onlineSpec())
            fail("本地重开应失败")
        } catch (_: IOException) { }
        try {
            source.open(onlineSpec())
            fail("已消费数据时必须要求重建")
        } catch (_: OnlineResourceChangedException) { }

        // 播放器重建后的新解码会话：从原位置打开新的在线地址，不能从 0 开始或复用旧字节。
        val rebuilt = factory.createDataSource()
        rebuilt.open(onlineSpec().buildUpon().setPosition(4).build())
        assertEquals("https://cdn.test/online2", upstream.spec.uri.toString())
        assertEquals(4L, upstream.spec.position)
        assertEquals(4, rebuilt.read(ByteArray(4), 0, 4))
        rebuilt.close()
    }

    @Test
    fun `解析耗时上报本地匹配与阶段`() {
        var stages: Map<String, Long>? = null
        var local = true
        val source = factory(RecordingDataSource(), onTiming = { _, _, isLocal, detail ->
            local = isLocal
            stages = detail
        }).createDataSource()
        source.open(onlineSpec())
        source.close()
        assertFalse(local)
        assertTrue(stages != null && stages!!.containsKey("findDownloaded"))
    }

    @Test
    fun `解析进行中切歌后旧请求读完仍归入原会话`() = withDiagnosticStore { store, events ->
        val diagnostics = com.muses.player.core.data.diagnostics.Diagnostics
        store.beginPlayback("甲", "user"); store.bindTrack(onlineSpec().uri.toString(), "甲")
        val first = store.context()
        val source = factory(RecordingDataSource("abcd".toByteArray()), resolveTrack = {
            assertEquals(first["session"], diagnostics.correlation()["session"])
            store.beginPlayback("乙", "user")
            assertEquals(first["session"], diagnostics.correlation()["session"])
            OnlinePlayableUrl("https://cdn.test/audio?signature=private")
        }).createDataSource()
        source.open(onlineSpec()); source.read(ByteArray(4), 0, 4); source.read(ByteArray(4), 0, 4); source.close()
        assertTrue(events.any { it.first == "data_eof" })
        assertTrue(events.filter { it.first.startsWith("data_") || it.first.startsWith("resolve_") }
            .all { it.second["session"] == first["session"] && it.second["song"] == first["song"] })
        assertTrue(events.none { it.second.values.any { value -> value.contains("signature=private") } })
    }

    @Test
    fun `点击乙后甲分段重开不能借用乙的会话`() = withDiagnosticStore { store, events ->
        store.beginPlayback("甲", "user"); store.bindTrack(onlineSpec().uri.toString(), "甲")
        val first = store.context()
        val source = factory(RecordingDataSource("abcdefgh".toByteArray())).createDataSource()
        source.open(onlineSpec()); source.read(ByteArray(2), 0, 2); source.close()
        store.beginPlayback("乙", "user")
        val second = store.context(); events.clear()
        source.open(onlineSpec().buildUpon().setPosition(2).build()); source.read(ByteArray(2), 0, 2); source.close()
        assertTrue(events.any { it.first == "data_open_start" && it.second["range_start"] == "2" })
        assertTrue(events.none { it.second["session"] == second["session"] || it.second["song"] == second["song"] })
        assertTrue(events.all { it.second["session"] == first["session"] })
    }

    @Test
    fun `不同曲目预加载不能借用当前歌曲会话`() = withDiagnosticStore { store, events ->
        val nextTrack = OnlineTrackRef("wy", "{\"id\":\"next\"}", "script")
        store.bindTrack(onlineSpec().uri.toString(), "甲"); store.bindTrack(nextTrack.encode(), "乙")
        store.beginPlayback("甲", "user"); val first = store.context()
        val source = factory(RecordingDataSource()).createDataSource()
        source.open(DataSpec.Builder().setUri(nextTrack.encode()).build()); source.close()
        assertTrue(events.any { it.first == "resolve_start" })
        assertTrue(events.none { it.second["session"] == first["session"] || it.second["song"] == first["song"] })
        assertTrue(events.all { it.second["song"] == store.anonymous("乙") })
    }

    @Test
    fun `解析失败后关闭不把开机时长记录为请求耗时`() = withDiagnosticStore { _, events ->
        val source = factory(RecordingDataSource(), resolveTrack = { throw IOException("解析失败") }).createDataSource()
        try { source.open(onlineSpec()); fail("预期解析失败") } catch (_: IOException) { }
        source.close()
        val elapsed = events.filter { it.first == "data_close" }.mapNotNull { it.second["elapsed_ms"] }
        assertTrue(elapsed.all { it == "未知" || (it.toLongOrNull()?.let { ms -> ms in 0..1000 } == true) })
    }

    @Test
    fun `甲一数据源创建后同歌甲二意图再首开不能借甲二会话`() = withDiagnosticStore { store, events ->
        store.bindTrack(onlineSpec().uri.toString(), "甲")
        store.beginPlayback("甲", "user"); val first = store.context()
        val source = factory(RecordingDataSource()).createDataSource()
        store.beginPlayback("甲", "user"); val second = store.context()
        assertFalse(first["session"] == second["session"])
        source.open(onlineSpec()); source.close()
        assertTrue(events.any { it.first == "resolve_start" })
        assertTrue(events.none { it.second["session"] == second["session"] })
        assertTrue(events.all { it.second["song"] == first["song"] })
    }
    private fun withDiagnosticStore(block: (com.muses.player.core.data.diagnostics.PersistentDiagnostics, MutableList<Pair<String, Map<String, String>>>) -> Unit) {
        val diagnostics = com.muses.player.core.data.diagnostics.Diagnostics
        val priorSink = diagnostics.sink; val priorContext = diagnostics.contextProvider; val priorIdentify = diagnostics.identify
        val priorBinding = diagnostics.trackBinding; val priorTrackContext = diagnostics.trackContextProvider
        val priorCapturedContext = diagnostics.capturedTrackContextProvider
        val directory = java.nio.file.Files.createTempDirectory("数据源诊断测试").toFile()
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        com.muses.player.core.data.diagnostics.PersistentDiagnostics(directory).use { store ->
            try {
                diagnostics.contextProvider = store::context; diagnostics.identify = store::anonymous
                diagnostics.trackBinding = store::bindTrack; diagnostics.trackContextProvider = store::contextForTrack
                diagnostics.capturedTrackContextProvider = store::contextForTrack
                diagnostics.sink = { event, fields -> events += event to fields }
                block(store, events)
            } finally {
                diagnostics.sink = priorSink; diagnostics.contextProvider = priorContext; diagnostics.identify = priorIdentify
                diagnostics.trackBinding = priorBinding; diagnostics.trackContextProvider = priorTrackContext
                diagnostics.capturedTrackContextProvider = priorCapturedContext
            }
        }
    }
    private fun onlineSpec() = DataSpec.Builder().setUri(OnlineTrackRef("wy", "{}", "script").encode()).build()

    private fun factory(upstream: RecordingDataSource,
        resolveTrack: suspend (OnlineTrackRef) -> OnlinePlayableUrl = { OnlinePlayableUrl("https://cdn.test/audio") },
        onError: (String, Throwable?) -> Unit = { _, _ -> },
        findDownloaded: suspend (OnlineTrackRef) -> String? = { null },
        onTiming: (String, Long, Boolean, Map<String, Long>) -> Unit = { _, _, _, _ -> }) = OnlineResolvingDataSourceFactory(
        upstreamFactory = DataSource.Factory { upstream },
        resolver = object : OnlineTrackResolver {
            override suspend fun resolve(ref: OnlineTrackRef) = resolveTrack(ref)
        },
        onResolveError = onError,
        findDownloaded = findDownloaded,
        onResolveTiming = onTiming,
    )

    private class RecordingDataSource(private val payload: ByteArray = ByteArray(0)) : BaseDataSource(false) {
        lateinit var spec: DataSpec
        var failNextOpen = false
        var failReads = 0
        private var position = 0
        override fun open(dataSpec: DataSpec): Long {
            if (failNextOpen) {
                failNextOpen = false
                throw IOException("expired")
            }
            spec = dataSpec
            position = dataSpec.position.toInt().coerceIn(0, payload.size)
            return (payload.size - position).toLong().coerceAtLeast(0)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (failReads > 0) {
                failReads--
                throw IOException("offline")
            }
            if (position >= payload.size) return -1
            val count = minOf(length, payload.size - position)
            payload.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
        override fun getUri(): Uri = spec.uri
        override fun close() = Unit
    }
}
