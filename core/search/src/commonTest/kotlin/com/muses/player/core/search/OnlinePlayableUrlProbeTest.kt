package com.muses.player.core.search

import com.muses.player.core.search.http.SearchHttp
import com.muses.player.core.search.http.audioHeaderDurationMs
import com.muses.player.core.search.http.isTruncatedAudio
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnlinePlayableUrlProbeTest {
    @Test fun `截短的未知响应和合法原始音频同步头均安全判断`() {
        for (size in 0..3) {
            assertFalse(com.muses.player.core.search.http.hasRecognizableAudioHeader(ByteArray(size) { 0x56 }))
        }
        assertFalse(com.muses.player.core.search.http.hasRecognizableAudioHeader(byteArrayOf(0x1a, 0x45)))
        assertFalse(com.muses.player.core.search.http.hasRecognizableAudioHeader(byteArrayOf(0x1a, 0x45, 0xdf.toByte())))
        assertTrue(com.muses.player.core.search.http.hasRecognizableAudioHeader(byteArrayOf(0x0b, 0x77)))
        assertTrue(com.muses.player.core.search.http.hasRecognizableAudioHeader(byteArrayOf(0xac.toByte(), 0x40)))
    }
    private fun flac(seconds: Int): ByteArray = ByteArray(42).also { bytes ->
        "fLaC".forEachIndexed { i, c -> bytes[i] = c.code.toByte() }
        bytes[4] = 0x80.toByte()
        bytes[7] = 34
        val packed = (44100L shl 44) or (seconds * 44100L)
        repeat(8) { bytes[18 + it] = (packed ushr ((7 - it) * 8)).toByte() }
    }

    @Test fun `一分钟片段换源而原生短曲和未知时长放行`() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("bytes=0-65535", request.headers[HttpHeaders.Range])
            respond(flac(if (request.url.encodedPath == "/trial") 60 else 240),
                HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentType, "audio/flac"))
        })
        try {
            val http = SearchHttp(client)
            assertFalse(http.canOpenAudio("https://cdn.test/trial", 240_000))
            assertTrue(http.canOpenAudio("https://cdn.test/full", 240_000))
            assertFalse(isTruncatedAudio(60_000, 60_000))
            assertFalse(isTruncatedAudio(null, 240_000))
            assertFalse(isTruncatedAudio(235_000, 240_000))
            assertFalse(isTruncatedAudio(0, 240_000))
        } finally { client.close() }
    }

    @Test fun `识别MP3帧数和M4A时长且不把随机字节当成试听`() {
        val mp3 = ByteArray(64)
        mp3[0] = 0xff.toByte(); mp3[1] = 0xfb.toByte(); mp3[2] = 0x90.toByte()
        "Xing".forEachIndexed { i, c -> mp3[36 + i] = c.code.toByte() }
        mp3[43] = 1
        mp3[46] = 0x08; mp3[47] = 0xf9.toByte() // 2297 帧，约 60 秒
        assertTrue(audioHeaderDurationMs(mp3)!! in 59_000..61_000)
        val m4a = ByteArray(44)
        m4a[3] = 8
        "ftyp".forEachIndexed { i, c -> m4a[4 + i] = c.code.toByte() }
        m4a[11] = 36
        "moov".forEachIndexed { i, c -> m4a[12 + i] = c.code.toByte() }
        m4a[19] = 28
        "mvhd".forEachIndexed { i, c -> m4a[20 + i] = c.code.toByte() }
        m4a[39] = 1 // 时间基准每秒 1
        m4a[43] = 60
        assertEquals(60_000, audioHeaderDurationMs(m4a))
        assertEquals(null, audioHeaderDurationMs(m4a.copyOf(30)))
        assertEquals(null, audioHeaderDurationMs(ByteArray(3)))
        assertEquals(null, audioHeaderDurationMs(ByteArray(128)))
    }

    @Test fun `小范围请求识别音频并拒绝403错误网页与不可识别容器`() = runTest {
        val mp3 = ByteArray(64).also { it[0] = 0xff.toByte(); it[1] = 0xfb.toByte(); it[2] = 0x90.toByte() }
        // 大 ID3 标签：只看到标签标记就必须放行，不能因头部读不完误拒。
        val largeId3 = ByteArray(64).also { bytes ->
            "ID3".forEachIndexed { i, c -> bytes[i] = c.code.toByte() }
            bytes[6] = 0x7f; bytes[7] = 0x7f; bytes[8] = 0x7f; bytes[9] = 0x7f
        }
        // 实测 kw 高档位返回的无普通音频头数据（不证明其性质，只证明当前播放器无法识别）。
        val unknownHeader = byteArrayOf(
            0x56, 0x09, 0x2b, 0x75, 0x30, 0x59, 0x4c, 0x40,
            0x23, 0x46, 0x5d, 0x34, 0x47, 0x74, 0x83.toByte(), 0x69, 0x7c,
        )
        val client = HttpClient(MockEngine { request ->
            assertEquals("bytes=0-16383", request.headers[HttpHeaders.Range])
            assertEquals(com.muses.player.core.model.online.OnlinePlaybackHttp.USER_AGENT,
                request.headers[HttpHeaders.UserAgent])
            when (request.url.encodedPath) {
                "/mp3" -> respond(mp3, HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentType, "audio/mpeg"))
                // 正常音频也可能返回 application/octet-stream，不能仅凭 Content-Type 拒绝。
                "/id3" -> respond(largeId3, HttpStatusCode.PartialContent,
                    headersOf(HttpHeaders.ContentType, "application/octet-stream"))
                "/unknown" -> respond(unknownHeader, HttpStatusCode.PartialContent,
                    headersOf(HttpHeaders.ContentType, "application/octet-stream"))
                "/short-flac" -> respond(flac(30), HttpStatusCode.PartialContent,
                    headersOf(HttpHeaders.ContentType, "application/octet-stream"))
                "/ogg" -> respond("OggS".toByteArray() + ByteArray(60), HttpStatusCode.PartialContent,
                    headersOf(HttpHeaders.ContentType, "application/octet-stream"))
                "/wav" -> respond("RIFF".toByteArray() + ByteArray(4) + "WAVE".toByteArray() + ByteArray(52),
                    HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentType, "audio/wav"))
                "/forbidden" -> respond("denied", HttpStatusCode.Forbidden)
                else -> respond("<html>error</html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
            }
        })
        val http = SearchHttp(client)
        try {
            assertTrue(http.canOpenAudio("https://cdn.test/mp3"))
            assertTrue(http.canOpenAudio("https://cdn.test/id3"))
            assertFalse(http.canOpenAudio("https://cdn.test/unknown"), "无容器头的地址不能当成功")
            // 短歌（<90s）不查时长，但必须仍能识别容器。
            assertTrue(http.canOpenAudio("https://cdn.test/short-flac", 30_000))
            assertTrue(http.canOpenAudio("https://cdn.test/ogg"))
            assertTrue(http.canOpenAudio("https://cdn.test/wav"))
            assertFalse(http.canOpenAudio("https://cdn.test/forbidden"))
            assertFalse(http.canOpenAudio("https://cdn.test/error"))
        } finally {
            client.close()
        }
    }

    @Test fun `无Range响应也按前缀识别容器`() = runTest {
        val flac = flac(240)
        val client = HttpClient(MockEngine {
            respond(flac, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/octet-stream"))
        })
        val http = SearchHttp(client)
        try {
            assertTrue(http.canOpenAudio("https://cdn.test/full", 240_000))
        } finally {
            client.close()
        }
    }
}
