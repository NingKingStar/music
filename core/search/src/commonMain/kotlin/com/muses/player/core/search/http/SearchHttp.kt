package com.muses.player.core.search.http

import io.ktor.client.HttpClient
import com.muses.player.core.model.online.OnlinePlaybackHttp
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import com.muses.player.core.network.ProviderRequestGate
import com.muses.player.core.data.store.platformNowMs
import io.ktor.http.Url

/**
 * 在线搜索的 HTTP 客户端。
 *
 * 各平台接口都是「逆向接口」，对 header 敏感（Referer/UA 缺失常直接 403 或返回空），
 * 故按平台需要注入默认 header；响应体解析一律走 [JsonElement] 松散模式
 * （字段结构不稳定，强类型映射易碎）。
 */
class SearchHttp(
    private val client: HttpClient = defaultSearchHttpClient(),
    private val json: Json = SearchJson,
) {
    private val cooldown = mutableMapOf<String, Long>()

    suspend fun canOpenAudio(url: String, expectedDurationMs: Long? = null): Boolean {
        val inspectDuration = expectedDurationMs != null && expectedDurationMs >= 90_000
        return client.prepareGet(url) {
            header(HttpHeaders.Range, if (inspectDuration) "bytes=0-65535" else "bytes=0-0")
            header(HttpHeaders.UserAgent, OnlinePlaybackHttp.USER_AGENT)
        }.execute { response ->
            val channel = response.bodyAsChannel()
            try {
                if (!response.status.isSuccess() || response.contentType()?.contentType == "text" ||
                    response.contentType()?.contentSubtype == "json") return@execute false
                if (!inspectDuration) return@execute true
                // 即使服务器忽略 Range，也只读取最多 64 KiB，随后取消响应。
                val prefix = ByteArray(65_536)
                var count = 0
                while (count < prefix.size) {
                    val read = channel.readAvailable(prefix, count, prefix.size - count)
                    if (read < 0) break
                    count += read
                }
                val actual = audioHeaderDurationMs(prefix.copyOf(count))
                !isTruncatedAudio(actual, expectedDurationMs)
            } finally {
                channel.cancel(null)
            }
        }
    }

    /** 桌面 UA：多数平台对移动 UA 返回的字段结构不同，统一桌面口径 */
    private val desktopUa: String = DEFAULT_UA

    /** GET 文本（自动带 UA；[referer] 非空时注入） */
    suspend fun getText(
        url: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String = requestGate.withSource(url) {
        checkCooldown(url)
        beforeRequest()
        val response: HttpResponse = client.get(url) {
            header(HttpHeaders.UserAgent, desktopUa)
            referer?.let { header(HttpHeaders.Referrer, it) }
            extraHeaders.forEach { (k, v) -> header(k, v) }
        }
        verifyResponse(url, response)
        response.bodyAsText()
    }

    /** POST JSON（QQ 音乐 musicu.fcg 用） */
    suspend fun postJson(
        url: String,
        body: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String = requestGate.withSource(url) {
        checkCooldown(url)
        beforeRequest()
        val response: HttpResponse = client.post(url) {
            header(HttpHeaders.UserAgent, desktopUa)
            referer?.let { header(HttpHeaders.Referrer, it) }
            contentType(ContentType.Application.Json)
            extraHeaders.forEach { (k, v) -> header(k, v) }
            setBody(body)
        }
        verifyResponse(url, response)
        response.bodyAsText()
    }

    private fun checkCooldown(url: String) {
        val remaining = synchronized(cooldown) { (cooldown[Url(url).host] ?: 0L) - platformNowMs() }
        check(remaining <= 0) { "平台请求受限，请在${(remaining + 999) / 1000}秒后重试" }
    }

    private fun verifyResponse(url: String, response: HttpResponse) {
        if (response.status.value == 429 || response.status.value == 403) {
            val wait = if (response.status.value == 429)
                searchRetryDelay(response.headers[HttpHeaders.RetryAfter], platformNowMs()) else 30_000L
            synchronized(cooldown) { cooldown[Url(url).host] = platformNowMs() + wait }
            throw IllegalStateException("平台暂时限制访问，请稍后重试（HTTP ${response.status.value}）")
        }
        check(response.status.isSuccess()) { "http ${response.status.value}" }
    }

    /**
     * 解析为 JSON 对象。
     *
     * 注意：酷我 `search.kuwo.cn/r.s` 返回的是**单引号伪 JSON**（键值用 `'`），
     * 需先做引号替换再解析；这里是统一的兜底处理。
     */
    fun parseObject(text: String): JsonObject {
        // 先 normalize 再解析：
        // - 对单引号伪 JSON（酷我）是必需的正规化；
        // - 对标准 JSON 是幂等的（字符串内的单引号不会被替换，见 normalizeSingleQuotedJson）。
        // 不能「先直接解析」：isLenient 会把单引号 JSON **解析成错误结构**（数组退化为标量），
        // 且不报错，导致兜底分支永远不触发。
        val normalized = normalizeSingleQuotedJson(text)
        val obj = runCatching { json.parseToJsonElement(normalized) as? JsonObject }.getOrNull()
        if (obj != null && obj.isNotEmpty()) return obj
        // 极致兜底：原文再试（应对 normalize 误伤的非典型文本）
        return runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: obj
            ?: JsonObject(emptyMap())
    }

    fun parseElement(text: String): JsonElement? =
        runCatching { json.parseToJsonElement(text) }.getOrNull()

    /**
     * 单引号伪 JSON → 标准 JSON。
     *
     * 保守实现：只在字符串外替换引号，避免破坏内容里的单引号
     * （如歌名 "Don't Stop"）。同时去掉 JS 里常见的 `undefined` 字面量。
     */
    private fun normalizeSingleQuotedJson(text: String): String {
        val sb = StringBuilder(text.length + 16)
        var inDouble = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length -> {
                    sb.append(c).append(text[i + 1]); i += 2; continue
                }
                c == '"' -> {
                    inDouble = !inDouble
                    sb.append(c)
                }
                c == '\'' && !inDouble -> sb.append('"')
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
            .replace(":undefined", ":null")
            .replace(": undefined", ": null")
    }

    companion object {
        // 所有搜索/榜单客户端共用同源间隔；不会因切页或更换关键词另建限流器。
        private val requestGate = ProviderRequestGate(intervalMs = 800L)
        const val DEFAULT_UA: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        fun defaultSearchHttpClient(): HttpClient = HttpClient(CIO) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 8_000
                socketTimeoutMillis = 15_000
            }
        }
    }
}

internal expect fun searchRetryDelay(value: String?, nowMs: Long): Long

/** 搜索响应结构不稳定：宽松解析 */
internal val SearchJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
}
