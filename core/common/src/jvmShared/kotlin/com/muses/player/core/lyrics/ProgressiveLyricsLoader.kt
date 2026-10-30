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
    val fromCache: Boolean = false, val elapsedMs: Long = 0, val translationSource: String? = null, val romanizationSource: String? = null)

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
        val savedAt: Long, val checkedAt: Long = 0, val revision: Int = 0,
        val translationSource: String? = null, val annotations: List<SavedTranslation> = emptyList(),
        val identityVerified: Boolean = false,
        val romanizationBaseText: String? = null, val romanizationFallbackText: String? = null,
        val romanizationSource: String? = null, val romanizationCheckedAt: Long = 0,
        val romanizationRetryAt: Long = 0, val originalFingerprint: String? = null,
    )
    @Serializable private data class SavedTranslation(val index: Int, val timeMs: Long, val text: String, val translation: String? = null, val romanization: String? = null,
        val romanizationSyllables: List<SavedSyllable> = emptyList())
    @Serializable private data class SavedSyllable(val start: Long, val end: Long, val text: String)
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
    private val parsedMemory = LinkedHashMap<String, Pair<Entry, LyricsDocument>>()
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
        synchronized(parsedMemory) { parsedMemory.remove(key) }
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
    private suspend fun parseRaw(entry: Entry): LyricsDocument? = if (entry.source == "script") {
        withContext(Dispatchers.Default) { LxLyricParser.parse(entry.text, entry.translation, entry.romanization, entry.lxlyric) }
    } else if (entry.source == "local") {
        withContext(Dispatchers.Default) { com.muses.player.feature.player.lyric.LyricsParser.parseDocument(entry.text) }
    } else parseLyricsMatch(OnlineLyricsMatchResult.Ok(entry.text,
        OnlineLyricsFormat.entries.first { it.wire == entry.format },
        OnlineLyricsSource.entries.first { it.wire == entry.source }, entry.translation, romanizationText = entry.romanization,
        romanizationBaseText = entry.romanizationBaseText, romanizationFallbackText = entry.romanizationFallbackText))

    private fun fingerprint(document: LyricsDocument): String = MessageDigest.getInstance("SHA-256")
        .digest(json.encodeToString(document.lines.map { listOf(it.timeMs.toString(), it.text) }).toByteArray())
        .joinToString("") { "%02x".format(it) }

    private suspend fun parse(entry: Entry): LyricsDocument? {
        synchronized(parsedMemory) { parsedMemory[entry.key]?.takeIf { it.first == entry }?.let { return it.second } }
        val raw = parseRaw(entry) ?: return null
        val document = LyricRomanizationPolicy.removeDuplicateReadings(LyricTranslationPolicy.restrictTranslations(raw, entry.source))
        val annotations = entry.annotations.associateBy { it.index }
        val matching = entry.revision >= 4 && entry.originalFingerprint == fingerprint(document)
        val result = document.copy(lines = document.lines.mapIndexed { index, line ->
            val saved = annotations[index]
            if (saved != null && saved.timeMs == line.timeMs && saved.text == line.text) {
                val translated = if (entry.revision >= 3 && entry.translationSource in listOf("wy", "local"))
                    line.copy(translation = saved.translation) else line
                if (matching && LyricRomanizationPolicy.priority(entry.romanizationSource) > 0 &&
                    LyricRomanizationPolicy.valid(line.text, saved.romanization)) translated.copy(
                        romanization = saved.romanization,
                        romanizationSyllables = saved.romanizationSyllables.map {
                            com.muses.player.core.lyrics.model.LyricSyllable(text = it.text, startTimeMs = it.start, endTimeMs = it.end)
                        }) else translated
            } else line
        })
        synchronized(parsedMemory) {
            parsedMemory.remove(entry.key); parsedMemory[entry.key] = entry to result
            while (parsedMemory.size > 64) parsedMemory.remove(parsedMemory.keys.first())
        }
        return result
    }

    private fun score(document: LyricsDocument): Int =
        (if (document.lines.any { it.timingKind == LyricTimingKind.Precise && it.syllables.isNotEmpty() }) 1_000 else 0) +
            (LyricTranslationPolicy.coverage(document) * 100).toInt() + document.quality.ordinal * 5

    private suspend fun load(query: OnlineLyricsQuery, key: String, metadata: OnlineTrackMetadataResolver?, flight: Flight,
        embeddedText: String?,
    ) {
        val start = nowMs()
        var best: Entry? = null
        var bestDocument: LyricsDocument? = null
        val offerLock = Mutex()
        fun needsMoreTranslation(): Boolean = flight.state.value.update?.let {
            LyricTranslationPolicy.needsEnrichment(it.document, it.translationSource)
        } ?: true
        fun needsMoreRomanization(): Boolean = flight.state.value.update?.let {
            LyricRomanizationPolicy.needsEnrichment(it.document) ||
                (it.document.lines.any { line -> LyricRomanizationPolicy.needsReading(line.text) } &&
                    LyricRomanizationPolicy.priority(it.romanizationSource) < 4)
        } ?: true
        fun needsAnnotations() = needsMoreTranslation() || needsMoreRomanization()
        val successfulReadingProviders = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        suspend fun offer(entry: Entry, fromCache: Boolean = false): LyricsDocument? {
            val document = try { parse(entry)?.takeIf { it.lines.isNotEmpty() } }
            catch (e: CancellationException) { throw e } catch (_: Exception) { null } ?: return null
            offerLock.withLock {
                fun rank(doc: LyricsDocument, source: String) = score(doc) + when (source) {
                    "local" -> 3
                    query.trackRef?.platform -> 4
                    "script" -> 0
                    else -> 2
                }
                val current = bestDocument
                var chosen = entry
                var result = LyricTranslationPolicy.Result(document, entry.translationSource?.takeIf { entry.revision >= 3 } ?: entry.source)
                var reading = LyricRomanizationPolicy.Result(document, entry.romanizationSource
                    ?: entry.source.takeIf { document.lines.any { line -> LyricRomanizationPolicy.valid(line.text, line.romanization) } })
                if (current != null) {
                    val previous = best!!
                    val previousReadingSource = previous.romanizationSource
                        ?: previous.source.takeIf { current.lines.any { line -> LyricRomanizationPolicy.valid(line.text, line.romanization) } }
                    val previousTranslationSource = previous.translationSource ?: previous.source
                    if (rank(document, entry.source) > rank(current, previous.source) ||
                        (!fromCache && previous.checkedAt > 0 && previous.source == entry.source &&
                            LyricTranslationPolicy.coverage(document) >= LyricTranslationPolicy.coverage(current)) ||
                        LyricTranslationPolicy.preferTranslatedDocument(current, document, previousTranslationSource, result.translationSource, entry.identityVerified)) {
                        if (entry.source != previous.source && LyricTranslationPolicy.hasForeign(current) &&
                            LyricTranslationPolicy.hasForeign(document) && !entry.identityVerified && !LyricTranslationPolicy.sameRecording(current, document)) return@withLock
                        result = LyricTranslationPolicy.enrich(document, current, result.translationSource, previousTranslationSource)
                        reading = LyricRomanizationPolicy.enrich(result.document, current, reading.source, previousReadingSource)
                        result = result.copy(document = reading.document)
                        // 换时间轴不能使已经显示的平台译文丢失或被低优先来源替换。
                        if (LyricTranslationPolicy.hasForeign(current) && LyricTranslationPolicy.coverage(current) >= 0.8 &&
                            LyricTranslationPolicy.priority(result.translationSource) < LyricTranslationPolicy.priority(previousTranslationSource)) return@withLock
                        if (LyricTranslationPolicy.hasForeign(current) &&
                            LyricTranslationPolicy.coverage(result.document) + 0.05 < LyricTranslationPolicy.coverage(current)) return@withLock
                        if (LyricRomanizationPolicy.coverage(result.document) + .05 < LyricRomanizationPolicy.coverage(current)) return@withLock
                    } else {
                        chosen = previous
                        result = LyricTranslationPolicy.enrich(current, document, previousTranslationSource, result.translationSource)
                        reading = LyricRomanizationPolicy.enrich(result.document, document, previousReadingSource, reading.source)
                        result = result.copy(document = reading.document)
                    }
                    if (result.document == current && chosen.source == previous.source &&
                        result.translationSource == previousTranslationSource && reading.source == previousReadingSource) return@withLock
                }
                chosen = chosen.copy(revision = 4, translationSource = result.translationSource,
                    romanizationSource = reading.source, originalFingerprint = fingerprint(result.document),
                    annotations = result.document.lines.mapIndexedNotNull { index, line ->
                        if (!line.translation.isNullOrBlank() || !line.romanization.isNullOrBlank())
                            SavedTranslation(index, line.timeMs, line.text, line.translation, line.romanization,
                                line.romanizationSyllables.map { SavedSyllable(it.startTimeMs, it.endTimeMs, it.text) }) else null
                    })
                best = chosen; bestDocument = result.document
                flight.state.value = State(PlaybackLyricsUpdate(result.document, chosen.source, fromCache, nowMs() - start, result.translationSource, reading.source))
                if (!fromCache) remember(chosen)
            }
            return document
        }
        try {
            embeddedText?.takeIf { it.isNotBlank() }?.let { offer(Entry(key, it, "local", savedAt = nowMs())) }
            cached(key)?.let { entry ->
                offer(entry, fromCache = true)
                val freshness = if (needsMoreTranslation()) 5L * 60_000 else 6L * 3_600_000
                val translationFresh = entry.checkedAt > 0 && nowMs() - entry.checkedAt in 0..freshness
                val readingFresh = if (!needsMoreRomanization()) entry.checkedAt > 0 && nowMs() - entry.checkedAt in 0..6L * 3_600_000
                    else (entry.romanizationCheckedAt > 0 && nowMs() - entry.romanizationCheckedAt in 0..5L * 60_000) ||
                        entry.romanizationRetryAt > nowMs()
                if (best != null && entry.revision >= 4 && translationFresh && readingFresh) return
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
                            val candidate = offer(Entry(key, hit.text, provider.id.wire, hit.format.wire, hit.translationText, romanization = hit.romanizationText, savedAt = nowMs(),
                                romanizationBaseText = hit.romanizationBaseText, romanizationFallbackText = hit.romanizationFallbackText,
                                // QQ/网易的跨平台查询已由 pickBest 严格核对歌名与歌手；只整份采用，不据此逐句拼接。
                                identityVerified = provider.id in listOf(OnlineLyricsSource.WY, OnlineLyricsSource.TX) && !query.artist.isNullOrBlank()))
                            // 仅有效且与当前原文相容的响应才证明查过读音；null/超时/限流不记成暂无。
                            if (provider.id in listOf(OnlineLyricsSource.WY, OnlineLyricsSource.TX)) {
                                val current = flight.state.value.update?.document
                                if (candidate != null && current != null && (candidate.lines.map { it.timeMs to it.text } == current.lines.map { it.timeMs to it.text } ||
                                    LyricTranslationPolicy.compatible(current, candidate))) successfulReadingProviders.add(provider.id.wire)
                            }
                        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                    }
                    val native = providers.firstOrNull { it.id.wire == query.trackRef?.platform }
                    // 补译文优先查询双语字段已接通的来源，原平台首个结果仍立即显示。
                    val fallback = providers.filter { it !== native }.sortedBy {
                        when (it.id) { OnlineLyricsSource.WY -> 0; OnlineLyricsSource.TX -> 1; else -> 2 }
                    }
                    if (native != null) launch { permits.withPermit { attempt(native) } }
                    fallback.forEachIndexed { index, provider -> launch {
                        delay(if (native == null && index == 0) 0 else if (index == 0) hedgeDelayMs else hedgeDelayMs * 2)
                        if (!needsAnnotations()) return@launch
                        permits.withPermit { if (needsAnnotations()) attempt(provider) }
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
                        for ((index, wait) in listOf(0L, 1_500L, 3_000L).withIndex()) {
                            delay(wait)
                            if (index > 0 && !needsAnnotations() && flight.state.value.update?.document?.lines?.any {
                                it.timingKind == LyricTimingKind.Precise && it.syllables.isNotEmpty()
                            } == true) break
                            permits.withPermit {
                                try {
                                    val hit = withTimeoutOrNull(2_000) { metadata.resolveLyrics(query.trackRef) } ?: return@withPermit
                                    offer(Entry(key, hit.lyric.orEmpty(), "script", translation = hit.tlyric,
                                        romanization = hit.rlyric, lxlyric = hit.lxlyric, savedAt = nowMs()))
                                } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                            }
                        }
                    }
                }
            }
            best?.let {
                val readingProviders = providers.filter { p -> p.id in listOf(OnlineLyricsSource.WY, OnlineLyricsSource.TX) }.map { p -> p.id.wire }
                val checked = readingProviders.isNotEmpty() && successfulReadingProviders.containsAll(readingProviders)
                remember(it.copy(checkedAt = nowMs(), revision = 4,
                    romanizationCheckedAt = if (checked) nowMs() else it.romanizationCheckedAt,
                    romanizationRetryAt = if (needsMoreRomanization() && !checked) nowMs() + 30_000 else 0))
            } ?: synchronized(memory) {
                misses[key] = nowMs() + 30_000
                while (misses.size > 64) misses.remove(misses.keys.first())
            }
        } finally { flight.state.value = flight.state.value.copy(done = true) }
    }
}
