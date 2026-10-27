package com.muses.player.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muses.player.core.lxsdk.LxQuality
import com.muses.player.core.model.Source
import com.muses.player.core.model.SourceType
import com.muses.player.core.model.decodeWebDavSourcePaths
import com.muses.player.core.model.download.DownloadStatus
import com.muses.player.core.model.download.DownloadTarget
import com.muses.player.core.model.download.DownloadTargetKind
import com.muses.player.core.model.download.downloadTargetAvailable
import com.muses.player.core.ui.components.MusesActionItem
import com.muses.player.core.ui.components.MusesActionsSheet
import com.muses.player.core.ui.components.MusesIconButton
import com.muses.player.core.ui.components.MusesListRow
import com.muses.player.core.ui.components.MusesIconButtonSize
import com.muses.player.core.ui.components.SongListLayout
import com.muses.player.core.ui.components.MusesCover
import com.muses.player.core.ui.icons.TablerIcons
import com.muses.player.core.ui.components.MusesTopBar
import com.muses.player.core.ui.components.MusesRefreshableContent
import com.muses.player.core.ui.theme.LocalBottomChromePadding
import org.koin.compose.koinInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.muses.player.core.ui.components.MusesTextButton
import com.muses.player.core.ui.components.SongListItem
import com.muses.player.core.ui.components.SongItem
import com.muses.player.core.ui.components.MusesSnackbar
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import com.muses.player.core.ui.components.MarqueeText as Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 下载任务和已经保存的音乐分别展示；完成记录不依赖可清理的任务队列。
 *
 * 保存位置、下载品质这类「全局默认」收在「设置 → 下载」；这里只保留队列操作
 * （全部下载 / 全部暂停 / 单任务继续暂停）、进度与失败原因。
 */
@Composable
fun DownloadQueueScreen(onBack: () -> Unit, manager: DownloadManager = koinInject()) {
    val tasks by manager.tasks.collectAsState()
    val downloaded by manager.downloadedSongs.collectAsState()
    var savedActions by remember { mutableStateOf<com.muses.player.core.model.download.DownloadedSong?>(null) }
    var deleteSavedEntry by remember { mutableStateOf<com.muses.player.core.model.download.DownloadedSong?>(null) }
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    val playback = koinInject<com.muses.player.core.playback.PlaybackPort>()
    val repository = koinInject<com.muses.player.core.data.repository.SongRepository>()
    val currentSongId by playback.currentSongId.collectAsState()
    val scope = rememberCoroutineScope()
    val savedSongs = remember(downloaded) { downloaded.map { it.toSong() } }
    val sources by manager.availableSources.collectAsState()
    val defaultTarget by manager.defaultTarget.collectAsState()
    val scrapeUploads by manager.pendingScrapeUploads.collectAsState()
    val uploadingScrapeId by manager.uploadingScrapeId.collectAsState()
    var targetPickerId by remember { mutableStateOf<String?>(null) }
    var targetPickerOpen by remember { mutableStateOf(false) }
    var actionsId by remember { mutableStateOf<String?>(null) }
    var qualityId by remember { mutableStateOf<String?>(null) }
    var expandedTasks by remember { mutableStateOf(emptySet<String>()) }
    val pending = tasks.filter { !it.status.active && it.status != DownloadStatus.COMPLETED }
    val active = tasks.any { it.status.active } || uploadingScrapeId != null
    val scheme = MiuixTheme.colorScheme
    val favoritesStore = koinInject<com.muses.player.core.data.repository.FavoriteSongsStore>()
    val favorites by favoritesStore.state.collectAsState()
    val preparingFavorites by manager.preparingFavoriteDownloads.collectAsState()
    val missingFavorites = favorites.missingOnlineSongs
    val targets = remember(sources) { downloadTargetOptions(sources) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = scheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { MusesTopBar(title = "下载管理", onBack = onBack, bottomContent = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MusesTextButton(text = "下载中 · ${tasks.count { it.status != DownloadStatus.COMPLETED } + scrapeUploads.size}",
                    modifier = Modifier.weight(1f), primary = !showCompleted, onClick = { showCompleted = false })
                MusesTextButton(text = "已完成 · ${downloaded.size}", modifier = Modifier.weight(1f),
                    primary = showCompleted, onClick = { showCompleted = true })
            }
        }) },
    ) { padding ->
        MusesRefreshableContent(onRefresh = manager::refresh, modifier = Modifier.padding(padding)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 16.dp + LocalBottomChromePadding.current,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!showCompleted) item("controls") {
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(if (active) "正在下载，可随时暂停" else "等待、暂停和失败的任务可继续", modifier = Modifier.weight(1f),
                                style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                            MusesTextButton(text = if (active) "全部暂停" else "全部继续",
                                enabled = active || pending.isNotEmpty(), primary = true,
                                onClick = {
                                    if (active) manager.pause()
                                    else manager.start(pending.map { it.id }.toSet(), defaultTarget)
                                })
                        }
                    }
                    if (!showCompleted) item("favorites") {
                        MusesListRow(title = "下载我喜欢", subtitle = when {
                            preparingFavorites -> "正在准备…"
                            !favorites.loaded -> "正在读取…"
                            missingFavorites.isEmpty() -> "喜欢的在线歌曲已全部保存"
                            else -> "${missingFavorites.size} 首在线歌曲待下载"
                        }, leading = {
                            Icon(TablerIcons.Heart, null, Modifier.padding(end = 12.dp).size(24.dp),
                                tint = scheme.onBackgroundVariant)
                        }, after = {
                            MusesIconButton(onClick = { manager.downloadFavorites(favoritesStore) },
                                imageVector = TablerIcons.Download, contentDescription = "下载未入库的喜欢歌曲",
                                enabled = favorites.loaded && missingFavorites.isNotEmpty() && !preparingFavorites,
                                tint = scheme.primary)
                        }, dividers = false)
                    }

                    if (!showCompleted && (tasks.any { it.status == DownloadStatus.PENDING_UPLOAD } || scrapeUploads.isNotEmpty())) {
                        item("upload-controls") {
                            Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("待补传 · ${tasks.count { it.status == DownloadStatus.PENDING_UPLOAD } + scrapeUploads.size}",
                                    modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                                MusesIconButton(onClick = {
                                    manager.start(tasks.filter { it.status == DownloadStatus.PENDING_UPLOAD }.map { it.id }.toSet(), defaultTarget)
                                    manager.retryScrapeUploads(scrapeUploads.map { it.id }.toSet())
                                }, enabled = !active, imageVector = TablerIcons.Upload, contentDescription = "全部补传", tint = scheme.primary)
                            }
                        }
                    }
                    if (!showCompleted) items(scrapeUploads, key = { "scrape-${it.id}" }) { task ->
                        Column {
                            MusesListRow(title = task.title,
                                subtitle = if (uploadingScrapeId == task.id) "刮削 · 上传与核验中" else "刮削 · 本地已保存，待上传",
                                songLayout = true,
                                after = {
                                    MusesIconButton(onClick = { manager.exportScrapeUpload(task.id) }, enabled = uploadingScrapeId != task.id,
                                        imageVector = TablerIcons.Download, contentDescription = "导出到本地", size = MusesIconButtonSize.SM)
                                    MusesIconButton(onClick = { manager.retryScrapeUploads(setOf(task.id)) }, enabled = uploadingScrapeId == null,
                                        imageVector = TablerIcons.Upload, contentDescription = "补传", size = MusesIconButtonSize.SM, tint = scheme.primary)
                                })
                            task.error?.let { Text(it, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                style = MiuixTheme.textStyles.footnote1, color = scheme.error) }
                        }
                    }

                    if (!showCompleted && tasks.none { it.status != DownloadStatus.COMPLETED } && scrapeUploads.isEmpty()) {
                        item("empty") {
                            Text("暂无下载中的任务\n在歌曲菜单点击「下载歌曲」即可开始", modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
                                style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }

                    val groups = listOf(
                        "进行中" to tasks.filter { it.status.active },
                        "等待下载" to tasks.filter { it.status == DownloadStatus.WAITING },
                        "已暂停" to tasks.filter { it.status == DownloadStatus.PAUSED },
                        "需要处理" to tasks.filter { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.PENDING_UPLOAD },
                    )
                    if (!showCompleted) groups.filter { it.second.isNotEmpty() }.forEach { (title, entries) ->
                        item("section-$title") {
                            Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(title, style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                                Text(entries.size.toString(), style = MiuixTheme.textStyles.footnote1,
                                    color = scheme.onSurfaceVariantSummary)
                            }
                        }
                        items(entries, key = { it.id }) { task ->
                            DownloadTaskRow(task, defaultTarget, sources,
                                expanded = task.id in expandedTasks,
                                onToggleDetails = { expandedTasks = if (task.id in expandedTasks) expandedTasks - task.id else expandedTasks + task.id },
                                onAction = {
                                    if (task.status.active) manager.pause(task.id)
                                    else manager.start(setOf(task.id), defaultTarget)
                                },
                                onMore = { actionsId = task.id })
                        }
                    }
                    if (showCompleted) {
                        item("saved-summary") {
                            Text("已保存的音乐 · 点击歌曲播放", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                        }
                        if (downloaded.isEmpty()) item("saved-empty") {
                            Text("还没有已完成的音乐\n下载并保存成功后，会自动出现在这里", modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
                                style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                        items(downloaded, key = { "saved-${it.taskId}" }) { entry ->
                            val song = entry.toSong()
                            Card(Modifier.fillMaxWidth()) {
                                SongListItem(SongItem(song.id, song.title, song.artist, song.album, song.coverUri, song.audioQuality),
                                    isCurrent = currentSongId == song.id,
                                    onClick = {
                                        scope.launch {
                                            try {
                                                withContext(Dispatchers.IO) { repository.prepareDownloads(savedSongs) }
                                                playback.play(song.id, savedSongs)
                                            } catch (e: CancellationException) { throw e }
                                            catch (_: Exception) { MusesSnackbar.show("无法播放，请检查保存位置和文件是否存在") }
                                        }
                                    },
                                    trailingContent = {
                                        Icon(TablerIcons.CheckCircle, contentDescription = "已保存", tint = scheme.primary, modifier = Modifier.size(20.dp))
                                        MusesIconButton(onClick = {
                                            expandedTasks = if (entry.taskId in expandedTasks) expandedTasks - entry.taskId else expandedTasks + entry.taskId
                                        }, imageVector = if (entry.taskId in expandedTasks) TablerIcons.Close else TablerIcons.Info,
                                            contentDescription = "查看${song.title}的保存位置", size = MusesIconButtonSize.SM)
                                        com.muses.player.core.ui.components.SongMoreButton(onClick = { savedActions = entry })
                                    })
                                if (entry.taskId in expandedTasks) Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("已保存到：${entry.target.displayLabel(sources)}", style = MiuixTheme.textStyles.footnote1,
                                        color = scheme.onSurfaceVariantSummary, marqueeEnabled = false)
                                    Text(entry.location, style = MiuixTheme.textStyles.footnote1,
                                        color = scheme.onSurfaceVariantSummary, maxLines = 3, overflow = TextOverflow.Ellipsis, marqueeEnabled = false)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    MusesActionsSheet(opened = savedActions != null, onDismiss = { savedActions = null }, label = "歌曲操作",
        items = listOf(MusesActionItem("删除下载", destructive = true) { deleteSavedEntry = savedActions; savedActions = null }))
    deleteSavedEntry?.let { DeleteDownloadedSongDialog(it, manager) { deleteSavedEntry = null } }

    val selected = tasks.firstOrNull { it.id == actionsId }
    MusesActionsSheet(
        opened = selected != null,
        onDismiss = { actionsId = null },
        label = "下载任务",
        songInfo = selected?.track?.let { com.muses.player.core.ui.components.MusesActionSongInfo(it.title, it.artist, it.album, it.coverUri) },
        items = buildList {
            selected?.let { task ->
                add(MusesActionItem(if (task.id in expandedTasks) "收起详情" else "查看详情") {
                    expandedTasks = if (task.id in expandedTasks) expandedTasks - task.id else expandedTasks + task.id
                    actionsId = null
                })
            }
            if (selected != null && !selected.status.active && selected.status != DownloadStatus.COMPLETED) {
                add(MusesActionItem("更改保存位置") { targetPickerId = selected.id; actionsId = null; targetPickerOpen = true })
                if (selected.localAudioPath == null) add(MusesActionItem("更改下载品质") { qualityId = selected.id; actionsId = null })
                if (selected.localAudioPath != null) add(MusesActionItem("导出到本地") { manager.exportDownload(selected.id); actionsId = null })
            }
            selected?.let { task -> add(MusesActionItem("移出队列") { manager.remove(task.id); actionsId = null }) }
        },
    )
    MusesActionsSheet(
        opened = targetPickerOpen,
        onDismiss = { targetPickerOpen = false },
        label = "保存位置",
        items = targets.map { target ->
            MusesActionItem(target.label) {
                targetPickerId?.let { manager.configure(it, target = target) }
                targetPickerOpen = false
            }
        },
    )
    MusesActionsSheet(
        opened = qualityId != null,
        onDismiss = { qualityId = null },
        label = "下载品质",
        items = LxQuality.entries.map { quality ->
            MusesActionItem(quality.label) {
                qualityId?.let { manager.configure(it, quality = quality.key) }
                qualityId = null
            }
        },
    )
}

/** 先展示歌曲和进度，品质与保存位置作为次要信息，异常单独保留。 */
@Composable
private fun DownloadTaskRow(
    task: com.muses.player.core.model.download.DownloadTask,
    defaultTarget: DownloadTarget,
    sources: List<Source>,
    expanded: Boolean,
    onToggleDetails: () -> Unit,
    onAction: () -> Unit,
    onMore: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val presentation = downloadProgressPresentation(task)
    val progress = presentation.fraction
    val animatedProgress by animateFloatAsState(progress ?: 0f, tween(220), label = "下载进度")
    val statusColor = when {
        task.status == DownloadStatus.FAILED -> scheme.error
        task.status.active || task.status == DownloadStatus.COMPLETED -> scheme.primary
        else -> scheme.onSurfaceVariantSummary
    }
    val marquee = com.muses.player.core.ui.components.LocalSongMarqueeState.current.matches(task.track.id)
    Card(Modifier.fillMaxWidth()) {
        val subtitle = listOfNotNull(task.track.artist, task.track.album).filter { it.isNotBlank() }.joinToString(" · ")
        MusesListRow(title = task.track.title, subtitle = subtitle, songLayout = true,
            songId = task.track.id, marqueeEnabled = marquee, dividers = false,
            onClick = onToggleDetails,
            leading = {
                MusesCover(task.track.coverUri, size = SongListLayout.coverSize,
                    modifier = Modifier.padding(end = SongListLayout.coverGap), contentDescription = task.track.title + "封面")
            }, after = {
                if (task.status != DownloadStatus.COMPLETED) {
                    val action = when {
                        task.status.active -> "暂停"
                        task.status == DownloadStatus.PENDING_UPLOAD -> "补传"
                        task.status == DownloadStatus.FAILED -> "重试"
                        task.status == DownloadStatus.PAUSED -> "继续"
                        else -> "下载"
                    }
                    MusesIconButton(onClick = onAction, size = MusesIconButtonSize.SM,
                        imageVector = when {
                            task.status.active -> TablerIcons.Pause
                            task.status == DownloadStatus.FAILED || task.status == DownloadStatus.PENDING_UPLOAD -> TablerIcons.Refresh
                            else -> TablerIcons.Download
                        }, tint = scheme.primary, contentDescription = "$action ${task.track.title}")
                }
                MusesIconButton(onClick = onMore, imageVector = TablerIcons.MoreVert, size = MusesIconButtonSize.SM,
                    contentDescription = "${task.track.title}的更多下载操作")
            })
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(presentation.stage, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.footnote1,
                    color = statusColor, maxLines = 2, overflow = TextOverflow.Ellipsis, marqueeEnabled = false)
                if (progress != null) Text("${(progress * 100).toInt()}%", style = MiuixTheme.textStyles.body2,
                    color = statusColor, marqueeEnabled = false)
            }
            if (task.status.active || (task.status == DownloadStatus.PAUSED && progress != null)) {
                LinearProgressIndicator(progress = if (progress == null) null else animatedProgress, modifier = Modifier.fillMaxWidth())
            }
            if (presentation.amount.isNotEmpty()) Text(presentation.amount, style = MiuixTheme.textStyles.footnote1,
                color = scheme.onSurfaceVariantSummary, marqueeEnabled = false, maxLines = 2)
            if (expanded) {
                val requested = task.requestedQuality
                Text("所选：${qualityLabel(task.quality)}" + when {
                    task.actualQualityLabel != null -> " · 实际：${task.actualQualityLabel}"
                    requested != null && requested != task.quality ->
                        " · 请求：${qualityLabel(requested)}（已降档，实际音质待核实）"
                    task.status == DownloadStatus.COMPLETED -> " · 实际音质未核实"
                    else -> ""
                }, style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary,
                    marqueeEnabled = false)
                val target = task.target ?: defaultTarget
                val available = downloadTargetAvailable(target, sources)
                Text("保存到：${target.displayLabel(sources)}" + if (available) "" else "（音源已删除）",
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (available) scheme.onSurfaceVariantSummary else scheme.error,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, marqueeEnabled = false)
            }
            task.error?.let { Text(it, color = scheme.error, style = MiuixTheme.textStyles.footnote1,
                maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis, marqueeEnabled = false) }
            task.qualityWarning?.let { Text(it, color = scheme.error, style = MiuixTheme.textStyles.footnote1, marqueeEnabled = false) }
            task.warnings.filter { it != task.qualityWarning }.forEach {
                Text(it, color = scheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote1, marqueeEnabled = false)
            }
        }
    }
}

/**
 * 可选保存位置：设备下载目录 + 每个本地/WebDAV 音源。
 * 设置页与队列页共用同一份选项，避免两处口径不一致。
 */
internal fun downloadTargetOptions(sources: List<Source>): List<DownloadTarget> =
    listOf(DownloadTarget()) + sources.flatMap { source ->
        when (source.type) {
            SourceType.LOCAL -> source.path?.takeIf { it.isNotBlank() }
                ?.let { listOf(DownloadTarget(DownloadTargetKind.LOCAL, source.id, it, source.name)) }
                .orEmpty()
            SourceType.WEBDAV -> {
                val directories = decodeWebDavSourcePaths(source.path).ifEmpty { listOf("/") }
                directories.map { directory ->
                    // 直接显示音源名称；同一音源配了多个目录时才补路径，避免两条同名项分不清
                    val label = if (directories.size == 1) source.name else "${source.name} · $directory"
                    DownloadTarget(DownloadTargetKind.WEBDAV, source.id, directory, label)
                }
            }
            else -> emptyList()
        }
    }

/**
 * 展示用名称：按当前音源配置重新算，
 * 这样早期存进队列/设置里的「音源名 · 路径」旧标签也会跟着显示成音源名。
 */
internal fun DownloadTarget.displayLabel(sources: List<Source>): String =
    downloadTargetOptions(sources)
        .firstOrNull { it.kind == kind && it.sourceId == sourceId && it.directory == directory }
        ?.label
        ?: label

internal fun qualityLabel(key: String): String = LxQuality.fromKey(key)?.label ?: key

internal fun formatBytes(bytes: Long): String =
    when {
        bytes < 1024 -> "${bytes.coerceAtLeast(0)} B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024))
    }

internal fun statusLabel(status: DownloadStatus): String = when (status) {
    DownloadStatus.PENDING_UPLOAD -> "本地已保存，待上传"
    DownloadStatus.WAITING -> "待下载"
    DownloadStatus.PREPARING -> "准备下载"
    DownloadStatus.DOWNLOADING -> "下载中"
    DownloadStatus.METADATA -> "保存歌曲信息"
    DownloadStatus.SAVING -> "保存中"
    DownloadStatus.UPLOADING -> "上传中"
    DownloadStatus.PAUSED -> "已暂停"
    DownloadStatus.FAILED -> "下载失败"
    DownloadStatus.COMPLETED -> "已完成"
}
