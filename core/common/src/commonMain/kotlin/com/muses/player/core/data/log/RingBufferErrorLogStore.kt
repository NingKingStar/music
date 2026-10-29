package com.muses.player.core.data.log

import com.muses.player.core.data.store.platformNowMs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 环形缓冲实现（任务 08-26-settings-log-viewer）。
 *
 * - [ArrayDeque] 容量上限 [CAPACITY]（500），超限丢弃最早条目，纯内存不落盘；
 * - 所有读写经 `synchronized(this)` 保证线程安全（埋点来自播放/扫描等多线程）；
 * - [serialize]/[restore] 为崩溃持久化的紧凑行格式，仅供 [CrashHandler] 使用。
 *
 * W1 KMP 上收：时间源经 platformNowMs（commonMain 无 System.currentTimeMillis），
 * 时间格式化经 expect formatLogTime（双端 actual 均为 SimpleDateFormat，输出不变）。
 */
class RingBufferErrorLogStore constructor() : ErrorLogStore, ErrorLogCrashPersistence {

    /** 单条日志记录；message 可含多行（throwable 堆栈附后） */
    internal data class Entry(
        val timestamp: Long,
        val level: ErrorLogStore.Level,
        val tag: String,
        val message: String,
        /** 来自上次会话的崩溃读回，dump 时归入「上次会话崩溃」段 */
        val fromPreviousSession: Boolean = false,
    )

    private val lock = Any()
    private val buffer = ArrayDeque<Entry>()

    private val latestSummaryInternal = MutableStateFlow<String?>(null)
    override val latestSummary: StateFlow<String?> = latestSummaryInternal.asStateFlow()

    override fun log(level: ErrorLogStore.Level, tag: String, message: String, throwable: Throwable?) {
        com.muses.player.core.data.diagnostics.Diagnostics.record("legacy_error", mapOf("session" to "未知", "song" to "未知",
            "code" to (throwable?.let { it::class.simpleName?.hashCode() } ?: 0).toString(),
        ))
        val fullMessage = if (throwable != null) {
            message + "\n" + throwable.stackTraceToString()
        } else {
            message
        }
        synchronized(lock) {
            buffer.addLast(Entry(platformNowMs(), level, tag, fullMessage))
            while (buffer.size > CAPACITY) {
                buffer.removeFirst()
            }
            latestSummaryInternal.value = formatSummary(buffer.last())
        }
    }

    override suspend fun dump(): String? {
        val snapshot: List<Entry> = synchronized(lock) { buffer.toList() }
        if (snapshot.isEmpty()) return null

        val current = snapshot.filterNot { it.fromPreviousSession }
        val previous = snapshot.filter { it.fromPreviousSession }
        return buildString {
            current.forEach { append(formatEntry(it)).append('\n') }
            if (previous.isNotEmpty()) {
                append("== 上次会话崩溃 ==\n")
                previous.forEach { append(formatEntry(it)).append('\n') }
            }
        }.trimEnd('\n')
    }

    // ── 崩溃持久化（CrashHandler 专用） ─────────────────────────

    /**
     * 序列化全部缓冲为紧凑行格式（崩溃现场写盘用）：
     * `L\t<ts>\t<LEVEL>\t<tag>\t<转义后的多行消息>`。
     * 消息内换行转义为字面 `\n`、制表符转义为 `\t`，保证一行一条可解析。
     */
    override fun serialize(): String {
        val snapshot: List<Entry> = synchronized(lock) { buffer.toList() }
        return snapshot.joinToString("\n") { entry ->
            "L\t${entry.timestamp}\t${entry.level.name}\t${escape(entry.tag)}\t${escape(entry.message)}"
        }
    }

    /**
     * 将上次会话持久化内容恢复进缓冲头部（启动读回路径）：
     * [serialized] 为空/无法解析时静默忽略；恢复条目标记 fromPreviousSession=true，
     * 并在缓冲为空时把最新摘要指向最后一条恢复条目（即崩溃现场）。
     */
    override fun restore(serialized: String) {
        val entries = serialized.lineSequence()
            .mapNotNull(::parseLine)
            .toList()
        if (entries.isEmpty()) return
        synchronized(lock) {
            // 头部插入保持原时间序；同样受容量上限约束（极端情况下挤掉最旧的本会话日志）
            entries.reversed().forEach { entry ->
                buffer.addFirst(entry.copy(fromPreviousSession = true))
            }
            while (buffer.size > CAPACITY) {
                buffer.removeFirst()
            }
            if (latestSummaryInternal.value == null) {
                latestSummaryInternal.value = formatSummary(buffer.last())
            }
        }
    }

    // ── 格式化 ────────────────────────────────────────────────

    /** 摘要：「MM-dd HH:mm 首行消息」（设置页副标题单行展示） */
    private fun formatSummary(entry: Entry): String {
        val firstLine = entry.message.lineSequence().firstOrNull().orEmpty()
        return "${formatLogTime(entry.timestamp, TIME_FORMAT_SUMMARY)} $firstLine"
    }

    /** 单条格式化：`--- WARN  yyyy-MM-dd HH:mm:ss [tag] 首行` + 续行缩进 4 空格 */
    private fun formatEntry(entry: Entry): String {
        val lines = entry.message.split('\n')
        val head = "--- ${entry.level.name.padEnd(5)} " +
            formatLogTime(entry.timestamp, TIME_FORMAT_DUMP) +
            " [${entry.tag}] ${lines.first()}"
        val rest = lines.drop(1).joinToString("\n") { "    $it" }
        return if (rest.isEmpty()) head else "$head\n$rest"
    }

    private fun parseLine(line: String): Entry? {
        if (!line.startsWith("L\t")) return null
        val parts = line.split('\t', limit = 5)
        if (parts.size < 5) return null
        val timestamp = parts[1].toLongOrNull() ?: return null
        val level = runCatching { ErrorLogStore.Level.valueOf(parts[2]) }.getOrNull() ?: return null
        return Entry(timestamp, level, unescape(parts[3]), unescape(parts[4]))
    }

    private fun escape(raw: String): String =
        raw.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unescape(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (raw[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    '\\' -> sb.append('\\')
                    else -> sb.append(c).append(raw[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i += 1
            }
        }
        return sb.toString()
    }

    companion object {
        /** 缓冲容量上限（PRD AC5：超限丢最早，不 OOM） */
        const val CAPACITY = 500

        private const val TIME_FORMAT_SUMMARY = "MM-dd HH:mm"
        private const val TIME_FORMAT_DUMP = "yyyy-MM-dd HH:mm:ss"
    }
}
