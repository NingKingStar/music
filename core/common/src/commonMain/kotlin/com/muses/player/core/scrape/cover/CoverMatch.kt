package com.muses.player.core.scrape.cover

import com.muses.player.core.data.store.platformNowMs
import com.muses.player.core.model.scrape.OnlineTextQuery
import com.muses.player.core.scrape.cover.provider.ItunesCoverProvider
import com.muses.player.core.scrape.cover.provider.KgCoverProvider
import com.muses.player.core.scrape.cover.provider.KwCoverProvider
import com.muses.player.core.scrape.cover.provider.MgCoverProvider
import com.muses.player.core.scrape.cover.provider.TxCoverProvider
import com.muses.player.core.scrape.cover.provider.WyCoverProvider
import com.muses.player.core.scrape.http.ScrapeHttp
import com.muses.player.core.scrape.text.NegativeCache
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 多源封面匹配编排（规格书 = src/features/cover/match.ts matchOnlineCoverRemote）：
 * 1. title 空白 → no-match 早退；
 * 2. songId 级负缓存命中（queryKey 一致且未过期）→ no-match 短路；
 * 3. 默认链 iTunes → kw → tx → wy → kg → mg（国内段对齐 any-listen sources）
 *    逐源尝试，首个 /^https?:\/\//i 命中即返回远程 URL（不落盘）；
 * 4. 全链无命中：写负缓存，sawNetworkError 区分 network / no-match。
 *
 * 负缓存 TTL 45min / 容量 256 与文本链相同常量但独立实例
 * （Web 为 match.ts 模块级独立 Map，此处新建 NegativeCache 实例对齐）。
 *
 * W2 上收注：`System.currentTimeMillis()` → [platformNowMs]（commonMain 等价替身，actual 即 System）。
 */
class CoverMatcher(
    providers: List<CoverProvider>,
    val negativeCache: NegativeCache = NegativeCache(),
) {

    /** 供 UI 单曲重试时清除限流未命中的负缓存。 */
    fun invalidateNegativeCache(songId: String) {
        negativeCache.remove(songId)
    }

    private val providers: List<CoverProvider> = providers

    suspend fun match(query: OnlineCoverQuery, rejectedUrls: Set<String> = emptySet()): OnlineCoverMatchResult {
        val title = query.title.trim()
        if (title.isEmpty()) {
            return OnlineCoverMatchResult.Fail(OnlineCoverMatchFailReason.NO_MATCH)
        }

        val queryKey = buildQueryKey(query)
        val cached = negativeCache.get(query.songId)
        if (rejectedUrls.isEmpty() && cached != null && cached.queryKey == queryKey && cached.expiresAt > platformNowMs()) {
            return OnlineCoverMatchResult.Fail(OnlineCoverMatchFailReason.NO_MATCH)
        }

        var sawNetworkError = false

        for (provider in providers) {
            try {
                val remoteUrl = provider.searchCoverUrl(query)
                if (!remoteUrl.isNullOrEmpty() && remoteUrl !in rejectedUrls && HTTPS_URL_PREFIX.containsMatchIn(remoteUrl)) {
                    return OnlineCoverMatchResult.Ok(remoteUrl = remoteUrl, source = provider.id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 尝试下一源
                sawNetworkError = true
            }
        }

        // 仅 NO_MATCH 写入负缓存；NETWORK（含 429）不入缓存以支持限流后重试（由限流器控频）
        val failReason = if (sawNetworkError) OnlineCoverMatchFailReason.NETWORK else OnlineCoverMatchFailReason.NO_MATCH
        if (failReason == OnlineCoverMatchFailReason.NO_MATCH && rejectedUrls.isEmpty()) {
            negativeCache.put(
                query.songId,
                NegativeCache.NegativeEntry(
                    queryKey = queryKey,
                    expiresAt = platformNowMs() + NEGATIVE_CACHE_TTL_MS,
                ),
            )
        }

        return OnlineCoverMatchResult.Fail(failReason)
    }

    companion object {
        /** match.ts NEGATIVE_CACHE_TTL_MS = 45 * 60 * 1000 */
        const val NEGATIVE_CACHE_TTL_MS: Long = 45 * 60 * 1000L

        /** /^https?:\/\//i 命中校验 */
        private val HTTPS_URL_PREFIX = Regex("^https?://", RegexOption.IGNORE_CASE)

        private val json = Json
        private val stringListSerializer = ListSerializer(String.serializer())

        /** match.ts buildQueryKey：JSON.stringify([title.trim(), artist?.trim() || '', album?.trim() || '']) */
        private fun buildQueryKey(query: OnlineCoverQuery): String =
            json.encodeToString(
                stringListSerializer,
                listOf(
                    query.title.trim(),
                    query.artist?.trim().orEmpty(),
                    query.album?.trim().orEmpty(),
                ),
            )

        /**
         * 默认链：iTunes → kw → tx → wy → kg → mg（match.ts defaultProviders）
         */
        fun defaultProviders(http: ScrapeHttp): List<CoverProvider> = listOf(
            ItunesCoverProvider(http),
            KwCoverProvider(http),
            TxCoverProvider(http),
            WyCoverProvider(http),
            KgCoverProvider(http),
            MgCoverProvider(http),
        )

        /** 默认装配：默认链 + 默认负缓存 */
        fun withDefaultProviders(http: ScrapeHttp = ScrapeHttp()): CoverMatcher =
            CoverMatcher(defaultProviders(http))
    }
}
