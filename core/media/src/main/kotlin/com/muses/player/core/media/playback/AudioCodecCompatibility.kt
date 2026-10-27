package com.muses.player.core.media.playback

/** 仅针对已复现音频缓冲异常的设备启用同步解码兼容模式。 */
internal fun requiresSynchronousCodec(manufacturer: String, model: String, sdk: Int): Boolean =
    sdk >= 37 && manufacturer.equals("OnePlus", ignoreCase = true) && model.equals("PJZ110", ignoreCase = true)
