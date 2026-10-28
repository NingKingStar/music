package com.muses.player.core.model.online

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 在线曲目身份：把「同一平台、同一音源、同一首歌」从 musicInfoJson 里稳定地识别出来。
 *
 * 存在的意义：下载记录早期用原始 JSON 字符串全等匹配，封面链接或字段顺序一变就失配。
 * 这里优先使用平台稳定歌曲 ID（songmid / songid / hash / copyrightId 等），
 * 只有拿不到稳定 ID 时才退回「规范化 JSON」的保守比较。
 */
object OnlineTrackIdentity {

    /** 平台稳定 ID 字段，按可靠性排序；`id` 放最后，避免误取专辑/歌单 ID。 */
    private val STABLE_ID_KEYS = listOf(
        "songmid", "songId", "songid", "musicId", "mediaId", "trackId",
        "copyrightId", "rid", "hash", "mid", "id",
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 返回形如 `songmid=abc` 的稳定标识；无法判定时返回 null。 */
    fun stableId(ref: OnlineTrackRef): String? = runCatching {
        val obj = json.parseToJsonElement(ref.musicInfoJson) as? JsonObject ?: return@runCatching null
        for (key in STABLE_ID_KEYS) {
            val value = (obj[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: continue
            return@runCatching "$key=$value"
        }
        null
    }.getOrNull()

    /** 排序后的规范 JSON：字段顺序不同不影响比较，但不忽略新增字段（保守）。 */
    fun canonicalJson(ref: OnlineTrackRef): String = runCatching {
        canonical(json.parseToJsonElement(ref.musicInfoJson))
    }.getOrDefault(ref.musicInfoJson)

    /**
     * 索引键：稳定 ID 与规范化 JSON 各一份，供不同来源的记录互相命中。
     * 键里始终带平台和音源，避免跨音源误配。
     */
    fun matchKeys(ref: OnlineTrackRef): List<String> {
        val prefix = "${ref.platform}|${ref.sourceId}|"
        val stable = stableId(ref)
        val canonical = canonicalJson(ref)
        return if (stable == null) listOf(prefix + canonical)
        else listOf(prefix + stable, prefix + canonical)
    }

    /** 有稳定 ID 时按稳定 ID 比较；缺失时退回规范化 JSON 全等。 */
    fun matches(left: OnlineTrackRef, right: OnlineTrackRef): Boolean {
        if (left.platform != right.platform || left.sourceId != right.sourceId) return false
        val leftId = stableId(left)
        val rightId = stableId(right)
        if (leftId != null && rightId != null) return leftId == rightId
        return canonicalJson(left) == canonicalJson(right)
    }

    private fun canonical(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries.sortedBy { it.key }
            .joinToString(",", "{", "}") { (key, value) ->
                JsonPrimitive(key).toString() + ":" + canonical(value)
            }
        is JsonArray -> element.joinToString(",", "[", "]") { canonical(it) }
        else -> element.toString()
    }
}
