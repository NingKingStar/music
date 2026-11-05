package com.muses.player.desktop.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class DesktopUpdateInfo(
    val version: String,
    val url: String,
    val sha256: String,
    val size: Long,
    val notes: String,
)

/**
 * 桌面更新：优先读取平台的 latest.json，失败时回退 GitHub 的 windows-v* Release。
 * 安装包固定为 Inno Setup EXE；不依赖 MSI。
 */
object DesktopUpdater {
    private val json = Json { ignoreUnknownKeys = true }
    private const val DEFAULT_MANIFEST = "https://up.raink.cloud/windows/latest.json"
    private const val GITHUB_RELEASES = "https://api.github.com/repos/NingKingStar/music/releases?per_page=30"

    private fun manifestUrl(): String = System.getenv("MUSES_UPDATE_URL")?.takeIf { it.isNotBlank() } ?: DEFAULT_MANIFEST

    suspend fun check(currentVersion: String): Result<DesktopUpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val manifest = runCatching { parseManifest(fetchText(manifestUrl())) }.getOrNull()
            if (manifest != null) {
                return@runCatching if (isNewer(manifest.version, currentVersion)) manifest else null
            }
            parseGithub(fetchText(GITHUB_RELEASES), currentVersion)
        }
    }

    suspend fun download(info: DesktopUpdateInfo, onProgress: (Float) -> Unit): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = updateDir().apply { mkdirs() }
                val target = File(dir, "Muses-${info.version}-windows.exe")
                if (target.isFile && target.length() == info.size && info.sha256.isNotBlank() &&
                    sha256(target) == info.sha256
                ) {
                    onProgress(1f)
                    return@runCatching target
                }
                val part = File(dir, target.name + ".part")
                if (part.exists()) part.delete()
                val connection = (URL(info.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 120_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Muses-Desktop-Updater")
                }
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("下载失败：HTTP ${connection.responseCode}")
                }
                val total = info.size.takeIf { it > 0 } ?: connection.contentLengthLong
                var read = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            read += count
                            if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                connection.disconnect()
                if (total > 0 && read != total) throw IllegalStateException("安装包大小不一致，已停止更新")
                val actual = sha256(part)
                if (info.sha256.isNotBlank() && !actual.equals(info.sha256, ignoreCase = true)) {
                    part.delete()
                    throw IllegalStateException("安装包校验失败，已删除下载文件")
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw IllegalStateException("无法保存安装包")
                onProgress(1f)
                target
            }
        }

    fun install(file: File): Result<Unit> = runCatching {
        ProcessBuilder(file.absolutePath, "/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART").start()
    }

    private fun parseManifest(text: String): DesktopUpdateInfo {
        val root = json.parseToJsonElement(text).jsonObject
        val version = root["version"]?.jsonPrimitive?.contentOrNull?.trim()?.removePrefix("v")
            ?: error("更新清单缺少版本号")
        val url = root["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("https://") }
            ?: error("更新清单缺少下载地址")
        val sha = root["sha256"]?.jsonPrimitive?.contentOrNull?.removePrefix("sha256:")?.lowercase()
            ?: error("更新清单缺少校验值")
        val size = root["size"]?.jsonPrimitive?.longOrNull ?: 0L
        val notes = root["notes"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return DesktopUpdateInfo(version, url, sha, size, notes)
    }

    private fun parseGithub(text: String, currentVersion: String): DesktopUpdateInfo? {
        val releases = runCatching { json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return null
        for (element in releases) {
            val release = runCatching { element.jsonObject }.getOrNull() ?: continue
            val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: continue
            if (!tag.startsWith("windows-v")) continue
            val version = tag.removePrefix("windows-v").removePrefix("v")
            if (!isNewer(version, currentVersion)) continue
            val assets = runCatching { release["assets"]?.jsonArray }.getOrNull() ?: continue
            for (assetElement in assets) {
                val asset = runCatching { assetElement.jsonObject }.getOrNull() ?: continue
                val name = asset["name"]?.jsonPrimitive?.contentOrNull ?: continue
                if (!name.endsWith(".exe", ignoreCase = true)) continue
                val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: continue
                val digest = asset["digest"]?.jsonPrimitive?.contentOrNull?.removePrefix("sha256:")?.lowercase()
                    ?: continue
                val size = asset["size"]?.jsonPrimitive?.longOrNull ?: 0L
                val notes = release["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
                return DesktopUpdateInfo(version, url, digest, size, notes)
            }
        }
        return null
    }

    private fun fetchText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Muses-Desktop-Updater")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("更新服务暂不可用（HTTP ${connection.responseCode}）")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun updateDir(): File {
        val local = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
        return if (local != null) File(local, "Muses/updates") else File(System.getProperty("java.io.tmpdir"), "Muses-updates")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.').mapNotNull { it.toIntOrNull() }
        val b = current.split('.').mapNotNull { it.toIntOrNull() }
        if (a.isEmpty() || b.isEmpty()) return candidate != current
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
