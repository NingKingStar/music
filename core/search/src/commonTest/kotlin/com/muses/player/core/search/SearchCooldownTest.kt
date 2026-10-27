package com.muses.player.core.search

import com.muses.player.core.search.http.SearchHttp
import com.muses.player.core.search.http.searchRetryDelay
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SearchCooldownTest {
    @Test fun `限流后的新词请求不会再次访问同主机`() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { calls++; respond("limited", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "120")) })
        try {
            val http = SearchHttp(client)
            assertFailsWith<IllegalStateException> { http.getText("https://muses-cooldown-test.invalid/search?q=a") }
            val second = assertFailsWith<IllegalStateException> { http.getText("https://muses-cooldown-test.invalid/search?q=b") }
            assertTrue(second.message.orEmpty().contains("秒"))
            assertEquals(1, calls)
        } finally { client.close() }
    }

    @Test fun `冷却时间支持秒数日期和缺失兜底`() {
        assertEquals(120_000L, searchRetryDelay("120", 0))
        assertEquals(120_000L, searchRetryDelay("Thu, 01 Jan 1970 00:02:00 GMT", 0))
        assertEquals(60_000L, searchRetryDelay(null, 0))
        assertEquals(60_000L, searchRetryDelay("invalid", 0))
    }
}
