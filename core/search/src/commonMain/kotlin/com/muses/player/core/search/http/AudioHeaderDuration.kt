package com.muses.player.core.search.http

import com.muses.player.core.model.online.isTruncatedOnlineAudio

/** 只根据文件头中明确声明的时长判断，不用码率猜测，避免误拒绝变码率音频。 */
internal fun isTruncatedAudio(actualMs: Long?, expectedMs: Long?): Boolean =
    isTruncatedOnlineAudio(actualMs, expectedMs)

/**
 * 判断这段前缀是否具备当前播放器（Media3 默认 extractor）可识别的音频容器头。
 *
 * 只用于拒绝「已确定无法解封装」的内容，不做内容性质判断，也不依赖 Content-Type：
 * 部分平台对正常音频返回 `application/octet-stream`，而某些高档位返回的是无普通头的
 * 数据块；后者会让 extractor 报 code 3003，只能在接受地址前就拦下。
 *
 * 覆盖播放器默认支持的常见封装：FLAC / MP3（含 ID3 大标签）/ Ogg / WAV / MP4-M4A /
 * ADTS AAC / AC3 / AC4 / Matroska / AMR / MIDI / MPEG-TS。
 */
internal fun hasRecognizableAudioHeader(data: ByteArray, size: Int = data.size): Boolean {
    val length = minOf(size, data.size)
    if (length < 2) return false
    fun u(index: Int) = data[index].toInt() and 255
    fun tag(index: Int, value: String): Boolean = index >= 0 && index + value.length <= length &&
        value.indices.all { u(index + it) == value[it].code }

    if (tag(0, "fLaC")) return true
    if (tag(0, "ID3")) return true
    if (tag(0, "OggS")) return true
    if (tag(0, "RIFF") && tag(8, "WAVE")) return true
    if (tag(4, "ftyp")) return true
    if (tag(0, "#!AMR")) return true
    if (tag(0, "MThd")) return true
    // Matroska / WebM 的 EBML 头
    if (length >= 4 && u(0) == 0x1A && u(1) == 0x45 && u(2) == 0xDF && u(3) == 0xA3) return true
    // 默认 extractor 也支持原始 AC3/EAC3 和 AC4，不能把这些有效音频一并拒绝。
    if (u(0) == 0x0B && u(1) == 0x77) return true
    if (u(0) == 0xAC && u(1) in 0x40..0x41) return true
    // MP3 / ADTS AAC 帧同步（含无 ID3 标签的 MP3）
    if (u(0) == 0xFF && (u(1) and 0xE0) == 0xE0) return true
    // MPEG-TS：188 字节同步；要求至少三个同步字节，避免把单个 0x47 当容器
    if (length >= 377 && u(0) == 0x47 && u(188) == 0x47 && u(376) == 0x47) return true
    return false
}

/** 支持 FLAC STREAMINFO、MP3 Xing/Info/VBRI 和头部可见的 MP4 时长；未知格式放行。 */
internal fun audioHeaderDurationMs(data: ByteArray): Long? {
    fun u(index: Int) = data[index].toInt() and 255
    fun be(index: Int, size: Int): Long {
        var value = 0L
        repeat(size) { value = (value shl 8) or u(index + it).toLong() }
        return value
    }
    fun tag(index: Int, value: String): Boolean = index >= 0 && index + value.length <= data.size &&
        value.indices.all { u(index + it) == value[it].code }
    if (data.size >= 42 && tag(0, "fLaC") && u(4) and 127 == 0 && be(5, 3) == 34L) {
        val packed = be(18, 8)
        val rate = (packed ushr 44) and 0xfffff
        val samples = packed and 0xfffffffff
        if (rate > 0 && samples > 0) return samples * 1000 / rate
    }
    var start = 0
    if (data.size >= 10 && tag(0, "ID3")) {
        if ((6..9).any { u(it) >= 128 }) return null
        start = 10 + (u(6) shl 21) + (u(7) shl 14) + (u(8) shl 7) + u(9)
        if (u(5) and 16 != 0) start += 10
    }
    // 只扫描标签后第一帧附近，不能把歌词/封面中的偶然字节当成音频头。
    for (offset in start until minOf(start + 4096, data.size - 4)) {
        if (u(offset) != 255 || u(offset + 1) and 224 != 224) continue
        val version = (u(offset + 1) shr 3) and 3
        if (version == 1 || (u(offset + 1) shr 1) and 3 != 1) continue
        val rateIndex = (u(offset + 2) shr 2) and 3
        val bitrateIndex = u(offset + 2) shr 4
        if (rateIndex == 3 || bitrateIndex == 0 || bitrateIndex == 15) continue
        val rate = intArrayOf(44100, 48000, 32000)[rateIndex] / when (version) { 3 -> 1; 2 -> 2; else -> 4 }
        val mono = u(offset + 3) shr 6 == 3
        val side = if (version == 3) { if (mono) 17 else 32 } else { if (mono) 9 else 17 }
        val crc = if (u(offset + 1) and 1 == 0) 2 else 0
        val xing = offset + 4 + crc + side
        val frames = when {
            (tag(xing, "Xing") || tag(xing, "Info")) && xing + 12 <= data.size &&
                be(xing + 4, 4) and 1L != 0L -> be(xing + 8, 4)
            tag(offset + 36, "VBRI") && offset + 54 <= data.size -> be(offset + 50, 4)
            else -> 0
        }
        if (frames > 0) return frames * (if (version == 3) 1152 else 576) * 1000 / rate
        break
    }
    fun mp4(startOffset: Int, end: Int, depth: Int): Long? {
        if (depth > 4) return null
        var offset = startOffset
        while (offset + 8 <= end) {
            var size = be(offset, 4)
            var header = 8
            if (size == 1L) {
                if (offset + 16 > end) return null
                size = be(offset + 8, 8)
                header = 16
            }
            if (size == 0L) size = (end - offset).toLong()
            if (size < header || size > end - offset) return null
            val body = offset + header
            val boxEnd = offset + size.toInt()
            if (tag(offset + 4, "mvhd") || tag(offset + 4, "mdhd")) {
                if (body + 20 <= boxEnd) {
                    val version = u(body)
                    val timeOffset = body + if (version == 1) 20 else 12
                    val durationSize = if (version == 1) 8 else 4
                    if (version in 0..1 && timeOffset + 4 + durationSize <= boxEnd) {
                        val rate = be(timeOffset, 4)
                        val duration = be(timeOffset + 4, durationSize)
                        if (rate > 0 && duration > 0 && duration <= Long.MAX_VALUE / 1000 &&
                            !(version == 0 && duration == 0xffffffffL)) return duration * 1000 / rate
                    }
                }
            } else if (tag(offset + 4, "moov") || tag(offset + 4, "trak") || tag(offset + 4, "mdia")) {
                mp4(body, boxEnd, depth + 1)?.let { return it }
            }
            offset = boxEnd
        }
        return null
    }
    return if (tag(4, "ftyp")) mp4(0, data.size, 0) else null
}
