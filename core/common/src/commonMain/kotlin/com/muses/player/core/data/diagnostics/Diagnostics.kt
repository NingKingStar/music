package com.muses.player.core.data.diagnostics

/** 观测失败始终与业务隔离；未安装时为空操作。字段仅接受结构化数值和受控枚举。 */
object Diagnostics {
    private class Correlation(val fields: Map<String, String>) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<Correlation>
    }
    suspend fun correlation(): Map<String, String> = kotlin.coroutines.coroutineContext[Correlation]?.fields ?: mapOf("session_basis" to "unknown", "session" to "未知", "song" to "未知")
    suspend fun <T> withCorrelation(fields: Map<String, String>, block: suspend () -> T): T =
        kotlinx.coroutines.withContext(Correlation(fields)) { block() }

    var trackBinding: ((String, String) -> Unit)? = null
    var songContextProvider: ((String) -> Map<String, String>)? = null
    fun contextForSong(songId: String?): Map<String, String> = try {
        if (songId == null) mapOf("session_basis" to "unknown", "session" to "未知", "song" to "未知")
        else songContextProvider?.invoke(songId) ?: mapOf("session_basis" to "unknown", "session" to "未知", "song" to anonymous(songId))
    } catch (_: Throwable) { mapOf("session_basis" to "unknown", "session" to "未知", "song" to "未知") }
    var capturedTrackContextProvider: ((String, Map<String, String>) -> Map<String, String>)? = null
    fun contextForTrack(trackUri: String, candidate: Map<String, String>): Map<String, String> = try {
        capturedTrackContextProvider?.invoke(trackUri, candidate)
            ?: mapOf("session_basis" to "unknown", "session" to "未知", "song" to (contextForTrack(trackUri)["song"] ?: anonymous(trackUri)))
    } catch (_: Throwable) { mapOf("session_basis" to "unknown", "session" to "未知", "song" to anonymous(trackUri)) }
    var trackContextProvider: ((String) -> Map<String, String>)? = null
    fun bindTrack(trackUri: String, songId: String) { try { trackBinding?.invoke(trackUri, songId) } catch (_: Throwable) { } }
    fun contextForTrack(trackUri: String): Map<String, String> = try {
        trackContextProvider?.invoke(trackUri) ?: mapOf("session_basis" to "unknown", "session" to "未知", "song" to anonymous(trackUri))
    } catch (_: Throwable) { mapOf("session_basis" to "unknown", "session" to "未知", "song" to anonymous(trackUri)) }
    var sink: ((String, Map<String, String>) -> Unit)? = null
    var identify: ((String) -> String)? = null
    var contextProvider: (() -> Map<String, String>)? = null
    fun context(): Map<String, String> = try { contextProvider?.invoke() ?: emptyMap() } catch (_: Throwable) { emptyMap() }
    var trackTransition: ((String) -> Unit)? = null
    fun transition(songId: String) { try { trackTransition?.invoke(songId) } catch (_: Throwable) { } }
    var startPlayback: ((String, String) -> Unit)? = null
    fun record(event: String, fields: Map<String, String> = emptyMap()) {
        try { sink?.invoke(event, fields) } catch (_: Throwable) { }
    }
    fun anonymous(value: String): String = try { identify?.invoke(value) ?: "未知" } catch (_: Throwable) { "未知" }
    fun beginPlayback(songId: String, origin: String) {
        try { startPlayback?.invoke(songId, origin) } catch (_: Throwable) { }
    }
}
