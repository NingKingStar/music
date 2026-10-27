package com.muses.player.core.appupdate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Windows 应用内更新解析测试：GitHub releases/latest 响应 → MSI 信息提取。
 * 纯函数 [parseWindowsRelease] 单测，不触网。
 */
class WindowsAppUpdateTest {

    private fun releaseJson(
        tag: String = "v0.5.6",
        htmlUrl: String = "https://github.com/NingKingStar/music/releases/tag/v0.5.6",
        body: String = "### 更新内容\\n- 应用内更新",
        assets: String = """
            {"name":"muses-v0.5.6.apk","browser_download_url":"https://example.com/a.apk","size":123},
            {"name":"Muses-v0.5.6.msi","browser_download_url":"https://example.com/Muses-v0.5.6.msi","size":45678901}
        """.trimIndent(),
    ): String = """
        {
          "tag_name":"$tag",
          "html_url":"$htmlUrl",
          "body":"$body",
          "assets":[$assets]
        }
    """.trimIndent()

    @Test
    fun 正常响应解析出MSI信息() {
        val info = parseWindowsRelease(releaseJson())
        assertEquals("v0.5.6", info?.tag)
        assertEquals("https://github.com/NingKingStar/music/releases/tag/v0.5.6", info?.htmlUrl)
        assertEquals("https://example.com/Muses-v0.5.6.msi", info?.msiUrl)
        assertEquals("Muses-v0.5.6.msi", info?.msiName)
        assertEquals(45678901L, info?.msiSizeBytes)
    }

    @Test
    fun 无MSI资产返回null() {
        val json = releaseJson(
            assets = """{"name":"muses-v0.5.6.apk","browser_download_url":"https://example.com/a.apk","size":1}""",
        )
        assertNull(parseWindowsRelease(json))
    }

    @Test
    fun 缺tag或地址返回null() {
        assertNull(parseWindowsRelease(releaseJson(tag = "")))
        assertNull(parseWindowsRelease("""{"tag_name":"v0.5.6","assets":[]}"""))
    }

    @Test
    fun 异常正文返回null不抛() {
        assertNull(parseWindowsRelease("not json"))
        assertNull(parseWindowsRelease("{}"))
    }
}
