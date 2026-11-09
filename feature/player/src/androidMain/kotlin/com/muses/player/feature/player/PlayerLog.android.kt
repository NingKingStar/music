package com.muses.player.feature.player

actual fun playerLog(message: String) {
    android.util.Log.i("MusesPlayer", message)
}
