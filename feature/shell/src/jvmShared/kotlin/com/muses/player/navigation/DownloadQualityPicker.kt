package com.muses.player.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.muses.player.core.lxsdk.LxQuality
import com.muses.player.core.model.Song
import com.muses.player.core.model.online.OnlineTrackRef
import com.muses.player.core.ui.components.MusesActionItem
import com.muses.player.core.ui.components.MusesActionSongInfo
import com.muses.player.core.ui.components.MusesActionsSheet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun catalogDownloadQualities(path: String?): List<LxQuality> {
    val ref = OnlineTrackRef.parse(path) ?: return emptyList()
    val keys = runCatching {
        Json.parseToJsonElement(ref.musicInfoJson).jsonObject["catalogQualityTiers"]
            ?.jsonPrimitive?.contentOrNull.orEmpty().split(',').toSet()
    }.getOrDefault(emptySet())
    return LxQuality.ordered().filter { it.key in keys }
}

@Composable
internal fun DownloadQualityPicker(song: Song?, defaultQuality: String, onDismiss: () -> Unit, onChoose: (Song, String) -> Unit) {
    var showMore by remember(song?.path) { mutableStateOf(false) }
    val known = remember(song?.path) { catalogDownloadQualities(song?.path) }
    val preferred = com.muses.player.core.lxsdk.qualitySelectionKey(defaultQuality)
    val initial = com.muses.player.core.lxsdk.musicQualitySelections
    val remaining = com.muses.player.core.lxsdk.spatialQualitySelections
    fun choose(quality: com.muses.player.core.lxsdk.QualitySelection) { song?.let { onChoose(it, quality.key) } }
    val items = if (showMore) {
        remaining.map { quality ->
            MusesActionItem("${quality.label} · 可用性待确认") { choose(quality) }
        } + MusesActionItem("返回") { showMore = false }
    } else {
        initial.map { quality ->
            MusesActionItem(quality.label + if (quality.key == preferred) "（默认）" else "") { choose(quality) }
        } + MusesActionItem("空间音频 ›") { showMore = true }
    }
    MusesActionsSheet(
        opened = song != null,
        onDismiss = onDismiss,
        label = "选择音质并开始下载（实际音质以下载结果为准）",
        songInfo = song?.let { MusesActionSongInfo(it.title, it.artist, it.album, it.coverUri) },
        items = items,
    )
}
