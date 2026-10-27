package com.muses.player.download

import com.muses.player.core.model.download.DownloadTarget
import com.muses.player.core.model.download.DownloadTargetKind
import com.muses.player.core.model.download.DownloadTask
import com.muses.player.core.data.repository.SettingsRepository
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

actual fun createDownloadStorage(settings: SettingsRepository): DownloadStorage = object : DownloadStorage {
    override val cacheDirectory = File(System.getProperty("java.io.tmpdir"), "muses-downloads")
    override val stagingDirectory = File(com.muses.player.core.data.platform.PlatformDirs.appDataDir(), "pending-downloads")
    override suspend fun deleteSaved(location: String): Boolean {
        val file = File(location)
        require(file.isAbsolute && !Files.isSymbolicLink(file.toPath())) { "文件位置无效" }
        if (!file.exists()) return false
        require(file.isFile) { "目标不是文件" }
        check(file.delete()) { "无法删除文件，请检查权限" }
        return true
    }
    override suspend fun exists(task: DownloadTask): Boolean {
        val location = task.savedLocation ?: return false
        return File(location).isFile
    }
    override suspend fun save(files: List<File>, target: DownloadTarget, progress: suspend (Long, Long) -> Unit): SavedDownload {
        // 设备下载目录可由用户自定义；未设置时落用户主目录的 Downloads/Muses
        val configured = settings.downloadDeviceDirectory.first().trim()
        val directory = if (target.kind == DownloadTargetKind.DEVICE) {
            configured.takeIf { it.isNotEmpty() }?.let(::File) ?: File(System.getProperty("user.home"), "Downloads/Muses")
        } else File(target.directory)
        require(directory.isAbsolute) { "保存目录无效" }
        directory.mkdirs()
        check(directory.isDirectory) { "保存目录不可用" }
        val root = directory.canonicalFile
        val total = files.sumOf { it.length() }
        var copied = 0L
        val warnings = mutableListOf<String>()
        var lastProgress = 0L
        for ((index, file) in files.withIndex()) {
            try {
            val destination = File(root, file.name).canonicalFile
            require(destination.parentFile == root) { "文件名无效" }
            val staging = Files.createTempFile(root.toPath(), ".muses-", ".part")
            try {
            Files.newOutputStream(staging, StandardOpenOption.WRITE).use { output ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count); copied += count
                        val now = System.nanoTime()
                        if (now - lastProgress > 250_000_000) { progress(copied, total); lastProgress = now }
                    }
                }
            }
            Files.move(staging, destination.toPath())
            } finally { staging.toFile().delete() }
            } catch (e: Exception) {
                if (index == 0 || e is kotlinx.coroutines.CancellationException) throw e
                warnings += "${file.extension} 附属信息保存失败，音频已保存"
            }
        }
        progress(copied, total)
        return SavedDownload(File(root, files.first().name).absolutePath, File(root, files.first().name).absolutePath, warnings)
    }
}

actual fun keepDownloadsRunning(running: Boolean) = Unit
