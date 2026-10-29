package com.muses.player.core.lxsdk

import com.muses.player.core.lxsdk.http.LxHttpClient
import com.muses.player.core.lxsdk.crypto.LxCrypto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers

/**
 * 已加载的音源脚本条目：脚本源码 + 元信息 + 运行中的引擎。
 *
 * 一个脚本可声明多个源（kw/kg/tx/wy/mg/local），故按 platform 建索引。
 */
class LoadedLxScript(
    val scriptId: String,
    val source: String,
) {
    private val loadMutex = Mutex()
    var engine: LxScriptEngine? = null
        private set

    /** 加载失败时的错误信息（供 UI 展示「脚本不可用」） */
    var loadError: String? = null
        private set

    /** 加载成功后的源声明 */
    val sources: Map<String, LxSourceDeclaration>
        get() = engine?.sources ?: emptyMap()

    val meta: LxScriptMeta
        get() = engine?.meta ?: LxScriptMetaParser.parse(source)

    suspend fun ensureLoaded(
        crypto: LxCrypto,
        httpClient: LxHttpClient,
        requestTimeoutMs: Long,
    ): Boolean = loadMutex.withLock {
        if (engine != null) return@withLock true
        try {
            val e = LxScriptEngine(
                scriptSource = source,
                crypto = crypto,
                httpClient = httpClient,
                requestTimeoutMs = requestTimeoutMs,
            )
            e.load()
            engine = e
            loadError = null
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.message ?: "脚本加载失败"
            false
        }
    }

    fun close() {
        runCatching { engine?.close() }
        engine = null
    }
}

/**
 * 在线音源脚本仓库：管理多个洛雪脚本，按 platform 路由解析请求。
 *
 * 职责：
 * - 持有已导入脚本列表（由上层持久化，仓库只关心运行态）；
 * - 懒加载脚本引擎（首次用到某平台时才加载对应脚本，避免启动即拉起全部 QuickJS runtime）；
 * - 按 platform 选择可用脚本并解析直链。
 *
 * 线程模型：脚本列表与索引变更经 [mutex] 串行化；引擎内部的并发由 [LxScriptEngine] 自身保证。
 */
class LxScriptRepository(
    private val crypto: LxCrypto,
    private val httpClient: LxHttpClient = LxHttpClient(),
    private val requestTimeoutMs: Long = 15_000L,
    /**
     * 已导入脚本的来源（懒加载）。
     *
     * 首次真正用到仓库时才从此处拉取并登记，而**不是**在构造时 eager 读取——
     * 因为 Android 的 `PlatformDirs` 依赖 `initPlatformDirs(context)` 先执行，
     * 构造期读取会在 Koin 解析链早期抛错（实测崩溃）。
     *
     * @return (scriptId, 脚本源码) 列表；仅返回已启用的脚本
     */
    private val storedScriptsProvider: (suspend () -> List<Pair<String, String>>)? = null,
    private val preferredScriptIdsProvider: suspend () -> Set<String> = { emptySet() },
) {

    private val mutex = Mutex()

    /** 懒同步互斥（与 [mutex] 分开，避免重入死锁：同步过程内部会调 register） */
    private val syncMutex = Mutex()
    private var synced = false

    private val scripts = mutableListOf<LoadedLxScript>()

    /**
     * 脚本集合版本号：注册/注销/刷新/清空时递增。
     * 短期直链复用把它纳入键，脚本一换就不会继续用旧解析结果。
     */
    private val generationState = kotlinx.coroutines.flow.MutableStateFlow(0L)

    val generation: Long get() = generationState.value

    private fun bumpGeneration() {
        generationState.value += 1L
    }

    /**
     * 首次调用时把存储中的脚本登记进仓库（幂等）。
     * 注意：必须在**获取 [mutex] 之前**调用，否则与 [register] 重入死锁。
     */
    private suspend fun ensureSynced() {
        if (synced) return
        syncMutex.withLock {
            if (synced) return
            val provider = storedScriptsProvider
            if (provider != null) {
                runCatching { provider() }.getOrDefault(emptyList()).forEach { (id, source) ->
                    register(id, source)
                }
            }
            synced = true
        }
    }

    /** 开关或内置更新变化后，按持久化状态替换运行态，不影响已解析出的播放地址。 */
    suspend fun refreshFromStore() = syncMutex.withLock {
        val provider = storedScriptsProvider ?: return@withLock
        val stored = provider()
        mutex.withLock {
            val sourcesById = stored.toMap()
            scripts.filter { sourcesById[it.scriptId] != it.source }.forEach { it.close() }
            scripts.removeAll { sourcesById[it.scriptId] != it.source }
            stored.forEach { (id, source) ->
                if (scripts.none { it.scriptId == id }) scripts += LoadedLxScript(id, source)
            }
        }
        bumpGeneration()
        synced = true
    }

    /** 当前已注册脚本快照（供 UI 展示） */
    suspend fun scripts(): List<LoadedLxScript> {
        ensureSynced()
        return mutex.withLock { scripts.toList() }
    }

    /**
     * 触发全部脚本加载（供 UI 展示源列表与加载错误）。
     * 加载失败的脚本不抛错，错误记录在各自的 [LoadedLxScript.loadError] 上。
     */
    suspend fun loadAll(): List<LoadedLxScript> {
        ensureSynced()
        val snapshot = mutex.withLock { scripts.toList() }
        coroutineScope {
            snapshot.map { async { it.ensureLoaded(crypto, httpClient, requestTimeoutMs) } }.awaitAll()
        }
        return snapshot
    }

    /**
     * 校验脚本源码（导入前预检）：试加载并返回源声明。
     * 临时引擎用完即关，不影响已注册脚本。
     *
     * 用于 UI 在用户粘贴脚本时立即反馈「能否初始化」「能提供哪些源」，
     * 避免导入一个跑不起来的脚本。
     */
    suspend fun validate(source: String): LxScriptDescriptor {
        val engine = LxScriptEngine(
            scriptSource = source,
            crypto = crypto,
            httpClient = httpClient,
            requestTimeoutMs = requestTimeoutMs,
        )
        return try {
            engine.load()
        } finally {
            engine.close()
        }
    }

    /**
     * 当前可用于某平台的源声明（需已加载）。
     * 供 UI 提示「该平台当前由哪个脚本提供」；未加载时返回空。
     */
    suspend fun declarationsFor(platform: String): List<Pair<String, LxSourceDeclaration>> {
        ensureSynced()
        return mutex.withLock { scripts.toList() }
            .mapNotNull { script ->
                val decl = script.sources[platform] ?: return@mapNotNull null
                (script.meta.name ?: script.scriptId) to decl
            }
    }

    /** 注册（或替换）脚本。不立即加载——首次请求该脚本的源时才加载。 */
    suspend fun register(scriptId: String, source: String) = mutex.withLock {
        scripts.filter { it.scriptId == scriptId }.forEach { it.close() }
        scripts.removeAll { it.scriptId == scriptId }
        scripts.add(LoadedLxScript(scriptId = scriptId, source = source))
        bumpGeneration()
    }

    /** 注销脚本并释放其引擎 */
    suspend fun unregister(scriptId: String) = mutex.withLock {
        scripts.filter { it.scriptId == scriptId }.forEach { it.close() }
        scripts.removeAll { it.scriptId == scriptId }
        bumpGeneration()
        Unit
    }

    suspend fun clear() = mutex.withLock {
        scripts.forEach { it.close() }
        scripts.clear()
        bumpGeneration()
    }

    /** 偏好指纹：首选选择 + 脚本集合版本，供短期直链复用做键。 */
    suspend fun preferenceFingerprint(): String {
        val ids = preferredScriptIdsProvider().sorted().joinToString(",")
        return "${generationState.value}:$ids"
    }

    /**
     * 空闲时预热当前首选脚本（不加载其它内置源）。
     *
     * 冷启动后第一首歌曲必须等 QuickJS 创建并执行脚本，才有音源请求可发；
     * 这里只提前做这一步，不解析地址、不发网络请求，也不改变既有回退策略。
     */
    suspend fun warmPreferred(timeoutMs: Long = 6_000L): Boolean {
        ensureSynced()
        val ids = preferredScriptIdsProvider()
        if (ids.isEmpty()) return false
        val targets = mutex.withLock { scripts.filter { it.scriptId in ids && it.engine == null } }
        if (targets.isEmpty()) return true
        return withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                targets.map { async { it.ensureLoaded(crypto, httpClient, requestTimeoutMs) } }.awaitAll()
            }
        } != null
    }

    /**
     * 已加载脚本对某平台声明的档位；未加载或无声明时返回 null（表示未知，不能据此跳过）。
     * 用于回退时跳过脚本明确不支持的档位，把预算留给能用的档位。
     */
    suspend fun loadedDeclaredQualities(platform: String): Set<LxQuality>? {
        ensureSynced()
        val snapshot = mutex.withLock { scripts.toList() }
        if (snapshot.isEmpty()) return null
        // 只要还有活跃脚本未加载，就无法断言「平台支持哪些档位」：
        // 部分加载的低档源会把尚未加载的高档源当成不存在，导致错误跳档。
        if (snapshot.any { it.engine == null }) return null
        val declarations = snapshot.mapNotNull { it.engine?.sources?.get(platform) }
            .filter { it.supports(LxAction.MUSIC_URL) }
        if (declarations.isEmpty()) return null
        val qualities = declarations.flatMap { it.qualitys }.mapNotNull(LxQuality::fromKey).toSet()
        return qualities.ifEmpty { null }
    }

    /**
     * 解析直链。
     *
     * 路由策略：所选音质优先，同档位跨脚本并行寻找可用地址，全部失败后降档。
     * 找不到可用脚本时抛 [LxResolveException]（上层转成用户可读文案）。
     */
    suspend fun resolveMusicUrl(
        platform: String,
        musicInfoJson: String,
        quality: LxQuality? = null,
    ): String = resolveMusicUrlInfo(platform, musicInfoJson, quality).url

    /** 返回实际请求档位。默认只降档；跨平台逐档调用时用 [exactQuality] 禁止内部提前降档。 */
    suspend fun resolveMusicUrlInfo(
        platform: String,
        musicInfoJson: String,
        quality: LxQuality? = null,
        @Suppress("UNUSED_PARAMETER") downloadFallback: Boolean = false,
        acceptUrl: suspend (String) -> Boolean = { true },
        exactQuality: Boolean = false,
    ): LxMusicUrl {
        ensureSynced()
        val candidates = mutex.withLock { scripts.toList() }
        val preferredIds = preferredScriptIdsProvider()
        val (preferred, others) = candidates.partition { it.scriptId in preferredIds }
        if (preferred.isEmpty()) {
            return resolveFromCandidates(candidates, platform, musicInfoJson, quality, acceptUrl, exactQuality)
        }
        val requested = quality ?: LxQuality.DEFAULT
        val qualities = if (exactQuality) listOf(requested) else fallbackQualities(requested)
        val failures = mutableListOf<String>()
        // 保持音质优先级：同档位先试首选脚本，再试后备；都失败后才降档。
        for (effective in qualities) {
            for ((index, group) in listOf(preferred, others).withIndex()) {
                if (group.isEmpty()) continue
                try {
                    val result = if (index == 0) {
                        withTimeoutOrNull(minOf(requestTimeoutMs, 6_000L)) {
                            resolveFromCandidates(group, platform, musicInfoJson, effective, acceptUrl, true)
                        }
                    } else resolveFromCandidates(group, platform, musicInfoJson, effective, acceptUrl, true)
                    if (result != null) return result
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { failures += e.message.orEmpty() }
            }
        }
        throw LxResolveException("首选及后备音源均无法解析播放地址。" + failures.joinToString("；"))
    }

    private suspend fun resolveFromCandidates(
        candidates: List<LoadedLxScript>,
        platform: String,
        musicInfoJson: String,
        quality: LxQuality?,
        acceptUrl: suspend (String) -> Boolean,
        exactQuality: Boolean,
    ): LxMusicUrl {
        if (candidates.isEmpty()) {
            throw LxResolveException("未导入任何音源脚本。")
        }

        val failures = mutableListOf<String>()
        val failuresMutex = Mutex()
        val downloadAttempts = mutableListOf<Triple<LxQuality, LxScriptEngine, String>>()
        val availability = coroutineScope {
            candidates.map { async {
                val mark = kotlin.time.TimeSource.Monotonic.markNow()
                val context = com.muses.player.core.data.diagnostics.Diagnostics.correlation()
                com.muses.player.core.data.diagnostics.Diagnostics.record("script_init_start", context + mapOf("script" to com.muses.player.core.data.diagnostics.Diagnostics.anonymous(it.scriptId)))
                try { it.ensureLoaded(crypto, httpClient, requestTimeoutMs) }
                finally { com.muses.player.core.data.diagnostics.Diagnostics.record("script_init_end", context + mapOf("script" to com.muses.player.core.data.diagnostics.Diagnostics.anonymous(it.scriptId), "elapsed_ms" to mark.elapsedNow().inWholeMilliseconds.toString())) }
            } }.awaitAll()
        }
        for ((script, loaded) in candidates.zip(availability)) {
            // 懒加载：加载失败的脚本跳过并记录（不阻断其它脚本）
            if (!loaded) {
                failures += "${script.meta.name ?: script.scriptId}: ${script.loadError}"
                continue
            }
            val engine = script.engine ?: continue
            val declared = engine.sources?.get(platform) ?: continue
            if (!declared.supports(LxAction.MUSIC_URL)) continue

            val requested = quality ?: LxQuality.DEFAULT
            fallbackQualities(requested).filter {
                (if (exactQuality) it == requested else true) && declared.supports(it)
            }
                .forEach { effectiveQuality ->
                    downloadAttempts += Triple(effectiveQuality, engine, script.meta.name ?: script.scriptId)
                }
            // local 源不声明音质，保留原有无档位解析能力。
            if (platform == "local" && declared.qualitys.isEmpty()) {
                val result = engine.getMusicUrl(platform, null, musicInfoJson)
                if (acceptUrl(result.url)) return result.copy(sourceName = script.meta.name ?: script.scriptId)
            }
        }

        // 同档位并行解析并探测；赢家确定后取消其余候选，避免继续消耗接口与流量。
        for ((_, attempts) in downloadAttempts.groupBy { it.first }.toList().sortedByDescending { it.first.rank }) {
            val winner = coroutineScope {
                val results = Channel<LxMusicUrl?>(Channel.UNLIMITED)
                val jobs = attempts.map { (effectiveQuality, engine, name) ->
                    launch(Dispatchers.Default) {
                        val context = com.muses.player.core.data.diagnostics.Diagnostics.correlation()
                        val script = com.muses.player.core.data.diagnostics.Diagnostics.anonymous(candidates.firstOrNull { it.engine === engine }?.scriptId ?: name)
                        val started = kotlin.time.TimeSource.Monotonic.markNow()
                        var outcome = "failure"
                        com.muses.player.core.data.diagnostics.Diagnostics.record("script_request_start", context + mapOf("script" to script, "platform" to platform, "quality" to effectiveQuality.key))
                        var result: LxMusicUrl? = null
                        try {
                            result = withTimeoutOrNull(requestTimeoutMs) {
                                engine.getMusicUrl(platform, effectiveQuality, musicInfoJson)
                                    .takeIf { acceptUrl(it.url) }
                                    ?.copy(quality = effectiveQuality, sourceName = name)
                            }
                            if (result != null) outcome = "success"
                        } catch (e: CancellationException) { outcome = "cancelled"; throw e }
                        catch (e: Exception) {
                            failuresMutex.withLock { failures += "$name: ${e.message}" }
                        } finally {
                            com.muses.player.core.data.diagnostics.Diagnostics.record("script_request_end", context + mapOf("script" to script, "platform" to platform, "quality" to effectiveQuality.key, "result" to outcome, "elapsed_ms" to started.elapsedNow().inWholeMilliseconds.toString()))
                            results.trySend(result)
                        }
                    }
                }
                try {
                    repeat(attempts.size) {
                        results.receive()?.let { return@coroutineScope it }
                    }
                    null
                } finally {
                    jobs.forEach { it.cancel() }
                    results.close()
                }
            }
            if (winner != null) return winner
        }

        throw LxResolveException(
            buildString {
                append("无法为源 '$platform' 解析播放地址。")
                if (failures.isNotEmpty()) {
                    append("已尝试：")
                    append(failures.joinToString("；"))
                }
            },
        )
    }

    /**
     * 解析歌词（脚本 `lyric` 动作）。
     *
     * 路由策略同 [resolveMusicUrl]：按 [platform] 找到第一个声明了该动作的脚本；
     * 找不到可用脚本或脚本报错时抛 [LxResolveException]（上层转用户可读文案或静默忽略）。
     */
    suspend fun resolveLyric(platform: String, musicInfoJson: String): LxLyric =
        routeToEngine(platform, LxAction.LYRIC, "歌词") { engine ->
            engine.getLyric(platform, musicInfoJson)
        }

    /**
     * 解析封面地址（脚本 `pic` 动作）。
     *
     * 失败语义同 [resolveLyric]：抛 [LxResolveException]。
     */
    suspend fun resolveCover(platform: String, musicInfoJson: String): String =
        routeToEngine(platform, LxAction.PIC, "封面") { engine ->
            engine.getPic(platform, musicInfoJson).url
        }

    /**
     * 按 [platform] + [action] 路由到第一个可用引擎并执行 [call]。
     *
     * 与 [resolveMusicUrl] 的差异：不做音质选择（lyric/pic 与音质无关），
     * 但沿用同一套「懒加载 → 声明校验 → 逐脚本尝试 → 汇总失败原因」口径。
     */
    private suspend fun <T> routeToEngine(
        platform: String,
        action: LxAction,
        what: String,
        call: suspend (LxScriptEngine) -> T,
    ): T {
        ensureSynced()
        val preferredIds = preferredScriptIdsProvider()
        val candidates = mutex.withLock { scripts.sortedBy { if (it.scriptId in preferredIds) 0 else 1 } }
        if (candidates.isEmpty()) {
            throw LxResolveException("未导入任何音源脚本。")
        }

        val failures = mutableListOf<String>()
        for (script in candidates) {
            val isPreferred = script.scriptId in preferredIds
            val loaded = if (isPreferred) withTimeoutOrNull(minOf(requestTimeoutMs, 6_000L)) {
                script.ensureLoaded(crypto, httpClient, requestTimeoutMs)
            } == true else script.ensureLoaded(crypto, httpClient, requestTimeoutMs)
            if (!loaded) {
                failures += "${script.meta.name ?: script.scriptId}: ${script.loadError}"
                continue
            }
            val engine = script.engine ?: continue
            val declared = engine.sources?.get(platform) ?: continue
            if (!declared.supports(action)) continue
            try {
                val result = if (isPreferred) withTimeoutOrNull(minOf(requestTimeoutMs, 6_000L)) { call(engine) }
                    else call(engine)
                if (result != null) return result
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                failures += "${script.meta.name ?: script.scriptId}: ${e.message}"
            }
        }

        throw LxResolveException(
            buildString {
                append("无法为源 '$platform' 获取$what。")
                if (failures.isNotEmpty()) {
                    append("已尝试：")
                    append(failures.joinToString("；"))
                }
            },
        )
    }

    fun close() {
        scripts.forEach { it.close() }
        scripts.clear()
    }
}

/** 解析失败（上层转用户可读文案，不直接透出） */
class LxResolveException(message: String, cause: Throwable? = null) : Exception(message, cause)
