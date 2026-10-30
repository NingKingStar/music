package com.muses.player.core.lyrics.provider

import com.muses.player.core.lyrics.http.LyricsHttp
import com.muses.player.core.lyrics.provider.qrc.QrcDecoder
import com.muses.player.core.model.lyrics.OnlineLyricsFormat
import com.muses.player.core.model.lyrics.OnlineLyricsProviderHit
import com.muses.player.core.model.lyrics.OnlineLyricsQuery
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull

/**
 * QQ 歌词 provider（规格书 = src/features/lyrics/providers/tx.ts）：
 * 优先 GetPlayLyricInfo 加密 QRC（QrcDecoder），失败降级 fcg_query_lyric_new 行级 LRC。
 */

private val TX_HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 14_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/14.0 Mobile/15E148 Safari/604.1",
    "Referer" to "https://y.qq.com/",
    "Accept" to "application/json,text/plain,*/*",
)

private val TX_DESKTOP_HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/86.0.4240.198 Safari/537.36",
    "Referer" to "https://y.qq.com",
    "Content-Type" to "application/json",
    "Accept" to "application/json,text/plain,*/*",
)

/** tx.ts decodeMaybeBase64：已是 LRC 明文原样返回；否则尝试 base64 解码 */
private fun decodeMaybeBase64(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    if (trimmed.contains('[') && Regex("\\[\\d+:\\d+").containsMatchIn(trimmed)) return trimmed
    return runCatching {
        String(java.util.Base64.getDecoder().decode(trimmed), Charsets.UTF_8)
    }.getOrDefault(trimmed)
}

private data class TxHit(
    val mid: String,
    val songId: Long?,
    override val title: String?,
    override val artist: String?,
    override val album: String?,
) : ScoreableHit

private suspend fun searchTxHit(http: LyricsHttp, query: OnlineLyricsQuery): TxHit? {
    val keyword = buildKeyword(query)
    if (keyword.isEmpty()) return null
    val url = "https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp?g_tk=5381&uin=0&format=json" +
        "&inCharset=utf-8&outCharset=utf-8&notice=0&platform=h5&needNewCode=1" +
        "&w=${enc(keyword)}&zhidaqu=1&catZhida=1&t=0&flag=1&ie=utf-8&sem=1&aggr=0" +
        "&perpage=10&n=10&p=1&remoteplace=txt.mqq.all"

    val body = try {
        http.getJson(url, TX_HEADERS) as? JsonObject ?: return null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return null
    }
    // song.list 双层/单层兼容（arrCompat：老对象包数组 / 新直接数组）
    val items = body.obj("data")?.obj("song")?.arrCompat("list").orEmpty()
    val list = items.mapNotNull { item ->
        val mid = item.str("songmid")?.trim()?.takeUnless { it.isEmpty() } ?: return@mapNotNull null
        val rawId = (item["songid"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        val songId = rawId?.toDoubleOrNull()?.takeIf { it > 0 }?.toLong()
            ?: rawId?.takeIf { Regex("^\\d+$").containsMatchIn(it) }?.toLongOrNull()
        TxHit(
            mid = mid,
            songId = songId,
            title = item.str("songname"),
            artist = item.get("singer").asObjArray()
                .mapNotNull { it.str("name")?.takeIf(String::isNotEmpty) }.joinToString(" "),
            album = item.str("albumname"),
        )
    }.filter { it.mid.isNotEmpty() }
    return pickBest(list, query)
}

/** 同时接收平台原文和译文，兼容明文、base64 与桌面端加密字段。 */
internal fun parseTxLyricsBody(body: JsonObject, encrypted: Boolean = false): OnlineLyricsProviderHit? {
    fun text(value: String?): String {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        if (encrypted && raw.length % 2 == 0 && raw.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' })
            return QrcDecoder.decryptHex(raw)?.let { QrcDecoder.extractLyricContent(it) }.orEmpty()
        return QrcDecoder.extractLyricContent(decodeMaybeBase64(raw))
    }
    val lyric = text(body.str("qrc")).ifBlank { text(body.str("lyric")) }
    if (lyric.isBlank()) return null
    val qrc = Regex("(?m)^\\[\\d+,\\d+\\]").containsMatchIn(lyric) && Regex("\\(\\d+,\\d+").containsMatchIn(lyric)
    if (!qrc && !Regex("\\[\\d+:\\d+").containsMatchIn(lyric)) return null
    val translation = text(body.str("trans")).takeIf { it.isNotBlank() }
    val romanization = text(body.str("roma")).takeIf { it.isNotBlank() }
    return OnlineLyricsProviderHit(lyric, if (qrc) OnlineLyricsFormat.QRC else OnlineLyricsFormat.LRC, translation, romanization)
}

private suspend fun fetchTxQrc(http: LyricsHttp, songId: Long): OnlineLyricsProviderHit? {
    val payload = """
        {"comm":{"ct":"19","cv":"1859","uin":"0"},"req":{"method":"GetPlayLyricInfo",
        "module":"music.musichallSong.PlayLyricInfo","param":{"format":"json","crypt":1,"ct":19,
        "cv":1873,"interval":0,"lrc_t":0,"qrc":1,"qrc_t":0,"roma":1,"roma_t":0,"songID":$songId,
        "trans":1,"trans_t":0,"type":-1}}}
    """.trimIndent().replace(Regex("\\s"), "")
    return try {
        val body = http.postJson("https://u.y.qq.com/cgi-bin/musicu.fcg", payload, TX_DESKTOP_HEADERS) as? JsonObject ?: return null
        if (body.str("code") != "0") return null
        val req = body.obj("req") ?: return null
        if (req.str("code") != "0") return null
        req.obj("data")?.let { parseTxLyricsBody(it, encrypted = true) }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
}

private suspend fun fetchTxLrcByMid(http: LyricsHttp, songmid: String): OnlineLyricsProviderHit? {
    val url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=${enc(songmid)}" +
        "&format=json&nobase64=1&g_tk=5381&loginUin=0&hostUin=0&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0"
    val raw = try { http.getText(url, TX_HEADERS) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { return null }
    val jsonText = raw.replace(Regex("^\\s*[a-zA-Z0-9_]*\\("), "").replace(Regex("\\)\\s*;?\\s*$"), "")
    val body = try { Json.parseToJsonElement(jsonText) as? JsonObject ?: return null } catch (_: Exception) { return null }
    return parseTxLyricsBody(body)
}

/** tx 搜索主流程（tx.ts searchTxLyrics）；失败返回 null 由链上下一源承接 */
suspend fun searchTxLyrics(http: LyricsHttp, query: OnlineLyricsQuery): OnlineLyricsProviderHit? {
    val originalMid = query.platformId("tx", "songmid", "mid", "id")
    val hit = if (originalMid != null) TxHit(
        mid = originalMid,
        songId = query.platformId("tx", "songid", "songId")?.toLongOrNull(),
        title = query.title, artist = query.artist, album = query.album,
    ) else searchTxHit(http, query) ?: return null

    if (hit.songId != null) {
        fetchTxQrc(http, hit.songId)?.let { return it }
    }

    return fetchTxLrcByMid(http, hit.mid)
}
