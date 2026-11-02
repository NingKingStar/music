package com.muses.player.navigation

import androidx.compose.runtime.*
import com.muses.player.core.model.Song
import com.muses.player.core.ui.components.MusesActionsSheet
import com.muses.player.core.ui.components.MusesActionItem
import com.muses.player.core.lxsdk.musicQualitySelections
import com.muses.player.download.DownloadManager
import com.muses.player.feature.home.OnlineArtistScreen
import com.muses.player.feature.home.OnlineArtistViewModel
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun OnlineArtistRoute(route: MusesRoute.OnlineArtist, onBack: () -> Unit) {
    val seed = remember(route) { Song(route.songId, route.sourceId, route.path, route.title, route.artist) }
    val vm: OnlineArtistViewModel = koinViewModel(key = route.toString(), parameters = { parametersOf(seed) })
    val manager = koinInject<DownloadManager>()
    val preparing by manager.preparingPlaylistDownloads.collectAsState()
    var selection by remember(route) { mutableStateOf<PlaylistDownloadSelection?>(null) }
    var quality by remember { mutableStateOf(false) }
    OnlineArtistScreen(vm, onBack, onDownload = { selection = PlaylistDownloadSelection.from(it); quality = false })
    selection?.let { selected ->
        PlaylistDownloadPicker(selected, onChange = { selection = it },
            onDismiss = { if (quality) quality = false else selection = null },
            onDownload = { if (!preparing && selected.selectedIds.isNotEmpty()) quality = true },
            qualityContent = {
                if (quality) MusesActionsSheet(opened = true, onDismiss = { quality = false },
                    label = "下载 ${selected.selectedIds.size} 首 · 选择音质", renderInRootScaffold = false,
                    items = musicQualitySelections.map { entry -> MusesActionItem(entry.label) {
                        quality = false; selection = null
                        if (!preparing) manager.downloadPlaylist(selected.selectedSongs, entry.key)
                    } })
            })
    }
}
