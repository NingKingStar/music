package com.muses.player.desktop.library

import com.muses.player.core.data.platform.PlatformDirs
import com.muses.player.core.model.Song
import com.muses.player.core.model.SourceType
import com.muses.player.core.scrape.ports.JaudiotaggerTagPort
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Windows 本地文件夹扫描：桌面没有 MediaStore，直接遍历目录并用 jaudiotagger 读标签。
 *
 * [Song.path] 存绝对路径而不是 file URI：JvmPlayerPort 明确要求把绝对路径交给 VLC，
 * Windows 下 File.toURI() 生成的畸形 MRL 会被当成 DVD 打开。封面则写入 cache/covers
 * 并以三斜杠 file URI 返回（对齐安卓 CoverCacheWriter）。
 */
object DesktopLocalLibraryScanner {
    const val SOURCE_ID = "desktop-local"

    private val audioExtensions = setOf(
        "mp3", "flac", "m4a", "aac", "wav", "ogg", "opus", "ape", "wma", "aiff", "aif", "dsf", "dff",
    )

    suspend fun scan(
        root: File,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<Song> = withContext(Dispatchers.IO) {
        require(root.isDirectory) { "请选择有效的音乐文件夹" }
        val context = coroutineContext
        val files = root.walkTopDown()
            .onEnter { !it.name.startsWith(".") }
            .filter { file ->
                context.ensureActive()
                file.isFile && file.extension.lowercase() in audioExtensions
            }
            .toList()
        files.mapIndexedNotNull { index, file ->
            context.ensureActive()
            val song = runCatching { readSong(file) }.getOrNull()
            onProgress(index + 1, files.size)
            song
        }.distinctBy { it.path }
    }

    private fun readSong(file: File): Song {
        val tags = JaudiotaggerTagPort.readTags(file)
        val title = tags?.title?.takeIf { it.isNotBlank() }
            ?: file.nameWithoutExtension.takeIf { it.isNotBlank() }
            ?: file.name
        val songId = "local:" + sha256(file.absolutePath).take(24)
        val cover = tags?.cover?.takeIf { it.isNotEmpty() }?.let { bytes -> writeCover(songId, bytes) }
        return Song(
            id = songId,
            sourceId = SOURCE_ID,
            path = file.absolutePath,
            title = title,
            artist = tags?.artist?.takeIf { it.isNotBlank() },
            album = tags?.album?.takeIf { it.isNotBlank() },
            durationMs = tags?.durationMs?.takeIf { it > 0L } ?: 0L,
            durationSec = (tags?.durationMs ?: 0L).coerceAtLeast(0L) / 1000L,
            coverUri = cover,
            lyrics = tags?.lyrics?.takeIf { it.isNotBlank() },
            sourceType = SourceType.LOCAL,
            tagsVersion = 1,
            audioQuality = tags?.audioQuality?.takeIf { it.isNotBlank() },
        )
    }

    private fun writeCover(songId: String, bytes: ByteArray): String? = runCatching {
        val directory = File(PlatformDirs.cacheDir(), "covers").apply { mkdirs() }
        val file = File(directory, sha256(songId) + ".jpg")
        file.writeBytes(bytes)
        "file:///" + file.absolutePath.replace('\\', '/')
    }.getOrNull()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
