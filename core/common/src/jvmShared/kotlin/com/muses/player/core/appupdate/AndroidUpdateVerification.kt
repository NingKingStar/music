package com.muses.player.core.appupdate

import java.io.File
import java.security.MessageDigest

data class AndroidPackageIdentity(val packageName: String, val versionName: String?, val versionCode: Long, val signingDigests: Set<String>)

/** 下载完成和每次唤起安装前都重新核验，避免损坏包或旧包被安装。 */
fun verifyAndroidUpdateFile(file: File, release: AndroidReleaseInfo) {
    require(file.isFile && file.length() == release.size) { "安装包大小不符，请重新下载" }
    require(!release.sha256.isNullOrBlank()) { "缺少安装包校验值，请使用网页下载" }
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(65536)
        while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
    }
    val hash = md.digest().joinToString("") { "%02x".format(it) }
    require(hash.equals(release.sha256, true)) { "安装包校验失败，已拒绝安装" }
}

fun verifyAndroidUpdateIdentity(release: AndroidReleaseInfo, current: AndroidPackageIdentity, downloaded: AndroidPackageIdentity) {
    require(downloaded.packageName == current.packageName) { "安装包不属于当前客户端" }
    require(downloaded.versionName == release.versionName && downloaded.versionCode > current.versionCode &&
        (release.versionCode == null || downloaded.versionCode == release.versionCode)) { "安装包版本不匹配或不是更新版本" }
    require(current.signingDigests.isNotEmpty() && current.signingDigests == downloaded.signingDigests) { "安装包签名与当前应用不同，无法覆盖更新" }
}
