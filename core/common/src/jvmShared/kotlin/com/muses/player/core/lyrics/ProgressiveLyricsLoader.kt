package com.muses.player.core.lyrics

import com.muses.player.core.lyrics.amll.AmllTtmlDbClient
import com.muses.player.core.lyrics.model.LyricsDocument
import com.muses.player.core.lyrics.model.LyricTimingKind
import com.muses.player.core.lyrics.parser.LxLyricParser
import com.muses.player.core.model.lyrics.*
import com.muses.player.core.model.online.OnlineTrackIdentity
import com.muses.player.core.model.online.OnlineTrackMetadataResolver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

data class PlaybackLyricsUpdate(val document: LyricsDocument, val source: String,
    val fromCache: Boolean = false, val elapsedMs: Long = 0)

/** 只服务播放；不改变刮削的来源排序，不涉及音频缓存或曲库迁移。 */
class ProgressiveLyricsLoader(
    private val amll: AmllTtmlDbClient,
    private val providers: List<LyricsProvider>,
    private val cacheDirectory: (() -> File)? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val totalTimeoutMs: Long = 10_000,
    private val providerTimeoutMs: Long = 3_000,
    private val hedgeDelayMs: Long = 900,
    private val localLyrics: (suspend (OnlineLyricsQuery) -> String?)? = null,
) {
    @Serializable private data class Entry(
        val key: String, val text: String, val source: String, val format: String = "lrc",
        val translation: String? = null, val romanization: String? = null, val lxlyric: String? = null,
        val savedAt: Long, val checkedAt: Long = 0,
    )
    private data class State(val update: PlaybackLyricsUpdate? = null, val done: Boolean = false)
    private class Flight {
        val state = MutableStateFlow(State())
        lateinit var job: Job
        var readers = 0
    }
    private val lock = Mutex()
    private val flights = mutableMapOf<String, Flight>()
    private val keysBySong = LinkedHashMap<String, String>()
    private val memory = LinkedHashMap<String, Entry>()
    private val misses = LinkedHashMap<String, Long>()
    private val json = Json { ignoreUnknownKeys = true }
    private val networkPermits = Semaphore(2)

    private fun key(query: OnlineLyricsQuery): String = json.encodeToString(listOf(
        query.trackRef?.let { OnlineTrackIdentity.matchKeys(it).firstOrNull() } ?: query.songId,
        query.title.trim(), query.artist.orEmpty().trim(), query.album.orEmpty().trim(),
        query.durationSec?.toLong()?.toString().orEmpty(),
    ))

    fun observe(query: OnlineLyricsQuery, metadata: OnlineTrackMetadataResolver? = null,
        embeddedText: String? = null,
    ): Flow<PlaybackLyricsUpdate> = flow {
        if (query.title.isBlank() || query.songId.isBlank()) return@flow
        val key = key(query)
        val flight = lock.withLock {
            keysBySong[query.songId] = key
            while (keysBySong.size > 128) keysBySong.remove(keysBySong.keys.first())
            flights.getOrPut(key) {
                Flight().also { f -> f.job = scope.launch(start = CoroutineStart.LAZY) { load(query, key, metadata, f, embeddedText) } }
            }.also { it.readers++ }
        }
        flight.job.start()
        var previous: PlaybackLyricsUpdate? = null
        try {
            flight.state.first { state ->
                state.update?.let { if (it != previous) { emit(it); previous = it } }
                state.done
            }
        } finally {
            lock.withLock {
                flight.readers--
                if (flight.readers == 0) {
                    if (flights[key] === flight) flights.remove(key)
                    flight.job.cancel()
                }
            }
        }
    }

    suspend fun invalidate(songId: String) {
        val key = lock.withLock {
            keysBySong[songId]?.also { key -> flights.remove(key)?.job?.cancel() }
        } ?: return
        synchronized(memory) { memory.remove(key); misses.remove(key) }
        withContext(Dispatchers.IO) { runCatching { file(key)?.delete() } }
    }

    private fun file(key: String): File? {
        val name = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return cacheDirectory?.invoke()?.let { File(it, "$name.json") }
    }
    private suspend fun cached(key: String): Entry? = withContext(Dispatchers.IO) {
        val value = synchronized(memory) { memory.remove(key)?.also { memory[key] = it } }
            ?: runCatching { file(key)?.takeIf { it.isFile && it.length() <= 2 * 1024 * 1024 }
                ?.readText(Charsets.UTF_8)?.let { json.decodeFromString<Entry>(it) } }.getOrNull()
        value?.takeIf { it.key == key && nowMs() - it.savedAt in 0..30L * 86_400_000 }
    }
    private suspend fun remember(entry: Entry) {
        synchronized(memory) {
            memory.remove(entry.key); memory[entry.key] = entry; misses.remove(entry.key)
            while (memory.size > 64) memory.remove(memory.keys.first())
        }
        withContext(Dispatchers.IO) {
            runCatching {
                val target = file(entry.key) ?: return@runCatching
                val text = json.encodeToString(entry)
                if (text.toByteArray().size > 2 * 1024 * 1024) return@runCatching
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, target.name + ".tmp")
                temp.writeText(text, Charsets.UTF_8)
                java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                val files = target.parentFile?.listFiles { f -> f.name.matches(Regex("[0-9a-f]{64}\\.json")) }
                    ?.sortedBy { it.lastModified() }.orEmpty()
                var size = files.sumOf { it.length() }; var count = files.size
                for (old in files) {
                    if (count <= 128 && size <= 16 * 1024 * 1024) break
                    val bytes = old.length()
                    if (old.delete()) { count--; size -= bytes }
                }
            }
        }
    }
    private suspend fun parse(entry: Entry): LyricsDocument? = if (entry.source == "script") {
        withContext(Dispatchers.Default) { LxLyricParser.parse(entry.text, entry.translation, entry.romanization, entry.lxlyric) }
    } else if (entry.source == "local") {
        withContext(Dispatchers.Default) { com.muses.player.feature.player.lyric.LyricsParser.parseDocument(entry.text) }
    } else parseLyricsMatch(OnlineLyricsMatchResult.Ok(entry.text,
        OnlineLyricsFormat.entries.first { it.wire == entry.format },
        OnlineLyricsSource.entries.first { it.wire == entry.source }, entry.translation))

    private fun score(document: LyricsDocument): Int =
        (if (document.lines.any { it.timingKind == LyricTimingKind.Precise && it.syllables.isNotEmpty() }) 1_000 else 0) +
            (if (document.lines.any { !it.translation.isNullOrBlank() }) 100 else 0) + document.quality.ordinal * 5

    private suspend fun load(query: OnlineLyricsQuery, key: String, metadata: OnlineTrackMetadataResolver?, flight: Flight,
        embeddedText: String?,
    ) {
        val start = nowMs()
        var best: Entry? = null
        var bestScore = -1
        val offerLock = Mutex()
        suspend fun offer(entry: Entry, fromCache: Boolean = false) {
            val document = try { parse(entry)?.takeIf { it.lines.isNotEmpty() } }
            catch (e: CancellationException) { throw e } catch (_: Exception) { null } ?: return
            offerLock.withLock {
                val rank = score(document) + when (entry.source) {
                    "local" -> 3
                    query.trackRef?.platform -> 4
                    "script" -> 0
                    else -> 2
                }
                if (rank <= bestScore) return@withLock
                bestScore = rank; best = entry
                flight.state.value = State(PlaybackLyricsUpdate(document, entry.source, fromCache, nowMs() - start))
                if (!fromCache) remember(entry)
            }
        }
        try {
            embeddedText?.takeIf { it.isNotBlank() }?.let {
                offer(Entry(key, it, "local", savedAt = nowMs()))
            }
            cached(key)?.let { entry ->
                offer(entry, fromCache = true)
                if (best != null && nowMs() - entry.checkedAt in 0..6L * 3_600_000) return
            }
            try {
                withTimeoutOrNull(400) { localLyrics?.invoke(query) }?.takeIf { it.isNotBlank() }?.let {
                    offer(Entry(key, it, "local", savedAt = nowMs()))
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            if (best == null && synchronized(memory) { (misses[key] ?: 0L) > nowMs() }) return
            withTimeoutOrNull(totalTimeoutMs) {
                coroutineScope {
                    val permits = networkPermits
                    suspend fun attempt(provider: LyricsProvider) {
                        try {
                            val hit = withTimeoutOrNull(providerTimeoutMs) { provider.searchLyrics(query) } ?: return
                            if (hit.text.isBlank()) return
                            offer(Entry(key, hit.text, provider.id.wire, hit.format.wire, hit.translationText, savedAt = nowMs()))
                        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                    }
                    val native = providers.firstOrNull { it.id.wire == query.trackRef?.platform }
                    val fallback = providers.filter { it !== native }
                    if (native != null) launch { permits.withPermit { attempt(native) } }
                    fallback.forEachIndexed { index, provider -> launch {
                        delay(if (native == null && index == 0) 0 else if (index == 0) hedgeDelayMs else hedgeDelayMs * 2)
                        if (best != null) return@launch
                        permits.withPermit { if (best == null) attempt(provider) }
                    } }
                    launch {
                        delay(hedgeDelayMs + 100)
                        permits.withPermit {
                            try {
                                val result = withTimeoutOrNull(6_000) { amll.match(AmllMatchQuery(
                                    query.songId, query.title, query.artist, query.album, query.durationSec)) }
                                if (result is AmllMatchResult.Ok && result.confidence != com.muses.player.core.model.scrape.MatchConfidence.LOW) {
                                    offer(Entry(key, result.ttml, "amll", "ttml", savedAt = nowMs()))
                                }
                            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                        }
                    }
                    if (metadata != null && query.trackRef != null) launch {
                        for (wait in listOf(0L, 1_500L, 3_000L)) {
                            delay(wait)
                            if (bestScore >= 1_100) break
                            try {
                                val hit = withTimeoutOrNull(2_000) { metadata.resolveLyrics(query.trackRef) } ?: continue
                                offer(Entry(key, hit.lyric.orEmpty(), "script", translation = hit.tlyric,
                                    romanization = hit.rlyric, lxlyric = hit.lxlyric, savedAt = nowMs()))
                            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                        }
                    }
                }
            }
            best?.let { remember(it.copy(checkedAt = nowMs())) } ?: synchronized(memory) {
                misses[key] = nowMs() + 30_000
                while (misses.size > 64) misses.remove(misses.keys.first())
            }
        } finally { flight.state.value = flight.state.value.copy(done = true) }
    }
}
