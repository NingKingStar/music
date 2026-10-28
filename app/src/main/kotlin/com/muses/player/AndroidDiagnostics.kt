package com.muses.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import com.muses.player.core.data.diagnostics.Diagnostics
import com.muses.player.core.data.diagnostics.PersistentDiagnostics
import java.io.File

/** 仅观察系统默认网络，不能据此推断请求实际网络或 Wi-Fi Direct。 */
internal fun installDiagnostics(context: Context) {
    try {
        val store = PersistentDiagnostics(File(context.filesDir, "diagnostics"))
        Diagnostics.sink = store::record
        Diagnostics.identify = store::anonymous
        Diagnostics.contextProvider = store::context
        Diagnostics.startPlayback = store::beginPlayback
        Diagnostics.trackTransition = store::transition
        Diagnostics.trackBinding = store::bindTrack
        Diagnostics.trackContextProvider = store::contextForTrack
        Diagnostics.capturedTrackContextProvider = { uri, candidate -> store.contextForTrack(uri, candidate) }
        Diagnostics.songContextProvider = store::contextForSong
        org.koin.core.context.GlobalContext.get().declare(store)
        Diagnostics.record("environment", mapOf("version" to BuildConfig.VERSION_CODE.toString(), "version_name" to BuildConfig.VERSION_NAME, "commit" to BuildConfig.DIAGNOSTIC_COMMIT, "sdk" to Build.VERSION.SDK_INT.toString()))
        installPlaybackEnvironmentDiagnostics(context)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun emit(network: Network, caps: NetworkCapabilities?, reason: String) {
                val transport = when {
                    caps == null -> "unknown"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    else -> "other"
                }
                Diagnostics.record("default_network", mapOf("network" to store.anonymous(network.toString()), "reason" to reason,
                    "transport" to transport, "internet" to (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)?.toString() ?: "未知"),
                    "validated" to (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)?.toString() ?: "未知"), "actual_network" to "未知"))
            }
            override fun onAvailable(network: Network) { emit(network, cm.getNetworkCapabilities(network), "available") }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { emit(network, caps, "changed") }
            override fun onLost(network: Network) { emit(network, null, "lost") }
        }
        cm.registerDefaultNetworkCallback(callback)
    } catch (_: Throwable) { /* 诊断失败不影响应用启动。 */ }
}

/** 只记录系统可见设备类型；可用设备不代表播放器实际选用的路由。 */
private fun installPlaybackEnvironmentDiagnostics(context: Context) {
    try {
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        fun emitDevices(devices: Array<out android.media.AudioDeviceInfo>, reason: String) {
            try {
                val outputs = devices.filter { it.isSink }.map { it.type }.distinct().take(32)
                if (outputs.isEmpty()) Diagnostics.record("audio_devices", mapOf("audio_output" to "未知", "actual_audio_output" to "未知", "reason" to reason))
                outputs.forEach { type -> Diagnostics.record("audio_devices", mapOf("audio_output" to type.toString(), "actual_audio_output" to "未知", "reason" to reason)) }
            } catch (_: Throwable) { Diagnostics.record("audio_devices", mapOf("audio_output" to "未知", "actual_audio_output" to "未知")) }
        }
        emitDevices(audio.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS), "available")
        audio.registerAudioDeviceCallback(object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>) { emitDevices(addedDevices, "available") }
            override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>) { emitDevices(removedDevices, "lost") }
        }, android.os.Handler(android.os.Looper.getMainLooper()))
    } catch (_: Throwable) { Diagnostics.record("audio_devices", mapOf("audio_output" to "未知", "actual_audio_output" to "未知")) }

    try {
        val power = context.getSystemService(android.os.PowerManager::class.java)
        fun emitPower() {
            try {
                Diagnostics.record("power_environment", mapOf("power_save" to power.isPowerSaveMode.toString(),
                    "device_idle" to power.isDeviceIdleMode.toString(), "interactive" to power.isInteractive.toString(),
                    "optimization_exempt" to power.isIgnoringBatteryOptimizations(context.packageName).toString()))
            } catch (_: Throwable) { Diagnostics.record("power_environment", mapOf("power_save" to "未知", "device_idle" to "未知", "interactive" to "未知", "optimization_exempt" to "未知")) }
        }
        emitPower()
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: android.content.Intent?) { emitPower() }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(android.os.PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
    } catch (_: Throwable) { Diagnostics.record("power_environment", mapOf("power_save" to "未知", "device_idle" to "未知", "interactive" to "未知", "optimization_exempt" to "未知")) }

    try {
        val app = context.applicationContext as? android.app.Application
        if (app == null) {
            Diagnostics.record("app_visibility", mapOf("visibility" to "未知"))
        } else {
            Diagnostics.record("app_visibility", mapOf("visibility" to "background", "count" to "0"))
            app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
                private var started = 0
                override fun onActivityStarted(activity: android.app.Activity) {
                    started++
                    if (started == 1) Diagnostics.record("app_visibility", mapOf("visibility" to "foreground", "count" to started.toString()))
                }
                override fun onActivityStopped(activity: android.app.Activity) {
                    started = (started - 1).coerceAtLeast(0)
                    if (started == 0) Diagnostics.record("app_visibility", mapOf("visibility" to "background", "count" to "0"))
                }
                override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) {}
                override fun onActivityResumed(activity: android.app.Activity) {}
                override fun onActivityPaused(activity: android.app.Activity) {}
                override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) {}
                override fun onActivityDestroyed(activity: android.app.Activity) {}
            })
        }
    } catch (_: Throwable) { Diagnostics.record("app_visibility", mapOf("visibility" to "未知")) }
}
