package com.muses.player.desktop.playback

import com.muses.player.core.model.playback.QueueItem
import com.muses.player.core.model.playback.QueueSnapshotData
import com.muses.player.core.model.playback.RepeatMode

/**
 * S2 本地队列状态机（纯逻辑，对齐 `QueueSnapshotData` 三序语义）。
 *
 * - `items` = 当前生效序列（shuffle 关 = 原始序，开 = 洗牌序）；
 * - `originalOrder` = 未洗牌原始序（`enqueue` 写入后不再变，仅删歌过滤）；
 * - `shuffleOrder` = 洗牌序（未启用为 null；启用时为原始序的确定性乱序）；
 * - `currentIndex` = 在 `items` 中的下标；`currentSongId` 冗余存当前曲便于快照。
 *
 * 安卓侧 shuffle/repeat 由 Media3 内部承担（恢复时按开关重洗）；
 * 桌面侧无 Media3，本机复刻等价语义：洗牌用种子随机保证可测，恢复链沿 active order 回绕。
 */
class DesktopQueueStateMachine {

    data class State(
        val snapshot: QueueSnapshotData = QueueSnapshotData(),
        val currentIndex: Int = -1,
        val currentSongId: String? = null,
    )

    private var state = State()

    /** 供单测注入确定性随机（默认 Random；测试传 Random(seed)）。 */
    var shuffleRandom: kotlin.random.Random = kotlin.random.Random.Default

    fun state(): State = state

    fun activeOrder(): List<QueueItem> = state.snapshot.items

    /** 入队：重置三序与恢复链由调用方（JvmPlayerPort）处理。 */
    fun enqueue(ids: List<String>, index: Int, shuffleEnabled: Boolean) {
        val original = ids.map { QueueItem(it) }
        val (items, shuffleOrder) = if (shuffleEnabled && original.isNotEmpty()) {
            val shuffled = original.shuffled(shuffleRandom)
            shuffled to shuffled
        } else {
            original to null
        }
        // 调用方的 index 指向原始列表；洗牌后仍须定位到用户点击的歌曲。
        val selectedId = original.getOrNull(index.coerceIn(0, original.lastIndex.coerceAtLeast(0)))?.songId
        val safeIndex = if (items.isEmpty()) -1 else items.indexOfFirst { it.songId == selectedId }
        state = State(
            snapshot = QueueSnapshotData(
                items = items,
                originalOrder = original,
                shuffleOrder = shuffleOrder,
            ),
            currentIndex = safeIndex,
            currentSongId = items.getOrNull(safeIndex)?.songId,
        )
    }

    /** 开关洗牌：开=生成洗牌序并尽量保持当前曲；关=回到原始序并尽量保持当前曲。 */
    /**
     * 追加到队列末尾：保留当前曲与既有顺序，不做重排（曲库「添加到播放队列」）。
     * 调用方负责过滤已在队列内的 id；洗牌开启时同时追加进洗牌序，避免关洗牌后丢条目。
     */
    fun append(ids: List<String>) {
        if (ids.isEmpty()) return
        val cur = state
        val additions = ids.map { QueueItem(it) }
        val original = cur.snapshot.originalOrder + additions
        val (items, shuffleOrder) = cur.snapshot.shuffleOrder?.let { shuffled ->
            (shuffled + additions) to (shuffled + additions)
        } ?: (original to null)
        // 队列原本为空时从追加的第一首开始，否则维持当前曲位置不变
        val index = if (cur.snapshot.items.isEmpty()) 0
        else items.indexOfFirst { it.songId == cur.currentSongId }.let { if (it >= 0) it else 0 }
        state = State(
            snapshot = QueueSnapshotData(items = items, originalOrder = original, shuffleOrder = shuffleOrder),
            currentIndex = index,
            currentSongId = items.getOrNull(index)?.songId,
        )
    }

    fun setShuffleEnabled(enabled: Boolean) {
        val cur = state
        if (enabled) {
            if (cur.snapshot.shuffleOrder != null) return
            val original = cur.snapshot.originalOrder
            if (original.isEmpty()) return
            val shuffled = original.shuffled(shuffleRandom)
            val id = cur.currentSongId
            state = cur.copy(
                snapshot = cur.snapshot.copy(items = shuffled, shuffleOrder = shuffled),
                currentIndex = shuffled.indexOfFirst { it.songId == id }.let { if (it >= 0) it else 0 },
            )
        } else {
            if (cur.snapshot.shuffleOrder == null) return
            val original = cur.snapshot.originalOrder
            val id = cur.currentSongId
            state = cur.copy(
                snapshot = cur.snapshot.copy(items = original, shuffleOrder = null),
                currentIndex = original.indexOfFirst { it.songId == id }.let { if (it >= 0) it else 0 },
            )
        }
    }

    /** repeat 模式由 JvmPlayerPort 持有（ONE 仅影响 finished 行为，不改变队列序列）。 */
    fun moveTo(index: Int): QueueItem? {
        val items = state.snapshot.items
        if (index !in items.indices) return null
        val item = items[index]
        state = state.copy(currentIndex = index, currentSongId = item.songId)
        return item
    }

    /** 沿 active order 步进（next=true 下一首；repeat ONE 由调用方先行处理，不在此回绕）。 */
    fun step(next: Boolean): QueueItem? {
        val items = state.snapshot.items
        if (items.isEmpty()) return null
        val cur = state.currentIndex
        val target = if (next) {
            if (cur + 1 < items.size) cur + 1 else 0
        } else {
            if (cur - 1 >= 0) cur - 1 else items.size - 1
        }
        return moveTo(target)
    }

    /** 删源/删歌后过滤三序；当前曲被删则 currentIndex=-1 由调用方决定下一步。 */
    fun removeSongs(songIds: Set<String>) {
        if (songIds.isEmpty()) return
        val cur = state
        val filter: (List<QueueItem>) -> List<QueueItem> = { list -> list.filter { it.songId !in songIds } }
        val newItems = filter(cur.snapshot.items)
        val newOriginal = filter(cur.snapshot.originalOrder)
        val newShuffle = cur.snapshot.shuffleOrder?.let { filter(it) }
        val currentDeleted = cur.currentSongId != null && cur.currentSongId in songIds
        val newIndex = if (currentDeleted || newItems.isEmpty()) {
            -1
        } else {
            newItems.indexOfFirst { it.songId == cur.currentSongId }.let { if (it >= 0) it else 0 }
        }
        state = State(
            snapshot = cur.snapshot.copy(
                items = newItems,
                originalOrder = newOriginal,
                shuffleOrder = newShuffle?.takeIf { newItems.isNotEmpty() },
            ),
            currentIndex = newIndex,
            currentSongId = if (currentDeleted) null else cur.currentSongId,
        )
    }

    /** 冷启动恢复：按快照重建（已被曲库删除的歌曲由调用方先过滤）。 */
    fun restore(
        items: List<QueueItem>,
        originalOrder: List<QueueItem>,
        shuffleOrder: List<QueueItem>?,
        currentIndex: Int,
        currentSongId: String?,
    ) {
        val safeIndex = if (items.isEmpty()) -1 else currentIndex.coerceIn(0, items.size - 1)
        state = State(
            snapshot = QueueSnapshotData(
                items = items,
                originalOrder = originalOrder.ifEmpty { items },
                shuffleOrder = shuffleOrder,
            ),
            currentIndex = safeIndex,
            currentSongId = currentSongId ?: items.getOrNull(safeIndex)?.songId,
        )
    }

    /**
     * 失败恢复候选（对齐 `PlaybackRecoveryController.selectNextCandidate`）：
     * 沿 active order 向后找未尝试过的下一首，最多回绕一圈；无候选返回 null。
     */
    fun selectNextCandidate(activeOrder: List<String>, errorIndex: Int, attempted: Set<String>): Int? {
        if (activeOrder.isEmpty()) return null
        val startIndex = if (errorIndex in activeOrder.indices) errorIndex else -1
        for (offset in 1..activeOrder.size) {
            val candidateIndex = (startIndex + offset).mod(activeOrder.size)
            if (activeOrder[candidateIndex] !in attempted) return candidateIndex
        }
        return null
    }
}

/** RepeatMode int ↔ 模型互转（int 口径对齐 Media3：OFF=0/ONE=1/ALL=2）。 */
fun repeatModeFromInt(mode: Int): RepeatMode = when (mode) {
    0 -> RepeatMode.NONE
    1 -> RepeatMode.ONE
    else -> RepeatMode.ALL
}

fun repeatModeToInt(mode: RepeatMode): Int = when (mode) {
    RepeatMode.NONE -> 0
    RepeatMode.ONE -> 1
    RepeatMode.ALL -> 2
}
