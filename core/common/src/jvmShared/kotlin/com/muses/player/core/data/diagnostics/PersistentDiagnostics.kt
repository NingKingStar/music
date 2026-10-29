package com.muses.player.core.data.diagnostics

import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.*

data class ExportSummary(val files: Int, val bytes: Long, val dropped: Long, val truncated: Long, val writeFailures: Long)

/** 单写线程、有界队列；文件和导出串行化，调用业务线程不执行磁盘操作。 */
class PersistentDiagnostics(
    private val directory: File,
    private val maxFileBytes: Long = 2L * 1024 * 1024,
    private val maxFiles: Int = 5,
    private val retentionMs: Long = 7L * 24 * 60 * 60 * 1000,
    queueCapacity: Int = 1024,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val monotonicNanos: () -> Long = System::nanoTime,
) : AutoCloseable {
    private data class Item(val line: ByteArray? = null, val barrier: CountDownLatch? = null)
    private val queue = ArrayBlockingQueue<Item>(queueCapacity.coerceIn(1, 1024))
    private val lock = Any()
    private val exporting = java.util.concurrent.atomic.AtomicBoolean(false)
    private val seq = AtomicLong()
    private val dropped = AtomicLong()
    private val truncated = AtomicLong()
    private val failures = AtomicLong()
    private val rotated = AtomicLong()
    private val expired = AtomicLong()
    private val run = UUID.randomUUID().toString()
    private val salt = UUID.randomUUID().toString()
    @Volatile private var session = "未知"
    @Volatile private var song = "未知"
    private val trackSongs = LinkedHashMap<String, String>()
    @Volatile private var closed = false
    private val limit = maxFileBytes.coerceIn(512, 2L * 1024 * 1024)
    private val count = maxFiles.coerceIn(1, 5)
    private val worker = Thread({
        while (!closed || queue.isNotEmpty()) {
            val item = try { queue.poll(200, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null } ?: continue
            try { item.line?.let { synchronized(lock) { append(it) } } }
            catch (_: Throwable) { failures.incrementAndGet() }
            finally { item.barrier?.countDown() }
        }
    }, "本地诊断写入").apply { isDaemon = true; start() }

    fun anonymous(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest((salt + value).toByteArray()).take(12).joinToString("") { "%02x".format(it.toInt() and 255) }

    @Synchronized
    fun bindTrack(trackUri: String, songId: String) {
        trackSongs[anonymous(trackUri)] = anonymous(songId)
        while (trackSongs.size > 1024) trackSongs.remove(trackSongs.keys.first())
    }
    @Synchronized
    fun contextForTrack(trackUri: String): Map<String, String> {
        val identity = anonymous(trackUri)
        val target = trackSongs[identity] ?: identity
        val matched = trackSongs.containsKey(identity) && target == song
        return mapOf("song" to target, "session" to if (matched) session else "未知", "session_basis" to if (matched) "user_intent" else "unknown")
    }
    @Synchronized
    fun contextForTrack(trackUri: String, candidate: Map<String, String>): Map<String, String> {
        val identity = anonymous(trackUri)
        val boundSong = trackSongs[identity]
        val target = boundSong ?: identity
        val capturedSession = if (boundSong != null && boundSong == candidate["song"]) candidate["session"] ?: "未知" else "未知"
        return mapOf("song" to target, "session" to capturedSession, "session_basis" to if (capturedSession == "未知") "unknown" else "creation_candidate")
    }
    @Synchronized
    fun contextForSong(songId: String): Map<String, String> =
        mapOf("song" to anonymous(songId), "session" to "未知", "session_basis" to "unknown")
    @Synchronized
    fun context(): Map<String, String> = mapOf("session" to session, "song" to song, "session_basis" to if (session == "未知") "unknown" else "user_intent")

    @Synchronized
    fun transition(songId: String) { if (anonymous(songId) != song) beginPlayback(songId, "auto") }

    @Synchronized
    fun beginPlayback(songId: String, origin: String) {
        session = UUID.randomUUID().toString()
        song = anonymous(songId)
        record("playback_intent", mapOf("origin" to origin, "song" to song))
    }

    @Synchronized
    fun record(event: String, fields: Map<String, String> = emptyMap()) {
        try {
            if (closed) return
            val safe = buildJsonObject {
                put("run", run); put("session", session); put("song", song)
                put("session_basis", if (fields["session"] == "未知" || session == "未知") "unknown" else "user_intent")
                put("seq", seq.incrementAndGet()); put("wall_ms", wallClockMs()); put("mono_ns", monotonicNanos())
                put("event", if (event.matches(Regex("[a-z_]{1,48}"))) event else "unknown")
                fields.entries.take(32).forEach { (key, raw) ->
                    if (key !in allowedKeys && key !in timingKeys) { truncated.incrementAndGet(); return@forEach }
                    val value = when {
                        key == "session_basis" -> if (raw in setOf("user_intent", "creation_candidate", "unknown")) raw else { truncated.incrementAndGet(); "unknown" }
                        raw == "未知" || raw.matches(Regex("-?[0-9]{1,20}")) -> raw
                        key == "host" && raw.length <= 64 && raw.matches(Regex("(?i)([a-z0-9-]+\\.)+[a-z]{2,24}")) && !raw.endsWith(".local") && !raw.endsWith(".internal") && !raw.endsWith(".lan") -> raw.lowercase()
                        key == "commit" && raw.matches(Regex("[a-f0-9]{7,40}")) -> raw
                        key == "version_name" && raw.matches(Regex("[0-9]{1,4}(\\.[0-9]{1,4}){1,3}(-[a-z0-9]{1,16}){0,3}")) -> raw
                        key == "session" && raw.matches(Regex("[a-f0-9-]{36}")) -> raw
                        key in hashKeys && raw.matches(Regex("[a-f0-9]{24}")) -> raw
                        raw in enums -> raw
                        else -> { truncated.incrementAndGet(); "未知" }
                    }
                    put(key.take(48), value.take(64))
                }
                if (fields.size > 32) truncated.addAndGet((fields.size - 32).toLong())
            }
            val bytes = (safe.toString() + "\n").toByteArray(Charsets.UTF_8)
            if (bytes.size > limit || !queue.offer(Item(bytes))) dropped.incrementAndGet()
        } catch (_: Throwable) { failures.incrementAndGet() }
    }

    private fun files() = directory.listFiles()?.filter { it.name.matches(Regex("diagnostic-[0-4]\\.jsonl")) }.orEmpty()
    private fun prune() {
        val cutoff = wallClockMs() - retentionMs.coerceIn(0, 7L * 24 * 60 * 60 * 1000)
        files().filter { file ->
            file.lastModified() < cutoff || runCatching {
                file.bufferedReader().use { reader ->
                    val first = reader.readLine()
                    first != null && (Json.parseToJsonElement(first).jsonObject["wall_ms"]?.jsonPrimitive?.longOrNull ?: Long.MAX_VALUE) < cutoff
                }
            }.getOrDefault(false)
        }.forEach { if (it.delete()) expired.incrementAndGet() else failures.incrementAndGet() }
    }
    /** 中断写入的无换行尾部不视为已提交事件；每次最多读一个文件上限。 */
    private fun repairTail(file: File) {
        if (!file.exists() || file.length() == 0L) return
        java.io.RandomAccessFile(file, "rw").use { data ->
            val size = data.length()
            data.seek(size - 1)
            if (data.readByte().toInt() == 10) return
            val tailSize = minOf(size, limit).toInt()
            val tail = ByteArray(tailSize)
            data.seek(size - tailSize); data.readFully(tail)
            val lastNewline = tail.indexOfLast { it.toInt() == 10 }
            val committed = if (lastNewline >= 0) size - tailSize + lastNewline + 1 else 0L
            data.setLength(committed)
            truncated.incrementAndGet()
        }
    }
    private fun append(bytes: ByteArray) {
        check(directory.isDirectory || directory.mkdirs())
        prune()
        val active = File(directory, "diagnostic-0.jsonl")
        repairTail(active)
        if (active.length() + bytes.size > limit) {
            val last = File(directory, "diagnostic-${count - 1}.jsonl")
            if (last.exists()) { check(last.delete()); rotated.incrementAndGet() }
            for (i in count - 2 downTo 0) {
                val from = File(directory, "diagnostic-$i.jsonl")
                if (from.exists()) check(from.renameTo(File(directory, "diagnostic-${i + 1}.jsonl")))
            }
        }
        active.appendBytes(bytes)
    }
    fun flush(timeoutMs: Long = 5000): Boolean {
        if (closed) return queue.isEmpty()
        val latch = CountDownLatch(1)
        return try { queue.offer(Item(barrier = latch), timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS) && latch.await(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
    }
    private data class SnapshotEntry(val name: String, val bytes: ByteArray, val size: Int)
    /** 外部输出不持有存储锁；仅允许一份最多十 MiB 的内存快照，无临时文件。 */
    fun export(output: OutputStream): ExportSummary {
        if (!exporting.compareAndSet(false, true)) throw java.io.IOException("已有诊断导出进行中")
        try {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            val flushed = flush()
            val snapshot = synchronized(lock) {
                prune()
                val selected = files().sortedBy { it.name }.take(count)
                selected.forEach(::repairTail)
                val oldest = selected.minOfOrNull { it.lastModified() } ?: 0L
                val newest = selected.maxOfOrNull { it.lastModified() } ?: 0L
                val entries = selected.map { file ->
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                    val buffer = ByteArray(minOf(file.length(), limit).toInt())
                    var filled = 0
                    file.inputStream().use { input ->
                        while (filled < buffer.size) {
                            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                            val read = input.read(buffer, filled, buffer.size - filled)
                            if (read < 0) break
                            filled += read
                        }
                        if (input.read() >= 0) truncated.incrementAndGet()
                    }
                    SnapshotEntry(file.name, buffer, filled)
                }
                Triple(entries, oldest, newest)
            }
            val entries = snapshot.first
            val zip = ZipOutputStream(object : java.io.FilterOutputStream(output) {
                override fun close() { flush() }
            })
            zip.use {
            var total = 0L
            entries.forEach { entry ->
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                zip.putNextEntry(ZipEntry(entry.name))
                var position = 0
                while (position < entry.size) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                    val amount = minOf(8192, entry.size - position)
                    zip.write(entry.bytes, position, amount)
                    position += amount
                }
                total += entry.size
                zip.closeEntry()
            }
            val summary = ExportSummary(entries.size, total, dropped.get(), truncated.get(), failures.get())
            zip.putNextEntry(ZipEntry("摘要.txt"))
            zip.write(("本地脱敏诊断；不含旧错误全文；实际请求网络未知；READY不代表实际出声。\n会话关联依据session_basis：user_intent为用户意图；creation_candidate为数据源创建时的意图候选，仅保证旧对象不改借新意图，不证明实际媒体播放代次；unknown为无法确定。\n" +
                "files=${summary.files} bytes=${summary.bytes} dropped=${summary.dropped} truncated=${summary.truncated} writeFailures=${summary.writeFailures} flush=$flushed rotated_files=${rotated.get()} expired_files=${expired.get()}\n" +
                "计数仅对应本次运行；文件修改时间范围=${snapshot.second}..${snapshot.third}；精确事件起止以各事件wall_ms为准。\n").toByteArray())
            zip.closeEntry(); zip.finish(); zip.flush()
            return summary
            }
        } finally { exporting.set(false) }
    }
    override fun close() { closed = true; worker.join(5000) }
    companion object {
        private val timingKeys = setOf("timing_script", "timing_probe", "timing_total", "timing_platform", "timing_candidateSearch", "timing_findDownloaded", "timing_dataSource", "timing_attempts", "timing_cacheHit", "timing_failures", "timing_budgetExhausted")
        private val hashKeys = setOf("song", "previous", "next", "resource", "script", "request", "network")
        private val allowedKeys = hashKeys + setOf("session_basis", "actual_audio_output", "power_save", "device_idle", "interactive", "optimization_exempt", "visibility", "host", "commit", "version_name", "session", "origin", "reason", "state", "position_ms", "duration_ms", "expected_ms", "code", "http", "bytes", "length", "range_start", "range_length", "elapsed_ms", "local", "cache", "result", "platform", "quality", "requested_quality", "transport", "internet", "validated", "actual_network", "dns_ms", "connect_ms", "tls_ms", "response_ms", "attempt", "count", "version", "sdk", "audio_output", "focus")
        private val enums = setOf("user_intent", "creation_candidate", "foreground", "background", "true", "false", "unknown", "user", "external", "auto", "prepare", "buffering", "ready", "ended", "idle", "playing", "paused", "next", "previous", "seek", "stop", "queue", "normal", "resource_changed", "container", "rate_limited", "server_error", "failure", "success", "eof", "cancelled", "open", "read", "close", "resolve", "probe", "script_init", "download", "wifi", "cellular", "ethernet", "vpn", "other", "available", "lost", "changed", "kw", "kg", "tx", "wy", "mg", "128k", "192k", "320k", "flac", "flac24bit", "hires", "atmos", "atmos_plus", "master", "early_eof", "read_failure", "buffer_stall")
    }
}
