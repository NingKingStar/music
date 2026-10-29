package com.muses.player.navigation

import androidx.compose.runtime.*
import com.muses.player.core.data.repository.UserPlaylistStore
import com.muses.player.core.search.provider.ImportedPlaylist
import com.muses.player.core.search.provider.PlaylistImportService
import com.muses.player.core.search.provider.parsePlaylistAddress
import com.muses.player.core.search.http.SearchHttp
import com.muses.player.core.ui.components.MusesDialog
import com.muses.player.core.ui.components.MusesTextField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import org.koin.compose.koinInject

@Composable
internal fun ImportPlaylistDialog(store: UserPlaylistStore, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    var link by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var playlist by remember { mutableStateOf<ImportedPlaylist?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val http = koinInject<SearchHttp>()
    val service = remember(http) { PlaylistImportService(http) }
    val scope = rememberCoroutineScope()
    val loaded = playlist
    MusesDialog(
        title = if (loaded == null) "导入歌单" else "确认导入",
        message = if (loaded == null) "粘贴网易云音乐或 QQ 音乐公开歌单的完整分享链接。" else "共 ${loaded.songs.size} 首，其中 ${loaded.songs.count { com.muses.player.core.search.needsSongMetadata(it.toSong("online")) }} 首信息待补全。播放可用性未检测；不自动下载。",
        onDismiss = { if (!busy) onDismiss() }, dismissText = "取消",
        confirmText = if (loaded == null) "读取歌单" else "导入歌单",
        confirmEnabled = if (loaded == null) link.isNotBlank() else name.isNotBlank(), confirmLoading = busy,
        onConfirm = {
            if (!busy) {
                busy = true; error = null
                scope.launch {
                    try {
                        if (loaded == null) {
                            val result = service.load(parsePlaylistAddress(link))
                            playlist = result
                            name = result.name.take(40)
                        } else {
                            // 自建歌单按添加时间倒序显示，反转后可保留来源歌单顺序。
                            val songs = loaded.songs.map { it.toSong("online") }.asReversed()
                            val id = store.createImported(name, songs)
                            onOpen(id)
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "读取失败，请稍后重试" }
                    finally { busy = false }
                }
            }
        },
        content = {
            if (loaded == null) MusesTextField(link, { link = it; error = null }, label = "歌单链接", enabled = !busy, singleLine = true)
            else MusesTextField(name, { name = it; error = null }, label = "歌单名称（1–40 个字符）", enabled = !busy, singleLine = true)
            error?.let { Text(it, color = MiuixTheme.colorScheme.error) }
        },
    )
}
