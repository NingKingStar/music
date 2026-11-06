package com.muses.player.feature.shell.platform

/**
 * 「我的 → 检查更新」这一行的副标题文案。
 *
 * 关键点：用户点过「不再提示」只是不再主动弹窗，新版本这件事仍然要在这里说清楚，
 * 否则入口看起来像"什么都查不到"，用户会以为没有更新。
 */
internal fun updateHintText(version: String?, ready: Boolean, ignored: String?): String? {
    if (version.isNullOrBlank()) return null
    if (ready) return "新版已下载，等待安装"
    return if (ignored == version) "$version 有新版本（已关闭提醒）" else "有新版本 $version"
}
