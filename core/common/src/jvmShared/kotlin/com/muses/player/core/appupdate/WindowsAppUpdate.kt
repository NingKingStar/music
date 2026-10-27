package com.muses.player.core.appupdate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * Windows 应用内更新（jvmShared：安卓/desktop 双端可编译，实际只在桌面 Windows 调用）。
 *
 * 发版约定（见 .github/workflows/release.yml）：每个 tag 附带 `Muses-v<version>.msi`
 *（jpackage 打包，upgradeUuid 固定，msiexec /i 可覆盖升级）。更新流程：
 * 检查 [fetchWindowsRelease] → 下载 [downloadWindowsInstaller]（进度回调）→
 * 启动安装 [launchWindowsInstaller]（msiexec /i /passive /norestart，调用方提示用户按向导完成）。
 */
data class WindowsReleaseInfo(
    /** 如 "v0.5.6" */
    val tag: String,
    /** Release 页面地址（下载失败/无 MSI 时的回退入口） */
    val htmlUrl: String,
    /** MSI 直链（release asset 的 browser_download_url） */
    val msiUrl: String,
    /** 如 "Muses-v0.5.6.msi"（本地缓存文件名用） */
    val msiName: String,
    /** asset 声明字节数；<=0 表示未知（进度条转不定态） */
    val msiSizeBytes: Long,
    /** Release 正文（更新日志 markdown 原文，可能为空） */
    val notes: String,
)

private val lenientJson = Json { ignoreUnknownKeys = true }

/**
 * 纯解析：GitHub `releases/latest` 响应正文 → [WindowsReleaseInfo]；
 * 无 tag / 无 html_url / 无 `.msi` asset 时返回 null（调用方统一按失败提示）。
 * 纯函数可单测（网络只在 [fetchWindowsRelease] 做）。
 */
fun parseWindowsRelease(body: String): WindowsReleaseInfo? {
    val root = runCatching { lenientJson.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: return null
    val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
        ?: return null
    val htmlUrl = root["html_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
        ?: return null
    val notes = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val assets = runCatching { root["assets"]?.jsonArray }.getOrNull() ?: return null
    for (element in assets) {
        val asset = runCatching { element.jsonObject }.getOrNull() ?: continue
        val name = asset["name"]?.jsonPrimitive?.contentOrNull ?: continue
        if (!name.endsWith(".msi", ignoreCase = true)) continue
        val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotEmpty() } ?: continue
        val size = asset["size"]?.jsonPrimitive?.longOrNull ?: -1L
        return WindowsReleaseInfo(
            tag = tag,
            htmlUrl = htmlUrl,
            msiUrl = url,
            msiName = name,
            msiSizeBytes = size,
            notes = notes,
        )
    }
    return null
}

/**
 * 拉取最新 Release 并解析出 MSI 信息；网络/格式异常返回 null。
 * 有新版与否由调用方经 [compareVersions] 判定（与安卓检查更新语义一致）。
 */
suspend fun fetchWindowsRelease(currentVersion: String): WindowsReleaseInfo? {
    return withContext(Dispatchers.IO) {
        var connection: java.net.HttpURLConnection? = null
        try {
            connection = java.net.URL(LATEST_RELEASE_API_URL)
                .openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.setRequestProperty("User-Agent", "Muses/$currentVersion")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            if (connection.responseCode != 200) return@withContext null
            parseWindowsRelease(connection.inputStream.bufferedReader().readText())
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}

/** 更新包本地缓存目录：%LOCALAPPDATA%/Muses/updates（异常回落系统临时目录）。 */
fun windowsUpdateDir(): File {
    val base = runCatching {
        val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotEmpty() }
        if (localAppData != null) File(localAppData, "Muses/updates") else null
    }.getOrNull()
    return base ?: File(System.getProperty("java.io.tmpdir"), "Muses-updates")
}

/**
 * 下载 MSI 到 [dest]（边下边写同名 .part，成功后原子改名；已存在且长度与 [expectedSize]
 * 一致则跳过下载）。[onProgress] 在 IO 线程回调 (已下载字节, 总字节<=0表未知)，
 * 调用方切主线程更新 UI；协程取消时删除 .part。
 */
suspend fun downloadWindowsInstaller(
    msiUrl: String,
    dest: File,
    expectedSize: Long = -1L,
    onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
): Result<File> = withContext(Dispatchers.IO) {
    try {
        if (dest.isFile && expectedSize > 0 && dest.length() == expectedSize) {
            onProgress(expectedSize, expectedSize)
            return@withContext Result.success(dest)
        }
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, "${dest.name}.part")
        var connection: java.net.HttpURLConnection? = null
        try {
            // release 下载链 github.com → objects.githubusercontent.com 跨域 302，
            // HttpURLConnection 对 GET 会自动跟随；显式打开以防平台默认差异。
            connection = java.net.URL(msiUrl).openConnection() as java.net.HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Muses")
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            if (connection.responseCode !in 200..299) {
                return@withContext Result.failure(IllegalStateException("下载失败(${connection.responseCode})"))
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: expectedSize
            var downloaded = 0L
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
            if (expectedSize > 0 && part.length() != expectedSize) {
                part.delete()
                return@withContext Result.failure(IllegalStateException("安装包不完整，请重试"))
            }
            if (dest.exists() && !dest.delete()) {
                part.delete()
                return@withContext Result.failure(IllegalStateException("无法写入更新目录"))
            }
            if (!part.renameTo(dest)) {
                part.delete()
                return@withContext Result.failure(IllegalStateException("无法保存安装包"))
            }
            onProgress(dest.length(), total.takeIf { it > 0 } ?: dest.length())
            Result.success(dest)
        } finally {
            connection?.disconnect()
        }
    } catch (e: CancellationException) {
        // 协程取消必须透出（否则上层 Job 无法进入 cancelled，取消按钮失效）
        runCatching { File(dest.parentFile, "${dest.name}.part").takeIf { it.exists() }?.delete() }
        throw e
    } catch (e: Exception) {
        // 失败清掉半截 .part，避免下次误判
        runCatching { File(dest.parentFile, "${dest.name}.part").takeIf { it.exists() }?.delete() }
        Result.failure(e)
    }
}

// 注意：MSI 安装启动 [launchWindowsInstaller] 落在 jvmMain（WindowsAppInstaller.jvm.kt）：
// jvmShared 会随 androidMain 一起编译，ProcessBuilder.Redirect.DISCARD 在安卓编译桩中
// 不存在；且调起 msiexec 本就是纯桌面行为。
