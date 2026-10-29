package com.muses.player.core.model

/** 从音频头判断文件音质，不从文件名推断码率或位深。 */
fun classifyAudioQuality(lossless: Boolean, bitDepth: Int, sampleRate: Int, bitrateKbps: Long): String? = when {
    lossless && (bitDepth > 16 || sampleRate > 48_000) -> "Hi-Res"
    lossless -> "无损"
    bitrateKbps >= 256 -> "HQ"
    bitrateKbps > 0 -> "标准"
    else -> null
}

/** 尚未读取音频头时只展示已知文件格式，不能据此标为高音质。 */
val Song.libraryQualityLabel: String?
    get() = shortAudioQualityLabel(audioQuality) ?: path.substringBefore('?').substringBefore('#').substringAfterLast('.')
        .lowercase().takeIf { it in setOf("flac", "ape", "wav", "aiff", "aif", "mp3", "aac", "m4a", "ogg", "opus", "wma") }
        ?.uppercase()

/** 兼容旧下载记录的详细规格，列表不挤占歌手与歌名；完整原文仍保留供详情展示。 */
fun shortAudioQualityLabel(raw: String?): String? {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (text.contains("未核实") || text.contains("未确认")) return text.substringBefore('（').substringBefore(" · ").take(8)
    if (text == "Hi-Res" || text == "HR") return "Hi-Res"
    if (text == "SQ" || text == "无损") return "无损"
    if (text == "HQ" || text == "标准") return text
    val bits = Regex("(\\d+)\\s*bit", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
    val lossless = "无损" in text && "有损" !in text
    val sampleRate = Regex("(\\d+)\\s*Hz", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    if (lossless) return classifyAudioQuality(true, bits ?: 0, sampleRate, 0)
    val rate = Regex("(\\d+)\\s*kbps", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
    if (rate != null) return if (rate >= 256) "HQ" else "标准"
    return text.take(8)
}
