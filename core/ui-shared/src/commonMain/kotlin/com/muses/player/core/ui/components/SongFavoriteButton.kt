package com.muses.player.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import com.muses.player.core.data.repository.FavoriteSongsState
import com.muses.player.core.model.Song
import com.muses.player.core.ui.icons.TablerIcons
import top.yukonga.miuix.kmp.theme.MiuixTheme

val LocalFavoriteSongs = staticCompositionLocalOf { FavoriteSongsState() }
val LocalSetSongLiked = staticCompositionLocalOf<((Song, Boolean) -> Unit)?> { null }

/** 列表中的轻量状态标记，操作仍由更多菜单提供。 */
@Composable
fun SongFavoriteMark() {
    Icon(
        imageVector = TablerIcons.HeartFill,
        contentDescription = "我喜欢",
        modifier = Modifier.size(12.dp),
        tint = Color(0xFFE85B65),
    )
}

@Composable
fun SongFavoriteButton(song: Song, likedTint: Color = Color.Unspecified, unlikedTint: Color = Color.Unspecified) {
    val setLiked = LocalSetSongLiked.current ?: return
    val favorites = LocalFavoriteSongs.current
    val liked = favorites.isLiked(song)
    MusesIconButton(
        onClick = { setLiked(song, !liked) },
        imageVector = if (liked) TablerIcons.HeartFill else TablerIcons.Heart,
        contentDescription = if (liked) "取消我喜欢" else "标记我喜欢",
        enabled = favorites.loaded,
        tint = if (liked) likedTint.takeUnless { it == Color.Unspecified } ?: MiuixTheme.colorScheme.primary
            else unlikedTint.takeUnless { it == Color.Unspecified } ?: MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}
