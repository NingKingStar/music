package com.muses.player.core.search.provider

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 平台目录的最高音质；不代表脚本解析后实际播放的音质。 */
internal fun catalogMetadata(platform: String, item: JsonObject): Map<String, String> = buildMap {
    val quality = when (platform) {
        "tx" -> item.obj("file")?.let { file ->
            when {
                (file.long("size_hires") ?: 0) > 0 -> "Hi-Res"
                (file.long("size_flac") ?: 0) > 0 -> "SQ"
                (file.long("size_320mp3") ?: 0) > 0 -> "HQ"
                (file.long("size_128mp3") ?: 0) > 0 -> "标准"
                else -> null
            }
        }
        "kg" -> {
            fun available(hash: String, size: String): Boolean = item.str(hash)
                ?.let { it != "0" && it.any { char -> char != '0' } } == true &&
                (item.long(size)?.let { it > 0 } ?: true)
            when {
                available("ResFileHash", "ResFileSize") -> "Hi-Res"
                available("SQFileHash", "SQFileSize") -> "SQ"
                available("HQFileHash", "HQFileSize") -> "HQ"
                available("FileHash", "FileSize") -> "标准"
                else -> null
            }
        }
        "kw" -> {
            val bitrates = Regex("bitrate:(\\d+),format:\\w+,size:([\\w.]+)")
                .findAll(item.str("N_MINFO").orEmpty()).mapNotNull { match ->
                    val size = match.groupValues[2].takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull()
                    match.groupValues[1].toIntOrNull()?.takeIf { size != null && size > 0 }
                }.toSet()
            when {
                4000 in bitrates -> "Hi-Res"
                2000 in bitrates -> "SQ"
                320 in bitrates -> "HQ"
                bitrates.any { it == 128 || it == 192 } -> "标准"
                else -> null
            }
        }
        "mg" -> {
            val formats = listOf("newRateFormats", "rateFormats", "audioFormats").flatMap { key ->
                item.arr(key).orEmpty().mapNotNull { it as? JsonObject }
            }.filter { format ->
                listOf("size", "androidSize", "asize", "isize").any { (format.long(it) ?: 0) > 0 }
            }.mapNotNull { it.str("formatType") }.toSet()
            when {
                "ZQ" in formats -> "Hi-Res"
                "SQ" in formats -> "SQ"
                "HQ" in formats -> "HQ"
                "PQ" in formats || "LQ" in formats -> "标准"
                else -> null
            }
        }
        else -> null
    }
    quality?.let { put("catalogQuality", it) }
    // 只保存目录中明确出现的档位；缺失字段并不能证明其他档位不存在。
    val tiers = when (platform) {
        "tx" -> item.obj("file")?.let { file ->
            listOf("128k" to "size_128mp3", "320k" to "size_320mp3", "flac" to "size_flac", "hires" to "size_hires")
                .filter { (_, field) -> (file.long(field) ?: 0) > 0 }.map { it.first }
        }.orEmpty()
        "kw" -> {
            val bitrates = Regex("bitrate:(\\d+),format:\\w+,size:([\\w.]+)")
                .findAll(item.str("N_MINFO").orEmpty()).mapNotNull { match ->
                    val size = match.groupValues[2].takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull()
                    match.groupValues[1].toIntOrNull()?.takeIf { size != null && size > 0 }
                }.toSet()
            listOf(128 to "128k", 192 to "192k", 320 to "320k", 2000 to "flac", 4000 to "hires")
                .filter { it.first in bitrates }.map { it.second }
        }
        else -> emptyList()
    }
    if (tiers.isNotEmpty()) put("catalogQualityTiers", tiers.joinToString(","))
    // 只接受平台明确给出的原唱文字标签，不从歌曲名、版权或歌手信息推断。
    val tags = item.arr("tags").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
    if ("原唱" in tags) put("catalogPerformance", "original")
}
