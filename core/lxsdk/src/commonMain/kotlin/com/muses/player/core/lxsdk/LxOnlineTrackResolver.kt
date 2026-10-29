package com.muses.player.core.lxsdk

import com.muses.player.core.data.diagnostics.Diagnostics
import com.muses.player.core.model.online.OnlinePlayableUrl
import com.muses.player.core.model.online.OnlineResolveException
import com.muses.player.core.model.online.OnlineTrackIdentity
import com.muses.player.core.model.online.OnlineTrackRef
import com.muses.player.core.model.online.OnlineTrackSession
import com.muses.player.core.model.online.OnlineTrackResolver
import com.muses.player.core.model.online.OnlineTrackCandidateProvider
import com.muses.player.core.model.online.OnlinePlayableUrlProbe
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import com.muses.player.core.model.online.expectedDurationMs

/**
 * 把 [LxScriptRepository] 适配为播放链路的 [OnlineTrackResolver] 端口。
 *
 * 播放器（Android `PlayerConnection` / 桌面 `JvmPlayerPort`）只依赖 [:core:common]
 * 的端口接口，本适配器把洛雪引擎的具体调用封装在 [:core:lxsdk] 内，
 * 避免播放层反向依赖 JS 引擎。
 *
 * ## 回退与预算
 * 顺序固定：当前首选脚本 → 同档后备脚本 → 同档跨平台候选 → 失败后才降一档。
 * 整体时限是**有界尽力**：候选搜索与每个候选都受剩余预算约束，并为后续档位保留
 * 一小段预算，避免慢候选把整首歌的时间全部吃掉。不会为了抢时间让后备越过有效首选。
 *
 * ## 音质来源
 * `ref.quality` 只视为「上次用过的档位」；只有显式标记（[OnlineTrackRef.qualityExplicit]）
 * 才代表本次指定，其余一律跟随当前设置，避免设置一刀切覆盖显式选择。
 */
class LxOnlineTrackResolver(
    private val repository: LxScriptRepository,
    /** 默认请求音质；null = 完全交给脚本自身的 mapQuality 兜底 */
    private val defaultQuality: LxQuality? = LxQuality.DEFAULT,
    private val candidateProvider: OnlineTrackCandidateProvider? = null,
    private val urlProbe: OnlinePlayableUrlProbe? = null,
    private val candidateSearchTimeoutMs: Long = 8_000,
    /** 整体解析上限：宁可报错让用户手动重试，也不让界面一直转圈。 */
    private val overallTimeoutMs: Long = 30_000,
    private val platformTimeoutMs: Long = 12_000,
    private val candidateTimeoutMs: Long = 6_000,
    /** 单次地址探针上限；超过即视为该地址不可用。 */
    private val probeTimeoutMs: Long = 4_000,
    private val preferredQualityProvider: (suspend () -> LxQuality?)? = null,
    /** 短期直链复用；默认 60 秒、32 条，测试可注入假时钟版本。 */
    private val reuseCache: OnlineResolveReuseCache = OnlineResolveReuseCache(),
    /** 运行期已证实无法解封装的地址（有界排除）。 */
    private val rejectedUrls: RejectedPlayableUrls = RejectedPlayableUrls(),
    /** 复用键的盐：首选选择 + 脚本集合版本。 */
    private val cacheKeySaltProvider: suspend () -> String = { "" },
    /** 单档位的时间上限；实际还会按剩余档位公平分配，避免高档请求耗尽全部时间。 */
    private val perTierBudgetMs: Long = 9_000,
    /** 单次实际尝试的最短时间片；剩余预算低于它就不再开启新尝试。 */
    private val minAttemptMs: Long = 1_500,
) : OnlineTrackResolver {

    override suspend fun resolve(ref: OnlineTrackRef): OnlinePlayableUrl {
        val quality = resolveQuality(ref)
        val cacheKey = buildCacheKey(ref, quality)
        reuseCache.get(cacheKey)?.let { hit ->
            // 命中复用时恢复「入队引用 → 实际资源」映射，封面/歌词仍跟随实际音源。
            OnlineTrackSession.rememberResolution(ref, hit.resolvedRef ?: ref)
            return hit.copy(
                requestedQuality = quality?.key,
                timings = hit.timings + ("cacheHit" to 1L),
            )
        }
        val stages = LinkedHashMap<String, Long>()
        val startedAt = TimeSource.Monotonic.markNow()
        val result = if (candidateProvider == null) {
            resolveDirect(ref, quality, exactQuality = false, stages = stages)
                .also { OnlineTrackSession.rememberResolution(ref, ref) }
        } else {
            withTimeoutOrNull(overallTimeoutMs) { resolveWithFallback(ref, quality, stages) }
                ?: throw OnlineResolveException("寻找可播放音源超时，请稍后重试")
        }
        val finalized = result.copy(
            requestedQuality = quality?.key,
            resolvedRef = OnlineTrackSession.resolvedReference(ref),
            timings = result.timings + stages + ("total" to startedAt.elapsedNow().inWholeMilliseconds),
        )
        reuseCache.put(cacheKey, finalized)
        return finalized
    }

    override fun invalidateReference(ref: OnlineTrackRef) {
        val identity = OnlineTrackIdentity.matchKeys(ref).firstOrNull() ?: return
        reuseCache.invalidatePrefix(identity + "|")
    }

    override fun invalidatePlayableUrl(url: String) {
        reuseCache.invalidateUrl(url)
    }

    override fun rejectPlayableUrl(url: String) {
        reuseCache.invalidateUrl(url)
        rejectedUrls.reject(url)
    }

    /** 显式指定优先；否则跟随当前设置；引用里的旧档位不再覆盖设置。 */
    private suspend fun resolveQuality(ref: OnlineTrackRef): LxQuality? {
        if (ref.qualityExplicit) {
            val explicit = ref.quality?.takeIf { it.isNotBlank() }?.let(LxQuality::fromKey)
            if (explicit != null) return explicit
        }
        return preferredQualityProvider?.invoke() ?: defaultQuality
    }

    private suspend fun buildCacheKey(ref: OnlineTrackRef, quality: LxQuality?): String {
        val identity = OnlineTrackIdentity.matchKeys(ref).firstOrNull().orEmpty()
        // 读取设置被切歌取消时必须退出；不能吞掉取消后继续联网或命中过期偏好的缓存。
        val salt = cacheKeySaltProvider()
        return "$identity|${quality?.key.orEmpty()}|$salt"
    }

    private suspend fun resolveWithFallback(
        ref: OnlineTrackRef,
        requestedQuality: LxQuality?,
        stages: MutableMap<String, Long>,
    ): OnlinePlayableUrl = kotlinx.coroutines.coroutineScope {
        val budgetStart = TimeSource.Monotonic.markNow()
        fun remainingMs(): Long = overallTimeoutMs - budgetStart.elapsedNow().inWholeMilliseconds
        val requested = requestedQuality ?: LxQuality.DEFAULT
        val qualities = fallbackQualities(requested)
        // 仅在「所有脚本都已加载、可明确断言」时才跳过不支持的档位；否则保持原样尝试。
        val knownQualities = try { repository.loadedDeclaredQualities(ref.platform) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        var failure: Exception? = null
        var skippedCurrentPlatform = false
        var attemptedAny = false

        // 候选搜索按需启动：首选/当前平台一旦成功就不初始化后备脚本（旧行为保持不变）；
        // 一旦需要候选，就在后台搜索，后续每档只等自己那份预算，不阻塞低档位尝试。
        var alternativesDeferred: kotlinx.coroutines.Deferred<List<OnlineTrackRef>>? = null
        fun candidatesDeferred(): kotlinx.coroutines.Deferred<List<OnlineTrackRef>>? {
            alternativesDeferred?.let { return it }
            val provider = candidateProvider ?: return null
            return async {
                runCatching {
                    withTimeoutOrNull(candidateSearchTimeoutMs) {
                        val platforms = repository.loadAll().filter { it.loadError == null }
                            .flatMap { it.sources.filterValues { source -> source.supports(LxAction.MUSIC_URL) }.keys }
                            .distinct()
                        if (platforms.isEmpty()) emptyList() else
                            provider.candidates(ref, platforms)
                                .filter { candidate -> !sameTrack(ref, candidate) }
                                .distinctBy { it.platform to OnlineTrackIdentity.canonicalJson(it) }
                                .take(4)
                    } ?: emptyList()
                }.getOrElse { e ->
                    if (e is CancellationException) throw e
                    stages["failures"] = (stages["failures"] ?: 0L) + 1
                    emptyList()
                }
            }.also { alternativesDeferred = it }
        }

        try {
            for ((index, quality) in qualities.withIndex()) {
                val remaining = remainingMs()
                val tiersLeft = qualities.size - index
                if (remaining < minAttemptMs) {
                    stages["budgetExhausted"] = (stages["budgetExhausted"] ?: 0L) + 1
                    break
                }
                // 每档公平份额（略向高档倾斜 2 倍）：保证后续档位一定有机会，
                // 不会因为前面几档搜索/探测慢而把当前平台能播的档位饿死。
                val fairShare = remaining * 2 / tiersLeft
                val tierBudget = minOf(perTierBudgetMs, fairShare).coerceAtLeast(minAttemptMs)
                val tierStart = TimeSource.Monotonic.markNow()
                fun tierLeftMs(): Long =
                    (tierBudget - tierStart.elapsedNow().inWholeMilliseconds).coerceAtLeast(0L)

                // 已知当前平台不支持该档位时，只跳过当前平台自身的尝试；
                // 同档跨平台候选仍要试，否则会违反「同档跨平台后才降档」。
                val currentPlatformUnsupported = knownQualities != null && quality !in knownQualities
                if (currentPlatformUnsupported) {
                    skippedCurrentPlatform = true
                    stages["skip.${quality.key}"] = 1
                } else {
                    attemptedAny = true
                    // 当前平台的慢脚本不能用尽本档预算，否则跨平台同档永远没有机会。
                    // 保留一半给候选搜索和同档尝试；没有跨平台能力时仍可使用完整本档预算。
                    val platformAllowance = if (candidateProvider != null) tierLeftMs() / 2 else tierLeftMs()
                    val platformBudget = minOf(platformTimeoutMs, platformAllowance).coerceAtLeast(minAttemptMs)
                    val platformStart = TimeSource.Monotonic.markNow()
                    try {
                        withTimeoutOrNull(platformBudget) {
                            resolveDirect(ref, quality, exactQuality = true, stages = stages)
                        }?.let {
                            stages["platform"] =
                                (stages["platform"] ?: 0L) + platformStart.elapsedNow().inWholeMilliseconds
                            OnlineTrackSession.rememberResolution(ref, ref)
                            return@coroutineScope it
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        stages["failures"] = (stages["failures"] ?: 0L) + 1
                        failure = e
                    }
                    stages["platform"] =
                        (stages["platform"] ?: 0L) + platformStart.elapsedNow().inWholeMilliseconds
                }

                // 等待候选搜索（首次需要时才启动；一旦完成就缓存给本次解析后续档位复用）；
                // 本档剩余时间不足以等待时直接进入下一档，搜索继续在后台跑。
                val candidates = candidatesDeferred()?.let { deferred ->
                    val waitBudget = tierLeftMs()
                    if (waitBudget < minAttemptMs) {
                        emptyList()
                    } else {
                        val waitStart = TimeSource.Monotonic.markNow()
                        val list = withTimeoutOrNull(waitBudget) { deferred.await() } ?: emptyList()
                        stages["candidateSearch"] =
                            (stages["candidateSearch"] ?: 0L) + waitStart.elapsedNow().inWholeMilliseconds
                        list
                    }
                }.orEmpty()

                for (candidate in candidates) {
                    val left = tierLeftMs()
                    if (left < minAttemptMs) break
                    attemptedAny = true
                    val candidateBudget = minOf(candidateTimeoutMs, left).coerceAtLeast(minAttemptMs)
                    try {
                        withTimeoutOrNull(candidateBudget) {
                            resolveDirect(candidate, quality, exactQuality = true, stages = stages)
                        }?.let {
                            OnlineTrackSession.rememberResolution(ref, candidate)
                            return@coroutineScope it
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        stages["failures"] = (stages["failures"] ?: 0L) + 1
                        failure = e
                    }
                }
            }
            val reason = when {
                skippedCurrentPlatform && !attemptedAny -> "当前音源不支持所选音质，其他平台也未找到可播放的同曲版本"
                else -> "当前音源无法播放，其他平台也未找到可播放的同曲版本"
            }
            throw OnlineResolveException(reason, failure)
        } finally {
            // 成功提前返回时不要等后台搜索跑完，否则会白白拖慢出声。
            alternativesDeferred?.cancel()
        }
    }

    private fun sameTrack(left: OnlineTrackRef, right: OnlineTrackRef): Boolean =
        left.platform == right.platform && left.musicInfoJson == right.musicInfoJson

    private suspend fun resolveDirect(
        ref: OnlineTrackRef,
        quality: LxQuality?,
        exactQuality: Boolean,
        stages: MutableMap<String, Long>,
    ): OnlinePlayableUrl {
        val correlation = Diagnostics.correlation()
        Diagnostics.record("resolve_attempt", correlation + mapOf("platform" to ref.platform, "quality" to quality?.key.orEmpty(), "result" to "resolve"))
        val expectedDurationMs = ref.expectedDurationMs()
        var probeMs = 0L
        val attemptStart = TimeSource.Monotonic.markNow()
        return try {
            val result = repository.resolveMusicUrlInfo(
                platform = ref.platform,
                musicInfoJson = ref.musicInfoJson,
                quality = quality,
                exactQuality = exactQuality,
                acceptUrl = { url ->
                    when {
                        rejectedUrls.contains(url) -> false
                        urlProbe == null -> true
                        else -> {
                            val probeStart = TimeSource.Monotonic.markNow()
                            Diagnostics.record("probe_start", correlation + mapOf("resource" to Diagnostics.anonymous(url)))
                            val ok = withTimeoutOrNull(probeTimeoutMs) { urlProbe.canOpen(url, expectedDurationMs) } == true
                            Diagnostics.record("probe_result", correlation + mapOf("result" to if(ok) "success" else "failure", "elapsed_ms" to probeStart.elapsedNow().inWholeMilliseconds.toString()))
                            probeMs += probeStart.elapsedNow().inWholeMilliseconds
                            ok
                        }
                    }
                },
            )
            Diagnostics.record("resolution_success", correlation + mapOf("quality" to result.quality?.key.orEmpty(), "result" to "success"))
            OnlinePlayableUrl(url = result.url, quality = result.quality?.key, sourceName = result.sourceName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LxResolveException) {
            throw OnlineResolveException(e.message ?: "在线音源解析失败", e)
        } catch (e: LxException) {
            throw OnlineResolveException(e.message ?: "在线音源解析失败", e)
        } catch (e: Exception) {
            throw OnlineResolveException("在线音源解析异常：${e.message}", e)
        } finally {
            // 失败与成功的阶段都要累加，否则日志里的 script/probe 只剩余最后一次尝试。
            val elapsed = attemptStart.elapsedNow().inWholeMilliseconds
            Diagnostics.record("resolve_attempt_end", correlation + mapOf("elapsed_ms" to elapsed.toString(), "platform" to ref.platform))
            stages["script"] = (stages["script"] ?: 0L) + (elapsed - probeMs).coerceAtLeast(0L)
            stages["probe"] = (stages["probe"] ?: 0L) + probeMs
            stages["attempts"] = (stages["attempts"] ?: 0L) + 1
        }
    }
}
