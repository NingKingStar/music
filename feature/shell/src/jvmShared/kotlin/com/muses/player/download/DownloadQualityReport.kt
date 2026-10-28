package com.muses.player.download

import com.muses.player.core.lxsdk.LxQuality
import com.muses.player.core.media.scanner.DownloadAudioQuality

internal data class DownloadQualityReport(val label: String, val warning: String?)

/** 音源脚本的档位只是请求参数，完成提示必须以实际音频头为准。 */
internal fun downloadQualityReport(
    selected: LxQuality,
    format: String,
    actual: DownloadAudioQuality?,
): DownloadQualityReport {
    val container = format.uppercase()
    if (actual == null) return DownloadQualityReport("$container（音质未核实）",
        "实际文件为 $container，无法核实音质，不能确认已达到所选「${selected.label}」")
    val detail = if (actual.lossless)
        "无损 · ${actual.bitDepth} bit / ${actual.sampleRate} Hz"
    else "有损 · ${actual.bitrate} kbps"
    val label = "$container · $detail"
    val mismatch = when (selected) {
        LxQuality.FLAC -> !actual.lossless
        LxQuality.FLAC_24BIT, LxQuality.HIRES -> !actual.lossless || actual.bitDepth < 24
        LxQuality.Q_128K, LxQuality.Q_192K, LxQuality.Q_320K ->
            !actual.lossless && actual.bitrate < selected.key.removeSuffix("k").toLong() * 0.9
        // 母带：文件头不能证明来源，但明显降级的有损/超低采样率不能当达标；
        // 不要求 192kHz（母带也可以是 44.1/48kHz），只排除明显不符合的情况。
        LxQuality.MASTER -> !actual.lossless || actual.sampleRate < 44_100
        // 全景声的交付格式常为有损，不能仅凭 lossless 判断，保持不臆断。
        else -> false
    }
    return DownloadQualityReport(label, if (mismatch)
        "音质未达到所选「${selected.label}」，实际下载为 $label" else null)
}
