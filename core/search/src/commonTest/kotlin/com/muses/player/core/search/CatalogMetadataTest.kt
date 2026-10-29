package com.muses.player.core.search

import com.muses.player.core.search.provider.catalogMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatalogMetadataTest {
    private fun metadata(platform: String, raw: String) =
        catalogMetadata(platform, Json.parseToJsonElement(raw).jsonObject)

    @Test fun `只记录目录明确提供的档位`() {
        assertEquals("128k,flac", metadata("tx", """{"file":{"size_128mp3":10,"size_320mp3":0,"size_flac":90}}""")["catalogQualityTiers"])
        assertEquals("320k,hires", metadata("kw", """{"N_MINFO":"bitrate:320,format:mp3,size:8M;bitrate:4000,format:flac,size:60M"}""")["catalogQualityTiers"])
        assertNull(metadata("tx", """{"file":{}}""")["catalogQualityTiers"])
    }

    @Test fun `各平台真实目录字段映射最高音质`() {
        assertEquals("Hi-Res", metadata("tx", """{"file":{"size_hires":100,"size_flac":90}}""")["catalogQuality"])
        assertEquals("HQ", metadata("tx", """{"file":{"size_hires":0,"size_flac":0,"size_320mp3":80}}""")["catalogQuality"])
        assertEquals("SQ", metadata("kg", """{"SQFileHash":"ABC","SQFileSize":99,"HQFileHash":"DEF"}""")["catalogQuality"])
        assertEquals("标准", metadata("kg", """{"SQFileHash":"0000","HQFileHash":"0","FileHash":"ABC"}""")["catalogQuality"])
        assertEquals("Hi-Res", metadata("kw", """{"N_MINFO":"level:exhi,bitrate:4000,format:flac,size:60.2M;level:hi,bitrate:320,format:mp3,size:8M"}""")["catalogQuality"])
        assertEquals("SQ", metadata("mg", """{"newRateFormats":[{"formatType":"SQ","androidSize":"9900"},{"formatType":"ZQ","size":"0"}]}""")["catalogQuality"])
        for (platform in listOf("kw", "kg", "tx", "mg")) assertNull(metadata(platform, "{}")["catalogQuality"])
    }

    @Test fun `原唱仅采用明确平台标签且五平台共用显示`() {
        for (platform in listOf("kw", "kg", "tx", "mg")) {
            assertNull(metadata(platform, """{"name":"原唱版","tags":["录音室版"],"copyright":1}""")["catalogPerformance"])
            val info = metadata(platform, """{"tags":["原唱"]}""")
            val result = OnlineSearchResult(platform, "1", "歌", null, null, null, null,
                """{"catalogPerformance":"${info["catalogPerformance"]}","catalogQuality":"SQ"}""")
            assertEquals("原唱", result.performanceLabel)
            assertEquals("无损", result.qualityLabel)
        }
    }
}
