package com.muses.player.core.ui.components

import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.utils.pressable
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.squircle.squircleClip

/**
 * 跨平台曲目行数据（平台无关，只承载展示信息）。
 *
 * Android 端由调用方从 [com.muses.player.core.data.db.SongEntity] 映射而来；
 * Desktop 端由调用方从同样的 [com.muses.player.core.data.db.SongEntity] 映射而来。
 */
data class SongItem(
    val id: String,
    val title: String,
    val artist: String? = null,
    val albumTitle: String? = null,
    /**
     * 封面（远程 URL 或本地 URI）。
     * null = 显示共用占位；调用方仍可通过 showCover 关闭封面位。
     */
    val coverUri: String? = null,
    val qualityLabel: String? = null,
)

/**
 * 跨平台曲目行组件（U5 曲目列表共用化）。
 *
 * 视觉契约（对照桌面 LibraryScreen SongRow + SaltListItem 风格）：
 * - 行高自适应，44dp 封面与上下各 10dp 留白，常规行约 64dp;
 * - 标题：scheme.onBackground / 15sp，当前曲仅使用 primary 色，字重与曲库一致;
 * - 副标题：artist - albumTitle，scheme.onBackgroundVariant / 12sp，单行省略;
 * - 当前行：标题与副标题使用 primary 色；各行保持透明底，无额外播放图标；
 * - 按压态：官方 pressable(SinkFeedback) 下沉反馈 + surface 变色叠层；
 * - 纯 UI 组件，零平台依赖，所有业务逻辑经回调注入。
 *
 * @param song 曲目数据
 * @param isCurrent 是否为当前播放曲
 * @param onClick 点击回调
 * @param onLongClick 长按回调（null = 不支持长按）
 */
@Composable
fun SongListItem(
    song: SongItem,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    titleBadgeLabel: String? = null,
    qualityBadgeLabel: String? = song.qualityLabel,
    showCover: Boolean = true,
    isLiked: Boolean = false,
) {
    val scheme = MiuixTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val bgColor by animateColorAsState(
        if (pressed) scheme.primary.copy(alpha = 0.09f) else Color.Transparent,
        animationSpec = tween(120), label = "歌曲行按压",
    )

    androidx.compose.runtime.CompositionLocalProvider(
        LocalTextMarqueeEnabled provides LocalSongMarqueeState.current.matches(song.id),
    ) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .squircleClip(8.dp)
            // 官方按压反馈（HyperOS 下沉效果），与下方 clickable 共享 interactionSource
            .pressable(interactionSource = interactionSource, indication = SinkFeedback())
            .background(bgColor)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                } else {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
                },
            )
            .padding(horizontal = SongListLayout.horizontalPadding, vertical = SongListLayout.verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 封面列表保留稳定占位，纯文字列表仍可不显示封面。
        if (showCover) {
            MusesCover(
                uri = song.coverUri,
                size = SongListLayout.coverSize,
                radius = MusesCoverRadius.SM,
            )
            Spacer(Modifier.width(SongListLayout.coverGap))
        }

        // 文字区：标题 + 副标题
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.weight(1f),
        ) {
            SongTitleWithBadge(
                title = song.title,
                label = titleBadgeLabel,
                color = if (isCurrent) scheme.primary else scheme.onBackground,
                style = MiuixTheme.textStyles.body2.copy(fontSize = SongListLayout.titleSize,
                    lineHeight = SongListLayout.titleLineHeight),
            )
            val subtitle = listOfNotNull(song.artist, song.albumTitle)
                .filter { it.isNotBlank() }
                .joinToString(" - ")
            if (subtitle.isNotBlank() || qualityBadgeLabel != null || isLiked) {
                Spacer(Modifier.size(SongListLayout.textGap))
                SongSubtitleWithQuality(
                    subtitle = subtitle,
                    label = qualityBadgeLabel,
                    isLiked = isLiked,
                    color = if (isCurrent) scheme.primary else scheme.onBackgroundVariant,
                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = SongListLayout.subtitleSize,
                        lineHeight = SongListLayout.subtitleLineHeight),
                )
            }
        }

        if (trailingContent != null) {
            Spacer(Modifier.width(SongListLayout.actionGap))
            trailingContent()
        }
    }
    }
}
