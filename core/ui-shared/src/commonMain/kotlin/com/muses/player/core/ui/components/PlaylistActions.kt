package com.muses.player.core.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.muses.player.core.data.repository.UserPlaylistStore
import com.muses.player.core.model.Song
import com.muses.player.core.ui.icons.TablerIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 独立的添加入口，不复用心形开关，也不向系统分类写入。 */
val LocalAddSongToPlaylist = staticCompositionLocalOf<((Song) -> Unit)?> { null }

@Composable
fun SongPlaylistButton(song: Song) {
    val add = LocalAddSongToPlaylist.current ?: return
    MusesIconButton(
        onClick = { add(song) },
        imageVector = TablerIcons.Add,
        contentDescription = "添加到歌单",
        size = SongListLayout.actionButtonSize,
    )
}

@Composable
fun PlaylistPickerDialog(song: Song, store: UserPlaylistStore, onDismiss: () -> Unit) {
    val playlists by store.playlists.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var selected by remember(song.id) { mutableStateOf(emptySet<String>()) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val existing = playlists.orEmpty().filter { it.contains(song) }.map { it.id }.toSet()
    val additions = selected - existing
    fun save(create: Boolean) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                if (create) store.create(name, song) else store.addTo(additions, song)
                MusesSnackbar.show(if (create) "已新建歌单并加入歌曲" else "已加入所选歌单")
                onDismiss()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "保存失败，请重试" }
            finally { saving = false }
        }
    }
    MusesDialog(
        title = "添加到歌单",
        message = song.title,
        onDismiss = { if (!saving) onDismiss() },
        dismissText = "取消",
        confirmText = "添加",
        confirmEnabled = playlists != null && additions.isNotEmpty(),
        confirmLoading = saving,
        onConfirm = { save(false) },
        content = {
            Text("只添加到自建歌单，不改变我喜欢和已下载。", color = MiuixTheme.colorScheme.onBackgroundVariant)
            Spacer(Modifier.height(8.dp))
            if (playlists == null) Text("正在加载歌单…")
            else if (playlists.orEmpty().isEmpty()) Text("还没有自建歌单，可以在下方新建。")
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                items(playlists.orEmpty(), key = { it.id }) { playlist ->
                    val added = playlist.id in existing
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        MusesCheckbox(
                            checked = added || playlist.id in selected,
                            enabled = !added && !saving,
                            onToggle = { selected = if (playlist.id in selected) selected - playlist.id else selected + playlist.id },
                        )
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(playlist.name)
                            Text(if (added) "已添加 · 移除请进入该歌单" else "${playlist.tracks.size} 首",
                                color = MiuixTheme.colorScheme.onBackgroundVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            MusesTextField(name, { name = it; error = null }, label = "新歌单名称", singleLine = true, enabled = !saving)
            MusesTextButton(text = "新建歌单并加入", onClick = { save(true) }, enabled = playlists != null && name.isNotBlank() && !saving)
            error?.let { Text(it, color = MiuixTheme.colorScheme.error) }
        },
    )
}
