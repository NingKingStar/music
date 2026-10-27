package com.muses.player.core.appupdate

/**
 * 更新检查指向的发布仓库。
 *
 * 安卓与 Windows 桌面共用同一处定义：仓库迁移时只改 [RELEASE_REPOSITORY_SLUG]，
 * 避免两端的「检查更新」地址各自漂移、悄悄查着失效的旧仓库。
 */
internal const val RELEASE_REPOSITORY_SLUG = "NingKingStar/music"

/** GitHub REST：最新 Release 元数据 */
internal const val LATEST_RELEASE_API_URL =
    "https://api.github.com/repos/$RELEASE_REPOSITORY_SLUG/releases/latest"

/** 仓库主页，用于 User-Agent 等展示用途 */
internal const val REPOSITORY_URL = "https://github.com/$RELEASE_REPOSITORY_SLUG"
