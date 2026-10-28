package com.muses.player.core.lxsdk

import com.muses.player.core.lxsdk.crypto.LxCryptoJvm
import com.muses.player.core.lxsdk.http.LxHttpClient
import com.muses.player.core.model.online.OnlineTrackRef
import com.muses.player.core.model.online.OnlineTrackSession
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** 音质来源、短期复用与预热：不改动首选优先级和回退顺序。 */
class LxOnlineTrackResolverTest {
    @Test fun `读取首选缓存标识被取消后不再发起脚本请求`() = runBlocking {
        val repo = repository()
        repo.register("src", script("src"))
        val resolver = LxOnlineTrackResolver(repo, cacheKeySaltProvider = {
            throw kotlinx.coroutines.CancellationException("已取消切歌")
        })
        try {
            assertFailsWith<kotlinx.coroutines.CancellationException> { resolver.resolve(ref()) }
            assertNull(repo.scripts().single().engine)
        } finally { repo.close() }
    }

    private fun script(
        name: String,
        qualities: String = "['320k','flac']",
        hangFlac: Boolean = false,
        failMaster: Boolean = false,
        platform: String = "kw",
        delayMs: Int = 0,
        stableUrl: Boolean = false,
    ) = """
        let calls = 0;
        lx.on('request', ({info}) => new Promise((resolve, reject) => {
          if ($hangFlac && info.type === 'flac') return;
          if ($failMaster && info.type === 'master') return reject(new Error('没有母带资源'));
          const url = 'https://cdn.test/$name/' + info.type + ($stableUrl ? '' : '/' + (++calls));
          const done = () => resolve(url);
          if ($delayMs > 0) setTimeout(done, $delayMs); else done();
        }));
        lx.send('inited', {sources: {$platform: {actions: ['musicUrl'], qualitys: $qualities}}});
    """.trimIndent()

    private fun repository(ids: suspend () -> Set<String> = { emptySet() }) = LxScriptRepository(
        crypto = LxCryptoJvm(),
        httpClient = LxHttpClient(HttpClient(MockEngine { respond("{}") })),
        requestTimeoutMs = 15_000,
        preferredScriptIdsProvider = ids,
    )

    private fun ref(json: String = """{"songmid":"m1"}""") = OnlineTrackRef("kw", json, "online")

    @Test
    fun `解码失败地址被排除后改试同档其他脚本`() = runBlocking {
        val repo = repository()
        // 同一档位两个脚本：坏地址先返回（签名稳定，模拟服务端复用同一地址），好地址稍后返回。
        repo.register("bad", script("bad", qualities = "['flac']", stableUrl = true))
        repo.register("good", script("good", qualities = "['flac']", delayMs = 60))
        val resolver = LxOnlineTrackResolver(
            repo,
            urlProbe = object : com.muses.player.core.model.online.OnlinePlayableUrlProbe {
                override suspend fun canOpen(url: String) = true
                override suspend fun canOpen(url: String, expectedDurationMs: Long?) = true
            },
        )
        try {
            val first = resolver.resolve(OnlineTrackRef("kw", """{"songmid":"m-reject"}""", "online", "flac", qualityExplicit = true))
            assertTrue(first.url.contains("/bad/"), first.url)
            // 播放器解封装失败后排除该地址
            resolver.rejectPlayableUrl(first.url)
            val second = resolver.resolve(OnlineTrackRef("kw", """{"songmid":"m-reject"}""", "online", "flac", qualityExplicit = true))
            assertTrue(second.url.contains("/good/"), second.url)
        } finally {
            repo.close()
            OnlineTrackSession.clear()
        }
    }

    @Test
    fun `失败尝试的耗时也会累加`() = runBlocking {
        val repo = repository()
        repo.register("src", script("src", qualities = "['flac','master']", failMaster = true))
        val resolver = LxOnlineTrackResolver(repo, candidateProvider = { _, _ -> emptyList() })
        try {
            val result = resolver.resolve(
                OnlineTrackRef("kw", """{"songmid":"m-timing"}""", "online", "master", qualityExplicit = true),
            )
            assertTrue((result.timings["attempts"] ?: 0L) >= 2, "timings=${result.timings}")
            assertTrue((result.timings["script"] ?: 0L) >= 0, "timings=${result.timings}")
        } finally {
            repo.close()
            OnlineTrackSession.clear()
        }
    }

    @Test
    fun `当前平台不支持该档位时仍尝试同档跨平台`() = runBlocking {
        val repo = repository()
        // kw 只有 320k/flac，tx 支持 master；所有脚本已加载，档位声明完全可知。
        repo.register("kwOnly", script("kwOnly", qualities = "['320k','flac']", platform = "kw"))
        repo.register("txMaster", script("txMaster", qualities = "['master']", platform = "tx"))
        repo.loadAll()
        assertEquals(setOf(LxQuality.Q_320K, LxQuality.FLAC), repo.loadedDeclaredQualities("kw"))
        val resolver = LxOnlineTrackResolver(
            repo,
            candidateProvider = { candidate, _ -> listOf(candidate.copy(platform = "tx")) },
        )
        try {
            val result = resolver.resolve(
                OnlineTrackRef("kw", """{"songmid":"cross-1"}""", "online", "master", qualityExplicit = true),
            )
            assertEquals("master", result.quality)
            assertTrue(result.url.contains("/txMaster/master/"), result.url)
        } finally {
            repo.close()
            OnlineTrackSession.clear()
        }
    }

    @Test
    fun `部分脚本未加载时不得断言平台档位并仍要尝试高档脚本`() = runBlocking {
        // 只预热低档脚本，高阶脚本尚未加载：此时「kw 不支持 master」只是未知，不能当作事实。
        val repo = repository { setOf("low") }
        repo.register("low", script("low", qualities = "['320k']", platform = "kw"))
        repo.register("high", script("high", qualities = "['master']", platform = "kw"))
        repo.warmPreferred()
        assertNotNull(repo.scripts().first { it.scriptId == "low" }.engine)
        assertNull(repo.scripts().first { it.scriptId == "high" }.engine)
        assertNull(repo.loadedDeclaredQualities("kw"))
        val resolver = LxOnlineTrackResolver(repo, candidateProvider = { _, _ -> emptyList() })
        try {
            val result = resolver.resolve(
                OnlineTrackRef("kw", """{"songmid":"partial-1"}""", "online", "master", qualityExplicit = true),
            )
            assertEquals("master", result.quality)
            assertTrue(result.url.contains("/high/master/"), result.url)
        } finally {
            repo.close()
            OnlineTrackSession.clear()
        }
    }

    @Test
    fun `显式音质优先于当前设置且旧引用不再覆盖设置`() = runBlocking {
        val repo = repository()
        repo.register("src", script("src"))
        var setting = LxQuality.Q_320K
        val resolver = LxOnlineTrackResolver(repo, preferredQualityProvider = { setting })
        try {
            val explicit = OnlineTrackRef("kw", """{"songmid":"m1"}""", "online", "flac", qualityExplicit = true)
            assertEquals("flac", resolver.resolve(explicit).quality)

            val stale = OnlineTrackRef("kw", """{"songmid":"m2"}""", "online", "flac")
            assertEquals("320k", resolver.resolve(stale).quality)

            setting = LxQuality.FLAC
            assertEquals("flac", resolver.resolve(stale).quality)
        } finally {
            repo.close()
            OnlineTrackSession.clear()
        }
    }

    @Test
    fun `短期复用命中且地址失效后重新解析`() = runBlocking {
        val repo = repository()
        repo.register("src", script("src"))
        val resolver = LxOnlineTrackResolver(repo)
        try {
            val first = resolver.resolve(ref())
            val second = resolver.resolve(ref())
            assertEquals(first.url, second.url)
            assertEquals(1L, second.timings["cacheHit"])

            resolver.invalidatePlayableUrl(first.url)
            val third = resolver.resolve(ref())
            assertNotEquals(first.url, third.url)
        } finally {
            repo.close()
            OnlineTrackSession.clear()
        }
    }

    @Test
    fun `首选或脚本集合变化使复用失效`() = runBlocking {
        val repo = repository()
        repo.register("src", script("src"))
        var salt = "a"
        val resolver = LxOnlineTrackResolver(repo, cacheKeySaltProvider = { salt })
        try {
            val first = resolver.resolve(ref())
            salt = "b"
            val second = resolver.resolve(ref())
            assertNotEquals(first.url, second.url)
        } finally {
            repo.close()
            OnlineTrackSession.clear()
        }
    }

    @Test
    fun `脚本明确不支持的档位直接跳过`() = runBlocking {
        val repo = repository()
        repo.register("only320", script("only320", qualities = "['320k']"))
        repo.loadAll()
        val resolver = LxOnlineTrackResolver(repo, candidateProvider = { _, _ -> emptyList() })
        try {
            val result = resolver.resolve(
                OnlineTrackRef("kw", """{"songmid":"m1"}""", "online", "flac", qualityExplicit = true),
            )
            assertEquals("320k", result.quality)
            assertEquals(1L, result.timings["skip.flac"])
        } finally {
            repo.close()
            OnlineTrackSession.clear()
        }
    }

    @Test
    fun `候选搜索超时不会挤掉后续档位`() = runBlocking {
        val repo = repository()
        repo.register("hangFlac", script("hangFlac", hangFlac = true))
        val resolver = LxOnlineTrackResolver(
            repo,
            candidateProvider = { _, _ -> awaitCancellation() },
            overallTimeoutMs = 4_000,
            platformTimeoutMs = 600,
            candidateSearchTimeoutMs = 600,
            candidateTimeoutMs = 300,
            perTierBudgetMs = 800,
            minAttemptMs = 150,
        )
        try {
            val result = resolver.resolve(
                OnlineTrackRef("kw", """{"songmid":"m1"}""", "online", "flac", qualityExplicit = true),
            )
            assertEquals("320k", result.quality)
            assertTrue(result.url.contains("/320k/"), result.url)
        } finally {
            repo.close()
            OnlineTrackSession.clear()
        }
    }

    @Test
    fun `当前平台卡住仍给跨平台同档保留尝试时间`() = runBlocking {
        val repo = repository()
        repo.register("kwSlow", script("kwSlow", hangFlac = true))
        repo.register("txFlac", script("txFlac", qualities = "['flac']", platform = "tx"))
        repo.loadAll()
        val resolver = LxOnlineTrackResolver(
            repo,
            candidateProvider = { candidate, _ -> listOf(candidate.copy(platform = "tx")) },
            overallTimeoutMs = 4_000,
            platformTimeoutMs = 2_000,
            candidateTimeoutMs = 300,
            perTierBudgetMs = 800,
            minAttemptMs = 100,
        )
        try {
            val result = resolver.resolve(ref().copy(quality = "flac", qualityExplicit = true))
            assertEquals("flac", result.quality)
            assertTrue(result.url.contains("/txFlac/flac/"), result.url)
        } finally {
            repo.close()
            OnlineTrackSession.clear()
        }
    }

    @Test
    fun `母带可用时直接按母带解析`() = runBlocking {
        val repo = repository()
        repo.register("src", script("src", qualities = "['flac','master']"))
        val resolver = LxOnlineTrackResolver(repo, candidateProvider = { _, _ -> emptyList() })
        try {
            val result = resolver.resolve(
                OnlineTrackRef("kw", """{"songmid":"m-master-ok"}""", "online", "master", qualityExplicit = true),
            )
            assertEquals("master", result.quality)
            assertTrue(result.url.contains("/master/"), result.url)
        } finally {
            repo.close()
            OnlineTrackSession.clear()
        }
    }

    @Test
    fun `母带播放不可用时逐档回退并保留请求档位`() = runBlocking {
        val repo = repository()
        repo.register("src", script("src", qualities = "['flac','master']", failMaster = true))
        val resolver = LxOnlineTrackResolver(repo, candidateProvider = { _, _ -> emptyList() })
        try {
            val result = resolver.resolve(
                OnlineTrackRef("kw", """{"songmid":"m-master"}""", "online", "master", qualityExplicit = true),
            )
            assertEquals("flac", result.quality)
            assertEquals("master", result.requestedQuality)
        } finally {
            repo.close()
            OnlineTrackSession.clear()
        }
    }

    @Test
    fun `空闲预热只加载首选脚本`() = runBlocking {
        val repo = repository { setOf("preferred") }
        repo.register("preferred", script("preferred"))
        repo.register("backup", script("backup"))
        try {
            assertTrue(repo.warmPreferred())
            assertNotNull(repo.scripts().first { it.scriptId == "preferred" }.engine)
            assertNull(repo.scripts().first { it.scriptId == "backup" }.engine)
        } finally {
            repo.close()
        }
    }

    @Test
    fun `全部加载后才能断言档位声明`() = runBlocking {
        val repo = repository()
        repo.register("only320", script("only320", qualities = "['320k']"))
        try {
            assertNull(repo.loadedDeclaredQualities("kw"))
            repo.loadAll()
            assertEquals(setOf(LxQuality.Q_320K), repo.loadedDeclaredQualities("kw"))
        } finally {
            repo.close()
        }
    }
}
