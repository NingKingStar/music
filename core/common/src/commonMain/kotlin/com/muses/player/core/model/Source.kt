package com.muses.player.core.model

/** 音源类型 */
@kotlinx.serialization.Serializable
enum class SourceType {
    /** 本地目录（SAF tree uri / 媒体库路径前缀） */
    LOCAL,

    /** WebDAV 服务 */
    WEBDAV,

    /**
     * 在线音源（洛雪自定义源脚本）。
     * 曲目的 [Song.path] 不指向实体文件，而是 [com.muses.player.core.model.online.OnlineTrackUri]
     * 编码的「可解析引用」，播放前需经 [com.muses.player.core.model.online.OnlineTrackResolver]
     * 异步换取 HTTP 直链。
     */
    ONLINE,
}

/**
 * 音源配置。
 * url：WebDAV 服务基地址（type=WEBDAV 时必填）；path：本地目录地址（type=LOCAL 时必填）。
 * 密码不落本对象——经 [com.muses.player.core.data.repository.CredentialsRepository] Keystore 加密单独存取。
 */
data class Source(
    val id: String,
    val name: String,
    val type: SourceType,
    val url: String? = null,
    val path: String? = null,
    /** WebDAV 登录名（type=WEBDAV；密码仍走 CredentialsRepository） */
    val username: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
