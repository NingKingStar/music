package com.muses.player.feature.sources

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muses.player.core.util.RefreshableState
import com.muses.player.core.data.dao.SongDao
import com.muses.player.core.data.repository.CredentialsRepository
import com.muses.player.core.data.repository.PlaybackStateRepository
import com.muses.player.core.data.repository.RecentPlaysRepository
import com.muses.player.core.data.repository.ScanMergeResult
import com.muses.player.core.data.repository.SettingsRepository
import com.muses.player.core.data.repository.SongRepository
import com.muses.player.core.data.repository.SourceRepository
import com.muses.player.core.data.store.platformNowMs
import com.muses.player.core.media.scanner.ScanProgress
import com.muses.player.core.model.Source
import com.muses.player.core.model.SourceType
import com.muses.player.core.lxsdk.LxScriptRepository
import com.muses.player.core.lxsdk.store.LxScriptStore
import com.muses.player.core.scrape.queue.ScrapeQueueStore
import com.muses.player.core.webdav.WebDavAuthRegistry
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 音源 ViewModel（U20 全量上收 commonMain）：数据核 + 页面扩展一体——音源 CRUD/表单/
 * 扫描编排/本地建源进 commonMain，依赖全部为 KMP 数据栈（P2b/W3/W4 产物）+
 * [LibraryScanPort]（androidMain 绑定 MediaStore/WebDAV 扫描器）+ 播放队列清理
 * （经 [com.muses.player.core.playback.PlaybackPort].removeFromQueue 注入回调）。
 * 原安卓子类（扫描器装配 + SAF 树 uri 建源）已并入：SAF 选目录改为
 * [rememberLocalFolderPicker] expect/actual，物理路径解析留安卓 actual。
 */
@OptIn(ExperimentalUuidApi::class)
class SourcesViewModel constructor(
    private val sourceRepository: SourceRepository,
    private val songRepository: SongRepository,
    private val scanPort: LibraryScanPort,
    private val settingsRepository: SettingsRepository,
    private val songDao: SongDao,
    private val scrapeQueueStore: ScrapeQueueStore,
    private val credentialsRepository: CredentialsRepository,
    private val webDavAuthRegistry: WebDavAuthRegistry,
    private val playbackStateRepository: PlaybackStateRepository,
    private val recentPlaysRepository: RecentPlaysRepository,
    private val lxScriptStore: LxScriptStore,
    private val lxScriptRepository: LxScriptRepository,
    private val libraryScanner: LibraryRefreshScanner,
    /** 删除音源时的播放队列清理（双端接 PlaybackPort.removeFromQueue；可缺省空实现） */
    private val onRemoveFromQueue: (Set<String>) -> Unit = {},
) : ViewModel() {

    private val sourceState = RefreshableState(
        sourceRepository.observeSources().map { sources ->
            val builtinIds = withContext(Dispatchers.IO) { lxScriptStore.builtinIds() }
            sources.filterNot { it.type == SourceType.ONLINE && it.id in builtinIds }
        },
        viewModelScope,
        emptyList(),
    )
    val sources: StateFlow<List<Source>> = sourceState.state

    /** 只重读音源列表，不扫描文件、不重新导入脚本。 */
    suspend fun refresh() = sourceState.refresh()

    init {
        // 音源列表只登记用户脚本；内置音源由设置中的统一开关管理。
        viewModelScope.launch(Dispatchers.IO) {
            lxScriptStore.list().filterNot { it.isBuiltin }.forEach { script ->
                if (sourceRepository.getSource(script.id) == null) {
                    val now = platformNowMs()
                    sourceRepository.upsert(Source(
                        id = script.id,
                        name = script.name,
                        type = SourceType.ONLINE,
                        path = script.id,
                        createdAt = now,
                        updatedAt = now,
                    ))
                }
            }
        }
    }

    // ── Salt 复刻交互状态（SourcesPage.vue ref 组）──────────

    /** m-actions：添加音源面板开关 */
    var isAddActionSheetOpen by mutableStateOf(false)
        private set

    /** m-dialog：删除确认目标 */
    var pendingDelete by mutableStateOf<Source?>(null)
        private set

    /** m-dialog：本地音源编辑目标 */
    var pendingEdit by mutableStateOf<Source?>(null)
        private set

    // ── 扫描流程 ──────────────────────────────

    /** m-dialog「扫描进度」开关 */
    var isScanProgressOpen by mutableStateOf(false)
        private set

    /** 扫描进度统一 UI 流（扫描端口各自持有进度流，startScan 时转发到此处） */
    private val _scanProgress = MutableStateFlow(ScanProgress())
    val scanProgress: StateFlow<ScanProgress> = _scanProgress.asStateFlow()

    /** 扫描失败信息（非空 = 进度弹窗显示「扫描失败」态，对照 Web stage=failed + message） */
    var scanError by mutableStateOf<String?>(null)
        private set

    /** 扫描结果汇总 toast 文案（单次事件，UI 消费后调 [clearScanResultMessage] 置空） */
    var scanResultMessage by mutableStateOf<String?>(null)
        private set

    /** 最近一次入库合并统计，用于扫描完成态展示成功与跳过数量。 */
    var scanMergeResult by mutableStateOf<ScanMergeResult?>(null)
        private set

    /** 防重入标记：扫描进行中禁止再次 startScan / 关闭进度弹窗 */
    private var isScanning = false

    fun openAddActionSheet() {
        isAddActionSheetOpen = true
    }

    fun closeAddActionSheet() {
        isAddActionSheetOpen = false
    }

    fun confirmDelete(source: Source) {
        pendingDelete = source
    }

    fun dismissDelete() {
        pendingDelete = null
    }

    /** 本地音源编辑（WebDAV 走浏览页） */
    fun openEditForm(source: Source) {
        pendingEdit = source
    }

    fun dismissEdit() {
        pendingEdit = null
    }

    // ── 扫描流程方法 ──────────────────────────────

    /** 关闭「扫描进度」弹窗（扫描进行中禁止关闭，双保险） */
    fun dismissScanProgress() {
        if (isScanning) return
        isScanProgressOpen = false
        scanError = null
        scanMergeResult = null
        clearScanResultMessage()
    }

    /** UI 消费完 toast 文案后置空（单次事件语义） */
    fun clearScanResultMessage() {
        scanResultMessage = null
    }

    /** 直接扫描：本地读取标签，WebDAV 发现文件后由播放懒扫描补充标签。 */
    fun startScan(source: Source) {
        if (source.type == SourceType.ONLINE || isScanning) return
        isScanProgressOpen = true
        isScanning = true
        scanError = null
        scanMergeResult = null
        clearScanResultMessage()
        viewModelScope.launch {
            // 把扫描端口的进度流转发到统一的 UI 流，
            // 转发协程随扫描结束在 finally 中取消（最简转发方案，不引入合并流复杂度）
            val progressJob = launch {
                scanPort.progressFor(source.type).collect { _scanProgress.value = it }
            }
            try {
                val result = libraryScanner.scan(source, readTags = source.type == SourceType.LOCAL)
                scanMergeResult = result
                scanResultMessage = scanResultText(result)
                // M3 自动补缺：扫描后把无标签歌曲排进刮削队列（开关默认关）
                if (settingsRepository.autoScrapeEnabled.first()) {
                    val untagged = songDao.getUntaggedSongIds()
                    if (untagged.isNotEmpty()) scrapeQueueStore.enqueue(untagged)
                }
            } catch (e: CancellationException) {
                // VM 销毁导致的协程取消：原样抛出交回结构化并发，不误报「扫描失败」
                throw e
            } catch (e: Exception) {
                // scanner 内部 progress 流已中断，异常消息写入 scanError 供进度弹窗显示失败态
                scanError = e.message ?: "扫描失败"
            } finally {
                progressJob.cancel()
                isScanning = false
            }
        }
    }

    /**
     * 扫描完成文案（含合并统计）：正常落库报告新增/丢失；
     * 空结果或锐减保护触发时说明原曲库未动（[ScanMergeResult.skipped]）。
     */
    private fun scanResultText(result: ScanMergeResult): String = when {
        result.skipped && result.scanned == 0 -> "本次未发现任何文件，已保留原有曲库。"
        result.skipped -> "本次仅发现 ${result.scanned} 首，疑似音源异常，已保留原有曲库。"
        else -> {
            val extras = buildList {
                if (result.added > 0) add("新增 ${result.added} 首")
                if (result.missing > 0) add("${result.missing} 首未找到已保留")
            }
            if (extras.isEmpty()) {
                "扫描完成：共 ${result.scanned} 首。"
            } else {
                "扫描完成：共 ${result.scanned} 首（${extras.joinToString("，")}）。"
            }
        }
    }

    /** 编辑保存：upsert + touch updatedAt（Web updateSource 同语义） */
    fun updateEditedSource(source: Source, name: String, path: String) {
        if (name.isBlank() || path.isBlank()) return
        viewModelScope.launch {
            sourceRepository.upsert(
                source.copy(name = name, path = path, updatedAt = platformNowMs()),
            )
            // 源变更后同步播放认证注册表（本地源无影响，refresh 幂等开销可忽略）
            webDavAuthRegistry.refresh()
        }
    }

    /**
     * 本地目录建源（U20：原安卓 saveLocalSourceFromTreeUri 的平台无关部分）。
     * 物理路径由各端选择器 actual 解析（安卓 SAF tree uri → 绝对路径；桌面原生路径），
     * 供 LocalLibraryScanner 的前缀过滤直接使用。
     */
    fun saveLocalSource(physicalPath: String) {
        if (physicalPath.isBlank()) return
        viewModelScope.launch {
            try {
                val displayName = physicalPath.substringAfterLast('/').ifEmpty { "本地文件夹" }
                val now = platformNowMs()
                sourceRepository.upsert(
                    Source(
                        id = Uuid.random().toString(),
                        name = displayName,
                        type = SourceType.LOCAL,
                        path = physicalPath,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            } catch (_: Exception) {
                // 建源失败静默（对齐 Web FilePicker 取消语义）
            }
        }
    }

    /** 删除音源 */
    fun deleteSource(source: Source, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            if (source.type == SourceType.ONLINE) {
                lxScriptStore.delete(source.id)
                lxScriptRepository.unregister(source.id)
                com.muses.player.core.lxsdk.LxSourcePriorityManager(lxScriptStore, lxScriptRepository, settingsRepository).repair()
            }
            // 先取待删歌曲 ids，用于清理播放快照/最近播放/播放队列
            val songIdsToRemove = try {
                songDao.getBySource(source.id).map { it.id }.toSet()
            } catch (_: Exception) {
                emptySet()
            }
            sourceRepository.deleteById(source.id)
            credentialsRepository.clearPassword(source.id)
            // 同步清理该音源入库歌曲（对齐 Web executeDeleteSource → reconcileSourceSongs(id, [])）
            songRepository.deleteSourceSongs(source.id)
            // 清理播放相关残留，避免底部栏仍显示已删歌曲
            if (songIdsToRemove.isNotEmpty()) {
                try {
                    playbackStateRepository.removeSongs(songIdsToRemove)
                } catch (_: Exception) { }
                try {
                    recentPlaysRepository.removeSongs(songIdsToRemove)
                } catch (_: Exception) { }
                try {
                    onRemoveFromQueue(songIdsToRemove)
                } catch (_: Exception) { }
            }
            // 源删除后同步播放认证注册表，移除残留凭据映射
            webDavAuthRegistry.refresh()
            onSuccess()
        }
    }
}
