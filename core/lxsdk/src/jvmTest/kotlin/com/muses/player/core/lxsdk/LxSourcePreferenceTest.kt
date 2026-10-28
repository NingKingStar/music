package com.muses.player.core.lxsdk

import com.muses.player.core.lxsdk.crypto.LxCryptoJvm
import com.muses.player.core.lxsdk.http.LxHttpClient
import com.muses.player.core.lxsdk.store.LxStoredScript
import com.muses.player.core.model.online.OnlineTrackRef
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class LxSourcePreferenceTest {
    private fun script(name: String, platform: String = "kw", qualities: String = "['320k']", delay: Int = 0) = """
        lx.on('request', ({info}) => new Promise(resolve => {
          const done = () => resolve('https://cdn.test/$name/' + info.type);
          if ($delay) setTimeout(done, $delay); else done();
        }));
        lx.send('inited', {sources: {$platform: {actions:['musicUrl'],qualitys:$qualities}}});
    """.trimIndent()

    private fun repo(timeout: Long = 15000, ids: suspend () -> Set<String>) = LxScriptRepository(
        LxCryptoJvm(), LxHttpClient(HttpClient(MockEngine { respond("{}") })),
        requestTimeoutMs = timeout, preferredScriptIdsProvider = ids,
    )

    @Test fun `较慢的首选仍先于更快后备而且不加载后备`() = runBlocking {
        val repo = repo { setOf("preferred") }
        try {
            repo.register("backup", script("backup"))
            repo.register("preferred", script("preferred", delay = 80))
            val result = repo.resolveMusicUrl("kw", "{}", LxQuality.Q_320K)
            assertTrue(result.contains("/preferred/"))
            assertNull(repo.scripts().first { it.scriptId == "backup" }.engine)
        } finally { repo.close() }
    }

    @Test fun `首选坏地址后备同档位成功且不提前降档`() = runBlocking {
        val repo = repo { setOf("preferred") }
        try {
            repo.register("preferred", script("preferred", qualities = "['320k','flac']"))
            repo.register("backup", script("backup", qualities = "['flac']"))
            val seen = mutableListOf<String>()
            val result = repo.resolveMusicUrlInfo("kw", "{}", LxQuality.FLAC, acceptUrl = {
                seen += it; it.contains("/backup/")
            })
            assertEquals(LxQuality.FLAC, result.quality)
            assertEquals(listOf("https://cdn.test/preferred/flac", "https://cdn.test/backup/flac"), seen)
        } finally { repo.close() }
    }

    @Test fun `首选不支持的平台直接使用后备`() = runBlocking {
        val repo = repo { setOf("preferred") }
        try {
            repo.register("preferred", script("preferred"))
            repo.register("backup", script("backup", platform = "wy"))
            assertTrue(repo.resolveMusicUrl("wy", "{}", LxQuality.Q_320K).contains("/backup/"))
        } finally { repo.close() }
    }

    @Test fun `首选缺少无损不抢先返回低音质`() = runBlocking {
        val repo = repo { setOf("preferred") }
        try {
            repo.register("preferred", script("preferred"))
            repo.register("backup", script("backup", qualities = "['flac']"))
            val result = repo.resolveMusicUrlInfo("kw", "{}", LxQuality.FLAC)
            assertEquals(LxQuality.FLAC, result.quality)
            assertTrue(result.url.contains("/backup/"))
        } finally { repo.close() }
    }

    @Test fun `切换选择和删除首选后下一次请求生效`() = runBlocking {
        var choice = setOf("one")
        val repo = repo { choice }
        try {
            repo.register("one", script("one")); repo.register("two", script("two"))
            assertTrue(repo.resolveMusicUrl("kw", "{}").contains("/one/"))
            choice = setOf("two")
            assertTrue(repo.resolveMusicUrl("kw", "{}").contains("/two/"))
            repo.unregister("two")
            assertTrue(repo.resolveMusicUrl("kw", "{}").contains("/one/"))
        } finally { repo.close() }
    }

    @Test fun `首选超时仍调用后备`() = runBlocking {
        val repo = repo(timeout = 500) { setOf("preferred") }
        try {
            repo.register("preferred", script("preferred", delay = 2000))
            repo.register("backup", script("backup"))
            assertTrue(repo.resolveMusicUrl("kw", "{}").contains("/backup/"))
        } finally { repo.close() }
    }

    @Test fun `同档均失败后降档仍先用首选且下载精确档位不降档`() = runBlocking {
        val repo = repo { setOf("preferred") }
        try {
            repo.register("preferred", script("preferred", qualities = "['320k','flac']"))
            repo.register("backup", script("backup", qualities = "['320k','flac']"))
            val seen = mutableListOf<String>()
            val result = repo.resolveMusicUrlInfo("kw", "{}", LxQuality.FLAC, acceptUrl = {
                seen += it; it.endsWith("320k")
            })
            assertEquals(LxQuality.Q_320K, result.quality)
            assertTrue(result.url.contains("/preferred/"))
            assertEquals(2, seen.takeWhile { it.endsWith("flac") }.size)
            assertFailsWith<LxResolveException> {
                repo.resolveMusicUrlInfo("kw", "{}", LxQuality.FLAC, acceptUrl = { it.endsWith("320k") }, exactQuality = true)
            }
            Unit
        } finally { repo.close() }
    }

    @Test fun `当前平台首选成功不为跨平台候选加载后备`() = runBlocking {
        val repo = repo { setOf("preferred") }
        try {
            repo.register("preferred", script("preferred"))
            repo.register("backup", script("backup"))
            val resolver = LxOnlineTrackResolver(repo, candidateProvider = { _, _ -> error("不应搜索跨平台候选") })
            assertTrue(resolver.resolve(OnlineTrackRef("kw", "{}", "online")).url.contains("/preferred/"))
            assertNull(repo.scripts().first { it.scriptId == "backup" }.engine)
        } finally { repo.close() }
    }

    @Test fun `首选不提供封面时由后备补齐`() = runBlocking {
        val repo = repo { setOf("preferred") }
        try {
            repo.register("preferred", script("preferred"))
            repo.register("backup", """
                lx.on('request', () => Promise.resolve('https://cdn.test/cover.jpg'));
                lx.send('inited', {sources:{kw:{actions:['pic'],qualitys:[]}}});
            """.trimIndent())
            assertEquals("https://cdn.test/cover.jpg", repo.resolveCover("kw", "{}"))
        } finally { repo.close() }
    }

    @Test fun `内置优先只匹配启用的内置脚本`() {
        fun item(id: String, builtin: Boolean, enabled: Boolean = true) = LxStoredScript(
            id, id, "", LxScriptMeta(), 0, enabled = enabled, isBuiltin = builtin)
        val items = listOf(item("builtin", true), item("disabled", true, false), item("custom", false))
        assertEquals(setOf("builtin"), LxSourcePreference.preferredIds(LxSourcePreference.BUILTIN, items))
        assertEquals(setOf("custom"), LxSourcePreference.preferredIds("custom", items))
        assertTrue(LxSourcePreference.preferredIds("deleted", items).isEmpty())
        assertTrue(LxSourcePreference.preferredIds("", items).isEmpty())
    }
}
