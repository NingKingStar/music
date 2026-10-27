package com.muses.player.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.muses.player.core.model.download.DownloadTarget
import com.muses.player.core.model.download.DownloadTargetKind
import com.muses.player.core.model.download.DownloadTask
import com.muses.player.core.data.repository.SettingsRepository
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import org.koin.core.context.GlobalContext

actual fun createDownloadStorage(settings: SettingsRepository): DownloadStorage =
    AndroidDownloadStorage(GlobalContext.get().get(), settings)

private class AndroidDownloadStorage(
    private val context: Context,
    private val settings: SettingsRepository,
) : DownloadStorage {
    override val cacheDirectory = File(context.cacheDir, "downloads")
    override val stagingDirectory = File(context.filesDir, "pending-downloads")
    private val resolver get() = context.contentResolver

    override suspend fun deleteSaved(location: String): Boolean {
        require(location.startsWith("content://")) { "无法安全确认文件位置，请在文件管理器中处理" }
        val uri = Uri.parse(location)
        val deleted = try {
            if (DocumentsContract.isDocumentUri(context, uri))
                if (DocumentsContract.deleteDocument(resolver, uri)) 1 else 0
            else resolver.delete(uri, null, null)
        } catch (_: java.io.FileNotFoundException) { 0 }
        return deleted > 0
    }

    override suspend fun exists(task: DownloadTask): Boolean {
        val location = task.savedLocation ?: return false
        // 设备/本地下载落的是 SAF 或 MediaStore 的 content URI，按 URI 查存在性
        if (location.startsWith("content://")) {
            return runCatching {
                resolver.query(Uri.parse(location), arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                    ?.use { cursor -> cursor.moveToFirst() }
                    ?: true
            }.getOrDefault(true)
        }
        return runCatching { File(location).isFile }.getOrDefault(true)
    }

    override suspend fun save(files: List<File>, target: DownloadTarget, progress: suspend (Long, Long) -> Unit): SavedDownload {
        val total = files.sumOf { it.length() }
        var copied = 0L
        var lastProgress = 0L
        val warnings = mutableListOf<String>()
        // 设备下载目录：用户自定义过就走 SAF 目录，否则走 MediaStore 公开下载目录
        val configured = settings.downloadDeviceDirectory.first().trim()
        val physicalDirectory = if (target.kind == DownloadTargetKind.DEVICE) {
            configured.takeIf { it.isNotEmpty() }?.let(::File)
                ?: File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Muses")
        } else File(target.directory)
        val parent = when {
            target.kind == DownloadTargetKind.LOCAL -> findAuthorizedDirectory(physicalDirectory)
            target.kind == DownloadTargetKind.DEVICE && configured.isNotEmpty() -> findAuthorizedDirectory(physicalDirectory)
            else -> null
        }
        var audioUri: Uri? = null
        for ((index, file) in files.withIndex()) {
            var created: Uri? = null
            try {
            val mime = when (file.extension) {
                "mp3" -> "audio/mpeg"; "flac" -> "audio/flac"; "m4a" -> "audio/mp4"; "ogg" -> "audio/ogg"
                "wav" -> "audio/wav"; "aac" -> "audio/aac"; "json" -> "application/json"; "jpg" -> "image/jpeg"
                "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"
                // 逐字歌词侧车文件：text/plain 会被 MediaProvider 改写成 .lrc.txt，改用通用类型保持扩展名
                "lrc" -> "application/octet-stream"
                else -> "text/plain"
            }
            val uri = if (parent != null) {
                val tree = parent.first
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent.second))
                val exists = resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                    var found = false
                    while (cursor.moveToNext()) if (cursor.getString(0) == file.name) found = true
                    found
                } ?: error("无法检查本地目录")
                check(!exists) { "目标文件已存在，未覆盖" }
                DocumentsContract.createDocument(resolver, parent.second, mime, file.name) ?: error("无法创建文件，请重新授权本地目录")
            } else {
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Muses")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }) ?: error("无法创建下载文件")
            }
            created = uri
            resolver.openOutputStream(uri, "w")?.use { output -> file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count); copied += count
                    val now = System.nanoTime()
                    if (now - lastProgress > 250_000_000) { progress(copied, total); lastProgress = now }
                }
            } } ?: error("目录没有写入权限")
            if (parent == null) resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            if (index == 0) audioUri = uri
            } catch (e: Exception) {
                // 只清理此次创建且未发布的半成品，不触碰原有文件。
                created?.let { uri -> runCatching {
                    if (parent != null) DocumentsContract.deleteDocument(resolver, uri) else resolver.delete(uri, null, null)
                } }
                if (index == 0 || e is kotlinx.coroutines.CancellationException) throw e
                warnings += "${file.extension} 附属信息保存失败，音频已保存"
            }
        }
        val physical = File(physicalDirectory, files.first().name).absolutePath
        MediaScannerConnection.scanFile(context, arrayOf(physical), null, null)
        progress(copied, total)
        return SavedDownload(audioUri!!.toString(), physical, warnings)
    }

    private fun findAuthorizedDirectory(directory: File): Pair<Uri, Uri> {
        val path = directory.canonicalPath
        val permission = resolver.persistedUriPermissions.filter { it.isWritePermission }.mapNotNull { permission ->
            val id = runCatching { DocumentsContract.getTreeDocumentId(permission.uri) }.getOrNull() ?: return@mapNotNull null
            val volume = id.substringBefore(':'); val relative = id.substringAfter(':', "")
            val root = if (volume.equals("primary", true)) "/storage/emulated/0" else "/storage/$volume"
            val actual = File(root, relative).canonicalPath
            if (path == actual || path.startsWith("$actual/")) Triple(permission.uri, id, actual) else null
        }.maxByOrNull { it.third.length } ?: error("该目录未授权写入，请重新选择目录")
        var current = DocumentsContract.buildDocumentUriUsingTree(permission.first, permission.second)
        val relative = path.removePrefix(permission.third).trim('/')
        for (part in relative.split('/').filter { it.isNotEmpty() }) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(permission.first, DocumentsContract.getDocumentId(current))
            val found = resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                var id: String? = null
                while (cursor.moveToNext()) if (cursor.getString(1) == part && cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) id = cursor.getString(0)
                id
            } ?: error("本地目录不存在或权限已失效")
            current = DocumentsContract.buildDocumentUriUsingTree(permission.first, found)
        }
        return permission.first to current
    }
}
