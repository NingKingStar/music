package com.muses.player.core.model.online

import com.muses.player.core.model.Song
import com.muses.player.core.model.SourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * 会话级「在线曲目」登记表。
 *
 * ## 为什么需要它
 * 在线曲目**不入库**（避免污染 `songs.path`/`tagsVersion`/扫描器语义），
 * 但播放链路是「队列只存 songId → 播放时查库拿 Song」：
 * ```
 * DesktopPlayerHook.play(songs) → JvmPlayerPort.enqueue(ids) → playSongId(id) → songLookup(id) → DB
 * ```
 * 在线曲目的 songId 在库里查不到，会导致「文件不存在」。
 *
 * 本表充当**播放期的临时缓存**：入队时登记，播放时作为查库的回退，
 * 使在线曲目无需落库即可被双端播放链路正常解析。
 *
 * ## 为什么用 [StateFlow] 而不是 Mutex + Map
 * 登记发生在播放链路（`setMediaItems` 之前），读取发生在播放页（`PlayerViewModel` 观察当前曲），
 * 二者是**不同线程的异步时序**：若用普通 Map + Mutex，播放页可能在登记完成前查一次拿到 null，
 * 且此后不会再收到通知 → 在线曲目封面/歌词永久缺失（实测竞态）。
 * [StateFlow.value] 的写入是原子的（`update` CAS），登记**即时可见**，
 * [observe] 还能把「迟到登记」推给订阅方，从根上消除这类时序问题。
 *
 * ## 生命周期
 * - 登记：开始播放在线曲目时（[remember]）；
 * - 读取：播放器的 songLookup 回退链（[find]）与播放页元数据订阅（[observe]）；
 * - 清理：切换到非在线内容或退出时可 [clear]；不做自动过期（条目极小，仅元数据）。
 */
object OnlineTrackSession {

    data class PlaybackDetails(
        val sourceName: String?,
        val requestedQuality: String?,
        val resolvedQuality: String?,
        val local: Boolean,
        val originalPlatform: String? = null,
        val resolvedPlatform: String? = null,
    )

    private val originalSongs = MutableStateFlow<Map<String, Song>>(emptyMap())
    fun originalSong(id: String): Song? = originalSongs.value[id]
    fun restoreOriginal(id: String) {
        val song = originalSongs.value[id] ?: return
        val ref = OnlineTrackRef.parse(song.path) ?: return
        resolvedReferences.update { it - ref.encode() }
        playbackDetails.update { it - ref.encode() }
        songs.update { it + (id to song) }
    }

    private val songs = MutableStateFlow<Map<String, Song>>(emptyMap())
    private val resolvedReferences = MutableStateFlow<Map<String, OnlineTrackRef>>(emptyMap())
    private val playbackDetails = MutableStateFlow<Map<String, PlaybackDetails>>(emptyMap())
    val details: StateFlow<Map<String, PlaybackDetails>> = playbackDetails.asStateFlow()

    fun rememberPlayback(ref: OnlineTrackRef, result: OnlinePlayableUrl, local: Boolean) {
        val detail = PlaybackDetails(result.sourceName, result.requestedQuality, result.quality, local, ref.platform, resolvedReference(ref).platform)
        playbackDetails.update { it + (ref.encode() to detail) + (resolvedReference(ref).encode() to detail) }
    }

    /** 保留原队列 ID，但元数据请求必须跟随真正解析成功的平台版本。 */
    fun rememberResolution(original: OnlineTrackRef, resolved: OnlineTrackRef) {
        resolvedReferences.update { current ->
            current.mapValues { (_, value) -> if (value == original) resolved else value } +
                (original.encode() to resolved)
        }
        if (original.platform == resolved.platform && original.musicInfoJson == resolved.musicInfoJson) return
        songs.update { current -> current.mapValues { (_, song) ->
            if (OnlineTrackRef.parse(song.path) == original) song.copy(path = resolved.encode(), lyrics = null)
            else song
        } }
    }

    fun resolvedReference(ref: OnlineTrackRef): OnlineTrackRef = resolvedReferences.value[ref.encode()] ?: ref

    /** 全量快照流（供需要自行组合的调用方） */
    val snapshot: StateFlow<Map<String, Song>> = songs.asStateFlow()

    /**
     * 登记一批在线曲目（仅登记 [SourceType.ONLINE] 的条目，其余忽略）。
     *
     * 非 suspend：播放链路可在 `setMediaItems` **之前**同步登记，
     * 保证当前曲一被观察到就已可见（避免「先观察后登记」的漏读）。
     */
    fun remember(candidates: List<Song>) {
        val online = candidates.filter { it.sourceType == SourceType.ONLINE }
        if (online.isEmpty()) return
        // 新一轮入队不能在解析成功前沿用上次播放的跨平台引用。
        val incomingRefs = online.mapNotNull { OnlineTrackRef.parse(it.path)?.encode() }.toSet()
        playbackDetails.update { current -> current - incomingRefs }
        resolvedReferences.update { current -> current - incomingRefs }
        originalSongs.update { it + online.associateBy { song -> song.id } }
        songs.update { current -> current + online.associateBy { it.id } }
    }

    /** 按 id 取会话内的在线曲目；未登记返回 null */
    suspend fun find(songId: String): Song? = songs.value[songId]

    /** 批量取（供批量解析场景） */
    suspend fun findAll(ids: List<String>): Map<String, Song> {
        val current = songs.value
        return ids.mapNotNull { id -> current[id]?.let { id to it } }.toMap()
    }

    /**
     * 观察某 id 的在线曲目。
     *
     * 语义：当前已登记 → 立即发值；未登记 → 发 null，登记后自动补发。
     * 播放页用它把「Room 查不到（在线曲目）」与「会话表」组合成同一份当前曲元数据。
     */
    fun observe(songId: String): Flow<Song?> =
        songs.map { it[songId] }.distinctUntilChanged()

    /** 当前登记数量（调试/测试用） */
    suspend fun size(): Int = songs.value.size

    /** 清空（切换音源/退出在线搜索时调用，避免陈旧条目长期占用） */
    suspend fun clear() {
        originalSongs.value = emptyMap()
        songs.value = emptyMap()
        resolvedReferences.value = emptyMap()
        playbackDetails.value = emptyMap()
    }
}

/**
 * 播放来源摘要（沉浸页展示用）。
 *
 * 只展示可核实的信息：
 * - 已下载文件只说明是本地文件，不猜测音质（流式播放不重新检测文件码率）；
 * - 在线音源把脚本声明的档位标成「脚本声明」，不冒充实测音质；
 * - 发生降档时保留请求档位，用户能看出是否回退过。
 */
fun playbackDetailSummary(detail: OnlineTrackSession.PlaybackDetails): String {
    if (detail.local) return "本地下载 · 音质未核实"
    val platform = detail.resolvedPlatform?.let(::onlinePlatformLabel)
    val changed = detail.originalPlatform != null && detail.resolvedPlatform != null && detail.originalPlatform != detail.resolvedPlatform
    val suffix = if (changed) " · 已从${onlinePlatformLabel(detail.originalPlatform!!)}切换同版" else ""
    val source = (platform?.let { "$it · " }.orEmpty() + (detail.sourceName?.takeIf { it.isNotBlank() } ?: "音源未核实")) + suffix
    val actual = detail.resolvedQuality?.takeIf { it.isNotBlank() }
    val requested = detail.requestedQuality?.takeIf { it.isNotBlank() }
    return when {
        actual == null -> "$source · 音质未核实"
        requested != null && requested != actual -> "$source · $actual（脚本声明，请求 $requested 已回退）"
        else -> "$source · $actual（脚本声明）"
    }
}

fun onlinePlatformLabel(platform: String): String = when (platform) {
    "wy" -> "网易云音乐"; "tx" -> "QQ音乐"; "kw" -> "酷我音乐"; "kg" -> "酷狗音乐"; "mg" -> "咪咕音乐"
    else -> platform
}
