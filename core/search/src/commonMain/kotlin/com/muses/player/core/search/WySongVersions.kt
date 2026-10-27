package com.muses.player.core.search

import com.muses.player.core.search.http.SearchHttp
import com.muses.player.core.search.provider.arr
import com.muses.player.core.search.provider.long
import com.muses.player.core.search.provider.obj
import com.muses.player.core.search.provider.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class OnlineSongVersionService(private val http: SearchHttp) {
    suspend fun enrich(results: List<OnlineSearchResult>): List<OnlineSearchResult> = enrichWySongVersions(http, results)
}

/** 网易搜索简表没有原唱字段，按歌曲编号批量补查，失败时保留搜索结果。 */
suspend fun enrichWySongVersions(http: SearchHttp, results: List<OnlineSearchResult>): List<OnlineSearchResult> {
    val ids = results.filter { it.platform == "wy" && it.songId.toLongOrNull() != null }.map { it.songId }.distinct()
    if (ids.isEmpty()) return results
    val details = try {
        withTimeoutOrNull(4_000) {
            val root = http.parseObject(http.getText(
                "https://music.163.com/api/song/detail/?ids=%5B${ids.joinToString(",")}%5D",
                referer = "https://music.163.com/",
            ))
            root.arr("songs").orEmpty().mapNotNull {
                val song = it as? JsonObject ?: return@mapNotNull null
                val id = song.str("id") ?: return@mapNotNull null
                id to song
            }.toMap()
        }.orEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyMap()
    }
    return results.map { song ->
        val detail = details[song.songId]?.takeIf { song.platform == "wy" } ?: return@map song
        val info = Json.parseToJsonElement(song.musicInfoJson).jsonObject
        val metadata = mapOf(
            "originCoverType" to JsonPrimitive(detail.long("originCoverType") ?: 0L),
            "catalogQuality" to JsonPrimitive(wyCatalogQualityLabel(detail).orEmpty()),
            "catalogQualityTiers" to JsonPrimitive(wyCatalogQualityTiers(detail).joinToString(",")),
        )
        val cover = (detail.obj("album") ?: detail.obj("al"))?.str("picUrl")
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        song.copy(
            coverUrl = song.coverUrl?.takeIf { it.isNotBlank() } ?: cover,
            musicInfoJson = JsonObject(info + metadata).toString(),
        )
    }
}

internal fun wyCatalogQualityTiers(song: JsonObject): List<String> {
    fun bitrate(vararg keys: String): Long? = keys.asSequence().mapNotNull { song.obj(it) }.firstOrNull()
        ?.let { it.long("bitrate") ?: it.long("br") }
    val result = mutableSetOf<String>()
    for (keys in listOf(arrayOf("lMusic", "l"), arrayOf("mMusic", "m"), arrayOf("hMusic", "h"))) {
        when (bitrate(*keys)) { 128_000L -> result += "128k"; 192_000L -> result += "192k"; 320_000L -> result += "320k" }
    }
    fun exists(vararg keys: String): Boolean = keys.asSequence().mapNotNull { song.obj(it) }
        .any { (it.long("size") ?: 0) > 0 || (it.long("bitrate") ?: it.long("br") ?: 0) > 0 }
    if (exists("sqMusic", "sq")) result += "flac"
    if (exists("hrMusic", "hr")) result += "hires"
    return result.toList()
}

/** 目录里最高可用音质，不代表播放换源后实际采用的音质。兼容新旧接口字段。 */
internal fun wyCatalogQualityLabel(song: JsonObject): String? {
    fun audio(old: String, current: String): JsonObject? = song.obj(old) ?: song.obj(current)
    fun JsonObject?.available(): Boolean = this != null &&
        ((long("size") ?: 0) > 0 || (long("bitrate") ?: long("br") ?: 0) > 0)
    val high = audio("hMusic", "h")
    return when {
        audio("hrMusic", "hr").available() -> "Hi-Res"
        audio("sqMusic", "sq").available() -> "SQ"
        high.available() && (high?.long("bitrate") ?: high?.long("br") ?: 0) >= 320_000 -> "HQ"
        high.available() || audio("mMusic", "m").available() || audio("lMusic", "l").available() -> "标准"
        else -> null
    }
}

val OnlineSearchResult.qualityLabel: String?
    get() = runCatching {
        Json.parseToJsonElement(musicInfoJson).jsonObject.str("catalogQuality")
            ?.takeIf { it in setOf("Hi-Res", "SQ", "HQ", "标准") }
    }.getOrNull()

/** 仅标记平台确认的原唱，翻唱和未知不展示，不从歌名文字推断。 */
val OnlineSearchResult.performanceLabel: String?
    get() = runCatching {
        val info = Json.parseToJsonElement(musicInfoJson).jsonObject
        if ((platform == "wy" && info.long("originCoverType") == 1L) ||
            info.str("catalogPerformance") == "original"
        ) "原唱" else null
    }.getOrNull()
