package com.muses.player.core.model.online

import com.muses.player.core.model.Song
import com.muses.player.core.model.SourceType

/**
 * 在线曲目引用（[SourceType.ONLINE] 曲目的 [Song.path] 编码）。
 *
 * ## 为什么用 URI 编码而不是新增数据库列
 * `songs.path` 是既有列，草率改写语义会污染扫描器与去重逻辑；而新增列要动 Room
 * schema 与迁移。用带协议的 URI 把「在线曲目信息」完整装进既有 `path` 列，
 * **零 schema 变更**，且天然可辨识（`muslx://` 前缀）。
 *
 * ## 格式
 * ```
 * muslx://<platform>/<base64url(musicInfoJson)>?src=<sourceId>&q=<quality>
 * ```
 * - `platform`：洛雪源 key（kw/kg/tx/wy/mg/local）
 * - `musicInfoJson`：脚本所需的音乐信息对象（各源自有字段：songmid/songid/hash 等）
 * - `src`：Muses 音源 id（对应 `sources` 表，用于定位音源脚本）
 * - `q`：上次成功解析的音质（可选，用于回退策略与展示）
 *
 * 设计取舍：`musicInfoJson` 用 base64url 编码，避免 JSON 里的 `/`、`?`、`#`
 * 等字符破坏 URI 结构；base64url 无填充且不含 `+`/`/`，可安全内嵌。
 */
data class OnlineTrackRef(
    /** 洛雪源 key（kw/kg/tx/wy/mg/local） */
    val platform: String,
    /** 脚本所需的音乐信息对象 JSON（原样透传给脚本） */
    val musicInfoJson: String,
    /** Muses 侧音源 id（定位音源脚本） */
    val sourceId: String,
    /** 上次成功解析的音质（可空） */
    val quality: String? = null,
    /**
     * 本次播放/下载是否显式指定了 [quality]。
     *
     * 队列、歌单和下载记录里保存的 `quality` 多数只是「上次用过的档位」快照，
     * 新播放应跟随当前设置；只有用户在某次操作里明确选了档位才置 true，
     * 避免设置一刀切覆盖显式选择，也避免旧快照锁死音质。
     */
    val qualityExplicit: Boolean = false,
) {

    /** 编码为 `Song.path` 可存的 URI 字符串 */
    fun encode(): String {
        val encoded = Base64Url.encode(musicInfoJson.toByteArray(Charsets.UTF_8))
        val query = buildString {
            append("?src=").append(encodeComponent(sourceId))
            if (!quality.isNullOrBlank()) append("&q=").append(encodeComponent(quality))
            if (qualityExplicit) append("&qx=1")
        }
        return "$SCHEME://$platform/$encoded$query"
    }

    companion object {
        const val SCHEME = "muslx"

        /** 快速判定：字符串是否为在线曲目引用 */
        fun isOnlineUri(path: String?): Boolean =
            path != null && path.startsWith("$SCHEME://")

        /**
         * 解析 `Song.path` 为在线曲目引用；非在线 URI 或格式损坏时返回 null。
         * 宽松解析：缺 `src`/`q` 仍可还原（sourceId 由调用方从 Song 补齐）。
         */
        fun parse(path: String?): OnlineTrackRef? {
            if (!isOnlineUri(path)) return null
            val raw = path!!.removePrefix("$SCHEME://")
            val platform = raw.substringBefore('/').takeIf { it.isNotBlank() } ?: return null
            val rest = raw.substringAfter('/', "")
            val encodedPayload = rest.substringBefore('?').takeIf { it.isNotBlank() } ?: return null
            val query = rest.substringAfter('?', "")

            val musicInfoJson = runCatching {
                Base64Url.decode(encodedPayload).toString(Charsets.UTF_8)
            }.getOrNull() ?: return null

            fun param(key: String): String? = query
                .split('&')
                .firstOrNull { it.startsWith("$key=") }
                ?.substringAfter('=')
                ?.let { decodeComponent(it) }
                ?.takeIf { it.isNotBlank() }

            return OnlineTrackRef(
                platform = platform,
                musicInfoJson = musicInfoJson,
                sourceId = param("src") ?: "",
                quality = param("q"),
                qualityExplicit = param("qx") == "1",
            )
        }
    }
}

/** 在线曲目解析结果（异步换取到的可播放地址） */
data class OnlinePlayableUrl(
    /** HTTP(S) 直链，可直接交给播放器 */
    val url: String,
    /** 实际生效音质（供展示） */
    val quality: String? = null,
    val sourceName: String? = null,
    val requestedQuality: String? = null,
    /** 实际命中的曲目引用（跨平台回退时与入队引用不同，供封面/歌词跟随）。 */
    val resolvedRef: OnlineTrackRef? = null,
    /** 解析阶段耗时（毫秒），仅用于诊断；不包含完整地址或密钥。 */
    val timings: Map<String, Long> = emptyMap(),
)

/**
 * 在线曲目直链解析端口（双端播放器注入）。
 *
 * 播放链路在遇到 [SourceType.ONLINE] 曲目时调用本端口，异步换取 HTTP 直链。
 * 实现方（如 `:core:lxsdk` 的适配层）负责：定位音源脚本 → 调 `musicUrl` → 缓存与回源。
 *
 * 约定：
 * - 实现方应按 [priority] 选择音质（高音质优先，失败回退）；
 * - 直链**会过期**，播放失败时上层应重新调用本端口（不做长期缓存）；
 * - 抛错表示解析失败，由播放器归入既有失败恢复链。
 */
interface OnlineTrackResolver {
    /**
     * 解析在线曲目为可播放直链。
     *
     * @param ref 曲目引用（含 platform / musicInfoJson / sourceId）
     * @throws OnlineResolveException 解析失败（脚本未加载、源不支持、脚本报错、超时等）
     */
    suspend fun resolve(ref: OnlineTrackRef): OnlinePlayableUrl

    /**
     * 地址已被证实失效（403/404 或读取失败）。实现方应丢弃对应的短期复用，
     * 避免下一次请求继续拿到同一个坏地址。
     */
    fun invalidatePlayableUrl(url: String) {}

    /** 用户重试原来源时，只清理该歌曲的短期解析结果。 */
    fun invalidateReference(ref: OnlineTrackRef) {}

    /**
     * 地址已被证实无法播放（例如播放器无法解封装）。与 [invalidatePlayableUrl] 不同，
     * 实现方应在一个有界时间内拒绝再次使用该地址，促使下一次解析尝试其他脚本/档位。
     */
    fun rejectPlayableUrl(url: String) {}
}

/** 在线解析失败（与播放器既有失败恢复链对接） */
class OnlineResolveException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

// ── 内部：base64url 与 URI 组件编解码（commonMain 无 java.net，自行实现） ──

private object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(input: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i + 2 < input.size) {
            val b0 = input[i].toInt() and 0xFF
            val b1 = input[i + 1].toInt() and 0xFF
            val b2 = input[i + 2].toInt() and 0xFF
            sb.append(ALPHABET[b0 shr 2])
            sb.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 shr 4)])
            sb.append(ALPHABET[((b1 and 0x0F) shl 2) or (b2 shr 6)])
            sb.append(ALPHABET[b2 and 0x3F])
            i += 3
        }
        when (input.size - i) {
            1 -> {
                val b0 = input[i].toInt() and 0xFF
                sb.append(ALPHABET[b0 shr 2])
                sb.append(ALPHABET[(b0 and 0x03) shl 4])
            }
            2 -> {
                val b0 = input[i].toInt() and 0xFF
                val b1 = input[i + 1].toInt() and 0xFF
                sb.append(ALPHABET[b0 shr 2])
                sb.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 shr 4)])
                sb.append(ALPHABET[(b1 and 0x0F) shl 2])
            }
        }
        return sb.toString()
    }

    fun decode(input: String): ByteArray {
        val clean = input.trim().trimEnd('=')
        val out = ByteArray(clean.length * 3 / 4)
        var outIdx = 0
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) continue
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[outIdx++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return if (outIdx == out.size) out else out.copyOf(outIdx)
    }
}

/** URI 查询参数的最小转义（仅处理会破坏结构或需还原的字符） */
private fun encodeComponent(value: String): String = buildString {
    for (c in value) {
        when {
            c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' || c == '~' -> append(c)
            else -> {
                val bytes = c.toString().toByteArray(Charsets.UTF_8)
                bytes.forEach { b ->
                    append('%')
                    append(HEX[(b.toInt() shr 4) and 0x0F])
                    append(HEX[b.toInt() and 0x0F])
                }
            }
        }
    }
}

private fun decodeComponent(value: String): String {
    if (!value.contains('%')) return value
    val bytes = ArrayList<Byte>(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '%' && i + 2 < value.length) {
            val hex = value.substring(i + 1, i + 3)
            val v = hex.toIntOrNull(16)
            if (v != null) {
                bytes.add(v.toByte())
                i += 3
                continue
            }
        }
        bytes.addAll(c.toString().toByteArray(Charsets.UTF_8).toList())
        i++
    }
    return bytes.toByteArray().toString(Charsets.UTF_8)
}

private const val HEX = "0123456789ABCDEF"
