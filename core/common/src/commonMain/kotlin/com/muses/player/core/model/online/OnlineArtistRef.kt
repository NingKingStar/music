package com.muses.player.core.model.online

import kotlinx.serialization.Serializable

/** 平台内歌手身份，与歌曲编号分离；不按标点拆分乐队名。 */
@Serializable
data class OnlineArtistRef(val platform: String, val id: String, val name: String, val avatar: String? = null)
