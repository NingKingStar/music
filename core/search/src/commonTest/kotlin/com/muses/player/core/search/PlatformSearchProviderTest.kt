package com.muses.player.core.search

import com.muses.player.core.search.http.SearchHttp
import com.muses.player.core.search.provider.KgSearchProvider
import com.muses.player.core.search.provider.KwSearchProvider
import com.muses.player.core.search.provider.MgSearchProvider
import com.muses.player.core.search.provider.TxSearchProvider
import com.muses.player.core.search.provider.WySearchProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 5 个平台搜索解析测试。
 *
 * 夹具取自**真实接口响应**（已裁剪），确保字段映射与线上一致 ——
 * 逆向接口的字段名是本模块最容易出错的地方，必须有回归保护。
 */
class PlatformSearchProviderTest {

    private fun providerOf(provider: OnlineSearchProvider, body: String): OnlineSearchProvider {
        val mock = MockEngine {
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = SearchHttp(HttpClient(mock))
        return when (provider.platform) {
            "kw" -> KwSearchProvider(http)
            "kg" -> KgSearchProvider(http)
            "tx" -> TxSearchProvider(http)
            "wy" -> WySearchProvider(http)
            "mg" -> MgSearchProvider(http)
            else -> error("未知平台")
        }
    }

    // ── 酷我（单引号伪 JSON 是其特征） ──

    private val kwBody = """
        {"ARTISTPIC":"","HIT":"2249","TOTAL":"2249","PN":"0","RN":"3","abslist":[
        {"ALBUM":"","ALBUMID":"0","ARTIST":"薛之谦&赵英俊","DC_TARGETID":"193507024",
         "DURATION":"22","MUSICRID":"MUSIC_193507024","NAME":"丑八怪",
         "SONGNAME":"丑八怪 (Live)","N_MINFO":"level:ff,bitrate:2000,format:flac,size:23.87Mb","web_albumpic_short":"120/240/abc.jpg","SUBList":[]}]}
    """.trimIndent()

    @Test
    fun `酷我解析出 songmid 与字段`() = runTest {
        val p = providerOf(KwSearchProvider(), kwBody)
        val page = p.search("薛之谦", 1, 30)
        assertEquals(1, page.results.size)
        val r = page.results.first()
        assertEquals("kw", r.platform)
        // MUSICRID 需剥掉 MUSIC_ 前缀（脚本侧要纯数字）
        assertEquals("193507024", r.songId)
        assertEquals("丑八怪 (Live)", r.name)
        assertEquals("无损", r.qualityLabel)
        assertEquals("薛之谦&赵英俊", r.artist)
        assertEquals(22_000L, r.durationMs)
        assertNotNull(r.coverUrl)
    }

    @Test
    fun `酷我 musicInfo 冗余提供 songmid 与 rid`() = runTest {
        val p = providerOf(KwSearchProvider(), kwBody)
        val r = p.search("x", 1, 30).results.first()
        val json = Json.parseToJsonElement(r.musicInfoJson).jsonObject
        // 不同脚本作者读的字段名不同，两者都必须有
        assertEquals("193507024", json["songmid"]?.jsonPrimitive?.content)
        assertEquals("193507024", json["rid"]?.jsonPrimitive?.content)
        assertEquals("丑八怪 (Live)", json["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `酷我解析单引号伪 JSON 不崩`() = runTest {
        // 真实接口返回的是单引号格式，解析必须容错
        val singleQuoted = """
            {'TOTAL':'2','abslist':[
            {'MUSICRID':'MUSIC_111','SONGNAME':'歌一','ARTIST':'甲','DURATION':'100'},
            {'MUSICRID':'MUSIC_222','SONGNAME':'歌二','ARTIST':'乙','DURATION':'200','ALBUM':'专辑'}]}
        """.trimIndent()
        val p = providerOf(KwSearchProvider(), singleQuoted)
        val page = p.search("x", 1, 30)
        assertEquals(2, page.results.size)
        assertEquals("111", page.results[0].songId)
        assertEquals("乙", page.results[1].artist)
        assertEquals("专辑", page.results[1].album)
    }

    // ── QQ 音乐 ──

    private val txBody = """
        {"code":0,"req":{"code":0,"data":{"body":{"song":{"totalnum":"100","list":[
        {"id":409068462,"mid":"001TQYtM47BGjK","name":"花海","title":"花海","interval":254,
         "singer":[{"id":4623547,"mid":"002P80KI41I05l","name":"周杰伦"}],
         "album":{"id":0,"mid":"","name":"叶惠美"},"file":{"size_hires":0,"size_flac":18000000}}]}}}}}
    """.trimIndent()

    @Test
    fun `QQ解析 mid 与歌手专辑`() = runTest {
        val p = providerOf(TxSearchProvider(), txBody)
        val r = p.search("周杰伦", 1, 30).results.first()
        assertEquals("tx", r.platform)
        assertEquals("001TQYtM47BGjK", r.songId)
        assertEquals("花海", r.name)
        assertEquals("无损", r.qualityLabel)
        assertEquals("周杰伦", r.artist)
        assertEquals("叶惠美", r.album)
        assertEquals(254_000L, r.durationMs)
    }

    @Test
    fun `QQ musicInfo 同时提供 songmid 与 mid`() = runTest {
        val p = providerOf(TxSearchProvider(), txBody)
        val json = Json.parseToJsonElement(p.search("x", 1, 30).results.first().musicInfoJson).jsonObject
        assertEquals("001TQYtM47BGjK", json["songmid"]?.jsonPrimitive?.content)
        assertEquals("001TQYtM47BGjK", json["mid"]?.jsonPrimitive?.content)
    }

    @Test
    fun `QQ 结构异常时抛可读错误`() = runTest {
        val p = providerOf(TxSearchProvider(), """{"code":0}""")
        val ex = kotlin.test.assertFailsWith<OnlineSearchException> { p.search("x", 1, 30) }
        assertTrue(ex.message!!.contains("QQ音乐"))
    }

    // ─ 网易云 ─

    private val wyBody = """
        {"result":{"songCount":500,"songs":[
        {"id":509781655,"name":"想你就写信 (Live)","duration":238698,
         "artists":[{"id":6452,"name":"周杰伦"}],
         "album":{"id":36412633,"name":"中国新歌声第二季 第13期","picId":109951163038292176}}]}}
    """.trimIndent()

    @Test
    fun `网易云解析 id 与时长毫秒`() = runTest {
        val p = providerOf(WySearchProvider(), wyBody)
        val r = p.search("周杰伦", 1, 30).results.first()
        assertEquals("wy", r.platform)
        assertEquals("509781655", r.songId)
        assertEquals("想你就写信 (Live)", r.name)
        assertEquals("周杰伦", r.artist)
        assertEquals("中国新歌声第二季 第13期", r.album)
        // 网易云 duration 是毫秒，不应再乘 1000
        assertEquals(238_698L, r.durationMs)
    }

    // ── 酷狗 ─

    private val kgBody = """
        {"status":1,"error_code":0,"data":{"total":480,"page":1,"pagesize":3,"lists":[
        {"FileHash":"B3A52A7A958BF0AED0EBFBA2E9A818B7","HQFileHash":"1B56126A8A03924F1DD066259C095CBC",
         "SQFileHash":"0A69169202DE95AAF24A9944CCF0730D","SongName":"晴天","SingerName":"周杰伦",
         "AlbumName":"叶惠美","AlbumID":966846,"Duration":269,"HQDuration":269,"SQDuration":269,
         "AlbumImage":"https://imge.kugou.com/stdmusic/240/20200601/abc.jpg"}]}}
    """.trimIndent()

    @Test
    fun `酷狗解析 hash 与字段`() = runTest {
        val p = providerOf(KgSearchProvider(), kgBody)
        val r = p.search("周杰伦", 1, 30).results.first()
        assertEquals("kg", r.platform)
        // 优先取无损 hash（音质更好）
        assertEquals("0A69169202DE95AAF24A9944CCF0730D", r.songId)
        assertEquals("晴天", r.name)
        assertEquals("无损", r.qualityLabel)
        assertEquals("周杰伦", r.artist)
        assertEquals("叶惠美", r.album)
        assertEquals(269_000L, r.durationMs)
        assertNotNull(r.coverUrl)
    }

    @Test
    fun `酷狗 musicInfo 提供各音质 hash 供脚本按音质取用`() = runTest {
        val p = providerOf(KgSearchProvider(), kgBody)
        val json = Json.parseToJsonElement(p.search("x", 1, 30).results.first().musicInfoJson).jsonObject
        assertEquals("0A69169202DE95AAF24A9944CCF0730D", json["hash"]?.jsonPrimitive?.content)
        assertNotNull(json["sqhash"])
        assertNotNull(json["hqhash"])
        assertEquals("966846", json["albumId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `酷狗无损 hash 为 0 时退回默认 hash`() = runTest {
        val body = """
            {"status":1,"data":{"total":1,"lists":[
            {"FileHash":"AAA","HQFileHash":"0","SQFileHash":"0","SongName":"歌","SingerName":"甲"}]}}
        """.trimIndent()
        val p = providerOf(KgSearchProvider(), body)
        assertEquals("AAA", p.search("x", 1, 30).results.first().songId)
    }

    // ── 咪咕 ──

    private val mgBody = """
        {"code":"000000","info":"成功","songResultData":{"totalCount":"401","result":[
        {"id":"1140505222","copyrightId":"60054704965","name":"圣诞星（feat. 杨瑞代）",
         "singers":[{"id":"112","name":"周杰伦"}],
         "albums":[{"id":"1140505221","name":"圣诞星","type":"1"}],
         "newRateFormats":[{"formatType":"HQ","size":"8000000"}],
         "imgItems":[{"imgSizeType":"01","img":"https://d.musicapp.migu.cn/data/oss/cover.jpg"}]}]}}
    """.trimIndent()

    @Test
    fun `咪咕解析 copyrightId 与字段`() = runTest {
        val p = providerOf(MgSearchProvider(), mgBody)
        val r = p.search("周杰伦", 1, 30).results.first()
        assertEquals("mg", r.platform)
        // 脚本侧通用标识是 copyrightId
        assertEquals("60054704965", r.songId)
        assertEquals("圣诞星（feat. 杨瑞代）", r.name)
        assertEquals("HQ", r.qualityLabel)
        assertEquals("周杰伦", r.artist)
        assertEquals("圣诞星", r.album)
        assertNotNull(r.coverUrl)
    }

    @Test
    fun `咪咕 copyrightId 缺失时退回 id`() = runTest {
        val body = """
            {"code":"000000","songResultData":{"totalCount":"1","result":[
            {"id":"999","name":"歌","singers":[{"name":"甲"}]}]}}
        """.trimIndent()
        val p = providerOf(MgSearchProvider(), body)
        assertEquals("999", p.search("x", 1, 30).results.first().songId)
    }

    @Test
    fun `咪咕 pageSize 被钳制到 20`() = runTest {
        // 服务端上限 20，请求更大值会导致分页错位
        var seenUrl = ""
        val mock = MockEngine { request ->
            seenUrl = request.url.toString()
            respond(
                content = """{"code":"000000","songResultData":{"totalCount":"0","result":[]}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val p = MgSearchProvider(SearchHttp(HttpClient(mock)))
        p.search("x", 1, 100)
        assertTrue(seenUrl.contains("pageSize=20"), "实际 URL：$seenUrl")
    }

    // ── 通用：网络失败语义 ──

    @Test
    fun `HTTP 失败时抛带平台名的异常`() = runTest {
        val mock = MockEngine {
            respond(content = "boom", status = HttpStatusCode.Forbidden)
        }
        val p = KwSearchProvider(SearchHttp(HttpClient(mock)))
        val ex = kotlin.test.assertFailsWith<OnlineSearchException> { p.search("x", 1, 30) }
        assertEquals("kw", ex.platform)
        assertTrue(ex.message!!.contains("酷我"))
    }
}
