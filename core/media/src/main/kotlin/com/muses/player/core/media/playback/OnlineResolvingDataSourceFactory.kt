package com.muses.player.core.media.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import com.muses.player.core.model.online.OnlineTrackIdentity
import com.muses.player.core.model.online.OnlineTrackRef
import com.muses.player.core.model.online.OnlineTrackResolver
import com.muses.player.core.model.online.OnlinePlaybackHttp
import kotlin.time.TimeSource
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

internal class OnlineResourceChangedException : IOException("在线资源已变化，需要重新初始化播放")

/**
 * 解析阶段失败（音源无可用地址/超时）。标记出来是为了让媒体层快速失败，
 * 不再被 Media3 的默认重试策略重复解析三次（每次几十秒），而是进入
 * 「排除当前地址后同曲重建一次」的有界恢复。
 */
internal class OnlineResolveFailedException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

internal fun onlineCacheKey(url: String): String = "online-v2:" +
    MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun matchesDownloadedRef(ref: OnlineTrackRef, saved: OnlineTrackRef?): Boolean =
    saved != null && OnlineTrackIdentity.matches(ref, saved)

/**
 * 在线曲目直链解析数据源：MediaItem 保留 `muslx://` 引用，
 * 在 **DataSource 真正打开时才**换取 HTTP 直链。
 *
 * ## 为什么必须这样做（而不是入队前批量解析）
 * 初版实现在入队前对**整队**逐首解析，实测 20 首队列需串行等待约 30 秒才开始播放
 * （每首含一次脚本调用 + 一次网络请求），用户点播放后长期无反应 = 功能不可用。
 * 且在线直链**带时效签名**，预解析的地址在真正播到时可能已失效。
 *
 * 交给 Media3 的 [ResolvingDataSource] 后：
 * - 点播放立即出声（只为当前曲解析一次）；
 * - 同一数据源短时间复用成功直链；失败或超时后重新解析，兼顾连续读取与过期恢复；
 * - 队列语义与本地曲目完全一致（MediaItem 仍持有稳定 `muslx://` 标识）。
 *
 * ## 资源身份与恢复边界
 * 数据源按「解码会话」维护资源身份：一旦本会话真的把数据交给解码器，后续任何换地址
 * 都必须走播放器重建（时间定位恢复），绝不允许在原位置直接换源——否则会把两段不同
 * 资源的字节拼在一起。只有整个会话尚未消费任何字节时，本地文件打开失败才可以直接
 * 转在线，省掉一次无意义的重建。
 *
 * 线程：`resolveDataSpec` 在 Media3 的加载线程调用（非主线程），
 * 故可用 [runBlocking] 桥接 suspend 解析器；带超时兜底避免加载线程被脚本挂死。
 */
class OnlineResolvingDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
    private val resolver: OnlineTrackResolver,
    /** 单次解析超时；略大于解析器内部总上限，超时视为打开失败，交 Media3 播放错误链处理 */
    private val resolveTimeoutMs: Long = 35_000L,
    /** 解析失败/超时的上报（供 R2 埋点） */
    private val onResolveError: (trackUri: String, error: Throwable?) -> Unit = { _, _ -> },
    private val findDownloaded: suspend (OnlineTrackRef) -> String? = { null },
    /** 解析耗时诊断（不含完整地址）；默认不做事。 */
    private val onResolveTiming: (trackUri: String, elapsedMs: Long, local: Boolean, stages: Map<String, Long>) -> Unit =
        { _, _, _, _ -> },
    /** 成功打开的地址（供播放器在解封装失败时做有界排除）。 */
    private val onOpenedUrl: (trackUri: String, url: String) -> Unit = { _, _ -> },
) : DataSource.Factory {
    private val rejectedDownloads = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun isLocalUrl(url: String) = !url.startsWith("http://", true) && !url.startsWith("https://", true)

    private fun now() = android.os.SystemClock.elapsedRealtime()

    private fun rejectLocal(url: String?) {
        url?.takeIf(::isLocalUrl)?.let { rejectedDownloads[it] = now() }
    }

    override fun createDataSource(): DataSource {
        // 以下状态只属于当前 DataSource，即一次解码会话。
        var cachedTrack: String? = null
        var cachedUrl: String? = null
        var cachedResult: com.muses.player.core.model.online.OnlinePlayableUrl? = null
        var previousTrack: String? = null
        var previousUrl: String? = null
        /** 本会话已经交给解码器的字节数：>0 后不允许原地换源。 */
        var deliveredBytes = 0L
        /** 当前打开的资源地址（失败时用于让解析复用失效）。 */
        var openedUri: String? = null
        val upstream = upstreamFactory.createDataSource()
        val guarded = object : DataSource by upstream {
            override fun open(dataSpec: DataSpec): Long {
                // 先记下本次真正尝试的地址：打开失败时能精确让对应直链失效。
                openedUri = dataSpec.uri.toString()
                return try {
                    upstream.open(dataSpec).also {
                        val trackUri = cachedTrack
                        if (trackUri != null) onOpenedUrl(trackUri, dataSpec.uri.toString())
                        val ref = OnlineTrackRef.parse(trackUri)
                        val result = cachedResult
                        if (ref != null && result != null && dataSpec.uri.toString() == result.url) {
                            com.muses.player.core.model.online.OnlineTrackSession.rememberPlayback(ref, result, isLocalUrl(result.url))
                        }
                    }
                } catch (e: IOException) {
                    // 地址已证实不可用：让短期解析复用丢弃它，下一次重新解析。
                    resolver.invalidatePlayableUrl(openedUri.orEmpty())
                    val failedLocal = cachedUrl?.takeIf(::isLocalUrl)
                    rejectLocal(failedLocal)
                    // 只有本会话尚未读出任何字节，才允许本地→在线直接切换；
                    // 已消费过数据时必须保留身份，触发播放器重建并按时间恢复位置。
                    if (failedLocal != null && deliveredBytes == 0L) {
                        previousTrack = null
                        previousUrl = null
                    }
                    cachedTrack = null
                    cachedUrl = null
                    cachedResult = null
                    throw e
                }
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
                val read = upstream.read(buffer, offset, length)
                if (read > 0) deliveredBytes += read
                read
            } catch (e: IOException) {
                openedUri?.let(resolver::invalidatePlayableUrl)
                rejectLocal(cachedUrl)
                cachedTrack = null
                cachedUrl = null
                cachedResult = null
                throw e
            }
        }
        return ResolvingDataSource(guarded) { dataSpec ->
            val trackUri = dataSpec.uri.toString()
            val ref = OnlineTrackRef.parse(trackUri) ?: return@ResolvingDataSource dataSpec
            val reusable = cachedUrl?.takeIf { cachedTrack == trackUri }
            if (reusable != null) {
                return@ResolvingDataSource dataSpec.buildUpon().setUri(Uri.parse(reusable))
                    .setKey(onlineCacheKey(reusable)).build()
                    .withRequestHeaders(OnlinePlaybackHttp.requestHeaders(dataSpec.httpRequestHeaders))
            }
            val startedAt = TimeSource.Monotonic.markNow()
            var findMs = 0L
            var localHit = false
            val resolved = try {
                runBlocking { withTimeoutOrNull(resolveTimeoutMs) {
                    val findStarted = TimeSource.Monotonic.markNow()
                    val local = findDownloaded(ref)?.takeUnless { location ->
                        rejectedDownloads[location]?.let { now() - it < 30_000L } == true
                    }
                    findMs = findStarted.elapsedNow().inWholeMilliseconds
                    localHit = local != null
                    local?.let { com.muses.player.core.model.online.OnlinePlayableUrl(it, sourceName = "本地下载") }
                        ?: resolver.resolve(ref)
                } }
            } catch (e: InterruptedException) {
                // Media3 取消加载会中断线程；保留中断语义，不作为音源故障上报。
                Thread.currentThread().interrupt()
                throw InterruptedIOException("在线直链加载已中断").apply { initCause(e) }
            } catch (e: CancellationException) {
                throw InterruptedIOException("在线直链加载已取消").apply { initCause(e) }
            } catch (e: Exception) {
                onResolveError(trackUri, e)
                throw OnlineResolveFailedException("在线音源直链解析失败：${e.message ?: "未知错误"}", e)
            }
            if (resolved == null) {
                val timeout = IOException("在线音源直链解析超时（${resolveTimeoutMs} ms）")
                onResolveError(trackUri, timeout)
                throw OnlineResolveFailedException(timeout.message ?: "解析超时", timeout)
            }
            if (previousTrack == trackUri && previousUrl != null && previousUrl != resolved.url) {
                throw OnlineResourceChangedException()
            }
            previousTrack = trackUri
            previousUrl = resolved.url
            cachedTrack = trackUri
            cachedUrl = resolved.url
            cachedResult = resolved
            val elapsed = startedAt.elapsedNow().inWholeMilliseconds
            onResolveTiming(
                trackUri, elapsed, localHit,
                resolved.timings + mapOf("findDownloaded" to findMs, "dataSource" to elapsed),
            )
            dataSpec.buildUpon().setUri(Uri.parse(resolved.url)).setKey(onlineCacheKey(resolved.url)).build()
                .withRequestHeaders(OnlinePlaybackHttp.requestHeaders(dataSpec.httpRequestHeaders))
        }
    }
}
