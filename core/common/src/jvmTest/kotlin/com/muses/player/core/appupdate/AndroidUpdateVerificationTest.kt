package com.muses.player.core.appupdate

import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

class AndroidUpdateVerificationTest {
    private val release = AndroidReleaseInfo("0.9.13", 100150, "", "", "muses-v0.9.13.apk", "", 9, "a".repeat(64))
    private val current = AndroidPackageIdentity("com.muses.player", "0.9.12", 100149, setOf("project-cert"))
    private val next = current.copy(versionName = "0.9.13", versionCode = 100150)
    @Test fun `安装前拒绝错包名错签名旧包和不匹配版本码`() {
        verifyAndroidUpdateIdentity(release, current, next)
        assertFails { verifyAndroidUpdateIdentity(release, current, next.copy(packageName = "com.miui.player")) }
        assertFails { verifyAndroidUpdateIdentity(release, current, next.copy(signingDigests = setOf("other-cert"))) }
        assertFails { verifyAndroidUpdateIdentity(release, current, next.copy(signingDigests = emptySet())) }
        assertFails { verifyAndroidUpdateIdentity(release, current, next.copy(versionCode = 100149)) }
        assertFails { verifyAndroidUpdateIdentity(release, current, next.copy(versionCode = 100151)) }
        assertFails { verifyAndroidUpdateIdentity(release, current, next.copy(versionName = "0.9.14")) }
    }
    @Test fun `旧发布无清单时仍检查真实升级版本码和签名`() {
        verifyAndroidUpdateIdentity(release.copy(versionCode = null), current, next)
        assertFails { verifyAndroidUpdateIdentity(release.copy(versionCode = null), current, current) }
    }
    @Test fun `下载后文件篡改缺失或不完整均拒绝且安装前可重复校验`() {
        val file = Files.createTempFile("update-gate", ".apk").toFile()
        try {
            file.writeText("valid-apk")
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            val info = release.copy(sha256 = hash)
            verifyAndroidUpdateFile(file, info)
            file.writeText("bad--apk!")
            assertFails { verifyAndroidUpdateFile(file, info) }
            file.writeText("short")
            assertFails { verifyAndroidUpdateFile(file, info) }
            file.writeText("valid-apk")
            assertFails { verifyAndroidUpdateFile(file, info.copy(sha256 = null)) }
            assertTrue(file.delete())
            assertFails { verifyAndroidUpdateFile(file, info) }
        } finally { file.delete() }
    }
}
