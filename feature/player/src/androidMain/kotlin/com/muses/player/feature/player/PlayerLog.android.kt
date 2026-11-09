package com.muses.player.feature.player

import java.io.File

/**
 * 诊断日志落盘目标。
 *
 * 这台设备（ColorOS/OPPO）对 release 包的系统日志有屏蔽，`adb logcat` 读不到应用日志，
 * 所以同时写一份到应用外部目录，接 adb 可以直接读出。
 */
object PlayerLogFile {
    @Volatile
    var file: File? = null
}

actual fun playerLog(message: String) {
    android.util.Log.i("MusesPlayer", message)
    val target = PlayerLogFile.file ?: return
    runCatching {
        target.parentFile?.mkdirs()
        val stamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        target.appendText("$stamp $message\n")
        // 只保留最近 200 KB，避免无限增长。
        if (target.length() > 200_000) {
            val lines = target.readLines().takeLast(1200)
            target.writeText(lines.joinToString("\n", postfix = "\n"))
        }
    }
}
