package com.muses.player.core.lyrics

import com.muses.player.core.lyrics.amll.*
import com.muses.player.core.lyrics.http.LyricsHttp
import com.muses.player.core.model.lyrics.*
import com.muses.player.core.model.online.*
import com.muses.player.core.model.scrape.MatchConfidence
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Test
import kotlin.test.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class ProgressiveLyricsLoaderTest {
    private val query = OnlineLyricsQuery("song-1", "原曲", "歌手", "专辑", 180.0,
        OnlineTrackRef("kw", """{"rid":"1","name":"原曲"}""", "online"))
    private fun provider(source: OnlineLyricsSource = OnlineLyricsSource.KW,
        block: suspend () -> OnlineLyricsProviderHit?) = object : LyricsProvider {
        override val id = source
        override suspend fun searchLyrics(query: OnlineLyricsQuery) = block()
    }
    private fun line(text: String = "原曲") = OnlineLyricsProviderHit("[00:05.00]$text\n[00:10.00]下一句", OnlineLyricsFormat.LRC)
    private class Amll(private val block: suspend () -> AmllMatchResult = { AmllMatchResult.Fail(AmllFailReason.NO_MATCH) }) :
        AmllTtmlDbClient(LyricsHttp(), AmllIndexRepository { "" }) {
        override suspend fun match(query: AmllMatchQuery) = block()
    }
    private val word = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="00:05.000" end="00:07.000"><span begin="00:05.000" end="00:06.000">精</span><span begin="00:06.000" end="00:07.000">准</span></p></div></body></tt>"""
    private suspend fun <T> loader(providers: List<LyricsProvider>, amll: Amll = Amll(), directory: File? = null,
        block: suspend (ProgressiveLyricsLoader) -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val loader = ProgressiveLyricsLoader(amll, providers, directory?.let { { it } }, scope,
            totalTimeoutMs = 650, providerTimeoutMs = 300, hedgeDelayMs = 20)
        return try { block(loader) } finally { scope.cancel() }
    }

    @Test fun `慢原平台和挂起脚本不阻止快速备用歌词`() = runBlocking {
        val metadata = object : OnlineTrackMetadataResolver {
            override suspend fun resolveCover(ref: OnlineTrackRef): String? = error("歌词不得等待封面")
            override suspend fun resolveLyrics(ref: OnlineTrackRef): OnlineTrackLyrics? = awaitCancellation()
        }
        loader(listOf(provider { awaitCancellation() }, provider(OnlineLyricsSource.TX) { line("快速") })) { loader ->
            val first = withTimeout(1_000) { loader.observe(query, metadata).first() }
            assertEquals("tx", first.source)
            assertEquals("快速", first.document.lines.first().text)
            assertTrue(first.elapsedMs < 600, "首屏耗时=${first.elapsedMs}")
        }
    }

    @Test fun `先上屏行歌词再升级逐字且保留时间轴`() = runBlocking {
        loader(listOf(provider { line() }), Amll { delay(70); AmllMatchResult.Ok(word, "test.ttml", 125, MatchConfidence.HIGH) }) { loader ->
            val updates = withTimeout(1_500) { loader.observe(query).toList() }
            assertEquals("kw", updates.first().source)
            assertEquals("amll", updates.last().source)
            assertTrue(updates.last().document.lines.any { it.syllables.isNotEmpty() })
            assertEquals(5_000L, updates.last().document.lines.first().timeMs)
        }
    }

    @Test fun `两个订阅合并请求且取消其中一个不取消另一个`() = runBlocking {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        loader(listOf(provider { calls.incrementAndGet(); gate.await(); line() })) { loader ->
            coroutineScope {
                val one = async { loader.observe(query).first() }
                val two = async { loader.observe(query).first() }
                withTimeout(1_000) { while (calls.get() == 0) delay(5) }
                delay(30); one.cancelAndJoin(); gate.complete(Unit)
                assertEquals("原曲", withTimeout(1_000) { two.await() }.document.lines.first().text)
                assertEquals(1, calls.get())
            }
        }
    }

    @Test fun `全部订阅取消后请求退出且不会负缓存取消结果`() = runBlocking {
        val calls = AtomicInteger(); val cancelled = CompletableDeferred<Unit>()
        loader(listOf(provider {
            if (calls.incrementAndGet() == 1) try { awaitCancellation() }
            finally { cancelled.complete(Unit) }
            else line()
        })) { loader ->
            coroutineScope {
                val job = launch { loader.observe(query).collect() }
                withTimeout(1_000) { while (calls.get() == 0) delay(5) }
                job.cancelAndJoin(); withTimeout(1_000) { cancelled.await() }
                assertEquals("原曲", withTimeout(1_000) { loader.observe(query).first() }.document.lines.first().text)
                assertEquals(2, calls.get())
            }
        }
    }

    @Test fun `新实例读磁盘缓存且专辑版本不同不会复用`() = runBlocking {
        val dir = Files.createTempDirectory("muses-lyrics-").toFile()
        try {
            loader(listOf(provider { line("缓存") }), directory = dir) { it.observe(query).toList() }
            val calls = AtomicInteger()
            loader(listOf(provider { calls.incrementAndGet(); line("新版") }), directory = dir) { loader ->
                assertTrue(loader.observe(query.copy(songId = "另一个UI的ID")).first().fromCache)
                assertEquals(0, calls.get())
                assertEquals("新版", loader.observe(query.copy(album = "现场专辑")).first().document.lines.first().text)
                assertEquals(1, calls.get())
            }
        } finally {
            check(dir.canonicalFile.parentFile == File(System.getProperty("java.io.tmpdir")).canonicalFile)
            dir.deleteRecursively()
        }
    }

    @Test fun `后到的行歌词不覆盖先到的逐字歌词`() = runBlocking {
        val metadata = object : OnlineTrackMetadataResolver {
            override suspend fun resolveCover(ref: OnlineTrackRef): String? = null
            override suspend fun resolveLyrics(ref: OnlineTrackRef) = OnlineTrackLyrics(lxlyric = "[00:05.000]<5000,200>精<5200,200>准")
        }
        loader(listOf(provider { delay(100); line("低档") })) { loader ->
            val results = loader.observe(query, metadata).toList()
            assertTrue(results.isNotEmpty())
            assertTrue(results.all { it.document.lines.any { line -> line.syllables.isNotEmpty() } })
        }
    }

    @Test fun `无结果短暂退避但手动重试可以立即重新请求`() = runBlocking {
        val calls = AtomicInteger()
        loader(listOf(provider { calls.incrementAndGet(); null })) { loader ->
            assertTrue(loader.observe(query).toList().isEmpty())
            assertTrue(loader.observe(query).toList().isEmpty())
            assertEquals(1, calls.get())
            loader.invalidate(query.songId)
            assertTrue(loader.observe(query).toList().isEmpty())
            assertEquals(2, calls.get())
        }
    }

    @Test fun `已有内嵌逐字歌词不被网络行歌词降级`() = runBlocking {
        loader(listOf(provider { delay(60); line("低档") })) { loader ->
            val updates = loader.observe(query, embeddedText = word).toList()
            assertEquals("local", updates.first().source)
            assertTrue(updates.all { it.document.lines.any { line -> line.syllables.isNotEmpty() } })
        }
    }

    @Test fun `已下载歌曲的本地歌词读取无需等待网络`() = runBlocking {
        val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val loader = ProgressiveLyricsLoader(Amll(), listOf(provider { awaitCancellation() }), scope = work,
                totalTimeoutMs = 650, localLyrics = { "[00:01.00]已下载歌词" })
            val first = withTimeout(700) { loader.observe(query).first() }
            assertEquals("local", first.source)
            assertEquals("已下载歌词", first.document.lines.first().text)
        } finally { work.cancel() }
    }

    @Test fun `多首并发查询共享两个网络名额`() = runBlocking {
        val active = AtomicInteger(); val maximum = AtomicInteger()
        loader(listOf(provider {
            val count = active.incrementAndGet(); maximum.updateAndGet { maxOf(it, count) }
            try { delay(70); line() } finally { active.decrementAndGet() }
        })) { loader ->
            coroutineScope { (1..4).map { index -> async {
                loader.observe(query.copy(album = "版本$index")).first()
            } }.awaitAll() }
            assertTrue(maximum.get() <= 2)
            assertEquals(0, active.get())
        }
    }
}
