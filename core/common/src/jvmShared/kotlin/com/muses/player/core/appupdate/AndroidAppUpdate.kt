package com.muses.player.core.appupdate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URI
import java.io.IOException

@Serializable
data class AndroidReleaseInfo(
    val versionName: String, val versionCode: Long? = null, val notes: String, val pageUrl: String,
    val assetName: String, val downloadUrl: String, val size: Long, val sha256: String? = null,
)

private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
const val DOMESTIC_UPDATE_ORIGIN = "https://up.raink.cloud"
const val DOMESTIC_UPDATE_MANIFEST = "$DOMESTIC_UPDATE_ORIGIN/muses/latest.json"
private val releaseVersionPattern = Regex("[0-9]+(?:[.][0-9]+){2,3}")
private fun safeHttps(uri: URI) = uri.scheme == "https" && uri.userInfo == null && uri.fragment == null && uri.port in listOf(-1, 443)
private fun isGitHubReleaseAsset(url: String): Boolean = runCatching {
    val uri = URI(url)
    safeHttps(uri) && uri.host == "github.com" && uri.rawQuery == null &&
        uri.path.startsWith("/$RELEASE_REPOSITORY_SLUG/releases/download/")
}.getOrDefault(false)
fun isDomesticReleaseAsset(url: String): Boolean = runCatching {
    val uri = URI(url)
    safeHttps(uri) && uri.host == "up.raink.cloud" && uri.rawQuery == null && uri.rawPath == uri.path &&
        Regex("/muses/releases/([0-9]+(?:[.][0-9]+){2,3})/muses-v([0-9]+(?:[.][0-9]+){2,3})(-mi)?[.]apk")
            .matchEntire(uri.path)?.let { it.groupValues[1] == it.groupValues[2] } == true
}.getOrDefault(false)
fun isOfficialReleaseAsset(url: String) = isGitHubReleaseAsset(url) || isDomesticReleaseAsset(url)

private fun androidAssetName(version: String, packageName: String) = when (packageName) {
    "com.muses.player" -> "muses-v$version.apk"
    "com.miui.player" -> "muses-v$version-mi.apk"
    else -> error("此测试渠道不接收正式版更新")
}

/** 国内清单包含完整附件资料，正常检查不再依赖 GitHub。 */
fun parseDomesticAndroidRelease(body: String, packageName: String): AndroidReleaseInfo {
    val root = Json.parseToJsonElement(body).jsonObject
    require(root["schemaVersion"]?.jsonPrimitive?.intOrNull == 1) { "更新清单格式暂不支持" }
    val version = root.string("versionName").orEmpty()
    require(releaseVersionPattern.matches(version)) { "更新版本信息不完整" }
    val code = root["versionCode"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: error("更新版本码无效")
    val name = androidAssetName(version, packageName)
    val item = root["packages"]?.jsonArray?.map { it.jsonObject }?.singleOrNull { it.string("packageName") == packageName }
        ?: error("新版尚未提供适合本客户端的安装包")
    require(item.string("assetName") == name) { "更新清单附件不匹配" }
    val url = item.string("url").orEmpty()
    require(isDomesticReleaseAsset(url) && url == "$DOMESTIC_UPDATE_ORIGIN/muses/releases/$version/$name") { "安装包下载地址无效" }
    val size = item["size"]?.jsonPrimitive?.longOrNull ?: 0
    require(size in 1..536_870_912L) { "安装包大小异常" }
    val hash = item.string("sha256").orEmpty()
    require(Regex("[a-fA-F0-9]{64}").matches(hash)) { "更新校验值无效" }
    return AndroidReleaseInfo(version, code, root.string("notes").orEmpty().take(32_000), "$DOMESTIC_UPDATE_ORIGIN/", name, url, size, hash.lowercase())
}

internal fun resolveAndroidRelease(packageName: String, domestic: () -> String, github: () -> AndroidReleaseInfo): AndroidReleaseInfo {
    androidAssetName("0.0.0", packageName)
    return try { parseDomesticAndroidRelease(domestic(), packageName) }
    catch (e: CancellationException) { throw e }
    catch (primary: Exception) {
        try { github() }
        catch (e: CancellationException) { throw e }
        catch (fallback: Exception) {
            throw IOException("暂时无法连接更新服务，请检查网络后重试，或选择网页下载", fallback).also { it.addSuppressed(primary) }
        }
    }
}

/** 只选本包名对应的正式附件；旧版没有清单时继续兼容 GitHub 的附件摘要。 */
fun parseAndroidRelease(body: String, packageName: String, manifest: String? = null): AndroidReleaseInfo {
    val root = Json.parseToJsonElement(body).jsonObject
    require(root["draft"]?.jsonPrimitive?.booleanOrNull != true && root["prerelease"]?.jsonPrimitive?.booleanOrNull != true) { "暂无正式更新" }
    val version = root.string("tag_name")?.removePrefix("v").orEmpty()
    require(Regex("[0-9]+(?:[.][0-9]+){2,3}").matches(version)) { "发布版本信息不完整" }
    val page = root.string("html_url").orEmpty()
    require(page.startsWith("$REPOSITORY_URL/releases/tag/")) { "发布页面地址无效" }
    val name = androidAssetName(version, packageName)
    val asset = root["assets"]?.jsonArray?.map { it.jsonObject }?.singleOrNull { it.string("name") == name }
        ?: error("新版尚未提供适合本客户端的安装包")
    val url = asset.string("browser_download_url").orEmpty()
    require(isGitHubReleaseAsset(url)) { "安装包下载地址无效" }
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
private fun releaseText(url: String, version: String, domestic: Boolean = false, redirects: Int = 0): String {
    require(redirects <= 3) { "更新地址跳转次数过多" }
    val uri = URI(url)
    require(safeHttps(uri) && if (domestic) url == DOMESTIC_UPDATE_MANIFEST else
        url == LATEST_RELEASE_API_URL || isGitHubReleaseAsset(url) || uri.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")) { "更新地址无效" }
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    try {
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "Muses/$version")
        connection.connectTimeout = if (domestic) 4_000 else 8_000
        connection.readTimeout = if (domestic) 4_000 else 8_000
        when (val code = connection.responseCode) {
            200 -> Unit
            301, 302, 303, 307, 308 -> {
                require(!domestic) { "国内更新地址发生异常跳转" }
                val next = URI(url).resolve(connection.getHeaderField("Location") ?: error("更新跳转地址缺失")).toString()
                connection.disconnect()
                return releaseText(next, version, redirects = redirects + 1)
            }
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
    resolveAndroidRelease(packageName, domestic = { releaseText(DOMESTIC_UPDATE_MANIFEST, currentVersion, domestic = true) }, github = {
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
    })
}
