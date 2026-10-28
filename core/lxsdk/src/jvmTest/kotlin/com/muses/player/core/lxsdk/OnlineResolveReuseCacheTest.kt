package com.muses.player.core.lxsdk

import com.muses.player.core.model.online.OnlinePlayableUrl
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 短期复用：命中、过期、失效、容量淘汰都要可预期。 */
class OnlineResolveReuseCacheTest {

    private fun url(value: String) = OnlinePlayableUrl(value, quality = "320k")

    @Test
    fun `写入后命中同一结果`() {
        val cache = OnlineResolveReuseCache()
        cache.put("k", url("https://cdn.test/a"))
        assertEquals("https://cdn.test/a", cache.get("k")?.url)
        assertNull(cache.get("other"))
    }

    @Test
    fun `超过有效期自动失效`() = runBlocking {
        val cache = OnlineResolveReuseCache(ttlMs = 40)
        cache.put("k", url("https://cdn.test/a"))
        delay(90)
        assertNull(cache.get("k"))
    }

    @Test
    fun `地址被证实失效后立即丢弃`() {
        val cache = OnlineResolveReuseCache()
        cache.put("a", url("https://cdn.test/a"))
        cache.put("b", url("https://cdn.test/b"))
        cache.invalidateUrl("https://cdn.test/a")
        assertNull(cache.get("a"))
        assertEquals("https://cdn.test/b", cache.get("b")?.url)
    }

    @Test
    fun `容量有界并淘汰最旧条目`() = runBlocking {
        val cache = OnlineResolveReuseCache(maxEntries = 2)
        cache.put("a", url("https://cdn.test/a"))
        delay(5)
        cache.put("b", url("https://cdn.test/b"))
        delay(5)
        cache.put("c", url("https://cdn.test/c"))
        assertNull(cache.get("a"))
        assertEquals("https://cdn.test/b", cache.get("b")?.url)
        assertEquals("https://cdn.test/c", cache.get("c")?.url)
    }
}
