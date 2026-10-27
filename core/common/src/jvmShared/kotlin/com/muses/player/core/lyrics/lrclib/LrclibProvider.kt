package com.muses.player.core.lyrics.lrclib

import com.muses.player.core.lyrics.http.LyricsHttp
import com.muses.player.core.lyrics.provider.ScoreableHit
import com.muses.player.core.lyrics.provider.enc
import com.muses.player.core.lyrics.provider.pickBest
import com.muses.player.core.model.lyrics.OnlineLyricsFormat
import com.muses.player.core.model.lyrics.OnlineLyricsProviderHit
import com.muses.player.core.model.lyrics.OnlineLyricsQuery
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * LRCLIB 公开 LRC provider（规格书 = src/features/lyrics/providers/lrclib.ts）：
 * 仅 syncedLyrics；精确 get → 检索 fallback；挂在平台五源之后。
 * 文档：https://lrclib.net/docs
 */

/** 供测试断言 UA（Web 导出 LRCLIB_USER_AGENT） */
internal const val LRCLIB_USER_AGENT = "Muses/0.1.2 (local music player; https://github.com/NingKingStar/music)"

private val LRCLIB_HEADERS = mapOf(
    "User-Agent" to LRCLIB_USER_AGENT,
    "Accept" to "application/json",
)

private fun hasTimedLrc(text: String): Boolean = Regex("\\[\\d{1,2}:\\d{2}").containsMatchIn(text)

private fun extractSynced(track: JsonObject?): String? {
    if (track == null) return null
    // instrumental 为 true → 无歌词
    val instrumental = track["instrumental"] as? JsonPrimitive
    if (instrumental?.content == "true") return null
    val synced = track.str("syncedLyrics")?.trim()
    return synced?.takeIf { it.isNotEmpty() && hasTimedLrc(it) }
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

/** 精确 get（含 duration 时更准）；404 抛错视为无精确命中 */
private suspend fun getExact(http: LyricsHttp, query: OnlineLyricsQuery): String? {
    val trackName = query.title.trim()
    if (trackName.isEmpty()) return null
    val params = buildList {
        add("track_name=${enc(trackName)}")
        query.artist?.trim()?.takeIf(String::isNotEmpty)?.let { add("artist_name=${enc(it)}") }
        query.album?.trim()?.takeIf(String::isNotEmpty)?.let { add("album_name=${enc(it)}") }
        query.durationSec?.takeIf { it > 0 }?.let { add("duration=${Math.round(it).toLong()}") }
    }.joinToString("&")

    val url = "https://lrclib.net/api/get?$params"
    return try {
        extractSynced(http.getJson(url, LRCLIB_HEADERS) as? JsonObject)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

private data class LrcCandidate(
    override val title: String?,
    override val artist: String?,
    override val album: String?,
    /** 原始时长（秒）；缺失为 null */
    override val durationSec: Double?,
    val synced: String,
) : ScoreableHit

/** 检索 fallback：时长接近优先（≤3s），否则打分最优（searchFallback） */
private suspend fun searchFallback(http: LyricsHttp, query: OnlineLyricsQuery): String? {
    val trackName = query.title.trim()
    if (trackName.isEmpty()) return null
    val params = buildList {
        add("track_name=${enc(trackName)}")
        query.artist?.trim()?.takeIf(String::isNotEmpty)?.let { add("artist_name=${enc(it)}") }
        query.album?.trim()?.takeIf(String::isNotEmpty)?.let { add("album_name=${enc(it)}") }
    }.joinToString("&")

    val url = "https://lrclib.net/api/search?$params"
    val array = try {
        http.getJson(url, LRCLIB_HEADERS) as? JsonArray ?: return null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return null
    }

    val candidates = array.mapNotNull { el ->
        val item = el as? JsonObject ?: return@mapNotNull null
        val synced = extractSynced(item) ?: return@mapNotNull null
        LrcCandidate(
            title = item.str("trackName") ?: item.str("name"),
            artist = item.str("artistName"),
            album = item.str("albumName"),
            durationSec = item.str("duration")?.toDoubleOrNull(),
            synced = synced,
        )
    }
    if (candidates.isEmpty()) return null

    // 有 duration 时优先时长接近的（≤3s）
    val queryDuration = query.durationSec
    if (queryDuration != null && queryDuration > 0) {
        val target = queryDuration
        val withDelta = candidates.map { c ->
            c to (c.durationSec?.let { Math.abs(it - target) } ?: Double.POSITIVE_INFINITY)
        }.sortedBy { it.second }
        withDelta.firstOrNull { it.second <= 3 && pickBest(listOf(it.first), query) != null }
            ?.let { return it.first.synced }
    }

    val best = pickBest(candidates, query)
    return best?.synced
}

/** lrclib 搜索主流程（lrclib.ts searchLrclibLyrics）：精确 get 优先，检索兜底 */
suspend fun searchLrclibLyrics(http: LyricsHttp, query: OnlineLyricsQuery): OnlineLyricsProviderHit? {
    getExact(http, query)?.let { exact ->
        return OnlineLyricsProviderHit(text = exact, format = OnlineLyricsFormat.LRC)
    }
    searchFallback(http, query)?.let { searched ->
        return OnlineLyricsProviderHit(text = searched, format = OnlineLyricsFormat.LRC)
    }
    return null
}

/** LyricsProvider 接口适配（id=lrclib） */
class LrclibProvider(private val http: LyricsHttp) :
    com.muses.player.core.model.lyrics.LyricsProvider {

    override val id = com.muses.player.core.model.lyrics.OnlineLyricsSource.LRCLIB

    override suspend fun searchLyrics(query: OnlineLyricsQuery): OnlineLyricsProviderHit? =
        searchLrclibLyrics(http, query)
}
