package com.muses.player.navigation

import androidx.compose.runtime.*
import com.muses.player.core.model.Song
import com.muses.player.core.ui.components.MusesActionsSheet
import com.muses.player.core.ui.components.MusesActionItem
import com.muses.player.core.lxsdk.musicQualitySelections
import com.muses.player.download.DownloadManager
import com.muses.player.feature.home.ChartDetailScreen
import org.koin.compose.koinInject

@Composable
internal fun ChartDetailRoute(platform: String, chartId: String, chartName: String, onBack: () -> Unit, isPlaylist: Boolean = false) {
    val manager = koinInject<DownloadManager>()
    val preparing by manager.preparingPlaylistDownloads.collectAsState()
    var selection by remember(chartId, platform) { mutableStateOf<PlaylistDownloadSelection?>(null) }
    var quality by remember { mutableStateOf(false) }
    ChartDetailScreen(platform, chartId, chartName, onBack, isPlaylist,
        onDownloadSongs = { songs: List<Song> -> selection = PlaylistDownloadSelection.from(songs); quality = false },
        downloadEnabled = !preparing)
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
