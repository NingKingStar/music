package com.muses.player.core.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
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
    var creating by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
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
    MusesFormSheet(
        title = if (creating) "新建歌单并加入" else "添加到歌单",
        message = listOfNotNull(song.title, song.artist).joinToString(" · "),
        onDismiss = { if (!saving) onDismiss() },
        dismissText = "取消",
        confirmText = if (creating) "创建并加入" else "添加（${additions.size}）",
        confirmEnabled = playlists != null && if (creating) name.isNotBlank() else additions.isNotEmpty(),
        confirmLoading = saving,
        onConfirm = { save(creating) },
        content = {
            if (creating) {
                MusesTextField(name, { name = it.take(40); error = null }, label = "歌单名称", singleLine = true, enabled = !saving)
                MusesContentAction("返回选择已有歌单", { creating = false }, enabled = !saving)
            } else {
            MusesTextField(query, { query = it }, label = "搜索我的歌单", singleLine = true)
            if (playlists == null) Text("正在加载歌单…")
            else if (playlists.orEmpty().isEmpty()) Text("还没有自建歌单，可以在下方新建。")
            val visible = playlists.orEmpty().filter { it.name.contains(query.trim(), ignoreCase = true) }
            if (playlists?.isNotEmpty() == true && visible.isEmpty()) Text("没有匹配的歌单")
            Column(Modifier.fillMaxWidth()) {
                visible.forEach { playlist ->
                    val added = playlist.id in existing
                    Row(Modifier.fillMaxWidth().clickable(enabled = !added && !saving) {
                        selected = if (playlist.id in selected) selected - playlist.id else selected + playlist.id
                    }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        MusesCover(playlist.tracks.firstOrNull { !it.coverUri.isNullOrBlank() }?.coverUri, size = 44.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(playlist.name)
                            Text(if (added) "已添加" else "${playlist.tracks.size} 首", color = MiuixTheme.colorScheme.onBackgroundVariant)
                        }
                        MusesCheckbox(
                            checked = added || playlist.id in selected,
                            enabled = !added && !saving,
                            onToggle = { selected = if (playlist.id in selected) selected - playlist.id else selected + playlist.id },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            MusesContentAction("新建歌单并加入", { creating = true }, Modifier.fillMaxWidth(), enabled = playlists != null && !saving, icon = TablerIcons.Add)
            }
            error?.let { Text(it, color = MiuixTheme.colorScheme.error) }
        },
    )
}
