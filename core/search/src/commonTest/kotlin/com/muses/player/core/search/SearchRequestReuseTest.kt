package com.muses.player.core.search

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SearchRequestReuseTest {
    @Test fun `并发重复搜索复用结果而分页与新词独立`() = runTest {
        var calls = 0
        val provider = object : OnlineSearchProvider {
            override val platform = "kw"
            override val displayName = "测试"
            override suspend fun search(keyword: String, page: Int, pageSize: Int): OnlineSearchPage {
                calls++; delay(100)
                return OnlineSearchPage(platform, keyword, page, emptyList(), false)
            }
        }
        val service = OnlineSearchService(listOf(provider), nowMs = { testScheduler.currentTime })
        val a = async { service.search("kw", "晴天") }; val b = async { service.search("kw", "晴天") }
        a.await(); b.await()
        assertEquals(1, calls)
        service.search("kw", "晴天", page = 2)
        service.search("kw", "夜曲")
        assertEquals(3, calls)
        testScheduler.advanceTimeBy(30_001)
        service.search("kw", "晴天")
        assertEquals(4, calls)
    }

    @Test fun `失败不缓存下一次查询仍可成功`() = runTest {
        var calls = 0
        val provider = object : OnlineSearchProvider {
            override val platform = "kw"
            override val displayName = "测试"
            override suspend fun search(keyword: String, page: Int, pageSize: Int): OnlineSearchPage {
                calls++
                if (calls == 1) error("限流")
                return OnlineSearchPage(platform, keyword, page, emptyList(), false)
            }
        }
        val service = OnlineSearchService(listOf(provider))
        assertFailsWith<IllegalStateException> { service.search("kw", "晴天") }
        service.search("kw", "晴天")
        assertEquals(2, calls)
    }

    @Test fun `主动刷新绕过缓存但普通查询复用刷新结果`() = runTest {
        var calls = 0
        val provider = object : OnlineSearchProvider {
            override val platform = "kw"
            override val displayName = "测试"
            override suspend fun search(keyword: String, page: Int, pageSize: Int): OnlineSearchPage {
                calls++
                return OnlineSearchPage(platform, keyword, page, emptyList(), false)
            }
        }
        val service = OnlineSearchService(listOf(provider))
        service.search("kw", "晴天"); service.search("kw", "晴天")
        assertEquals(1, calls)
        service.search("kw", "晴天", forceRefresh = true)
        service.search("kw", "晴天")
        assertEquals(2, calls)
    }
}
