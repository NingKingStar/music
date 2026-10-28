package com.muses.player.core.model.online

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 下载匹配用的稳定身份：有平台 ID 时容忍附带字段变化，没有时保持保守。 */
class OnlineTrackIdentityTest {
    private fun ref(json: String, platform: String = "kw", sourceId: String = "src") =
        OnlineTrackRef(platform, json, sourceId)

    @Test
    fun `稳定歌曲ID忽略封面与字段顺序变化`() {
        val saved = ref("""{"songmid":"m1","name":"歌","cover":"https://a/1.jpg"}""")
        val playing = ref("""{"cover":"https://b/2.jpg","name":"歌","songmid":"m1"}""")
        assertTrue(OnlineTrackIdentity.matches(saved, playing))
        assertEquals(OnlineTrackIdentity.matchKeys(saved).first(), OnlineTrackIdentity.matchKeys(playing).first())
    }

    @Test
    fun `无稳定ID时按规范化JSON比较且不忽略新增字段`() {
        val a = ref("""{"a":1,"b":2}""")
        val reordered = ref("""{"b":2,"a":1}""")
        assertTrue(OnlineTrackIdentity.matches(a, reordered))
        val extra = ref("""{"a":1,"b":2,"cover":"https://a/1.jpg"}""")
        assertFalse(OnlineTrackIdentity.matches(a, extra))
    }

    @Test
    fun `不同平台或音源即使ID相同也不匹配`() {
        val base = ref("""{"songmid":"m1"}""")
        assertFalse(OnlineTrackIdentity.matches(base, ref("""{"songmid":"m1"}""", platform = "tx")))
        assertFalse(OnlineTrackIdentity.matches(base, ref("""{"songmid":"m1"}""", sourceId = "other")))
    }

    @Test
    fun `不同歌曲ID不匹配`() {
        assertFalse(
            OnlineTrackIdentity.matches(
                ref("""{"songmid":"m1"}"""),
                ref("""{"songmid":"m2"}"""),
            ),
        )
    }

    @Test
    fun `显式音质标记随引用编码往返`() {
        val explicit = OnlineTrackRef("kw", """{"songmid":"m1"}""", "src", "flac", qualityExplicit = true)
        val parsed = assertNotNull(OnlineTrackRef.parse(explicit.encode()))
        assertEquals("flac", parsed.quality)
        assertTrue(parsed.qualityExplicit)

        val plain = OnlineTrackRef("kw", """{"songmid":"m1"}""", "src", "flac")
        val parsedPlain = assertNotNull(OnlineTrackRef.parse(plain.encode()))
        assertEquals("flac", parsedPlain.quality)
        assertFalse(parsedPlain.qualityExplicit)
    }

    @Test
    fun `ID字段作为最后兜底且优先更具体字段`() {
        assertEquals("songmid=m1", OnlineTrackIdentity.stableId(ref("""{"id":"album","songmid":"m1"}""")))
        assertEquals("id=m1", OnlineTrackIdentity.stableId(ref("""{"id":"m1"}""")))
    }
}
