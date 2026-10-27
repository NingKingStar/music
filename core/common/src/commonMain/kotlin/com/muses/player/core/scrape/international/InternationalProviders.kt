package com.muses.player.core.scrape.international

import com.muses.player.core.data.store.platformNowMs
import com.muses.player.core.model.scrape.*
import com.muses.player.core.network.ProviderHttpException
import com.muses.player.core.network.expectingProviderNotFound
import com.muses.player.core.scrape.cover.*
import com.muses.player.core.scrape.editmeta.*
import com.muses.player.core.scrape.http.ScrapeHttp
import com.muses.player.core.scrape.text.*
import com.muses.player.core.scrape.text.provider.urlEncode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

/** Apple 官方歌曲搜索；审核保留多个发行版本，补缺链仍按现有规则选取。 */
class ItunesTextProvider(private val http: ScrapeHttp) : TextMetaProvider {
    override val id = OnlineTextSource.ITUNES
    override suspend fun search(query: OnlineTextQuery) = pickBestHit(searchCandidates(query), query)
    override suspend fun searchCandidates(query: OnlineTextQuery): List<TextMetaHit> {
        if (query.title.isBlank()) return emptyList()
        val keyword = listOfNotNull(query.title.trim(), query.artist?.trim()?.takeIf(String::isNotEmpty)).joinToString(" ")
        val root = http.getJson("https://itunes.apple.com/search?term=${urlEncode(keyword)}&entity=song&limit=20").jsonObject
        val rows = root["results"] as? JsonArray ?: error("iTunes 搜索返回结构无效")
        return rows.mapNotNull { row ->
            val item = row as? JsonObject ?: return@mapNotNull null
            if (item.text("kind") != "song") return@mapNotNull null
            val title = item.text("trackName") ?: return@mapNotNull null
            TextMetaHit(title, item.text("artistName"), item.text("collectionName"), id,
                durationSec = item.text("trackTimeMillis")?.toDoubleOrNull()?.div(1000)?.takeIf { it.isFinite() && it > 0 },
                releaseId = item.text("collectionId")?.let { "itunes:$it" },
                candidateId = item.text("trackId")?.let { "itunes:$it" },
            )
        }.distinct()
    }
}

/** 文本与封面共用录音搜索缓存；发行候选不会折叠成第一张专辑。 */
class MusicBrainzClient(private val http: ScrapeHttp, private val now: () -> Long = { platformNowMs() }) {
    internal data class Recording(val hit: TextMetaHit, val releaseId: String?)
    private val mutex = Mutex()
    private val cache = linkedMapOf<List<String?>, Pair<Long, List<Recording>>>()
    private val headers = mapOf("User-Agent" to "Muses/0.9 (https://github.com/NingKingStar/music)", "Accept" to "application/json")
    internal suspend fun search(query: OnlineTextQuery): List<Recording> {
        if (query.title.isBlank()) return emptyList()
        val key = listOf(query.title.trim(), query.artist?.trim())
        val diagnosing = kotlin.coroutines.coroutineContext[com.muses.player.core.network.ProviderDiagnosticContext.Key] != null
        return mutex.withLock {
            if (!diagnosing) cache[key]?.takeIf { now() - it.first < 300_000 }?.let { return@withLock it.second }
            fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            val expression = "recording:${quoted(query.title.trim())}" +
                query.artist?.takeIf(String::isNotBlank)?.let { " AND artist:${quoted(it.trim())}" }.orEmpty()
            val root = http.getJson("https://musicbrainz.org/ws/2/recording/?query=${urlEncode(expression)}&fmt=json&limit=15", headers).jsonObject
            val rows = root["recordings"] as? JsonArray ?: error("MusicBrainz 搜索返回结构无效")
            val result = rows.flatMap { row ->
                val recording = row as? JsonObject ?: return@flatMap emptyList()
                val title = recording.text("title") ?: return@flatMap emptyList()
                val artist = (recording["artist-credit"] as? JsonArray).orEmpty().joinToString("") { credit ->
                    val c = credit as? JsonObject
                    (c?.text("name") ?: (c?.get("artist") as? JsonObject)?.text("name")).orEmpty() + ((c?.get("joinphrase") as? JsonPrimitive)?.contentOrNull).orEmpty()
                }.takeIf(String::isNotBlank)
                val releases = (recording["releases"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.take(5)
                val hit = TextMetaHit(title, artist, source = OnlineTextSource.MUSICBRAINZ,
                    durationSec = recording.text("length")?.toDoubleOrNull()?.div(1000)?.takeIf { it.isFinite() && it > 0 },
                    recordingId = recording.text("id")?.let { "musicbrainz:$it" },
                    candidateId = recording.text("id")?.let { "musicbrainz:$it" },
                    versionInfo = recording.text("disambiguation"),
                )
                if (releases.isEmpty()) listOf(Recording(hit, null))
                else releases.map { release ->
                    val releaseId = release.text("id")
                    Recording(hit.copy(album = release.text("title"), releaseId = releaseId?.let { "musicbrainz:$it" },
                        candidateId = hit.candidateId?.let { "$it:${releaseId.orEmpty()}" }), releaseId)
                }
            }.distinct()
            if (!diagnosing && cache.size >= 32) cache.remove(cache.keys.first())
            if (!diagnosing) cache[key] = now() to result
            result
        }
    }

    internal suspend fun cover(query: OnlineCoverQuery): String? {
        // 封面必须来自可信录音的发行；未知歌手不自动取同名歌曲的封面。
        if (query.artist.isNullOrBlank()) return null
        val textQuery = OnlineTextQuery(query.songId, query.title, artist = query.artist, album = query.album)
        fun normalized(value: String?) = normalizeNfkc(value.orEmpty()).lowercase().filter(Char::isLetterOrDigit)
        val recordings = search(textQuery).filter {
            !it.hit.artist.isNullOrBlank() && normalized(it.hit.title) == normalized(query.title) &&
                normalized(it.hit.artist) == normalized(query.artist) && !evaluateTextMatch(it.hit, textQuery).requiresReview
        }
            .sortedByDescending { it.hit.album == query.album && !query.album.isNullOrBlank() }
        for (recording in recordings.distinctBy { it.releaseId }.take(3)) {
            val id = recording.releaseId?.takeIf { Regex("[0-9a-fA-F-]{36}").matches(it) } ?: continue
            val root = try { expectingProviderNotFound { http.getJson("https://coverartarchive.org/release/$id", headers).jsonObject } }
            catch (e: ProviderHttpException) { if (e.status == 404) continue else throw e }
            val front = (root["images"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .firstOrNull { (it["front"] as? JsonPrimitive)?.booleanOrNull == true } ?: continue
            val url = (front["thumbnails"] as? JsonObject)?.text("1200") ?: front.text("image") ?: continue
            if (url.startsWith("https://")) return url
            if (url.startsWith("http://")) return "https://" + url.removePrefix("http://")
        }
        return null
    }
}

class MusicBrainzTextProvider(private val client: MusicBrainzClient) : TextMetaProvider {
    override val id = OnlineTextSource.MUSICBRAINZ
    override suspend fun search(query: OnlineTextQuery) = pickBestHit(searchCandidates(query), query)
    override suspend fun searchCandidates(query: OnlineTextQuery) = client.search(query).map { it.hit }.distinct()
}
class MusicBrainzCoverProvider(private val client: MusicBrainzClient) : CoverProvider {
    override val id = OnlineCoverSource.MUSICBRAINZ
    override suspend fun searchCoverUrl(query: OnlineCoverQuery) = client.cover(query)
}

/** 仅传入搜索词，不向公开服务发送本地文件路径。 */
internal fun lrcApiUrl(base: String, endpoint: String, title: String, artist: String?, album: String?): String =
    "${base.trimEnd('/')}/$endpoint?title=${urlEncode(title.trim())}" +
        "&artist=${urlEncode(artist.orEmpty().trim())}&album=${urlEncode(album.orEmpty().trim())}"

class LrcApiLyricsPort(private val http: ScrapeHttp, private val baseUrl: suspend () -> String = { DEFAULT_LRC_API_URL }) : LyricsSearchPort {
    override val id = "lrcapi"
    override suspend fun searchLyrics(query: EditCloudMetaQuery): LyricsHit? {
        if (query.title.isBlank()) return null
        val text = try { expectingProviderNotFound { http.getText(lrcApiUrl(baseUrl(), "lyrics", query.title, query.artist, query.album)).trim() } }
        catch (e: ProviderHttpException) { if (e.status == 404) return null else throw e }
        if (text.isBlank() || text.equals("Lyrics not found.", true)) return null
        // 接口出错时可能返回 HTML；不把网页或 JSON 错误内容当歌词保存。
        if (!Regex("\\[\\d{1,3}:\\d{2}(?:[.:]\\d+)?]").containsMatchIn(text)) return null
        return LyricsHit(text, "lrc")
    }
}
class LrcApiCoverProvider(private val http: ScrapeHttp, private val baseUrl: suspend () -> String = { DEFAULT_LRC_API_URL }) : CoverProvider {
    override val id = OnlineCoverSource.LRCAPI
    override suspend fun searchCoverUrl(query: OnlineCoverQuery): String? {
        if (query.title.isBlank() || query.artist.isNullOrBlank()) return null
        val url = lrcApiUrl(baseUrl(), "cover", query.title, query.artist, query.album)
        try { expectingProviderNotFound { http.verifyImage(url) } }
        catch (e: ProviderHttpException) { if (e.status == 404) return null else throw e }
        return url
    }
}
