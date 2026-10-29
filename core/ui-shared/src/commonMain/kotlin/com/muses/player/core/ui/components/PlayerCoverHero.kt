package com.muses.player.core.ui.components

import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import top.yukonga.miuix.kmp.basic.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import coil3.compose.AsyncImage
import com.muses.player.core.ui.icons.TablerIcons
import top.yukonga.miuix.kmp.squircle.squircleClip

val LocalFindOtherSongVersions = androidx.compose.runtime.staticCompositionLocalOf<((String) -> Unit)?> { null }
val LocalPlayerCoverFailure = androidx.compose.runtime.staticCompositionLocalOf<((String) -> Unit)?> { null }

/**
 * `.player-page__cover-hero` —— 播放页封面 hero（aspect 1 正方形，圆角 12dp）。
 *
 * 对齐 Capacitor player-page__cover-hero：容器 max-height min(50vh,420px) +
 * cover-hero-img aspect 1 contain。封面加载走 Coil（file://`/`content://`/`data:`/`https:` 均可）。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PlayerCoverHero(
    coverUri: String?,
    modifier: Modifier = Modifier,
    screenHeight: Dp = 800.dp,
    screenWidth: Dp = 360.dp,
    isNarrowHeight: Boolean = false,
) {
    val onFailure = LocalPlayerCoverFailure.current
    val maxHeroHeight = remember(screenHeight) { minOf(screenHeight * 0.5f, 420.dp) }
    val narrowMaxWidth = remember(screenWidth, isNarrowHeight) {
        if (isNarrowHeight) minOf(screenWidth * 0.34f, 150.dp) else null
    }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeroHeight),
        contentAlignment = Alignment.Center,
    ) {
        val availableWidth = maxWidth
        val availableHeight = maxHeight
        val targetSize = remember(availableWidth, availableHeight, narrowMaxWidth) {
            when {
                narrowMaxWidth != null -> narrowMaxWidth
                else -> minOf(availableWidth, availableHeight)
            }
        }
        // 封面共享元素：两端同 key（迷你条与沉浸页正封），转场时由 Compose 把封面
        // 从迷你条尺寸插值到全屏正封尺寸；比只共享外壳更绲滑。
        // 仅当外层提供了作用域与 key 时才加（见 PlayerTransitionLocals）。
        val sharedArtworkScope = LocalPlayerSharedTransitionScope.current
        val sharedArtworkVisibility = LocalPlayerAnimatedVisibilityScope.current
        val sharedArtworkKey = LocalPlayerArtworkKey.current
        val artworkOverlayClip = LocalPlayerArtworkOverlayClip.current
        val artworkMorph = LocalPlayerArtworkMorph.current
        val density = LocalDensity.current
        val artworkSharedModifier =
            if (artworkMorph == null && sharedArtworkKey != null && sharedArtworkScope != null && sharedArtworkVisibility != null) {
                with(sharedArtworkScope) {
                    if (artworkOverlayClip != null) {
                        Modifier.sharedElement(
                            sharedContentState = rememberSharedContentState(sharedArtworkKey),
                            animatedVisibilityScope = sharedArtworkVisibility,
                            clipInOverlayDuringTransition = artworkOverlayClip,
                        )
                    } else {
                        Modifier.sharedElement(
                            sharedContentState = rememberSharedContentState(sharedArtworkKey),
                            animatedVisibilityScope = sharedArtworkVisibility,
                        )
                    }
                }
            } else {
                Modifier
            }
        Box(
            modifier = Modifier
                .size(targetSize)
                .aspectRatio(1f)
                .onGloballyPositioned { artworkMorph?.targetBounds = it.boundsInWindow() }
                .then(if (artworkMorph == null) Modifier.squircleClip(12.dp) else Modifier)
                // 有封面时外壳不能留底色：共享元素移动的是图片节点，
                // 外壳背景仍在原尺寸绘制，会在收起时留下半透明方框。
                .background(if (coverUri.isNullOrBlank()) Color.White.copy(alpha = 0.06f) else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            if (!coverUri.isNullOrBlank()) {
                AsyncImage(
                    model = coverUri,
                    onError = { onFailure?.invoke(coverUri) },
                    contentDescription = "封面",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .then(if (artworkMorph != null) Modifier.graphicsLayer {
                            clip = true
                            val target = artworkMorph.targetBounds
                            if (target != null && target.width > 0f) {
                                val p = artworkMorph.progress().coerceIn(0f, 1f)
                                val source = artworkMorph.sourceBounds
                                val sourceScale = source.width / target.width
                                val scale = sourceScale + (1f - sourceScale) * p
                                scaleX = scale
                                scaleY = scale
                                translationX = (source.center.x - target.center.x) * (1f - p)
                                translationY = (source.center.y - target.center.y) * (1f - p)
                                // 图层整体会缩放，裁剪半径须反向补偿；屏幕上的圆角始终为 12dp。
                                shape = RoundedCornerShape(with(density) { (12.dp.toPx() / scale).toDp() })
                            } else {
                                shape = RoundedCornerShape(12.dp)
                            }
                        } else Modifier)
                        // 与迷你条一样，把共享元素放到位图节点上而非尺寸外壳，
                        // 避免只插值容器 bounds、图片仍停留在迷你尺寸。
                        .then(artworkSharedModifier)
                        .fillMaxSize()
                        .then(if (artworkMorph == null) Modifier.squircleClip(12.dp) else Modifier)
                        .background(Color.White.copy(alpha = 0.06f)),
                )
            } else {
                Icon(TablerIcons.MusicNoteOutlined, contentDescription = null, tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.size(64.dp))
            }
        }
    }
}
