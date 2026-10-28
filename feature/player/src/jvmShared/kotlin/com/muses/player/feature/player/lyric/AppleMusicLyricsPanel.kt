package com.muses.player.feature.player.lyric

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.muses.player.core.data.store.platformMonotonicMs
import com.muses.player.core.data.repository.SettingsRepository
import com.muses.player.core.lyrics.model.LyricAgentAlignment
import com.muses.player.core.lyrics.model.LyricHighlightStrategy
import com.muses.player.core.lyrics.model.LyricsDocument
import com.muses.player.core.lyrics.model.withPseudoTiming
import com.muses.player.core.lyrics.processor.LyricTimelineProcessor
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.muses.player.core.ui.components.LocalPlayerContentColor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AppleMusicLyricsPanel(
    state: PlaybackUiState,
    modifier: Modifier = Modifier,
    isInterfaceHidden: Boolean = false,
    onInterfaceInteraction: () -> Unit = {},
    onInterfaceVisibilityChange: (Boolean) -> Unit = {},
    active: Boolean = true,
    externalDocument: LyricsDocument? = null,
    onAtTopChange: (Boolean) -> Unit = {},
) {
    val settingsRepository = koinInject<SettingsRepository>()
    val showLyricTranslation by settingsRepository.lyricTranslationEnabled.collectAsStateWithLifecycle(initialValue = true)
    val showLyricRomanization by settingsRepository.lyricRomanizationEnabled.collectAsStateWithLifecycle(initialValue = true)
    val contentColor = LocalPlayerContentColor.current
    val activeState = rememberUpdatedState(active)
    // Keep four future lines composed below the viewport. Their independent
    // cascade animations can then run before clipping reveals them.
    val listState = rememberLazyListState(
        cacheWindow = LazyLayoutCacheWindow(
            ahead = 720.dp,
            behind = 240.dp,
        ),
    )
    val density = LocalDensity.current
    val latestAtTopChange = rememberUpdatedState(onAtTopChange)
    LaunchedEffect(listState) {
        snapshotFlow { !listState.canScrollBackward }.distinctUntilChanged().collect {
            latestAtTopChange.value(it)
        }
    }
    val mediaId = state.mediaId

    val automaticLyricSelectionEnabled = SettingsRuntime.automaticLyricSelectionEnabled
    var lyrics by remember(mediaId, automaticLyricSelectionEnabled) { mutableStateOf<LyricsDocument?>(null) }
    var isLoading by remember(mediaId) { mutableStateOf(false) }
    var errorMessage by remember(mediaId) { mutableStateOf<String?>(null) }

    var anchorPositionMs by remember(mediaId) { mutableLongStateOf(state.positionMs) }
    var anchorRealtimeMs by remember(mediaId) { mutableLongStateOf(platformMonotonicMs()) }
    val renderedPositionState = remember(mediaId) { mutableLongStateOf(state.positionMs) }
    var holdTrackAtStart by remember(mediaId) { mutableStateOf(false) }
    var initializedMediaId by remember { mutableStateOf(mediaId) }
    var hasInitializedTrack by remember { mutableStateOf(false) }

    // A Bluetooth/headset skip can deliver the first UI state for the new item
    // before its position callback arrives.  Do not let the previous track's
    // progress keep the new lyric document focused in the middle of the list.
    // A newly composed panel, however, is re-entering the current track and must
    // retain its reported position.
    LaunchedEffect(mediaId) {
        val initialization = lyricsPanelPlaybackInitialization(
            isFirstComposition = !hasInitializedTrack,
            mediaIdChanged = hasInitializedTrack && initializedMediaId != mediaId,
            reportedPositionMs = state.positionMs,
        )
        initializedMediaId = mediaId
        hasInitializedTrack = true
        anchorPositionMs = initialization.positionMs
        anchorRealtimeMs = platformMonotonicMs()
        renderedPositionState.longValue = initialization.positionMs
        holdTrackAtStart = initialization.holdAtTrackStart
        if (initialization.resetListToStart) listState.scrollToItem(0)
    }

    LaunchedEffect(state.positionMs, state.isPlaying, mediaId) {
        if (holdTrackAtStart) return@LaunchedEffect
        anchorPositionMs = state.positionMs
        anchorRealtimeMs = platformMonotonicMs()
        renderedPositionState.longValue = state.positionMs
    }

    LaunchedEffect(mediaId, state.title, state.artist, state.album, state.durationMs, automaticLyricSelectionEnabled) {
        if (mediaId.isNullOrBlank()) return@LaunchedEffect
        isLoading = true
        errorMessage = null
        runCatching { ProviderLyricsLoader.load(state) }
            .onSuccess { lyrics = it }
            .onFailure { errorMessage = it.message ?: "歌词加载失败" }
        // A newly selected item always enters from its lyric start anchor.
        // Subsequent seeks keep using the normal position callback above.
        holdTrackAtStart = false
        isLoading = false
    }

    val document = externalDocument ?: lyrics
    val pseudoTimingEnabled = SettingsRuntime.lyricPseudoTimingEnabled
    val renderedDocument = remember(document, pseudoTimingEnabled) {
        if (pseudoTimingEnabled) document?.withPseudoTiming() else document
    }
    val lines = renderedDocument?.lines.orEmpty()
    val hasSyllableSync = remember(renderedDocument) { lines.any { it.syllables.isNotEmpty() } }
    val bilibiliOffsetMs = rememberBilibiliLyricOffset(mediaId)
    val lyricAdvanceMs = effectiveBilibiliLyricAdvance(SettingsRuntime.lyricAdvanceMs, bilibiliOffsetMs)
    val isBilibiliLyrics = remember(mediaId) { isBilibiliMediaId(mediaId) }
    val usesWordByWordPresentation = hasSyllableSync && SettingsRuntime.lyricWordByWordEnabled
    val timedAdvanceMs = if (isBilibiliLyrics || SettingsRuntime.lyricAdvanceAppliesToWordByWord) {
        lyricAdvanceMs
    } else 0L
    val lineAdvanceMs = if (usesWordByWordPresentation) timedAdvanceMs else lyricAdvanceMs
    var highlightedIndex by remember(document) {
        mutableIntStateOf(
            renderedDocument?.highlightedIndex(
                initialLyricsHighlightPositionMs(renderedPositionState.longValue, lineAdvanceMs),
                LyricHighlightStrategy.FirstSyllableOrLineStart,
            ) ?: -1,
        )
    }
    var colorHighlightedIndex by remember(document) { mutableIntStateOf(highlightedIndex) }
    var activeTimedLineIndexes by remember(document) { mutableStateOf(emptySet<Int>()) }
    val playbackTimeProvider = remember(mediaId, timedAdvanceMs) {
        { renderedPositionState.longValue + timedAdvanceMs }
    }

    // Update the line index only when it actually changes. The per-frame time is
    // read later from Canvas, so the 60 Hz clock invalidates drawing rather than
    // recomposing and relaying out the complete lyric list.
    val renderingQuality = SettingsRuntime.lyricRenderingQuality
    val refreshRate = when (renderingQuality) {
        LyricsRenderingQuality.Low -> min(SettingsRuntime.lyricRefreshRate, 30)
        LyricsRenderingQuality.Balanced -> min(SettingsRuntime.lyricRefreshRate, 60)
        // Native glyph drawing is CPU-bound; more than one update per display
        // frame adds work without producing a visible state. 60 Hz remains the
        // ceiling even when a migrated preference contains 90/120 Hz.
        LyricsRenderingQuality.High -> min(SettingsRuntime.lyricRefreshRate, 60)
    }
    val interludes = remember(lines) { sourceLyricInterludes(lines) }
    val interludeByLyricIndex = remember(interludes) { interludes.associateBy { it.followingLyricIndex } }
    var activeInterludeIndex by remember(document) { mutableIntStateOf(-1) }

    // High-frequency clock only for Canvas word animation. Line focus changes
    // are computed in a separate event-driven loop so compositional work only
    // happens at lyric boundaries.
    LaunchedEffect(state.isPlaying, mediaId, document, refreshRate, AppVisibility.isForeground) {
        var lastFrameNanos = 0L
        val minimumFrameNanos = 1_000_000_000L / refreshRate.coerceIn(30, 120)
        while (true) {
            if (!activeState.value || !AppVisibility.isForeground || holdTrackAtStart) {
                delay(200L)
                continue
            }
            if (state.isPlaying) {
                val frameNanos = withFrameNanos { it }
                if (lastFrameNanos != 0L && frameNanos - lastFrameNanos < minimumFrameNanos) continue
                lastFrameNanos = frameNanos
            }
            val position = if (state.isPlaying) {
                anchorPositionMs + (platformMonotonicMs() - anchorRealtimeMs)
            } else {
                anchorPositionMs
            }
            renderedPositionState.longValue = position
            val nextInterlude = interludes.indexOfFirst { position >= it.startTimeMs && position < it.followingLyricTimeMs }
            if (nextInterlude != activeInterludeIndex) activeInterludeIndex = nextInterlude
            if (!state.isPlaying) delay(200L)
        }
    }

    val lineIndexSeekSignal = remember(mediaId) { Channel<Unit>(Channel.CONFLATED) }
    var seekGeneration by remember(mediaId) { mutableIntStateOf(0) }
    var consumedSeekGeneration by remember(mediaId) { mutableIntStateOf(0) }
    var previousSeekPositionMs by remember(mediaId) { mutableLongStateOf(state.positionMs) }
    var previousSeekRealtimeMs by remember(mediaId) { mutableLongStateOf(platformMonotonicMs()) }
    LaunchedEffect(state.positionMs) {
        if (state.positionMs != previousSeekPositionMs) {
            val expected = previousSeekPositionMs + if (state.isPlaying) {
                platformMonotonicMs() - previousSeekRealtimeMs
            } else {
                0L
            }
            if (kotlin.math.abs(state.positionMs - expected) > 200L) {
                seekGeneration += 1
                lineIndexSeekSignal.trySend(Unit)
            }
            previousSeekPositionMs = state.positionMs
            previousSeekRealtimeMs = platformMonotonicMs()
        }
    }

    LaunchedEffect(state.isPlaying, mediaId, document, hasSyllableSync, lineAdvanceMs, AppVisibility.isForeground) {
        while (true) {
            if (!activeState.value || !AppVisibility.isForeground || holdTrackAtStart || renderedDocument == null) {
                delay(200L)
                continue
            }
            val position = if (state.isPlaying) {
                anchorPositionMs + (platformMonotonicMs() - anchorRealtimeMs)
            } else {
                anchorPositionMs
            }
            val effectivePosition = position + lineAdvanceMs
            val activeInterlude = if (
                SettingsRuntime.lyricInterludeCountdownEnabled
            ) interludes.getOrNull(activeInterludeIndex) else null
            val nextIndex = if (
                SettingsRuntime.lyricInterludeCountdownEnabled &&
                activeInterlude != null &&
                position >= activeInterlude.startTimeMs &&
                position < activeInterlude.followingLyricTimeMs
            ) {
                activeInterlude.followingLyricIndex
            } else {
                renderedDocument.highlightedIndex(effectivePosition, LyricHighlightStrategy.FirstSyllableOrLineStart) ?: -1
            }
            if (nextIndex != highlightedIndex) highlightedIndex = nextIndex
            val nextColorIndex = renderedDocument.highlightedIndex(
                effectivePosition + SettingsRuntime.lyricFocusColorLeadMs,
                LyricHighlightStrategy.FirstSyllableOrLineStart,
            ) ?: -1
            if (nextColorIndex != colorHighlightedIndex) colorHighlightedIndex = nextColorIndex
            val timedPosition = position + timedAdvanceMs
            val nextTimedLines = LyricTimelineProcessor.activeTimedLineIndexes(renderedDocument, timedPosition)
            if (nextTimedLines != activeTimedLineIndexes) activeTimedLineIndexes = nextTimedLines

            val lineWait = LyricTimelineProcessor.nextEventTimeMs(renderedDocument, effectivePosition)
                ?.minus(effectivePosition)
            val timedWait = LyricTimelineProcessor.nextEventTimeMs(renderedDocument, timedPosition)
                ?.minus(timedPosition)
            val waitMs = listOfNotNull(lineWait, timedWait).minOrNull()?.coerceIn(16L, 500L) ?: 250L
            withTimeoutOrNull(waitMs) { lineIndexSeekSignal.receive() }
            if (!state.isPlaying) delay(200L)
        }
    }

    val focusProgress = remember(renderedDocument) { List(lines.size) { Animatable(0f) } }
    val scaleProgress = remember(renderedDocument) { List(lines.size) { Animatable(0f) } }
    // 每行使用持久的像素坐标动画；切行时只改目标，不把进度归零。
    val lineTravel = remember(renderedDocument) { List(lines.size) { Animatable(0f) } }
    val scrollTravel = remember(renderedDocument) { Animatable(0f) }
    val motionScope = rememberCoroutineScope()
    val pendingMoves = remember(renderedDocument) { mutableMapOf<Int, Job>() }
    val runningMoves = remember(renderedDocument) { mutableMapOf<Int, Job>() }
    var scrollJob by remember(renderedDocument) { mutableStateOf<Job?>(null) }
    var appliedScrollPx by remember(renderedDocument) { mutableStateOf(0f) }
    var rowBaselines by remember(renderedDocument) { mutableStateOf<Map<Int, Float>>(emptyMap()) }
    val rowHeightsPx = remember(
        renderedDocument, SettingsRuntime.lyricFontScale, showLyricTranslation,
        showLyricRomanization,
    ) { mutableStateMapOf<Int, Int>() }
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    var viewportWidthPx by remember { mutableIntStateOf(0) }
    var visualFocusIndex by remember(renderedDocument) { mutableIntStateOf(-1) }
    var isBrowsingLyrics by remember(renderedDocument) { mutableStateOf(false) }
    var playbackFocusGeneration by remember(renderedDocument) { mutableIntStateOf(0) }
    var browseGeneration by remember(renderedDocument) { mutableIntStateOf(0) }
    var scrollHideDistancePx by remember { mutableStateOf(0f) }
    val latestInterfaceHidden = rememberUpdatedState(isInterfaceHidden)
    val latestVisibilityCallback = rememberUpdatedState(onInterfaceVisibilityChange)
    val latestInteractionCallback = rememberUpdatedState(onInterfaceInteraction)
    var initialLyricsPositioned by remember(renderedDocument) { mutableStateOf(lines.isEmpty()) }
    val lyricFontScale = SettingsRuntime.lyricFontScale
    val lyricSpacingScale = SettingsRuntime.lyricSpacingScale
    val lineSpacingPx = with(density) { (UpstreamLyrics.LINE_SPACING_DP * lyricSpacingScale).dp.toPx() }
    val primaryHeightPx = with(density) { (UpstreamLyrics.LINE_HEIGHT_SP * lyricFontScale).sp.toPx() }
    val annotationFontPx = with(density) {
        max(UpstreamLyrics.FONT_SIZE_SP * lyricFontScale * SettingsRuntime.lyricRomanizationFontScale, 13f).sp.toPx()
    }
    val annotationSpacingPx = with(density) { UpstreamLyrics.ANNOTATION_SPACING_DP.dp.toPx() }

    fun estimatedHeight(index: Int): Float {
        rowHeightsPx[index]?.let { return it.toFloat() }
        val line = lines.getOrNull(index)
        var height = primaryHeightPx
        if (showLyricRomanization && !line?.romanization.isNullOrBlank()) {
            height += annotationFontPx * 1.2f + annotationSpacingPx
        }
        if (showLyricTranslation && !line?.translation.isNullOrBlank()) {
            height += annotationFontPx * 1.2f + annotationSpacingPx
        }
        return height
    }

    // 直接用真实列表落位补偿，避免整数列表位置与浮点弹簧相减时产生收尾抖动。
    fun currentMovementOffset(index: Int): Float {
        val baseline = rowBaselines[index] ?: return 0f
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index + 1 }
            ?: return 0f
        return baseline - lineTravel[index].value - item.offset
    }

    suspend fun resetMotion() {
        scrollJob?.cancel()
        pendingMoves.values.forEach { it.cancel() }
        runningMoves.values.forEach { it.cancel() }
        pendingMoves.clear()
        runningMoves.clear()
        rowBaselines = emptyMap()
        appliedScrollPx = 0f
        scrollTravel.snapTo(0f)
        lineTravel.forEach { it.snapTo(0f) }
    }

    DisposableEffect(renderedDocument) {
        onDispose {
            scrollJob?.cancel()
            pendingMoves.values.forEach { it.cancel() }
            runningMoves.values.forEach { it.cancel() }
        }
    }

    LaunchedEffect(colorHighlightedIndex, activeTimedLineIndexes, activeInterludeIndex, renderedDocument, state.isPlaying) {
        val targets = if (activeInterludeIndex >= 0 && SettingsRuntime.lyricInterludeCountdownEnabled) emptySet()
        else activeTimedLineIndexes.ifEmpty {
            colorHighlightedIndex.takeIf(lines.indices::contains)?.let(::setOf).orEmpty()
        }
        coroutineScope {
            focusProgress.forEachIndexed { index, anim ->
                val target = if (index in targets) 1f else 0f
                launch {
                    if (SettingsRuntime.lyricReduceMotion) anim.snapTo(target)
                    else anim.animateTo(target, tween(if (target > 0f) 300 else 450, easing = AmllWordEffects.easeOut))
                }
                launch {
                    val scaleTarget = if (index in targets || !state.isPlaying) 1f else 0f
                    if (SettingsRuntime.lyricReduceMotion) scaleProgress[index].snapTo(scaleTarget)
                    else scaleProgress[index].animateTo(scaleTarget, AmllScrollPhysics.scaleSpring)
                }
            }
        }
    }

    val scrollHideThresholdPx = with(density) { SettingsRuntime.lyricScrollHideThresholdDp.dp.toPx() }
    val lyricInteractionConnection = remember(renderedDocument, scrollHideThresholdPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || abs(available.y) < .01f) return Offset.Zero
                isBrowsingLyrics = true
                browseGeneration += 1
                val offsetDelta = -available.y
                if (offsetDelta < 0f) {
                    scrollHideDistancePx = 0f
                    if (latestInterfaceHidden.value) latestVisibilityCallback.value(true)
                    else latestInteractionCallback.value()
                } else if (!latestInterfaceHidden.value) {
                    latestInteractionCallback.value()
                    scrollHideDistancePx += offsetDelta
                    if (scrollHideDistancePx >= scrollHideThresholdPx) {
                        scrollHideDistancePx = 0f
                        latestVisibilityCallback.value(false)
                    }
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(isBrowsingLyrics, renderedDocument) {
        if (isBrowsingLyrics) resetMotion()
    }
    // 计时从惯性滚动真正结束开始，防止手指仍按住或列表仍滑动时被自动跟随抢走。
    LaunchedEffect(browseGeneration, listState.isScrollInProgress, renderedDocument) {
        if (!isBrowsingLyrics || listState.isScrollInProgress) return@LaunchedEffect
        delay(SettingsRuntime.lyricFollowDelayMs.toLong())
        isBrowsingLyrics = false
        playbackFocusGeneration += 1
    }

    Box(
        modifier = modifier.fillMaxSize().onSizeChanged {
            viewportHeightPx = it.height
            viewportWidthPx = it.width
        },
    ) {
        val focusPosition = SettingsRuntime.lyricFocusPosition
        val topPaddingPx = viewportHeightPx * focusPosition
        val bottomPaddingPx = max(viewportHeightPx * (1f - focusPosition), with(density) { 40.dp.toPx() })
        val interludeHeightPx = with(density) { 56.dp.toPx() }
        fun reservedInterludeHeight(index: Int): Float =
            if (SettingsRuntime.lyricInterludeCountdownEnabled && index in interludeByLyricIndex) interludeHeightPx else 0f
        fun focusItemScrollOffset(index: Int): Int {
            val isInterludeFocused = SettingsRuntime.lyricInterludeCountdownEnabled &&
                interludes.getOrNull(activeInterludeIndex)?.followingLyricIndex == index
            val rowHeight = if (isInterludeFocused) interludeHeightPx else estimatedHeight(index) + lineSpacingPx
            val top = AmllScrollPhysics.focusTop(viewportHeightPx, rowHeight, focusPosition)
            return -(top - if (isInterludeFocused) 0f else reservedInterludeHeight(index)).roundToInt()
        }

        val playbackFocusIndex = activeTimedLineIndexes.minOrNull() ?: highlightedIndex
        val focusHeight = rowHeightsPx[playbackFocusIndex]
        LaunchedEffect(
            playbackFocusIndex, playbackFocusGeneration, viewportHeightPx, viewportWidthPx,
            isBrowsingLyrics, renderedDocument, focusHeight, seekGeneration, activeInterludeIndex,
        ) {
            if (viewportHeightPx <= 0 || isBrowsingLyrics || lines.isEmpty()) return@LaunchedEffect
            val nextIndex = playbackFocusIndex.coerceIn(lines.indices)
            val previousIndex = visualFocusIndex
            val targetOffset = focusItemScrollOffset(nextIndex)
            if (!initialLyricsPositioned) {
                resetMotion()
                // 两次测量使进入长句中段时也能以真实换行高度居中。
                listState.scrollToItem(nextIndex + 1, targetOffset)
                withFrameNanos { }
                listState.scrollToItem(nextIndex + 1, focusItemScrollOffset(nextIndex))
                if (!SettingsRuntime.lyricReduceMotion) {
                    val enteringRows = listState.layoutInfo.visibleItemsInfo.filter { it.index - 1 in lines.indices }
                    rowBaselines = enteringRows.associate { it.index - 1 to it.offset.toFloat() }
                    enteringRows.forEach { item ->
                        val index = item.index - 1
                        // 上游新建歌词组从视口下方两倍高度飞入，所有行共享起点且不加阶梯延迟。
                        lineTravel[index].snapTo(item.offset - viewportHeightPx * 2f)
                        runningMoves[index] = motionScope.launch {
                            lineTravel[index].animateTo(0f, AmllScrollPhysics.positionSpring(null))
                        }
                    }
                }
                visualFocusIndex = nextIndex
                initialLyricsPositioned = true
                return@LaunchedEffect
            }
            visualFocusIndex = nextIndex
            if (!SettingsRuntime.lyricAutoFollowEnabled) return@LaunchedEffect
            if (SettingsRuntime.lyricReduceMotion) {
                resetMotion()
                listState.scrollToItem(nextIndex + 1, targetOffset)
                return@LaunchedEffect
            }
            val seeking = seekGeneration != consumedSeekGeneration || nextIndex < previousIndex
            consumedSeekGeneration = seekGeneration
            val springSpec = AmllScrollPhysics.positionSpring(
                intervalMs = lines.getOrNull(nextIndex - 1)?.let {
                    sourceLineActivationTimeMs(lines[nextIndex]) - sourceLineActivationTimeMs(it)
                },
                seeking = seeking || playbackFocusGeneration > 0 && previousIndex == nextIndex,
                interlude = activeInterludeIndex >= 0,
                ended = state.durationMs > 0L && renderedPositionState.longValue >= state.durationMs,
            )
            val items = listState.layoutInfo.visibleItemsInfo
            val targetItem = items.firstOrNull { it.index == nextIndex + 1 }
            if (targetItem == null) {
                resetMotion()
                // 未测量的远端行先以估算高度定位；进入预取区后按真实高度再收敛。
                // 与普通切行共用弹簧，避免跳转退回列表的默认动画曲线。
                scrollJob = motionScope.launch {
                    repeat(4) {
                        val visibleRows = listState.layoutInfo.visibleItemsInfo
                        val exact = visibleRows.firstOrNull { it.index == nextIndex + 1 }
                        val reference = exact ?: visibleRows.firstOrNull { it.index - 1 in lines.indices }
                            ?: return@launch
                        val referenceIndex = reference.index - 1
                        val estimatedDistance = if (referenceIndex <= nextIndex) {
                            (referenceIndex until nextIndex).sumOf {
                                (estimatedHeight(it) + lineSpacingPx + reservedInterludeHeight(it)).toDouble()
                            }.toFloat()
                        } else {
                            -(nextIndex until referenceIndex).sumOf {
                                (estimatedHeight(it) + lineSpacingPx + reservedInterludeHeight(it)).toDouble()
                            }.toFloat()
                        }
                        val remaining = reference.offset + estimatedDistance + focusItemScrollOffset(nextIndex)
                        if (abs(remaining) < 1f) return@launch
                        listState.scroll {
                            scrollTravel.animateTo(appliedScrollPx + remaining, springSpec) {
                                appliedScrollPx += scrollBy(value - appliedScrollPx)
                            }
                        }
                    }
                    listState.scrollToItem(nextIndex + 1, focusItemScrollOffset(nextIndex))
                }
                return@LaunchedEffect
            }
            val distance = targetItem.offset + targetOffset.toFloat()
            if (abs(distance) < .5f && !scrollTravel.isRunning) return@LaunchedEffect
            val targetTravel = appliedScrollPx + distance
            val visible = items.filter { it.index - 1 in lines.indices }
            if (visible.isEmpty()) return@LaunchedEffect
            val delays = AmllScrollPhysics.staggerDelays(
                rowBottoms = visible.map { it.offset + it.size - distance },
                firstIndex = visible.first().index - 1,
                focusIndex = nextIndex,
                enabled = !seeking && nextIndex != previousIndex,
            )
            val visibleIndexes = visible.map { it.index - 1 }.toSet()
            val baselines = rowBaselines.filterKeys { it in visibleIndexes }.toMutableMap()
            visible.forEachIndexed { order, item ->
                val index = item.index - 1
                if (index !in baselines) lineTravel[index].snapTo(appliedScrollPx)
                baselines[index] = item.offset + appliedScrollPx
                pendingMoves.remove(index)?.cancel()
                pendingMoves[index] = motionScope.launch {
                    val durationScale = coroutineContext[androidx.compose.ui.MotionDurationScale]?.scaleFactor ?: 1f
                    delay((delays[order] * durationScale).toLong())
                    // 等待阶梯延迟期间，旧弹簧继续运行；新目标接管时继承此刻速度。
                    runningMoves[index] = motionScope.launch {
                        lineTravel[index].animateTo(targetTravel, springSpec)
                    }
                }
            }
            rowBaselines = baselines
            val velocity = scrollTravel.velocity
            scrollJob?.cancel()
            scrollJob = motionScope.launch {
                listState.scroll {
                    scrollTravel.animateTo(targetTravel, springSpec, initialVelocity = velocity) {
                        appliedScrollPx += scrollBy(value - appliedScrollPx)
                    }
                }
            }
        }
        when {
            isLoading && document == null -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = contentColor.copy(alpha = 0.9f)),
                )
            }
            errorMessage != null && document == null -> {
                Text(
                    text = errorMessage.orEmpty(),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    color = contentColor.copy(alpha = 0.52f),
                    style = MiuixTheme.textStyles.body1,
                )
            }
            document != null && lines.isEmpty() -> {
                Text(
                    text = "空空如也~",
                    modifier = Modifier.align(Alignment.Center),
                    color = contentColor.copy(alpha = 0.42f),
                    style = MiuixTheme.textStyles.title4,
                )
            }
            document == null -> {
                val retry = LocalPlaybackLyricsRetry.current
                val loading = LocalPlaybackLyricsLoading.current
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (loading) "正在加载歌词…" else "暂无歌词",
                        color = contentColor.copy(alpha = 0.52f),
                        style = MiuixTheme.textStyles.title4,
                    )
                    if (retry != null && !loading) {
                        top.yukonga.miuix.kmp.basic.TextButton(text = "重新加载歌词", onClick = retry)
                    }
                }
            }
            else -> {
                val annotationHeightPx = annotationFontPx * 1.2f * 2f + annotationSpacingPx * 2f
                val lyricStridePx = max(primaryHeightPx + annotationHeightPx + lineSpacingPx, 1f)
                val layoutOverscanPx = (lyricStridePx * 4f).roundToInt()

                Layout(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .graphicsLayer { alpha = if (initialLyricsPositioned) 1f else 0f },
                    content = {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().nestedScroll(lyricInteractionConnection),
                        ) {
                    item(key = "lyrics-top-padding") {
                        Spacer(Modifier.height(with(density) { topPaddingPx.toDp() }))
                    }
                    itemsIndexed(
                        items = lines,
                        key = { index, line -> "${line.timeMs}:$index" },
                    ) { index, line ->
                        val interlude = interludeByLyricIndex[index]
                        // Reserve the interlude's space as soon as the document
                        // is laid out.  Inserting it only after playback reaches
                        // the gap makes the countdown pop in and fight the lyric
                        // scroll animation.
                        val showsInterlude = SettingsRuntime.lyricInterludeCountdownEnabled &&
                            interlude != null
                        val isActiveLine = index in activeTimedLineIndexes
                        val fp = focusProgress[index].value.coerceIn(0f, 1f)
                        val effectiveFocus = fp
                        val lastActiveIndex = activeTimedLineIndexes.maxOrNull() ?: visualFocusIndex
                        val distanceBlur = AmllScrollPhysics.blur(
                            index, visualFocusIndex, lastActiveIndex,
                            active = isActiveLine || index == colorHighlightedIndex,
                            browsing = isBrowsingLyrics,
                            narrow = with(density) { viewportWidthPx.toDp() } <= 1024.dp,
                        ) * SettingsRuntime.lyricBlurStrength
                        // 非活跃行不订阅逐字时钟，用整行透明度还原上游的暗字层。
                        // 当前行的未唱部分再按整行透明度归一化，避免被重复压暗。
                        val inactiveAlpha = .2f
                        val rowAlpha = inactiveAlpha + (.85f - inactiveAlpha) * fp
                        val timedUnplayedAlpha = ((.2f + (.85f * .4f - .2f) * fp) / rowAlpha).coerceIn(0f, 1f)
                        val distanceBlurForQuality = when (renderingQuality) {
                            LyricsRenderingQuality.Low -> 0f
                            LyricsRenderingQuality.Balanced -> distanceBlur * .55f
                            LyricsRenderingQuality.High -> distanceBlur
                        }
                        Column(
                            Modifier.graphicsLayer {
                                translationY = currentMovementOffset(index)
                                val scale = (1f + (SettingsRuntime.lyricFocusScale - 1f) *
                                    scaleProgress[index].value) / SettingsRuntime.lyricFocusScale
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = TransformOrigin(
                                    if (line.agent?.alignment == LyricAgentAlignment.Flipped) 1f else 0f,
                                    .5f,
                                )
                            }.blur(distanceBlurForQuality.dp, BlurredEdgeTreatment.Unbounded),
                        ) {

                        if (showsInterlude) {
                            LyricInterludeCountdown(
                                interlude = checkNotNull(interlude),
                                playbackTimeProvider = { renderedPositionState.longValue },
                                reduceMotion = SettingsRuntime.lyricReduceMotion,
                                visualScale = 1f,
                                visualOffsetPx = 0f,
                                rowAlpha = rowAlpha,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }

                        UpstreamLyricLine(
                            line = line,
                            playbackTimeProvider = playbackTimeProvider,
                            // Only the focused line (plus its short colour hand-off)
                            // needs the playback clock. Subscribing every visible
                            // syllable row made the complete LazyColumn redraw at
                            // the lyric refresh rate on low-end devices.
                            supportsTimedLyrics = line.syllables.isNotEmpty() &&
                                SettingsRuntime.lyricWordByWordEnabled &&
                                (effectiveFocus > 0.001f || isActiveLine || index == visualFocusIndex + 1),
                            fontScale = lyricFontScale,
                            reduceMotion = SettingsRuntime.lyricReduceMotion,
                            focusProgress = effectiveFocus,
                            timingEffectsStrength = if (isActiveLine) 1f else effectiveFocus,
                            timedUnplayedAlpha = timedUnplayedAlpha,
                            visualScale = 1f,
                            focusScaleReservePadding = 0.dp,
                            visualOffsetPx = 0f,
                            rowAlpha = rowAlpha,
                            distanceBlurDp = 0f,
                            focusBlurDp = 0f,
                            renderingQuality = renderingQuality,
                            showRuby = showLyricRomanization,
                            showTranslation = showLyricTranslation &&
                                !line.translation.isNullOrBlank(),
                            showRomanization = showLyricRomanization &&
                                !line.romanization.isNullOrBlank() &&
                                (SettingsRuntime.lyricRomanizationDisplayMode == LyricAnnotationDisplayMode.AllLines || isActiveLine),
                            reserveTranslation = showLyricTranslation && !line.translation.isNullOrBlank(),
                            reserveRomanization = showLyricRomanization && !line.romanization.isNullOrBlank(),
                            onMeasured = { measured ->
                                if (measured > 0 && rowHeightsPx[index] != measured) rowHeightsPx[index] = measured
                            },
                            tapSeekEnabled = SettingsRuntime.lyricTapSeekEnabled,
                            onClick = { onInterfaceInteraction(); state.seekTo(line.timeMs) },
                        )
                        }

                        if (index != lines.lastIndex) {
                            Spacer(Modifier.height((UpstreamLyrics.LINE_SPACING_DP * lyricSpacingScale).dp))
                        }
                    }
                    item(key = "lyrics-bottom-padding") {
                        Spacer(Modifier.height(with(density) { (bottomPaddingPx + layoutOverscanPx).toDp() }))
                    }
                        }
                    },
                ) { measurables, constraints ->
                    val expandedHeight = (constraints.maxHeight.toLong() + layoutOverscanPx)
                        .coerceAtMost(Int.MAX_VALUE.toLong())
                        .toInt()
                    val list = measurables.single().measure(
                        constraints.copy(minHeight = expandedHeight, maxHeight = expandedHeight),
                    )
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        list.placeRelative(0, 0)
                    }
                }
            }
        }

        //  iOS reserves only the former bottom-controls region as the
        // hidden-controls tap target. Covering the complete viewport here made
        // every lyric stop receiving scroll input as soon as transport chrome
        // retracted, so the lines the user was browsing appeared to vanish or
        // become unreachable.
        if (isInterfaceHidden) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(NowPlayingControlsHeight.dp)
                    .zIndex(100f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onInterfaceInteraction,
                    ),
            )
        }
    }
}
