package com.muses.player.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import com.muses.player.desktop.playback.DesktopErrorLog
import com.muses.player.desktop.playback.DesktopPlayerHook
import com.muses.player.desktop.smtc.SmtcController
import com.muses.player.desktop.smtc.SmtcMetadata
import com.muses.player.desktop.tray.DesktopTray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

fun main() = application {
    val state = remember {
        WindowState(
            position = WindowPosition(100.dp, 100.dp),
            size = DpSize(1280.dp, 800.dp),
        )
    }
    // 单一 hook：screens 与托盘/SMTC 共享同一播放状态源（U12 收敛到 DesktopRuntime 单例）
    val playerHook = remember { DesktopRuntime.playerHook() }
    val tray = remember {
        DesktopTray(
            isPlaying = playerHook.isPlaying,
            onShowMainWindow = { state.isMinimized = false },
            onTogglePlay = playerHook::togglePlayPause,
            onNext = playerHook::next,
            onPrevious = playerHook::previous,
            onExit = ::exitApplication,
            onLyricsSettings = DesktopLyricsInteraction::openSettings,
            lyricsVisible = DesktopLyricsInteraction.visible,
        )
    }
    // SMTC 元数据：曲库列表 × 当前曲目（播放状态/进度由 controller 内部订阅，见 install）
    val smtcMetadata: StateFlow<SmtcMetadata?> = remember {
        combine(playerHook.songs, playerHook.currentSongId) { songs, songId ->
            songId?.let { id -> songs.firstOrNull { it.id == id } }
                ?.let { song -> SmtcMetadata(title = song.title, artist = song.artist, album = song.albumTitle) }
        }.stateIn(CoroutineScope(Dispatchers.Default), SharingStarted.Eagerly, null)
    }
    val smtc = remember {
        SmtcController(
            errorLog = { tag, msg, e -> DesktopErrorLog.log(tag, msg, e) },
        )
    }
    DisposableEffect(Unit) {
        tray.install()
        smtc.install(
            windowTitle = "Muses",
            metadata = smtcMetadata,
            isPlaying = playerHook.isPlaying,
            positionMs = playerHook.positionMs,
            durationMs = playerHook.durationMs,
            onTogglePlay = playerHook::togglePlayPause,
            onNext = playerHook::next,
            onPrevious = playerHook::previous,
        )
        onDispose {
            smtc.uninstall()
            tray.uninstall()
        }
    }

    Window(
        onCloseRequest = ::exitApplication,
        state = state,
        title = "Muses",
        icon = painterResource("muses/window-icon.png"),
        undecorated = true,
        transparent = false,
    ) {
        MusesDesktopApp(
            windowState = state,
            onClose = ::exitApplication,
        )
    }
}
