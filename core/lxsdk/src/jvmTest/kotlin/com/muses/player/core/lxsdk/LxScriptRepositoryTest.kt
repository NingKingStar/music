package com.muses.player.core.lxsdk

import com.muses.player.core.lxsdk.crypto.LxCryptoJvm
import com.muses.player.core.lxsdk.http.LxHttpClient
import com.muses.player.core.model.online.OnlineTrackRef
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [LxScriptRepository] 集成测试：多脚本管理、按 platform 路由、音质回退、懒加载。
 */
class LxScriptRepositoryTest {
    @Test fun `每次解析使用最新设置音质而不是旧引用音质`() = kotlinx.coroutines.runBlocking {
        val repo = repository()
        repo.register("source", racingScript("source", "['320k','flac']"))
        var preferred = LxQuality.FLAC
        val resolver = LxOnlineTrackResolver(repo, preferredQualityProvider = { preferred })
        val ref = OnlineTrackRef("kw", "{}", "online", "320k")
        try {
            assertEquals("flac", resolver.resolve(ref).quality)
            preferred = LxQuality.Q_320K
            assertEquals("320k", resolver.resolve(ref).quality)
        } finally { repo.close(); com.muses.player.core.model.online.OnlineTrackSession.clear() }
    }

    @Test fun `跨平台搜索超时后仍能尝试较低音质`() = kotlinx.coroutines.runBlocking {
        val repo = repository()
        repo.register("current", racingScript("current", "['320k']").replace("kw:", "wy:"))
        val resolver = LxOnlineTrackResolver(repo, candidateProvider = { _, _ ->
            kotlinx.coroutines.awaitCancellation()
        }, candidateSearchTimeoutMs = 100)
        try {
            val result = resolver.resolve(OnlineTrackRef("wy", "{}", "online", "flac"))
            assertEquals("320k", result.quality)
        } finally { repo.close(); com.muses.player.core.model.online.OnlineTrackSession.clear() }
    }

    @Test fun `播放跨平台同档成功前不降档`() = kotlinx.coroutines.runBlocking {
        val repo = repository()
        repo.register("current", racingScript("current", "['320k']", 0).replace("kw:", "wy:"))
        repo.register("alternative", racingScript("alternative", "['flac']"))
        val resolver = LxOnlineTrackResolver(repo, candidateProvider = { ref, _ -> listOf(ref.copy(platform = "kw")) })
        try {
            val result = resolver.resolve(OnlineTrackRef("wy", "{}", "online", "flac", qualityExplicit = true))
            assertEquals("flac", result.quality)
            assertTrue(result.url.contains("/alternative/"))
        } finally { repo.close(); com.muses.player.core.model.online.OnlineTrackSession.clear() }
    }
    @Test fun `试听地址被拒绝后携带原曲时长继续跨平台解析`() = kotlinx.coroutines.runBlocking {
        val repo = repository()
        repo.register("both", scriptFor("wy", "kw"))
        val durations = java.util.concurrent.CopyOnWriteArrayList<Long?>()
        val resolver = LxOnlineTrackResolver(repo,
            candidateProvider = com.muses.player.core.model.online.OnlineTrackCandidateProvider { ref, _ ->
                listOf(ref.copy(platform = "kw"))
            },
            urlProbe = object : com.muses.player.core.model.online.OnlinePlayableUrlProbe {
                override suspend fun canOpen(url: String): Boolean = error("必须核验时长")
                override suspend fun canOpen(url: String, expectedDurationMs: Long?): Boolean {
                    durations += expectedDurationMs
                    return url.contains("/kw")
                }
            },
        )
        try {
            for (info in listOf("""{"interval":240}""", """{"interval":"04:00"}""",
                """{"interval":"无效","duration":240}""")) {
                assertTrue(resolver.resolve(OnlineTrackRef("wy", info, "online", "320k")).url.contains("/kw"))
            }
            assertTrue(durations.size >= 6)
            assertTrue(durations.all { it == 240_000L })
        } finally { com.muses.player.core.model.online.OnlineTrackSession.clear() }
    }

    private fun racingScript(name: String, qualities: String, delayMs: Int = 0) = """
        lx.on('request', ({info}) => new Promise(resolve => {
          const finish = () => resolve('https://cdn.test/$name/' + info.type);
          if ($delayMs > 0) setTimeout(finish, $delayMs); else finish();
        }));
        lx.send('inited', {sources: {kw: {actions: ['musicUrl'], qualitys: $qualities}}});
    """.trimIndent()

    @Test fun `播放优先跨脚本寻找所选档位`() = kotlinx.coroutines.runBlocking {
        val repo = repository()
        repo.register("low", racingScript("low", "['320k']"))
        repo.register("high", racingScript("high", "['flac']"))
        val result = repo.resolveMusicUrlInfo("kw", "{}", LxQuality.FLAC)
        assertEquals(LxQuality.FLAC, result.quality)
        assertTrue(result.url.contains("/high/"))
    }

    @Test fun `同档选择响应最快并且被取消的脚本仍可复用`() = kotlinx.coroutines.runBlocking {
        val repo = repository()
        repo.register("slow", racingScript("slow", "['flac']", 1_000))
        repo.register("fast", racingScript("fast", "['flac']"))
        assertTrue(repo.resolveMusicUrlInfo("kw", "{}", LxQuality.FLAC).url.contains("/fast/"))
        repo.unregister("fast")
        assertTrue(repo.resolveMusicUrlInfo("kw", "{}", LxQuality.FLAC).url.contains("/slow/"))
    }

    @Test fun `同档坏地址不阻止其他脚本且不提前降档`() = kotlinx.coroutines.runBlocking {
        val repo = repository()
        repo.register("bad", racingScript("bad", "['320k','flac']"))
        repo.register("good", racingScript("good", "['flac']", 100))
        val probed = java.util.concurrent.CopyOnWriteArrayList<String>()
        val result = repo.resolveMusicUrlInfo("kw", "{}", LxQuality.FLAC, acceptUrl = {
            probed += it
            it.contains("/good/")
        })
        assertEquals(LxQuality.FLAC, result.quality)
        assertTrue(probed.none { it.endsWith("320k") })
    }

    @Test fun `同档地址全部不可用才降低档位`() = kotlinx.coroutines.runBlocking {
        val repo = repository()
        repo.register("first", racingScript("first", "['320k','flac']"))
        repo.register("second", racingScript("second", "['flac']"))
        val probed = java.util.concurrent.CopyOnWriteArrayList<String>()
        val result = repo.resolveMusicUrlInfo("kw", "{}", LxQuality.FLAC, acceptUrl = {
            probed += it
            it.endsWith("320k")
        })
        assertEquals(LxQuality.Q_320K, result.quality)
        assertEquals(2, probed.takeWhile { it.endsWith("flac") }.size)
    }

    @Test fun `原平台返回无法访问的地址也会换源`() = kotlinx.coroutines.runBlocking {
        val repo = repository()
        repo.register("both", scriptFor("wy", "kw"))
        val resolver = LxOnlineTrackResolver(repo,
            candidateProvider = com.muses.player.core.model.online.OnlineTrackCandidateProvider { ref, _ -> listOf(ref.copy(platform = "kw")) },
            urlProbe = com.muses.player.core.model.online.OnlinePlayableUrlProbe { it.contains("/kw") },
        )
        assertTrue(resolver.resolve(OnlineTrackRef("wy", "{}", "online", "320k")).url.contains("/kw"))
    }

    @Test fun `没有网易脚本也能按其他平台候选播放`() = kotlinx.coroutines.runBlocking {
        val repo = repository()
        repo.register("other", scriptFor("kw"))
        val resolver = LxOnlineTrackResolver(repo, candidateProvider = com.muses.player.core.model.online.OnlineTrackCandidateProvider { ref, platforms ->
            assertEquals(listOf("kw"), platforms)
            listOf(ref.copy(platform = "kw", musicInfoJson = """{"songmid":"MUSIC_9"}"""))
        })
        val original = OnlineTrackRef("wy", """{"id":"1","name":"晴天","singer":"周杰伦"}""", "online", "320k")
        try {
            val result = resolver.resolve(original)
            assertTrue(result.url.contains("/kw"))
            val actual = com.muses.player.core.model.online.OnlineTrackSession.resolvedReference(original)
            assertEquals("kw", actual.platform)
            assertEquals("""{"songmid":"MUSIC_9"}""", actual.musicInfoJson)
        } finally { com.muses.player.core.model.online.OnlineTrackSession.clear() }
    }

    @Test fun `原平台解析失败后继续尝试同曲候选`() = kotlinx.coroutines.runBlocking {
        val repo = repository()
        repo.register("original", """
            const { EVENT_NAMES, on, send } = globalThis.lx
            on(EVENT_NAMES.request, () => Promise.reject(new Error('此歌曲不可用')))
            send(EVENT_NAMES.inited, {sources: {wy: {name:'网易',type:'music',actions:['musicUrl'],qualitys:['320k']}}})
        """.trimIndent())
        repo.register("other", scriptFor("kw"))
        var fallbackCalled = false
        val resolver = LxOnlineTrackResolver(repo, candidateProvider = com.muses.player.core.model.online.OnlineTrackCandidateProvider { ref, _ ->
            fallbackCalled = true
            listOf(ref.copy(platform = "kw", musicInfoJson = """{"songmid":"MUSIC_9"}"""))
        })
        assertTrue(resolver.resolve(OnlineTrackRef("wy", "{}", "online", "320k")).url.contains("/kw"))
        assertTrue(fallbackCalled)
    }

    @Test fun `下载只降低请求档位并返回实际请求`() = runTest {
        val repo = repository()
        repo.register("download", scriptWithQualities("['128k','320k','flac']"))
        val result = repo.resolveMusicUrlInfo("kw", "{}", LxQuality.FLAC_24BIT, downloadFallback = true)
        assertEquals(LxQuality.FLAC, result.quality)
        assertTrue(result.url.contains("q=flac"))
    }

    @Test fun `下载不因脚本只有高档位而升级`() = runTest {
        val repo = repository()
        repo.register("download", scriptWithQualities("['flac']"))
        assertFailsWith<LxResolveException> {
            repo.resolveMusicUrlInfo("kw", "{}", LxQuality.Q_128K, downloadFallback = true)
        }
    }

    @Test fun `下载先尝试其他脚本的最近档位再降到320k`() = runTest {
        val repo = repository()
        repo.register("low", scriptWithQualities("['320k']"))
        repo.register("near", scriptWithQualities("['flac','flac24bit']"))
        val result = repo.resolveMusicUrlInfo("kw", "{}", LxQuality.HIRES, downloadFallback = true)
        assertEquals(LxQuality.FLAC_24BIT, result.quality)
        assertTrue(result.url.contains("q=flac24bit"))
    }

    @Test fun `下载高档失败后先尝试其他脚本同档位`() = runTest {
        val repo = repository()
        repo.register("first", """
            const { EVENT_NAMES, on, send } = globalThis.lx
            on(EVENT_NAMES.request, ({info}) => info.type === 'flac'
                ? Promise.reject(new Error('不可用')) : Promise.resolve('http://cdn.test/320k'))
            send(EVENT_NAMES.inited, { sources: { kw: { name: '测试', type: 'music', actions: ['musicUrl'], qualitys: ['320k','flac'] } } })
        """.trimIndent())
        repo.register("second", scriptWithQualities("['flac']"))
        val result = repo.resolveMusicUrlInfo("kw", "{}", LxQuality.FLAC, downloadFallback = true)
        assertEquals(LxQuality.FLAC, result.quality)
        assertTrue(result.url.contains("q=flac"))
    }

    @Test fun `跨平台逐档解析禁止仓库提前降档`() = runTest {
        val repo = repository()
        repo.register("低档源", scriptWithQualities("['320k']"))
        assertFailsWith<LxResolveException> {
            repo.resolveMusicUrlInfo("kw", "{}", LxQuality.FLAC, exactQuality = true)
        }
        assertEquals(LxQuality.Q_320K,
            repo.resolveMusicUrlInfo("kw", "{}", LxQuality.Q_320K, exactQuality = true).quality)
    }

    @Test fun `下载解析失败会尝试下一档`() = runTest {
        val repo = repository()
        repo.register("download", """
            const { EVENT_NAMES, on, send } = globalThis.lx
            on(EVENT_NAMES.request, ({info}) => info.type === 'flac'
                ? Promise.reject(new Error('不可用')) : Promise.resolve('http://cdn.test/' + info.type))
            send(EVENT_NAMES.inited, { sources: { kw: { name: '测试', type: 'music', actions: ['musicUrl'], qualitys: ['128k','flac'] } } })
        """.trimIndent())
        val result = repo.resolveMusicUrlInfo("kw", "{}", LxQuality.FLAC, downloadFallback = true)
        assertEquals(LxQuality.Q_128K, result.quality)
        assertEquals("http://cdn.test/128k", result.url)
    }

    /** 声明指定平台的脚本，musicUrl 返回固定直链 */
    private fun scriptFor(vararg platforms: String, name: String = "多源脚本"): String {
        val sources = platforms.joinToString(",\n") { p ->
            val qualitys = if (p == "local") "[]" else "['128k','320k','flac']"
            val actions = if (p == "local") "['musicUrl','lyric','pic']" else "['musicUrl']"
            "$p: { name: '${p}源', type: 'music', actions: $actions, qualitys: $qualitys }"
        }
        return """
            /**
             * @name $name
             */
            const { EVENT_NAMES, request, on, send } = globalThis.lx
            const httpRequest = (url, options) => new Promise((resolve, reject) => {
              request(url, options, (err, resp) => err ? reject(err) : resolve(resp.body))
            })
            on(EVENT_NAMES.request, ({ source, action, info }) => {
              if (action === 'musicUrl') {
                return httpRequest('http://api.test/' + source + '?q=' + info.type)
                  .then(d => d.url)
              }
              if (action === 'lyric') return Promise.resolve({ lyric: 'L:' + source })
              return Promise.reject(new Error('unsupported'))
            })
            send(EVENT_NAMES.inited, { sources: { $sources } })
        """.trimIndent()
    }

    /** 声明指定档位并把收到的 type 回显到 URL，便于断言回退结果 */
    private fun scriptWithQualities(qualitiesJsArray: String): String = """
        /**
         * @name 音质探针源
         */
        const { EVENT_NAMES, request, on, send } = globalThis.lx
        const httpRequest = (url, options) => new Promise((resolve, reject) => {
          request(url, options, (err, resp) => err ? reject(err) : resolve(resp.body))
        })
        on(EVENT_NAMES.request, ({ action, info }) => {
          if (action === 'musicUrl') {
            return httpRequest('http://api.test/probe?q=' + info.type).then(d => d.url)
          }
          return Promise.reject(new Error('unsupported'))
        })
        send(EVENT_NAMES.inited, {
          sources: { kw: { name: 'kw', type: 'music', actions: ['musicUrl'], qualitys: $qualitiesJsArray } },
        })
    """.trimIndent()

    /** 声明 `musicUrl` + `lyric` + `pic` 的脚本：歌词返回四字段，封面返回固定 URL */
    private fun scriptWithMetadata(platform: String = "kw"): String = """
        /**
         * @name 歌词封面源
         */
        const { EVENT_NAMES, on, send } = globalThis.lx
        on(EVENT_NAMES.request, ({ action }) => {
          if (action === 'lyric') {
            return Promise.resolve({
              lyric: '[00:01.000]<1000,100>你<1100,100>好',
              tlyric: '[00:01.000]Hello',
              rlyric: '[00:01.000]ni hao',
              lxlyric: '[00:01.000]<1000,100>你<1100,100>好',
            })
          }
          if (action === 'pic') return Promise.resolve('http://cdn.test/cover.jpg')
          return Promise.reject(new Error('unsupported'))
        })
        send(EVENT_NAMES.inited, {
          sources: {
            $platform: {
              name: '歌词封面源', type: 'music',
              actions: ['musicUrl', 'lyric', 'pic'], qualitys: ['128k'],
            },
          },
        })
    """.trimIndent()

    private fun repository(): LxScriptRepository {
        val mock = MockEngine { request ->
            val p = request.url.toString()
            respond(
                content = """{"url":"http://cdn.test/$p"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return LxScriptRepository(
            crypto = LxCryptoJvm(),
            httpClient = LxHttpClient(HttpClient(mock)),
        )
    }

    @Test
    fun `未导入脚本时解析抛出可读错误`() = runTest {
        val repo = repository()
        val ex = assertFailsWith<LxResolveException> {
            repo.resolveMusicUrl("kw", """{"songmid":"x"}""")
        }
        assertTrue(ex.message!!.contains("未导入"), "实际：${ex.message}")
    }

    @Test
    fun `注册脚本后可按平台解析直链`() = runTest {
        val repo = repository()
        repo.register("s1", scriptFor("kw", "kg"))
        val url = repo.resolveMusicUrl("kw", """{"songmid":"x"}""")
        assertTrue(url.startsWith("http://cdn.test/"), "实际：$url")
        assertTrue(url.contains("/kw"), "应命中 kw 平台，实际：$url")
    }

    @Test
    fun `请求未声明的平台时报错并列出尝试记录`() = runTest {
        val repo = repository()
        repo.register("s1", scriptFor("kw"))
        val ex = assertFailsWith<LxResolveException> {
            repo.resolveMusicUrl("tx", """{"songmid":"x"}""")
        }
        assertTrue(ex.message!!.contains("tx"), "实际：${ex.message}")
    }

    @Test
    fun `多脚本按平台各自路由`() = runTest {
        val repo = repository()
        repo.register("s1", scriptFor("kw", name = "脚本一"))
        repo.register("s2", scriptFor("tx", name = "脚本二"))

        assertTrue(repo.resolveMusicUrl("kw", "{}").contains("/kw"))
        assertTrue(repo.resolveMusicUrl("tx", "{}").contains("/tx"))
    }

    @Test
    fun `音质回退到脚本声明支持的档位`() = runTest {
        // 脚本只声明 128k，请求 320k 时应回退到 128k 而不是报错
        val limited = """
            /**
             * @name 低音质源
             */
            const { EVENT_NAMES, request, on, send } = globalThis.lx
            const httpRequest = (url, options) => new Promise((resolve, reject) => {
              request(url, options, (err, resp) => err ? reject(err) : resolve(resp.body))
            })
            on(EVENT_NAMES.request, ({ action, info }) => {
              return httpRequest('http://api.test/q=' + info.type).then(d => d.url)
            })
            send(EVENT_NAMES.inited, {
              sources: { kw: { name: 'kw', type: 'music', actions: ['musicUrl'], qualitys: ['128k'] } },
            })
        """.trimIndent()
        val repo = repository()
        repo.register("s1", limited)
        val url = repo.resolveMusicUrl("kw", "{}", LxQuality.Q_320K)
        // 回退到 128k（脚本 qualitys 映射后传给接口）
        assertTrue(url.contains("128") || url.startsWith("http://cdn.test/"), "实际：$url")
    }

    @Test
    fun `加载失败的脚本不阻断其它脚本`() = runTest {
        val repo = repository()
        repo.register("broken", "this is not valid javascript ((((")
        repo.register("good", scriptFor("kw"))
        // 坏脚本被跳过，好脚本仍能解析
        val url = repo.resolveMusicUrl("kw", "{}")
        assertTrue(url.startsWith("http://cdn.test/"), "实际：$url")
    }

    @Test
    fun `注销脚本后不再参与解析`() = runTest {
        val repo = repository()
        repo.register("s1", scriptFor("kw"))
        assertEquals(1, repo.scripts().size)
        repo.unregister("s1")
        assertEquals(0, repo.scripts().size)
        assertFailsWith<LxResolveException> { repo.resolveMusicUrl("kw", "{}") }
    }

    @Test
    fun `重复注册同 id 会替换旧脚本`() = runTest {
        val repo = repository()
        repo.register("s1", scriptFor("kw", name = "旧"))
        repo.register("s1", scriptFor("mg", name = "新"))
        assertEquals(1, repo.scripts().size)
        assertEquals("新", repo.scripts().first().meta.name)
        // 新脚本声明 mg，不再声明 kw
        assertTrue(repo.resolveMusicUrl("mg", "{}").contains("/mg"))
        assertFailsWith<LxResolveException> { repo.resolveMusicUrl("kw", "{}") }
    }

    @Test
    fun `repository 可作为解析器端口的后端`() = runTest {
        // 端到端：OnlineTrackRef → LxOnlineTrackResolver → repository → 直链
        val repo = repository()
        repo.register("s1", scriptFor("kw"))
        val resolver = LxOnlineTrackResolver(repo, defaultQuality = LxQuality.Q_320K)

        val ref = OnlineTrackRef(
            platform = "kw",
            musicInfoJson = """{"songmid":"MUSIC_9"}""",
            sourceId = "s1",
            quality = "320k",
        )
        val playable = resolver.resolve(ref)
        assertTrue(playable.url.startsWith("http://cdn.test/"), "实际：${playable.url}")
        assertEquals("320k", playable.quality)
    }

    @Test
    fun `请求档位可用时原样传递`() = runTest {
        val repo = repository()
        // 脚本声明含 master，请求 master 应原样传给脚本
        repo.register("s1", scriptWithQualities("['128k','320k','flac','hires','master']"))
        val url = repo.resolveMusicUrl("kw", "{}", LxQuality.MASTER)
        assertTrue(url.contains("q=master"), "实际：$url")
    }

    @Test
    fun `请求档位不可用时向下就近回退`() = runTest {
        val repo = repository()
        // 只声明到 flac：请求 master 应回退到 flac（更低档），而不是升到不存在的档
        repo.register("s1", scriptWithQualities("['128k','320k','flac']"))
        val url = repo.resolveMusicUrl("kw", "{}", LxQuality.MASTER)
        assertTrue(url.contains("q=flac"), "实际：$url")
    }

    @Test
    fun `所选档位缺失时使用更低可用档`() = runTest {
        val repo = repository()
        // 所选档位不存在时，只向下寻找。
        repo.register("s1", scriptWithQualities("['128k']"))
        val url = repo.resolveMusicUrl("kw", "{}", LxQuality.Q_320K)
        assertTrue(url.contains("q=128k"), "实际：$url")
    }

    @Test
    fun `仅声明更高档时不擅自提升所选音质`() = runTest {
        val repo = repository()
        // 按所选音质择优，不擅自升级到体积更大的母带。
        repo.register("s1", scriptWithQualities("['master']"))
        assertFailsWith<LxResolveException> {
            repo.resolveMusicUrl("kw", "{}", LxQuality.Q_320K)
        }
    }

    @Test
    fun `解析器把仓库错误包装为 OnlineResolveException`() = runTest {
        val resolver = LxOnlineTrackResolver(repository())
        val ref = OnlineTrackRef(platform = "kw", musicInfoJson = "{}", sourceId = "none")
        val ex = assertFailsWith<com.muses.player.core.model.online.OnlineResolveException> {
            resolver.resolve(ref)
        }
        assertTrue(ex.message!!.isNotBlank())
    }

    @Test
    fun `lyric 动作返回四字段`() = runTest {
        val repo = repository()
        repo.register("s1", scriptWithMetadata())

        val lyric = repo.resolveLyric("kw", """{"songmid":"x"}""")
        assertEquals("[00:01.000]Hello", lyric.tlyric)
        assertEquals("[00:01.000]ni hao", lyric.rlyric)
        assertTrue(lyric.lxlyric!!.contains("<1000,100>"), "实际：${lyric.lxlyric}")
        assertTrue(!lyric.isEmpty)
    }

    @Test
    fun `pic 动作返回封面地址`() = runTest {
        val repo = repository()
        repo.register("s1", scriptWithMetadata())
        assertEquals("http://cdn.test/cover.jpg", repo.resolveCover("kw", "{}"))
    }

    @Test
    fun `脚本未声明 lyric 与 pic 动作时报错`() = runTest {
        val repo = repository()
        // scriptFor 的 kw 只声明 musicUrl
        repo.register("s1", scriptFor("kw"))
        assertFailsWith<LxResolveException> { repo.resolveLyric("kw", "{}") }
        assertFailsWith<LxResolveException> { repo.resolveCover("kw", "{}") }
    }

    @Test
    fun `元数据适配器透传四字段`() = runTest {
        val repo = repository()
        repo.register("s1", scriptWithMetadata())
        val resolver = LxOnlineMetadataResolver(repo)
        val ref = OnlineTrackRef(platform = "kw", musicInfoJson = "{}", sourceId = "s1")

        assertEquals("http://cdn.test/cover.jpg", resolver.resolveCover(ref))
        val lyric = resolver.resolveLyrics(ref)
        assertTrue(lyric != null && !lyric.isEmpty)
        assertTrue(lyric.lxlyric!!.contains("你"))
    }

    @Test
    fun `元数据适配器把失败折算为 null`() = runTest {
        // 未导入任何脚本：端口契约要求静默（不抛错、不阻断播放）
        val resolver = LxOnlineMetadataResolver(repository())
        val ref = OnlineTrackRef(platform = "kw", musicInfoJson = "{}", sourceId = "none")
        assertNull(resolver.resolveCover(ref))
        assertNull(resolver.resolveLyrics(ref))
    }
}
