package com.muses.player.core.lyrics.provider

import com.muses.player.core.lyrics.crypto.WyCrypto
import com.muses.player.core.lyrics.http.LyricsHttp
import com.muses.player.core.model.lyrics.OnlineLyricsFormat
import com.muses.player.core.model.lyrics.OnlineLyricsProviderHit
import com.muses.player.core.model.lyrics.OnlineLyricsQuery
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 网易云歌词（规格书 = src/features/lyrics/providers/wy.ts）：
 * 优先 eapi 拿 yrc，否则公开 API 行级 LRC；tlyric 为 timed LRC 译文。
 */

private val WY_HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:120.0) Gecko/20100101 Firefox/120.0",
    "Referer" to "https://music.163.com/",
    "Accept" to "application/json,text/plain,*/*",
)

private val WY_EAPI_HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.90 Safari/537.36",
    "origin" to "https://music.163.com",
    "Referer" to "https://music.163.com/",
    "Content-Type" to "application/x-www-form-urlencoded",
    "Accept" to "*/*",
)

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonElement?.asObjArray(): List<JsonObject> =
    (this as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

/**
 * 双层/单层兼容取数组：老结构 `{key: {key: [...]}}`（对象包数组）与新结构 `{key: [...]}`（直接数组）都兼容。
 * 背景：2026-09 发现 kw/tx/kg/wy 搜索接口陆续把外层对象 flatten 成直接数组，老解析全返回空。
 */
internal fun JsonObject.arrCompat(key: String): List<JsonObject> {
    val node = this[key] ?: return emptyList()
    return (node as? JsonObject)?.get(key)?.asObjArray() ?: node.asObjArray()
}

/** 判断是否为 yrc 逐字体（wy.ts isYrcBody + qrc.ts looksLikeWordLevelBracket） */
private fun looksLikeWordLevelBracket(text: String): Boolean {
    val line = text.split(Regex("\\r?\\n")).firstOrNull { l ->
        l.trim().isNotEmpty() && !l.trim().startsWith("{")
    } ?: return false
    return Regex("^\\[\\d+,\\d+\\]").containsMatchIn(line.trim())
}

private fun isYrcBody(text: String): Boolean {
    if (text.isBlank()) return false
    if (Regex("\\(\\d+,\\d+,\\d+\\)").containsMatchIn(text) && looksLikeWordLevelBracket(text)) return true
    return looksLikeWordLevelBracket(text) && text.contains("(")
}

/** 从歌词响应挑 yrc 优先、lrc 兜底，并携带校验过的译文（wy.ts pickFromBody） */
internal fun pickWyLyricsBody(body: JsonObject): OnlineLyricsProviderHit? {
    fun field(key: String) = body.obj(key)?.str("lyric")?.trim()?.takeIf { it.isNotBlank() }
    fun timed(key: String) = field(key)?.takeIf { Regex("\\[\\d+:\\d+|\\[\\d+,\\d+\\]").containsMatchIn(it) }
    val yrc = field("yrc")?.takeIf(::isYrcBody)
    val lrc = timed("lrc")
    val translatedYrc = timed("ytlrc")
    val translatedLrc = timed("tlyric")
    // 缺少YRC对应译文时，采用原生LRC双语组合；不能仅靠相近时间把LRC译文塞到不同分句的YRC上。
    if (yrc != null && (translatedYrc != null || translatedLrc == null || lrc == null)) {
        return OnlineLyricsProviderHit(yrc, OnlineLyricsFormat.YRC, translatedYrc,
            field("yromalrc"),
            romanizationBaseText = lrc?.takeIf { field("yromalrc") == null && field("romalrc") != null },
            romanizationFallbackText = field("romalrc")?.takeIf { field("yromalrc") == null && lrc != null })
    }
    if (lrc != null) return OnlineLyricsProviderHit(lrc, OnlineLyricsFormat.LRC, translatedLrc, field("romalrc"))
    return null
}

private fun JsonElement?.path(vararg keys: String): String? {
    var cur: JsonElement? = this
    for (key in keys) {
        cur = (cur as? JsonObject)?.get(key) ?: return null
    }
    return (cur as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
}

private suspend fun searchWySongIds(http: LyricsHttp, query: OnlineLyricsQuery): List<Long> {
    val keyword = buildKeyword(query)
    if (keyword.isEmpty()) return emptyList()
    val url = "https://music.163.com/api/search/get/web?s=${java.net.URLEncoder.encode(keyword, "UTF-8")}" +
        "&type=1&offset=0&total=true&limit=10"
    val body = try {
        http.getJson(url, WY_HEADERS) as? JsonObject
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return emptyList()
    } ?: return emptyList()
    // 搜索返回结构兼容：老结构 result.songs.songs（对象包数组）与新结构 result.songs（直接数组）
    val songs = body.obj("result")?.arrCompat("songs").orEmpty()
    data class WySong(val id: Long, override val title: String?, override val artist: String?,
        override val album: String?, override val durationSec: Double?) : ScoreableHit
    val list = songs.mapNotNull { s ->
        val idPrimitive = s["id"] as? JsonPrimitive ?: return@mapNotNull null
        val id = idPrimitive.content.toDoubleOrNull()?.toLong() ?: return@mapNotNull null
        if (id <= 0) return@mapNotNull null
        WySong(
            id = id,
            title = s.str("name"),
            artist = s.get("artists")?.asObjArray().orEmpty()
                .mapNotNull { it.str("name")?.takeIf(String::isNotEmpty) }.joinToString(" / "),
            album = s.obj("album")?.str("name"),
            durationSec = s.str("duration")?.toDoubleOrNull()?.div(1000),
        )
    }
    return pickCandidates(list, query).map { it.id }.distinct()
}

private suspend fun fetchWyEapiLyric(http: LyricsHttp, id: Long): JsonObject? {
    val apiPath = "/api/song/lyric/v1"
    // -1：请求翻译/原文/逐字；0 时部分环境不返回 tlyric
    val payload = """{"id":$id,"cp":false,"tv":-1,"lv":-1,"rv":-1,"kv":-1,"yv":1,"ytv":1,"yrv":-1}"""
    return try {
        val params = WyCrypto.buildEapiParams(apiPath, payload)
        http.postJson(
            "https://interface3.music.163.com/eapi/song/lyric/v1",
            "params=$params",
            WY_EAPI_HEADERS,
        ) as? JsonObject
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

private suspend fun fetchWyPublicLyric(http: LyricsHttp, id: Long): JsonObject? = try {
    val url = "https://music.163.com/api/song/lyric?id=$id&lv=-1&kv=-1&tv=-1&rv=-1&yv=1"
    http.getJson(url, WY_HEADERS) as? JsonObject
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/** 副歌区间缓存的共享 HTTP（副歌请求很轻，复用一份即可）。 */
private val wyChorusHttp by lazy { LyricsHttp() }

/**
 * 网易原生副歌区间（`/api/song/chorus`）。
 *
 * 2026-11 实测：匿名 eapi 即可返回 `code=200, chorus=[{startTime,endTime}]`（毫秒），不需要登录。
 * 优先用当前曲的网易平台 ID（用户选中的版本），没有再用与歌词同一套搜索打分。
 * 任何失败都返回空表，不抛错、不阻断歌词展示。
 */
suspend fun fetchWyChorusRanges(http: LyricsHttp, query: OnlineLyricsQuery): List<com.muses.player.core.model.lyrics.ChorusRange> {
    val id = query.platformId("wy", "songmid", "songId", "id")?.toLongOrNull()
        ?: searchWySongIds(http, query).firstOrNull() ?: return emptyList()
    return fetchWyChorusById(http, id)
}

suspend fun fetchWyChorusRanges(query: OnlineLyricsQuery): List<com.muses.player.core.model.lyrics.ChorusRange> =
    fetchWyChorusRanges(wyChorusHttp, query)

private suspend fun fetchWyChorusById(http: LyricsHttp, id: Long): List<com.muses.player.core.model.lyrics.ChorusRange> {
    val payload = """{"ids":"[$id]"}"""
    val body = try {
        val params = WyCrypto.buildEapiParams("/api/song/chorus", payload)
        http.postJson("https://interface3.music.163.com/eapi/song/chorus", "params=$params", WY_EAPI_HEADERS) as? JsonObject
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return emptyList()
    } ?: return emptyList()
    if (body.str("code")?.toIntOrNull() != 200) return emptyList()
    val items = body["chorus"]?.asObjArray().orEmpty().ifEmpty { body["data"]?.asObjArray().orEmpty() }
    return items.mapNotNull { item ->
        val start = item.str("startTime")?.toLongOrNull() ?: return@mapNotNull null
        val end = item.str("endTime")?.toLongOrNull() ?: return@mapNotNull null
        if (start >= 0 && end > start) com.muses.player.core.model.lyrics.ChorusRange(start, end) else null
    }.sortedBy { it.startMs }
}

/** wy 搜索主流程（wy.ts searchWyLyrics）；失败返回 null 由链上下一源承接 */
suspend fun searchWyLyrics(http: LyricsHttp, query: OnlineLyricsQuery): OnlineLyricsProviderHit? {
    val id = query.platformId("wy", "songmid", "songId", "id")?.toLongOrNull()
        ?: searchWySongIds(http, query).firstOrNull() ?: return null

    fetchWyEapiLyric(http, id)?.let { body ->
        pickWyLyricsBody(body)?.let { return it }
    }
    fetchWyPublicLyric(http, id)?.let { body ->
        pickWyLyricsBody(body)?.let { return it }
    }
    return null
}

/** 原ID优先；缺注释时补查同ID另一接口及最多两个严格同曲候选。 */
internal suspend fun searchWyLyricsProgressively(http: LyricsHttp, query: OnlineLyricsQuery, onHit: suspend (OnlineLyricsProviderHit) -> Unit): Boolean {
    val attempted = mutableSetOf<Long>()
    var complete = false
    var requestsOkay = true
    suspend fun attempt(id: Long) {
        if (!attempted.add(id) || complete) return
        val primary = annotationRequest { fetchWyEapiLyric(http, id) }?.let(::pickWyLyricsBody)
        if (primary == null) requestsOkay = false
        if (primary != null) { onHit(primary); complete = !needsAnnotations(primary, com.muses.player.core.model.lyrics.OnlineLyricsSource.WY) }
        if (complete) return
        val fallback = annotationRequest { fetchWyPublicLyric(http, id) }?.let(::pickWyLyricsBody)
        if (fallback == null) requestsOkay = false
        if (fallback != null && fallback != primary) { onHit(fallback); complete = !needsAnnotations(fallback, com.muses.player.core.model.lyrics.OnlineLyricsSource.WY) }
    }
    query.platformId("wy", "songmid", "songId", "id")?.toLongOrNull()?.let { attempt(it) }
    if (!complete) {
        val candidates = annotationRequest { searchWySongIds(http, query) }.orEmpty()
        for (id in candidates.filter { it !in attempted }.take(if (attempted.isEmpty()) 3 else 2)) { attempt(id); if (complete) break }
    }
    return complete || (requestsOkay && attempted.isNotEmpty())
}
