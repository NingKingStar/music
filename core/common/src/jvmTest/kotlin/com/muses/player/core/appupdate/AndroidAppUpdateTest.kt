package com.muses.player.core.appupdate

import kotlin.test.*

class AndroidAppUpdateTest {
    private val hash = "ab".repeat(32)
    private fun response(version: String = "0.9.11.1", draft: Boolean = false, host: String = "github.com") = """
        {"tag_name":"v$version","draft":$draft,"prerelease":false,
        "author":{"html_url":"https://github.com/someone"},
        "html_url":"https://github.com/NingKingStar/music/releases/tag/v$version","body":"更新说明",
        "assets":[
        {"name":"muses-v$version.apk","size":100,"digest":"sha256:$hash",
        "browser_download_url":"https://$host/NingKingStar/music/releases/download/v$version/muses-v$version.apk"},
        {"name":"muses-v$version-mi.apk","size":101,"digest":"sha256:$hash",
        "browser_download_url":"https://$host/NingKingStar/music/releases/download/v$version/muses-v$version-mi.apk"}]}
    """.trimIndent()
    @Test fun `兼容四段版本且只读取顶层发布地址`() {
        val result = parseAndroidRelease(response(), "com.muses.player")
        assertEquals("0.9.11.1", result.versionName)
        assertTrue(result.pageUrl.endsWith("/releases/tag/v0.9.11.1"))
        assertEquals(hash, result.sha256)
        assertTrue(compareVersions(result.versionName, "0.9.11") > 0)
    }
    @Test fun `渠道隔离且拒绝测试渠道草稿和伪下载地址`() {
        assertTrue(parseAndroidRelease(response(), "com.miui.player").assetName.endsWith("-mi.apk"))
        assertFails { parseAndroidRelease(response(), "com.muses.player.lyrics") }
        assertFails { parseAndroidRelease(response(draft = true), "com.muses.player") }
        assertFails { parseAndroidRelease(response(host = "example.com"), "com.muses.player") }
        assertFails { parseAndroidRelease(response(version = "0.9.12-beta"), "com.muses.player") }
    }
    private fun manifest(code: Int = 100150, value: String = hash) = """{"versionName":"0.9.11.1","versionCode":$code,
        "packages":[{"packageName":"com.muses.player","assetName":"muses-v0.9.11.1.apk","sha256":"$value"}]}"""
    @Test fun `清单版本码和摘要必须一致`() {
        assertEquals(100150L, parseAndroidRelease(response(), "com.muses.player", manifest()).versionCode)
        assertFails { parseAndroidRelease(response(), "com.muses.player", manifest(0)) }
        assertFails { parseAndroidRelease(response(), "com.muses.player", manifest(value = "cd".repeat(32))) }
        assertFails { parseAndroidRelease(response(), "com.muses.player", manifest().replace("0.9.11.1", "0.9.12")) }
    }
    @Test fun `旧校验文件按完整文件名读取且拒绝重复歧义`() {
        assertEquals(hash, parseReleaseChecksum("$hash  muses-v0.9.11.1.apk\n${"cd".repeat(32)}  other.apk", "muses-v0.9.11.1.apk"))
        assertNull(parseReleaseChecksum("$hash  muses-v0.9.11.1.apk\n$hash  muses-v0.9.11.1.apk", "muses-v0.9.11.1.apk"))
    }
}
