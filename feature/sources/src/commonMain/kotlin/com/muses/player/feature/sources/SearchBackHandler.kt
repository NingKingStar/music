package com.muses.player.feature.sources

import androidx.compose.runtime.Composable

@Composable
internal expect fun SearchBackHandler(enabled: Boolean, onBack: () -> Unit)
