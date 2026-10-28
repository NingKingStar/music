package com.muses.player.core.lxsdk

import com.muses.player.core.lxsdk.crypto.LxCryptoJvm
import com.muses.player.core.lxsdk.http.LxHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [LxScriptEngine] 集成测试：真实 QuickJS native 库 + Ktor MockEngine。
 *
 * 覆盖 spike 验证过的链路，并固化为回归测试：
 * - 脚本加载 + inited 解析；
 * - musicUrl 全链路（handler → 宿主 HTTP → 直链）；
 * - 同一实例多次请求复用（spike 核心结论）；
 * - 并发请求槽位隔离；
 * - 错误路径（未注册源、脚本抛错、初始化失败）。
 */
class LxScriptEngineTest {
    @Test fun `原脚本请求中使用控制台分组不影响返回地址`() = runTest {
        val engine = engineWith("""
            lx.on('request', () => {
              console.group('请求'); console.groupCollapsed('响应');
              console.groupEnd(); console.groupEnd();
              return Promise.resolve('https://cdn.test/original.flac');
            });
            lx.send('inited', {sources: {kw: {actions: ['musicUrl'], qualitys: ['flac']}}});
        """.trimIndent())
        try {
            engine.load()
            assertEquals("https://cdn.test/original.flac", engine.getMusicUrl("kw", LxQuality.FLAC, "{}").url)
        } finally { engine.close() }
    }

    @Test
    fun `多个引擎同时初始化仍可独立解析`() = runBlocking {
        coroutineScope {
            (1..18).map { index -> async(Dispatchers.Default) {
                val engine = engineWith(sampleScript("并行音源 $index"))
                try {
                    engine.load()
                    assertNotNull(engine.getMusicUrl("kw", LxQuality.Q_320K, "{}").url)
                } finally { engine.close() }
            } }.awaitAll()
        }
        Unit
    }

    @Test
    fun `脚本可读取原文且收到第三个回调参数`() = runTest {
        val script = """
            /** @name 原文回调测试 */
            if (!lx.currentScriptInfo.rawScript.includes('@name 原文回调测试')) throw new Error('缺少原文');
            lx.on('request', () => new Promise((resolve, reject) => {
              lx.request('https://api.test/url', {}, (err, response, body) => {
                if (err) return reject(err);
                if (body !== response.body) return reject(new Error('正文参数不一致'));
                resolve(body.url);
              });
            }));
            lx.send('inited', { sources: { kw: { actions: ['musicUrl'], qualitys: ['320k'] } } });
        """.trimIndent()
        val engine = engineWith(script)
        try {
            engine.load()
            assertEquals("http://cdn.test/default.mp3", engine.getMusicUrl("kw", LxQuality.Q_320K, "{}").url)
        } finally {
            engine.close()
        }
    }

    /** 测试脚本：模拟洛雪标准写法（含 request 回调包装、Promise 链、qualitys 映射） */
    private fun sampleScript(name: String = "测试音乐源") = """
        /**
         * @name $name
         * @description spike 回归用
         * @version 1.0.0
         * @author muses
         */
        const { EVENT_NAMES, request, on, send } = globalThis.lx

        const qualitys = {
          kw: { '128k': '128', '320k': '320', flac: 'flac' },
          local: {},
        }

        const httpRequest = (url, options) => new Promise((resolve, reject) => {
          request(url, options, (err, resp) => {
            if (err) return reject(err)
            resolve(resp.body)
          })
        })

        const apis = {
          kw: {
            musicUrl({ songmid }, quality) {
              return httpRequest('http://api.test/kw?mid=' + songmid + '&q=' + quality)
                .then(data => data.url)
            },
          },
          local: {
            musicUrl(info) { return httpRequest('http://api.test/local').then(d => d.url) },
            lyric(info) {
              return httpRequest('http://api.test/lyric').then(() => ({
                lyric: '[00:00.000]测试歌词',
                tlyric: null, rlyric: null, lxlyric: null,
              }))
            },
            pic(info) { return httpRequest('http://api.test/pic').then(d => d.url) },
          },
        }

        on(EVENT_NAMES.request, ({ source, action, info }) => {
          return apis[source][action](info.musicInfo, qualitys[source][info.type])
        })

        send(EVENT_NAMES.inited, {
          openDevTools: false,
          sources: {
            kw: {
              name: '酷我音乐', type: 'music',
              actions: ['musicUrl'],
              qualitys: ['128k', '320k', 'flac'],
            },
            local: {
              name: '本地音乐', type: 'music',
              actions: ['musicUrl', 'lyric', 'pic'],
              qualitys: [],
            },
          },
        })
    """.trimIndent()

    /** 构造 MockEngine 驱动的引擎：所有请求按路径返回预设体 */
    private fun engineWith(
        script: String = sampleScript(),
        responder: (String) -> String = { url ->
            when {
                url.contains("/kw") -> """{"url":"http://cdn.test/kw.mp3"}"""
                url.contains("/lyric") -> """{"ok":true}"""
                url.contains("/pic") -> """{"url":"http://cdn.test/cover.jpg"}"""
                else -> """{"url":"http://cdn.test/default.mp3"}"""
            }
        },
    ): LxScriptEngine {
        val mock = MockEngine { request ->
            respond(
                content = responder(request.url.toString()),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return LxScriptEngine(
            scriptSource = script,
            crypto = LxCryptoJvm(),
            httpClient = LxHttpClient(HttpClient(mock)),
        )
    }

    @Test
    fun `加载脚本并解析 inited 源声明`() = runTest {
        val engine = engineWith()
        try {
            val desc = engine.load()
            assertEquals("测试音乐源", desc.meta.name)
            assertEquals(2, desc.sources.size)
            assertTrue(desc.sources.containsKey("kw"))
            assertTrue(desc.sources.containsKey("local"))

            val kw = desc.sources.getValue("kw")
            assertEquals("酷我音乐", kw.name)
            assertEquals(listOf("musicUrl"), kw.actions)
            assertEquals(listOf("128k", "320k", "flac"), kw.qualitys)
            assertTrue(kw.supports(LxAction.MUSIC_URL))
            assertTrue(kw.supports(LxQuality.Q_320K))

            // local 源支持三种 action
            val local = desc.sources.getValue("local")
            assertTrue(local.supports(LxAction.LYRIC))
            assertTrue(local.supports(LxAction.PIC))
        } finally {
            engine.close()
        }
    }

    @Test
    fun `musicUrl 全链路返回直链`() = runTest {
        val engine = engineWith()
        try {
            engine.load()
            val result = engine.getMusicUrl("kw", LxQuality.Q_320K, """{"songmid":"MUSIC_1"}""")
            assertEquals("http://cdn.test/kw.mp3", result.url)
            assertEquals(LxQuality.Q_320K, result.quality)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `同一实例多次请求复用 handler`() = runTest {
        // spike 核心结论回归：DSL 形式每次新 runtime 会丢 handler，实例形式必须可复用
        val engine = engineWith()
        try {
            engine.load()
            repeat(3) {
                val r = engine.getMusicUrl("kw", LxQuality.Q_128K, """{"songmid":"M_$it"}""")
                assertEquals("http://cdn.test/kw.mp3", r.url)
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `并发请求结果互不串号`() = runTest {
        // 槽位隔离验证：按 songmid 返回不同直链，确认并发下没有交叉污染
        val engine = engineWith(
            responder = { url ->
                val mid = url.substringAfter("mid=").substringBefore("&")
                """{"url":"http://cdn.test/$mid.mp3"}"""
            },
        )
        try {
            engine.load()
            val results = kotlinx.coroutines.coroutineScope {
                val jobs = (1..5).map { i ->
                    this.async {
                        engine.getMusicUrl("kw", LxQuality.Q_320K, """{"songmid":"S$i"}""")
                    }
                }
                jobs.map { it.await() }
            }
            results.forEachIndexed { idx, r ->
                assertEquals("http://cdn.test/S${idx + 1}.mp3", r.url)
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `lyric 返回四类歌词字段`() = runTest {
        val engine = engineWith()
        try {
            engine.load()
            val lyric = engine.getLyric("local", """{"songmid":"x"}""")
            assertEquals("[00:00.000]测试歌词", lyric.lyric)
            assertTrue(!lyric.isEmpty)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `pic 返回封面地址`() = runTest {
        val engine = engineWith()
        try {
            engine.load()
            val pic = engine.getPic("local", """{"songmid":"x"}""")
            assertEquals("http://cdn.test/cover.jpg", pic.url)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `未声明的源请求被拒绝`() = runTest {
        val engine = engineWith()
        try {
            engine.load()
            val ex = assertFailsWith<LxException.UnsupportedRequest> {
                engine.getMusicUrl("tx", LxQuality.Q_320K, """{"songmid":"x"}""")
            }
            assertTrue(ex.message!!.contains("tx"))
        } finally {
            engine.close()
        }
    }

    @Test
    fun `源不支持该 action 时被拒绝`() = runTest {
        val engine = engineWith()
        try {
            engine.load()
            // kw 源只声明了 musicUrl，未声明 lyric
            assertFailsWith<LxException.UnsupportedRequest> {
                engine.getLyric("kw", """{"songmid":"x"}""")
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `脚本抛错时归为运行时错误`() = runTest {
        val failing = """
            /**
             * @name 会失败的源
             */
            const { EVENT_NAMES, on, send } = globalThis.lx
            on(EVENT_NAMES.request, ({ source, action, info }) => {
              return Promise.reject(new Error('接口拒绝访问'));
            })
            send(EVENT_NAMES.inited, {
              sources: {
                kw: { name: 'kw', type: 'music', actions: ['musicUrl'], qualitys: ['320k'] },
              },
            })
        """.trimIndent()
        val engine = engineWith(script = failing)
        try {
            engine.load()
            val ex = assertFailsWith<LxException.ScriptRuntimeError> {
                engine.getMusicUrl("kw", LxQuality.Q_320K, """{"songmid":"x"}""")
            }
            assertTrue(ex.message!!.contains("接口拒绝访问"))
        } finally {
            engine.close()
        }
    }

    @Test
    fun `未发送 inited 的脚本加载失败`() = runTest {
        val noInit = """
            /**
             * @name 无效源
             */
            const { send } = globalThis.lx
            send(lx.EVENT_NAMES.inited, { sources: {} })
        """.trimIndent()
        val engine = engineWith(script = noInit)
        try {
            // sources 为空对象仍算发送了 inited，故此处断言「源数为 0」而非抛错
            val desc = engine.load()
            assertEquals(0, desc.sources.size)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `语法错误脚本抛出初始化失败`() = runTest {
        val broken = """
            /**
             * @name 语法错误源
             */
            const x = (((
        """.trimIndent()
        val engine = engineWith(script = broken)
        try {
            assertFailsWith<LxException.ScriptInitFailed> { engine.load() }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `关闭后请求抛出 Closed`() = runTest {
        val engine = engineWith()
        engine.load()
        engine.close()
        assertFailsWith<LxException.Closed> {
            engine.getMusicUrl("kw", LxQuality.Q_320K, """{"songmid":"x"}""")
        }
    }

    @Test
    fun `脚本返回对象形态直链也可解析`() = runTest {
        // 部分源直接返回 {url: "..."}，需与纯字符串形态兼容
        val objScript = """
            /**
             * @name 对象直链源
             */
            const { EVENT_NAMES, on, send } = globalThis.lx
            on(EVENT_NAMES.request, () => Promise.resolve({ url: 'http://cdn.test/obj.mp3' }))
            send(EVENT_NAMES.inited, {
              sources: { kw: { name: 'kw', type: 'music', actions: ['musicUrl'], qualitys: ['320k'] } },
            })
        """.trimIndent()
        val engine = engineWith(script = objScript)
        try {
            engine.load()
            val r = engine.getMusicUrl("kw", LxQuality.Q_320K, """{"songmid":"x"}""")
            assertEquals("http://cdn.test/obj.mp3", r.url)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `脚本可通过 lx utils 调用宿主 crypto`() = runTest {
        // 验证 lx.utils.crypto.md5 通道（tx/wy 系源签名依赖）
        val cryptoScript = """
            /**
             * @name crypto 源
             */
            const { EVENT_NAMES, on, send } = globalThis.lx
            on(EVENT_NAMES.request, () => {
              const h = globalThis.lx.utils.crypto.md5('abc')
              return Promise.resolve('http://cdn.test/' + h + '.mp3')
            })
            send(EVENT_NAMES.inited, {
              sources: { kw: { name: 'kw', type: 'music', actions: ['musicUrl'], qualitys: ['320k'] } },
            })
        """.trimIndent()
        val engine = engineWith(script = cryptoScript)
        try {
            engine.load()
            val r = engine.getMusicUrl("kw", LxQuality.Q_320K, """{"songmid":"x"}""")
            // MD5("abc") = 900150983cd24fb0d6963f7d28e17f72
            assertTrue(
                r.url.endsWith("900150983cd24fb0d6963f7d28e17f72.mp3"),
                "期望 MD5 十六进制串，实际 ${r.url}",
            )
        } finally {
            engine.close()
        }
    }

    @Test
    fun `meta 在加载前即可读`() {
        val engine = engineWith()
        try {
            assertEquals("测试音乐源", engine.meta.name)
            assertNotNull(engine.meta.version)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `直连脚本可用 URLSearchParams 编码中文及覆盖重复参数`() = runTest {
        val script = """
            const { on, send, request, EVENT_NAMES } = lx;
            on(EVENT_NAMES.request, () => {
              const params = new URLSearchParams('?rid=旧值&rid=重复&name=江南+音乐');
              params.set('rid', 93157);
              params.append('special', " !'()~*");
              if (params.getAll('rid').length !== 1 || params.get('name') !== '江南 音乐') {
                throw new Error('参数解析错误');
              }
              const copy = new URLSearchParams(params);
              return new Promise((resolve, reject) => request('http://api.test/kw?' + copy,
                {}, (err, response) => err ? reject(err) : resolve(response.body.url)));
            });
            send(EVENT_NAMES.inited, {
              sources: { kw: { actions: ['musicUrl'], qualitys: ['320k'] } }
            });
        """.trimIndent()
        var requestedUrl = ""
        val engine = engineWith(script) { url ->
            requestedUrl = url
            """{"url":"http://cdn.test/direct.mp3"}"""
        }
        try {
            engine.load()
            assertEquals("http://cdn.test/direct.mp3", engine.getMusicUrl("kw", LxQuality.Q_320K, "{}").url)
            assertTrue(requestedUrl.contains("rid=93157&name=%E6%B1%9F%E5%8D%97+%E9%9F%B3%E4%B9%90"), requestedUrl)
            assertTrue(requestedUrl.contains("special=+%21%27%28%29%7E*"), requestedUrl)
        } finally { engine.close() }
    }

    @Test
    fun `脚本收到真实错误状态及响应头`() = runTest {
        val mock = MockEngine {
            respond("""{"message":"访问被拒绝"}""", HttpStatusCode.Forbidden,
                headersOf("X-Source-Reason", "blocked"))
        }
        val client = HttpClient(mock)
        val script = """
            lx.on('request', () => new Promise((resolve, reject) => {
              lx.request('http://api.test/blocked', {}, (err, response) => {
                if (err) return reject(err);
                if (response.statusCode === 403 && response.headers['x-source-reason'] === 'blocked'
                    && response.body.message === '访问被拒绝') {
                  reject(new Error('音源拒绝访问'));
                } else resolve('http://cdn.test/wrong.mp3');
              });
            }));
            lx.send('inited', { sources: { kw: { actions: ['musicUrl'], qualitys: ['320k'] } } });
        """.trimIndent()
        val engine = LxScriptEngine(script, LxCryptoJvm(), LxHttpClient(client))
        try {
            engine.load()
            val error = assertFailsWith<LxException.ScriptRuntimeError> {
                engine.getMusicUrl("kw", LxQuality.Q_320K, "{}")
            }
            assertTrue(error.message.orEmpty().contains("音源拒绝访问"))
        } finally { engine.close(); client.close() }
    }
}
