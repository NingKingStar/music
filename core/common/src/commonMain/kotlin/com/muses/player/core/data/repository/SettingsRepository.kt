package com.muses.player.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine

/** 原文可单独显示，译文和读音只能选择其中一种。 */
enum class LyricSupplementMode { Original, Translation, Romanization }

/** 应用设置（DataStore Preferences） */
interface SettingsRepository {
    /** 是否由播放器管理音频焦点；默认关闭，允许与其他应用同时播放。 */
    val audioFocusEnabled: Flow<Boolean>
    suspend fun setAudioFocusEnabled(enabled: Boolean)

    /** 使用应用内置的免费 LX 音源，默认开启。 */
    val builtinLxSourcesEnabled: Flow<Boolean>
    suspend fun setBuiltinLxSourcesEnabled(enabled: Boolean)
    /** 空串为自动选择，@builtin 为内置优先，其他值为用户选中的脚本 ID。 */
    val preferredLxSource: Flow<String> get() = kotlinx.coroutines.flow.flowOf("")
    suspend fun setPreferredLxSource(sourceId: String) {}
    /** 上次完成扫描的时间戳（epoch millis，0 = 从未扫描） */
    val lastScanTimestamp: Flow<Long>

    /** M3 自动补缺：扫描入库后对无标签歌曲自动加入刮削队列（默认关，DataStore 手动改） */
    val autoScrapeEnabled: Flow<Boolean>

    /** 播放控件（底部迷你条）歌词开关：开启后迷你条第二行显示当前歌词而非艺术家 */
    val miniPlayerLyricsEnabled: Flow<Boolean>

    /** 媒体通知歌词开关：开启后通知标题显示歌词，艺术家显示「标题-艺术家」 */
    val notificationLyricsEnabled: Flow<Boolean>
    /** 桌面悬浮歌词，默认关闭。 */
    val desktopLyricsEnabled: Flow<Boolean>
    val desktopLyricsLocked: Flow<Boolean>
    val desktopLyricsFontSize: Flow<Long>
    /** 0 表示跟随原有配色，其他值为用户选择的 ARGB 颜色。 */
    val desktopLyricsColor: Flow<Long>
    suspend fun setDesktopLyricsLocked(locked: Boolean)
    suspend fun setDesktopLyricsFontSize(size: Long)
    suspend fun setDesktopLyricsColor(color: Long)

    /** 沉浸式播放页的文字与图标使用封面协调色（默认开启） */
    val coverContentColorEnabled: Flow<Boolean>

    /** 沉浸式播放页是否显示歌词翻译（默认开启） */
    val lyricTranslationEnabled: Flow<Boolean>

    /** 沉浸式播放页是否显示歌词音译与逐字注音（默认关闭） */
    val lyricRomanizationEnabled: Flow<Boolean>

    val lyricSupplementMode: Flow<LyricSupplementMode>
        get() = combine(lyricTranslationEnabled, lyricRomanizationEnabled) { translation, romanization ->
            when {
                translation -> LyricSupplementMode.Translation
                romanization -> LyricSupplementMode.Romanization
                else -> LyricSupplementMode.Original
            }
        }

    val lyricTextSize: Flow<Long> get() = kotlinx.coroutines.flow.flowOf(24L)
    val lyricLineSpacing: Flow<Long> get() = kotlinx.coroutines.flow.flowOf(28L)
    val lyricWordHighlightEnabled: Flow<Boolean> get() = kotlinx.coroutines.flow.flowOf(true)
    suspend fun setLyricTextSize(size: Long) {}
    suspend fun setLyricLineSpacing(spacing: Long) {}
    suspend fun setLyricWordHighlightEnabled(enabled: Boolean) {}

    suspend fun setAutoScrapeEnabled(enabled: Boolean)

    suspend fun setMiniPlayerLyricsEnabled(enabled: Boolean)

    suspend fun setNotificationLyricsEnabled(enabled: Boolean)
    suspend fun setDesktopLyricsEnabled(enabled: Boolean)

    suspend fun setCoverContentColorEnabled(enabled: Boolean)

    suspend fun setLyricTranslationEnabled(enabled: Boolean)

    suspend fun setLyricRomanizationEnabled(enabled: Boolean)

    /**
     * 在线音源首选音质（洛雪音质 key，如 `320k`/`flac`/`hires`/`master`）。
     * 仅作**偏好**：脚本未声明该档位时会就近回退（见 LxScriptRepository.pickQuality）。
     */
    val onlinePreferredQuality: Flow<String>

    suspend fun setOnlinePreferredQuality(qualityKey: String)

    /** 下载品质独立于播放品质，入队时记录，默认 320k。 */
    val downloadPreferredQuality: Flow<String>
    suspend fun setDownloadPreferredQuality(qualityKey: String)

    val chooseDownloadQuality: Flow<Boolean> get() = kotlinx.coroutines.flow.flowOf(true)
    suspend fun setChooseDownloadQuality(enabled: Boolean) {}

    /**
     * 下载到设备时使用的目录（绝对路径）；空串 = 系统下载目录下的 Muses 文件夹。
     * 安卓侧由 SAF 目录选择器写入并持久化授权。
     */
    val downloadDeviceDirectory: Flow<String>
    suspend fun setDownloadDeviceDirectory(path: String)

    suspend fun updateLastScanTimestamp(timestampMillis: Long)

    // ── AI 推荐（首页「猜你喜欢」，见 :core:ai）──

    /** AI 服务名称（用户自填，仅展示用，如 DeepSeek） */
    val aiServiceName: Flow<String>

    /** AI 服务地址（OpenAI 兼容 baseUrl，含版本段，如 https://host/v1） */
    val aiBaseUrl: Flow<String>

    /** AI 模型名 */
    val aiModel: Flow<String>
    val aiDailyRecommend: Flow<String>

    suspend fun setAiServiceName(name: String)

    suspend fun setAiBaseUrl(baseUrl: String)

    suspend fun setAiModel(model: String)

    suspend fun setAiDailyRecommend(snapshot: String)
}

class DataStoreSettingsRepository constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {
    private val chooseDownloadQualityKey = booleanPreferencesKey("choose_download_quality")
    override val chooseDownloadQuality: Flow<Boolean>
        get() = dataStore.data.map { it[chooseDownloadQualityKey] ?: true }
    override suspend fun setChooseDownloadQuality(enabled: Boolean) {
        dataStore.edit { it[chooseDownloadQualityKey] = enabled }
    }
    override val audioFocusEnabled: Flow<Boolean>
        get() = dataStore.data.map { it[AUDIO_FOCUS_ENABLED] ?: false }

    override suspend fun setAudioFocusEnabled(enabled: Boolean) {
        dataStore.edit { it[AUDIO_FOCUS_ENABLED] = enabled }
    }

    override val builtinLxSourcesEnabled: Flow<Boolean>
        get() = dataStore.data.map { it[BUILTIN_LX_SOURCES_ENABLED] ?: true }

    override suspend fun setBuiltinLxSourcesEnabled(enabled: Boolean) {
        dataStore.edit { it[BUILTIN_LX_SOURCES_ENABLED] = enabled }
    }

    override val preferredLxSource: Flow<String>
        get() = dataStore.data.map { it[PREFERRED_LX_SOURCE].orEmpty() }
    override suspend fun setPreferredLxSource(sourceId: String) {
        dataStore.edit { it[PREFERRED_LX_SOURCE] = sourceId }
    }

    override val lastScanTimestamp: Flow<Long>
        get() = dataStore.data.map { prefs -> prefs[LAST_SCAN_TIMESTAMP] ?: 0L }

    override val autoScrapeEnabled: Flow<Boolean>
        get() = dataStore.data.map { prefs -> prefs[AUTO_SCRAPE_ENABLED] == true }

    override val miniPlayerLyricsEnabled: Flow<Boolean>
        get() = dataStore.data.map { prefs -> prefs[MINI_PLAYER_LYRICS_ENABLED] == true }

    override val notificationLyricsEnabled: Flow<Boolean>
        get() = dataStore.data.map { prefs -> prefs[NOTIFICATION_LYRICS_ENABLED] == true }
    override val desktopLyricsEnabled: Flow<Boolean>
        get() = dataStore.data.map { prefs -> prefs[DESKTOP_LYRICS_ENABLED] == true }
    override val desktopLyricsLocked: Flow<Boolean>
        get() = dataStore.data.map { it[DESKTOP_LYRICS_LOCKED] ?: false }
    override val desktopLyricsFontSize: Flow<Long>
        get() = dataStore.data.map { (it[DESKTOP_LYRICS_FONT_SIZE] ?: 22L).coerceIn(14L, 40L) }
    override val desktopLyricsColor: Flow<Long>
        get() = dataStore.data.map { it[DESKTOP_LYRICS_COLOR] ?: 0L }
    override suspend fun setDesktopLyricsLocked(locked: Boolean) {
        dataStore.edit { it[DESKTOP_LYRICS_LOCKED] = locked }
    }
    override suspend fun setDesktopLyricsFontSize(size: Long) {
        dataStore.edit { it[DESKTOP_LYRICS_FONT_SIZE] = size.coerceIn(14L, 40L) }
    }
    override suspend fun setDesktopLyricsColor(color: Long) {
        dataStore.edit { it[DESKTOP_LYRICS_COLOR] = color }
    }

    override val coverContentColorEnabled: Flow<Boolean>
        get() = dataStore.data.map { prefs -> prefs[COVER_CONTENT_COLOR_ENABLED] ?: true }

    override val lyricTranslationEnabled: Flow<Boolean>
        get() = lyricSupplementMode.map { it == LyricSupplementMode.Translation }

    override val lyricRomanizationEnabled: Flow<Boolean>
        get() = lyricSupplementMode.map { it == LyricSupplementMode.Romanization }

    // 旧版本两项同时为真时优先译文；单次快照决定显示模式，避免切换时短暂双开。
    override val lyricSupplementMode: Flow<LyricSupplementMode>
        get() = dataStore.data.map { prefs ->
            when {
                prefs[LYRIC_TRANSLATION_ENABLED] ?: true -> LyricSupplementMode.Translation
                prefs[LYRIC_ROMANIZATION_ENABLED] ?: false -> LyricSupplementMode.Romanization
                else -> LyricSupplementMode.Original
            }
        }

    override val lyricTextSize: Flow<Long>
        get() = dataStore.data.map { (it[LYRIC_TEXT_SIZE] ?: 24L).coerceIn(18L, 32L) }
    override val lyricLineSpacing: Flow<Long>
        get() = dataStore.data.map { (it[LYRIC_LINE_SPACING] ?: 28L).coerceIn(12L, 36L) }
    override val lyricWordHighlightEnabled: Flow<Boolean>
        get() = dataStore.data.map { it[LYRIC_WORD_HIGHLIGHT] ?: true }
    override suspend fun setLyricTextSize(size: Long) {
        dataStore.edit { it[LYRIC_TEXT_SIZE] = size.coerceIn(18L, 32L) }
    }
    override suspend fun setLyricLineSpacing(spacing: Long) {
        dataStore.edit { it[LYRIC_LINE_SPACING] = spacing.coerceIn(12L, 36L) }
    }
    override suspend fun setLyricWordHighlightEnabled(enabled: Boolean) {
        dataStore.edit { it[LYRIC_WORD_HIGHLIGHT] = enabled }
    }

    override suspend fun updateLastScanTimestamp(timestampMillis: Long) {
        dataStore.edit { prefs -> prefs[LAST_SCAN_TIMESTAMP] = timestampMillis }
    }

    override suspend fun setAutoScrapeEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[AUTO_SCRAPE_ENABLED] = enabled }
    }

    override suspend fun setMiniPlayerLyricsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[MINI_PLAYER_LYRICS_ENABLED] = enabled }
    }

    override suspend fun setNotificationLyricsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[NOTIFICATION_LYRICS_ENABLED] = enabled }
    }
    override suspend fun setDesktopLyricsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[DESKTOP_LYRICS_ENABLED] = enabled }
    }

    override suspend fun setCoverContentColorEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[COVER_CONTENT_COLOR_ENABLED] = enabled }
    }

    override suspend fun setLyricTranslationEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[LYRIC_TRANSLATION_ENABLED] = enabled
            if (enabled) prefs[LYRIC_ROMANIZATION_ENABLED] = false
        }
    }

    override suspend fun setLyricRomanizationEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[LYRIC_ROMANIZATION_ENABLED] = enabled
            if (enabled) prefs[LYRIC_TRANSLATION_ENABLED] = false
        }
    }

    override val onlinePreferredQuality: Flow<String>
        get() = dataStore.data.map { prefs -> prefs[ONLINE_PREFERRED_QUALITY] ?: DEFAULT_ONLINE_QUALITY }

    override suspend fun setOnlinePreferredQuality(qualityKey: String) {
        dataStore.edit { prefs -> prefs[ONLINE_PREFERRED_QUALITY] = qualityKey }
    }

    override val aiServiceName: Flow<String>
        get() = dataStore.data.map { prefs -> prefs[AI_SERVICE_NAME].orEmpty() }

    override val aiBaseUrl: Flow<String>
        get() = dataStore.data.map { prefs -> prefs[AI_BASE_URL].orEmpty() }

    override val aiModel: Flow<String>
        get() = dataStore.data.map { prefs -> prefs[AI_MODEL].orEmpty() }

    override val aiDailyRecommend: Flow<String>
        get() = dataStore.data.map { prefs -> prefs[AI_DAILY_RECOMMEND].orEmpty() }

    override suspend fun setAiServiceName(name: String) {
        dataStore.edit { prefs -> prefs[AI_SERVICE_NAME] = name }
    }

    override suspend fun setAiBaseUrl(baseUrl: String) {
        dataStore.edit { prefs -> prefs[AI_BASE_URL] = baseUrl }
    }

    override suspend fun setAiModel(model: String) {
        dataStore.edit { prefs -> prefs[AI_MODEL] = model }
    }

    override val downloadPreferredQuality: Flow<String>
        get() = dataStore.data.map { it[DOWNLOAD_QUALITY] ?: DEFAULT_ONLINE_QUALITY }
    override suspend fun setDownloadPreferredQuality(qualityKey: String) {
        dataStore.edit { it[DOWNLOAD_QUALITY] = qualityKey }
    }
    override val downloadDeviceDirectory: Flow<String>
        get() = dataStore.data.map { it[DOWNLOAD_DEVICE_DIRECTORY] ?: "" }
    override suspend fun setDownloadDeviceDirectory(path: String) {
        dataStore.edit { it[DOWNLOAD_DEVICE_DIRECTORY] = path }
    }

    override suspend fun setAiDailyRecommend(snapshot: String) {
        dataStore.edit { prefs -> prefs[AI_DAILY_RECOMMEND] = snapshot }
    }

    private companion object {
        val AUDIO_FOCUS_ENABLED = booleanPreferencesKey("audio_focus_enabled")
        val BUILTIN_LX_SOURCES_ENABLED = booleanPreferencesKey("builtin_lx_sources_enabled")
        val PREFERRED_LX_SOURCE = stringPreferencesKey("preferred_lx_source")
        val LAST_SCAN_TIMESTAMP = longPreferencesKey("last_scan_timestamp")
        val DOWNLOAD_QUALITY = stringPreferencesKey("download_quality")
        val DOWNLOAD_DEVICE_DIRECTORY = stringPreferencesKey("download_device_directory")
        val AUTO_SCRAPE_ENABLED = booleanPreferencesKey("auto_scrape_enabled")
        val MINI_PLAYER_LYRICS_ENABLED = booleanPreferencesKey("mini_player_lyrics_enabled")
        val NOTIFICATION_LYRICS_ENABLED = booleanPreferencesKey("notification_lyrics_enabled")
        val DESKTOP_LYRICS_ENABLED = booleanPreferencesKey("desktop_lyrics_enabled")
        val DESKTOP_LYRICS_LOCKED = booleanPreferencesKey("desktop_lyrics_locked")
        val DESKTOP_LYRICS_FONT_SIZE = longPreferencesKey("desktop_lyrics_font_size")
        val DESKTOP_LYRICS_COLOR = longPreferencesKey("desktop_lyrics_color")
        val COVER_CONTENT_COLOR_ENABLED = booleanPreferencesKey("cover_content_color_enabled")
        val LYRIC_TRANSLATION_ENABLED = booleanPreferencesKey("lyric_translation_enabled")
        val LYRIC_TEXT_SIZE = longPreferencesKey("lyric_text_size")
        val LYRIC_LINE_SPACING = longPreferencesKey("lyric_line_spacing")
        val LYRIC_WORD_HIGHLIGHT = booleanPreferencesKey("lyric_word_highlight")
        val LYRIC_ROMANIZATION_ENABLED = booleanPreferencesKey("lyric_romanization_enabled")
        val ONLINE_PREFERRED_QUALITY = stringPreferencesKey("online_preferred_quality")
        val AI_SERVICE_NAME = stringPreferencesKey("ai_service_name")
        val AI_BASE_URL = stringPreferencesKey("ai_base_url")
        val AI_MODEL = stringPreferencesKey("ai_model")
        val AI_DAILY_RECOMMEND = stringPreferencesKey("ai_daily_recommend")

        /** 默认 320k：全平台可用、体积与兼容性最稳（高音质档由用户显式选择） */
        const val DEFAULT_ONLINE_QUALITY = "320k"
    }
}
