package com.muses.player.core.appupdate

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlin.test.*

class DomesticAndroidUpdateTest {
    private val hash = "ab".repeat(32)
    private fun manifest(pkg: String = "com.muses.player", suffix: String = "") = """
        {"schemaVersion":1,"versionName":"0.9.16","versionCode":100153,"notes":"国内更新",
        "packages":[{"packageName":"$pkg","assetName":"muses-v0.9.16$suffix.apk",
        "url":"https://up.raink.cloud/muses/releases/0.9.16/muses-v0.9.16$suffix.apk",
        "size":123,"sha256":"$hash"}]}
    """.trimIndent()
    private val fallback = AndroidReleaseInfo("0.9.14", 100151, "", "", "", "", 123, hash)

    @Test fun 国内清单不依赖GitHub附件并保留完整校验信息() {
        val release = parseDomesticAndroidRelease(manifest(), "com.muses.player")
        assertEquals("0.9.16", release.versionName)
        assertEquals(100153L, release.versionCode)
        assertEquals(hash, release.sha256)
        assertEquals(123L, release.size)
        assertEquals("https://up.raink.cloud/", release.pageUrl)
        assertTrue(isOfficialReleaseAsset(release.downloadUrl))
    }
    @Test fun 国内渠道按包名选择附件() {
        assertTrue(parseDomesticAndroidRelease(manifest("com.miui.player", "-mi"), "com.miui.player").assetName.endsWith("-mi.apk"))
        assertFails { parseDomesticAndroidRelease(manifest(), "com.miui.player") }
        assertFails { parseDomesticAndroidRelease(manifest(), "com.muses.player.lyrics") }
        assertFails { parseDomesticAndroidRelease(manifest().replace("muses-v0.9.16.apk", "muses-v0.9.16-mi.apk"), "com.muses.player") }
    }
    @Test fun 拒绝缺失摘要非法大小版本码和未知协议() {
        listOf(manifest().replace(hash, ""), manifest().replace("123", "0"), manifest().replace("123", "536870913"),
            manifest().replace("100153", "0"), manifest().replace("schemaVersion\":1", "schemaVersion\":2"),
            manifest().replace("0.9.16", "0.9.16-beta")).forEach { body ->
            assertFails { parseDomesticAndroidRelease(body, "com.muses.player") }
        }
    }
    @Test fun 国内下载地址只允许精确域名HTTPS和版本路径() {
        val valid = "https://up.raink.cloud/muses/releases/0.9.16/muses-v0.9.16.apk"
        assertTrue(isDomesticReleaseAsset(valid))
        listOf(valid.replace("https:", "http:"), valid.replace("up.raink.cloud", "up.raink.cloud.evil.test"),
            valid.replace("up.raink.cloud", "evil.test@up.raink.cloud"), valid + "?url=evil", valid + "#x",
            valid.replace(".cloud/", ".cloud:444/"), valid.replace("/0.9.16/", "/0.9.15/"),
            valid.replace("/muses/", "/muses/../muses/"), valid.replace("muses-v", "muses-%76"),
            valid.replace(".apk", ".txt")).forEach { assertFalse(isDomesticReleaseAsset(it), it) }
    }
    @Test fun 清单版本与下载路径必须一致() {
        assertFails { parseDomesticAndroidRelease(manifest().replace("releases/0.9.16/", "releases/0.9.15/"), "com.muses.player") }
        val foreign = manifest().replace("https://up.raink.cloud", "https://example.com")
        assertFails { parseDomesticAndroidRelease(foreign, "com.muses.player") }
    }
    @Test fun 国内可用时不发起GitHub请求() {
        var githubCalls = 0
        val result = resolveAndroidRelease("com.muses.player", { manifest() }, { githubCalls++; fallback })
        assertEquals("0.9.16", result.versionName)
        assertEquals(0, githubCalls)
    }
    @Test fun 国内网络失败或清单错误时尝试备用() {
        assertEquals(fallback, resolveAndroidRelease("com.muses.player", { throw IOException("offline") }, { fallback }))
        assertEquals(fallback, resolveAndroidRelease("com.muses.player", { "not json" }, { fallback }))
    }
    @Test fun 两路失败不能误报已是最新() {
        val error = assertFailsWith<IOException> {
            resolveAndroidRelease("com.muses.player", { throw IOException("primary") }, { throw IOException("fallback") })
        }
        assertEquals(1, error.suppressed.size)
        assertEquals("fallback", error.cause?.message)
    }
    @Test fun 取消请求不触发备用且测试渠道不访问网络() {
        assertFailsWith<CancellationException> { resolveAndroidRelease("com.muses.player", { throw CancellationException() }, { error("must not run") }) }
        assertFailsWith<CancellationException> { resolveAndroidRelease("com.muses.player", { throw IOException() }, { throw CancellationException() }) }
        var calls = 0
        assertFails { resolveAndroidRelease("com.muses.player.lyrics", { calls++; manifest() }, { calls++; fallback }) }
        assertEquals(0, calls)
    }
    @Test fun 拒绝同包名重复清单() {
        val body = manifest()
        val item = body.substringAfter("\"packages\":[").substringBeforeLast("]")
        assertFails { parseDomesticAndroidRelease(body.replace(item, "$item,$item"), "com.muses.player") }
    }
}
