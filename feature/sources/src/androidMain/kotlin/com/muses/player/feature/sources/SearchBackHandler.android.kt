package com.muses.player.feature.sources

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
internal actual fun SearchBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}
