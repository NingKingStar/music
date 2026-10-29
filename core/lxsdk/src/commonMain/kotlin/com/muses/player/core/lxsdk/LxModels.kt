package com.muses.player.core.lxsdk

import kotlinx.serialization.Serializable

/**
 * 洛雪自定义音源脚本的领域模型。
 *
 * 对应洛雪「自定义源脚本编写说明」：
 * - 脚本头部注释块的 `@name`/`@version` 等元信息；
 * - `inited` 事件上报的 `sources` 源信息对象。
 *
 * 本模块只做「脚本 → 直链/歌词/封面」的解析，不涉及曲库与播放。
 */

/** 洛雪支持的平台源 key（脚本可声明其一或多个） */
enum class LxPlatform(val key: String) {
    KW("kw"),
    KG("kg"),
    TX("tx"),
    WY("wy"),
    MG("mg"),
    LOCAL("local"),
    ;

    companion object {
        fun fromKey(key: String): LxPlatform? = entries.firstOrNull { it.key == key }
    }
}

/** 脚本可响应的 action 类型 */
enum class LxAction(val key: String) {
    MUSIC_URL("musicUrl"),
    LYRIC("lyric"),
    PIC("pic"),
    ;

    companion object {
        fun fromKey(key: String): LxAction? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 音质档位。
 *
 * 官方规范只列了 `128k/320k/flac/flac24bit` 四档，但**实际音源脚本普遍声明更多档位**
 * （实测「星海音乐源」声明 wy/tx/kg/kw 均含 `hires/atmos/atmos_plus/master`，tx 另有 `192k`）。
 * 若枚举不覆盖这些 key，用户就无法选用（旧实现只认 4 档，最多只能拿到 320k）。
 *
 * 约定：
 * - [key] 必须与脚本 `qualitys` 数组中的字面量完全一致（脚本按此查表）；
 * - [rank] 仅用于「请求档位不可用时」的**就近回退**排序，不代表音质高低的价值判断
 *   （例如 `master` 是母带重制、`hires` 是 24bit，两者不同维度，此处仅给一个稳定的比较序）；
 * - [label] 供 UI 展示。
 *
 * 注意：脚本自身还有一层 `mapQuality` 映射回退，故应用侧只需**如实传递**用户所选档位。
 */
enum class LxQuality(val key: String, val label: String, val rank: Int) {
    Q_128K("128k", "128k", 1),
    Q_192K("192k", "192k", 2),
    Q_320K("320k", "320k", 3),
    FLAC("flac", "无损", 4),
    FLAC_24BIT("flac24bit", "24bit", 5),
    HIRES("hires", "Hi-Res", 6),
    ATMOS("atmos", "全景声", 7),
    ATMOS_PLUS("atmos_plus", "全景声+", 8),
    MASTER("master", "母带", 9),
    ;

    /**
     * 是否为「高音质档」（无损及以上）。
     * UI 用于提示：这些档位可能返回加密容器（如酷我 `.mflac`/`.mgg` 需代理解密）
     * 或播放器不支持的编码，成功率低于 320k。
     */
    val isHighTier: Boolean get() = rank >= FLAC.rank

    companion object {
        fun fromKey(key: String): LxQuality? = if (key == "auto_best") MASTER else entries.firstOrNull { it.key == key }

        /**
         * 默认偏好档位。
         * 取 320k 而非最高档：320k 全平台可用、体积与兼容性最稳；
         * 更高档位可能返回加密容器或播放器不支持的编码，应由用户显式选择。
         */
        val DEFAULT: LxQuality = Q_320K

        /** 按 rank 升序（UI 展示顺序：从低到高） */
        fun ordered(): List<LxQuality> = entries.sortedBy { it.rank }
    }
}

/**
 * 脚本头部元信息（首个块注释里的 `@name` 等标签）。
 * 对应 `globalThis.lx.currentScriptInfo` 的可见字段。
 */
@Serializable
data class LxScriptMeta(
    val name: String? = null,
    val description: String? = null,
    val version: String? = null,
    val author: String? = null,
    val homepage: String? = null,
)

/** 脚本声明的单个源信息（`inited` 事件的 `sources[key]`） */
@Serializable
data class LxSourceDeclaration(
    /** 源 key（kw/kg/tx/wy/mg/local） */
    val platform: String,
    val name: String? = null,
    /** 类型，目前固定 `music` */
    val type: String = "music",
    /** 支持的 action 列表 */
    val actions: List<String> = emptyList(),
    /** 支持的音质列表（`local` 源为空） */
    val qualitys: List<String> = emptyList(),
) {
    fun supports(action: LxAction): Boolean = actions.contains(action.key)

    fun supports(quality: LxQuality): Boolean = qualitys.contains(quality.key)
}

/** 一次脚本初始化（`inited` 事件）的结果 */
@Serializable
data class LxScriptDescriptor(
    val meta: LxScriptMeta = LxScriptMeta(),
    /** 初始化上报的源声明，key = 源 key */
    val sources: Map<String, LxSourceDeclaration> = emptyMap(),
) {
    /** 脚本声明的平台枚举列表（过滤掉未知 key） */
    val platforms: List<LxPlatform>
        get() = sources.keys.mapNotNull(LxPlatform::fromKey)
}

/**
 * 直链解析结果。
 * [url] 为 HTTP(S) 播放地址；[quality] 为实际生效音质（供上层展示）。
 */
data class LxMusicUrl(
    val url: String,
    val quality: LxQuality? = null,
    val sourceName: String? = null,
)

/** 歌词解析结果（洛雪四类歌词字段，均可为 null） */
data class LxLyric(
    /** 原始歌词 */
    val lyric: String? = null,
    /** 翻译歌词 */
    val tlyric: String? = null,
    /** 罗马音歌词 */
    val rlyric: String? = null,
    /** 逐字歌词（形如 `[00:00.000]<0,36>测<36,36>试`） */
    val lxlyric: String? = null,
) {
    val isEmpty: Boolean
        get() = lyric.isNullOrBlank() && tlyric.isNullOrBlank() &&
            rlyric.isNullOrBlank() && lxlyric.isNullOrBlank()
}

/** 封面解析结果 */
data class LxPic(val url: String)

/**
 * 引擎错误类型。
 * 脚本执行失败、超时、源不支持等均经此暴露给上层，便于 UI 归类展示。
 */
sealed class LxException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** 脚本语法错误 / 初始化失败（未发送 inited） */
    class ScriptInitFailed(message: String, cause: Throwable? = null) : LxException(message, cause)

    /** 脚本运行时错误（handler 抛错） */
    class ScriptRuntimeError(message: String, cause: Throwable? = null) : LxException(message, cause)

    /** 请求超时 */
    class Timeout(message: String) : LxException(message)

    /** 源未声明 / 不支持该 action 或音质 */
    class UnsupportedRequest(message: String) : LxException(message)

    /** 引擎已关闭 */
    class Closed : LxException("音源引擎已关闭")
}
