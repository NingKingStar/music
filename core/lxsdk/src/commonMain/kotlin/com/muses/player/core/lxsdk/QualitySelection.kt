package com.muses.player.core.lxsdk

/** 用户档位与脚本参数分离；旧设置仍可读取，隐藏的脚本档位保留解析能力。 */
data class QualitySelection(val key: String, val label: String)

val musicQualitySelections = listOf(
    QualitySelection("auto_best", "自动最佳"),
    QualitySelection("128k", "标准"),
    QualitySelection("320k", "HQ"),
    QualitySelection("flac", "无损"),
    QualitySelection("master", "Hi-Res"),
)
val spatialQualitySelections = listOf(QualitySelection("atmos", "全景声"), QualitySelection("atmos_plus", "全景声+"))

fun qualitySelectionKey(key: String): String = when (key) {
    "192k" -> "128k"
    "hires", "flac24bit" -> "master"
    else -> key
}
fun qualitySelectionLabel(key: String): String = (musicQualitySelections + spatialQualitySelections)
    .firstOrNull { it.key == qualitySelectionKey(key) }?.label ?: "音质待确认"

/** 空间音频是独立类型；普通立体声回退不得夹入空间混音。 */
fun fallbackQualities(requested: LxQuality): List<LxQuality> = LxQuality.ordered()
    .filter { it.rank <= requested.rank && (requested in listOf(LxQuality.ATMOS, LxQuality.ATMOS_PLUS) || it !in listOf(LxQuality.ATMOS, LxQuality.ATMOS_PLUS)) }
    .sortedByDescending { it.rank }
