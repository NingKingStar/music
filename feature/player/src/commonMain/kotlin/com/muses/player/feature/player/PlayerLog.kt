package com.muses.player.feature.player

/**
 * 播放页轻量诊断日志。
 *
 * 为什么不是 println：Android 上 stdout 默认不进 logcat，release 包里 println 等于静默丢弃，
 * 之前几次“加了日志却看不到”就是这个原因。这里用平台原生日志接口。
 */
expect fun playerLog(message: String)
