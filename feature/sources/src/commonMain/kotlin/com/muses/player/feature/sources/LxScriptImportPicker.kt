package com.muses.player.feature.sources

import androidx.compose.runtime.Composable

@Composable
expect fun rememberLxScriptFilePicker(onPicked: (Result<String>) -> Unit): () -> Unit

@Composable
expect fun rememberLxScriptUrlImporter(onImported: (Result<String>) -> Unit): (String) -> Unit
