package com.muses.player.core.appupdate

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.*
import kotlinx.coroutines.withContext

/**
 * 应用更新检查（U15 从安卓装配层上收 jvmShared：java.net/JVM 双端可用）。
 *
 * 检查 GitHub 最新 release，返回 (tag, html_url)；网络/格式异常返回 null——
 * 调用方统一按失败文案提示（与 Web 层 403 分支的细分提示在行为层等价）。
 */
suspend fun checkLatestRelease(currentVersion: String): Pair<String, String>? {
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
            val code = connection.responseCode
            if (code != 200) {
                return@withContext null
            }
            val body = connection.inputStream.bufferedReader().readText()
            val root = Json.parseToJsonElement(body).jsonObject
            val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull
            val htmlUrl = root["html_url"]?.jsonPrimitive?.contentOrNull
            if (tag != null && Regex("v[0-9]+(?:[.][0-9]+){2,3}").matches(tag) &&
                htmlUrl?.startsWith("$REPOSITORY_URL/releases/tag/") == true) tag to htmlUrl else null
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}

/** 版本号比较（语义同 Web 层 compareVersions）：>0 表示 a 更新 */
fun compareVersions(a: String, b: String): Int {
    val partsA = a.split('.').map { it.toIntOrNull() ?: 0 }
    val partsB = b.split('.').map { it.toIntOrNull() ?: 0 }
    val len = maxOf(partsA.size, partsB.size)
    for (i in 0 until len) {
        val va = partsA.getOrElse(i) { 0 }
        val vb = partsB.getOrElse(i) { 0 }
        if (va > vb) return 1
        if (va < vb) return -1
    }
    return 0
}
