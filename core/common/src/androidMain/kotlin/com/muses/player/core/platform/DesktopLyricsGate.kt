package com.muses.player.core.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * 桌面歌词的悬浮窗授权跳转与「待开启」标记。
 *
 * 通知栏按钮、播放页按钮、歌词设置弹层都可能在没授权时被点按，统一走这里，
 * 避免只是弹一句「请先去设置里允许」把用户丢回设置页自己找。
 *
 * 流程：点按 → [markPending] + [openPermissionPage] → 用户授权 → 播放服务里的监视协程
 * 发现权限已给且存在待开启标记时，自动把开关打开。
 */
object DesktopLyricsGate {
    private const val PREFS = "desktop_lyrics_window"
    private const val KEY_PENDING = "pending_enable"

    fun canOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun markPending(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PENDING, true).apply()
    }

    fun pending(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PENDING, false)

    fun clearPending(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_PENDING).apply()
    }

    /** 直接打开本应用的「显示在其他应用上层」授权页；启动失败返回 false，由调用方给可读提示。 */
    fun openPermissionPage(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess
}
