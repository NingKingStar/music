package com.muses.player.core.appupdate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URI

@Serializable
data class AndroidReleaseInfo(
    val versionName: String, val versionCode: Long? = null, val notes: String, val pageUrl: String,
    val assetName: String, val downloadUrl: String, val size: Long, val sha256: String? = null,
)

private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
fun isOfficialReleaseAsset(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null &&
        uri.path.startsWith("/$RELEASE_REPOSITORY_SLUG/releases/download/")
}.getOrDefault(false)

/** 只选本包名对应的正式附件；旧版没有清单时继续兼容 GitHub 的附件摘要。 */
fun parseAndroidRelease(body: String, packageName: String, manifest: String? = null): AndroidReleaseInfo {
    val root = Json.parseToJsonElement(body).jsonObject
    require(root["draft"]?.jsonPrimitive?.booleanOrNull != true && root["prerelease"]?.jsonPrimitive?.booleanOrNull != true) { "暂无正式更新" }
    val version = root.string("tag_name")?.removePrefix("v").orEmpty()
    require(Regex("[0-9]+(?:[.][0-9]+){2,3}").matches(version)) { "发布版本信息不完整" }
    val page = root.string("html_url").orEmpty()
    require(page.startsWith("$REPOSITORY_URL/releases/tag/")) { "发布页面地址无效" }
    val name = when (packageName) {
        "com.muses.player" -> "muses-v$version.apk"
        "com.miui.player" -> "muses-v$version-mi.apk"
        else -> error("此测试渠道不接收正式版更新")
    }
    val asset = root["assets"]?.jsonArray?.map { it.jsonObject }?.singleOrNull { it.string("name") == name }
        ?: error("新版尚未提供适合本客户端的安装包")
    val url = asset.string("browser_download_url").orEmpty()
    require(isOfficialReleaseAsset(url)) { "安装包下载地址无效" }
    val size = asset["size"]?.jsonPrimitive?.longOrNull ?: 0
    require(size in 1..536_870_912L) { "安装包大小异常" }
    var hash = asset.string("digest")?.removePrefix("sha256:")?.takeIf { Regex("[a-fA-F0-9]{64}").matches(it) }
    var code: Long? = null
    if (manifest != null) {
        val meta = Json.parseToJsonElement(manifest).jsonObject
        require(meta.string("versionName") == version) { "更新清单与发布版本不一致" }
        code = meta["versionCode"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: error("更新版本码无效")
        val item = meta["packages"]?.jsonArray?.map { it.jsonObject }?.singleOrNull { it.string("packageName") == packageName }
            ?: error("更新清单缺少本客户端")
        require(item.string("assetName") == name) { "更新清单附件不匹配" }
        val declared = item.string("sha256").orEmpty()
        require(Regex("[a-fA-F0-9]{64}").matches(declared)) { "更新校验值无效" }
        require(hash == null || hash.equals(declared, true)) { "发布附件校验值不一致" }
        hash = declared
    }
    return AndroidReleaseInfo(version, code, root.string("body").orEmpty().take(32_000), page, name, url, size, hash?.lowercase())
}

fun parseReleaseChecksum(text: String, assetName: String): String? = text.lineSequence().mapNotNull { line ->
    Regex("^([a-fA-F0-9]{64})[ \t]+[*]?(.+)$").matchEntire(line.trim())?.let {
        if (it.groupValues[2] == assetName) it.groupValues[1].lowercase() else null
    }
}.singleOrNull()

/** 有界响应读取；限流和网络错误不误报成最新版本。 */
private fun releaseText(url: String, version: String): String {
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    try {
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "Muses/$version")
        connection.connectTimeout = 8_000; connection.readTimeout = 8_000
        when (val code = connection.responseCode) {
            200 -> Unit
            403, 429 -> error("更新服务暂时限流，请稍后重试或前往 GitHub 下载")
            404 -> error("暂未找到正式发布版本")
            else -> error("更新服务响应异常（$code），请稍后重试")
        }
        val bytes = connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= 2_000_000) {
                val count = input.read(buffer, 0, minOf(buffer.size, 2_000_001 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(bytes.size <= 2_000_000) { "更新信息过大" }
        return bytes.toString(Charsets.UTF_8)
    } finally { connection.disconnect() }
}

suspend fun checkAndroidRelease(currentVersion: String, packageName: String): AndroidReleaseInfo = withContext(Dispatchers.IO) {
    try {
        val body = releaseText(LATEST_RELEASE_API_URL, currentVersion)
        val root = Json.parseToJsonElement(body).jsonObject
        fun attachment(name: String) = root["assets"]?.jsonArray?.map { it.jsonObject }?.singleOrNull { it.string("name") == name }
            ?.string("browser_download_url")?.also { require(isOfficialReleaseAsset(it)) { "更新附件地址无效" } }
        val meta = attachment("update.json")?.let { releaseText(it, currentVersion) }
        val release = parseAndroidRelease(body, packageName, meta)
        if (release.sha256 != null) release else {
            val checksum = attachment("SHA256SUMS.txt")?.let { parseReleaseChecksum(releaseText(it, currentVersion), release.assetName) }
            release.copy(sha256 = checksum)
        }
    } catch (e: java.io.IOException) { throw java.io.IOException("无法连接更新服务，请检查网络或前往 GitHub 下载", e) }
}
