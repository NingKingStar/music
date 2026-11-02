package com.muses.player.feature.shell.platform

import android.app.DownloadManager
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.muses.player.core.appupdate.*
import com.muses.player.core.playback.PlaybackPort
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.koin.core.context.GlobalContext
import java.io.File
import java.security.MessageDigest

internal data class AndroidUpdateState(
    val release: AndroidReleaseInfo? = null, val checking: Boolean = false, val status: String = "尚未检查更新",
    val error: String? = null, val downloadId: Long = -1, val progress: Int? = null, val ready: Boolean = false,
    val autoCheck: Boolean = true, val wifiDownload: Boolean = false, val checkedAt: Long = 0, val ignored: String? = null,
)

/** 下载由系统托管；进程重启后根据任务ID恢复，不保存任何安装授权。 */
internal class AndroidUpdateManager private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("app_update_v1", Context.MODE_PRIVATE)
    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val installed by lazy { context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES) }
    private val file get() = File(requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)) { "设备存储暂不可用，请稍后重试" }, "updates/update.apk")
    private val saved = runCatching { prefs.getString("release", null)?.let { json.decodeFromString<AndroidReleaseInfo>(it) } }.getOrNull()
    private val mutable = MutableStateFlow(AndroidUpdateState(release = saved,
        downloadId = prefs.getLong("download_id", -1), autoCheck = prefs.getBoolean("auto_check", true),
        wifiDownload = prefs.getBoolean("wifi_download", false), checkedAt = prefs.getLong("checked_at", 0),
        ignored = prefs.getString("ignored", null)))
    val state = mutable.asStateFlow()
    val currentVersion: String get() = installed.versionName.orEmpty()
    fun newer(release: AndroidReleaseInfo?) = release != null &&
        (release.versionCode?.let { it > installed.longVersionCode } ?: (compareVersions(release.versionName, currentVersion) > 0))
    private fun change(block: (AndroidUpdateState) -> AndroidUpdateState) { mutable.update(block) }
    private fun action(block: suspend () -> Unit) { scope.launch {
        try { mutex.withLock { block() } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { change { it.copy(checking = false, error = e.message ?: "更新操作失败，请重试") } }
    } }
    fun foreground() {
        refresh()
        val last = prefs.getLong("last_attempt", 0)
        val age = System.currentTimeMillis() - last
        if (state.value.autoCheck && (last == 0L || age !in 0..86_399_999L)) check(false)
    }
    fun setAutoCheck(value: Boolean) { prefs.edit().putBoolean("auto_check", value).apply(); change { it.copy(autoCheck = value) } }
    fun setWifiDownload(value: Boolean) {
        prefs.edit().putBoolean("wifi_download", value).apply(); change { it.copy(wifiDownload = value) }
        if (value) download(automatic = true)
        else if (prefs.getBoolean("download_automatic", false) && !state.value.ready) cancel()
    }
    fun check(manual: Boolean = true) = action {
        if (state.value.downloadId >= 0) return@action
        // 再次检查节流状态，避免多次前台事件排队后重复请求。
        val elapsed = System.currentTimeMillis() - prefs.getLong("last_attempt", 0)
        if (!manual && elapsed in 0..86_399_999L) return@action
        prefs.edit().putLong("last_attempt", System.currentTimeMillis()).apply()
        change { it.copy(checking = true, error = null, status = "正在检查更新…") }
        val release = checkAndroidRelease(currentVersion, context.packageName)
        val time = System.currentTimeMillis()
        prefs.edit().putString("release", json.encodeToString(AndroidReleaseInfo.serializer(), release)).putLong("checked_at", time).apply()
        change { it.copy(release = release, checking = false, checkedAt = time,
            status = if (newer(release)) "发现新版本 ${release.versionName}" else "当前已是最新版本") }
        if (state.value.wifiDownload && state.value.ignored != release.versionName) enqueue(automatic = true)
    }
    fun download(automatic: Boolean = false) = action { enqueue(automatic) }
    private fun enqueue(automatic: Boolean) {
        val release = state.value.release ?: return
        if (automatic && (!state.value.wifiDownload || state.value.ignored == release.versionName)) return
        if (!newer(release) || state.value.downloadId >= 0) return
        require(!release.sha256.isNullOrBlank()) { "发布方未提供校验值，请前往 GitHub 下载" }
        require(isOfficialReleaseAsset(release.downloadUrl)) { "安装包地址无效" }
        file.parentFile?.mkdirs()
        if (file.exists()) require(file.delete()) { "无法清理旧安装包，请重试" }
        val request = DownloadManager.Request(Uri.parse(release.downloadUrl))
            .setTitle("Muses ${release.versionName}").setDescription("正在下载应用更新")
            .setMimeType("application/vnd.android.package-archive")
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "updates/update.apk")
        if (automatic) request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI).setAllowedOverMetered(false)
        val id = downloads.enqueue(request)
        prefs.edit().putLong("download_id", id).putBoolean("download_automatic", automatic).apply()
        change { it.copy(downloadId = id, ready = false, error = null, progress = null, status = if (automatic) "等待 Wi-Fi 下载安装包" else "正在下载安装包…") }
    }
    fun cancel() = action { cancelLocked() }
    private fun cancelLocked() {
        if (state.value.downloadId >= 0) downloads.remove(state.value.downloadId)
        prefs.edit().remove("download_id").apply()
        file.delete()
        change { it.copy(downloadId = -1, ready = false, progress = null, error = null, status = "下载已取消，可重新下载") }
    }
    fun ignore() = action {
        val version = state.value.release?.versionName ?: return@action
        cancelLocked(); prefs.edit().putString("ignored", version).apply()
        change { it.copy(ignored = version, status = "已忽略此版本，可手动下载") }
    }
    fun refresh() = action { refreshLocked() }
    suspend fun completed() { mutex.withLock {
        try { refreshLocked() } catch (e: Exception) { change { it.copy(error = e.message ?: "更新校验失败", ready = false) } }
    } }
    private fun refreshLocked() {
        val id = state.value.downloadId
        if (id < 0 || state.value.ready) return
        downloads.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) {
                prefs.edit().remove("download_id").apply()
                change { it.copy(downloadId = -1, ready = false, error = "下载任务已被移除，请重新下载") }; return
            }
            fun number(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
            when (number(DownloadManager.COLUMN_STATUS).toInt()) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    try {
                        change { it.copy(status = "正在核验安装包…", progress = 100) }
                        verify()
                        change { it.copy(ready = true, error = null, status = "安装包已就绪，点击安装") }
                    } catch (e: Exception) {
                        downloads.remove(id); file.delete(); prefs.edit().remove("download_id").apply()
                        change { it.copy(downloadId = -1, ready = false, progress = null, error = e.message ?: "安装包校验失败，请重试") }
                    }
                }
                DownloadManager.STATUS_FAILED -> {
                    val reason = number(DownloadManager.COLUMN_REASON)
                    downloads.remove(id); prefs.edit().remove("download_id").apply()
                    change { it.copy(downloadId = -1, ready = false, error = "更新下载失败（$reason），可重试或前往 GitHub 下载") }
                }
                else -> {
                    val total = number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val read = number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val downloadStatus = number(DownloadManager.COLUMN_STATUS).toInt()
                    change { it.copy(progress = if (total > 0) ((read * 100 / total).toInt().coerceIn(0, 99)) else null,
                        status = when (downloadStatus) {
                            DownloadManager.STATUS_PENDING -> "等待系统开始下载…"
                            DownloadManager.STATUS_PAUSED -> "等待网络或系统继续下载…"
                            else -> "正在下载安装包…"
                        }) }
                }
            }
        }
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun verify() {
        val release = requireNotNull(state.value.release)
        verifyAndroidUpdateFile(file, release)
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error("无法识别安装包")
        val old = installed.signingInfo?.apkContentsSigners?.map { digest(it.toByteArray()) }?.toSet().orEmpty()
        val next = archive.signingInfo?.apkContentsSigners?.map { digest(it.toByteArray()) }?.toSet().orEmpty()
        verifyAndroidUpdateIdentity(release, AndroidPackageIdentity(context.packageName, installed.versionName, installed.longVersionCode, old),
            AndroidPackageIdentity(archive.packageName, archive.versionName, archive.longVersionCode, next))
    }
    fun install() = action {
        require(state.value.ready) { "请先下载并核验安装包" }
        require(!GlobalContext.get().get<PlaybackPort>().isPlaying.value) { "请先暂停音乐，再安装更新" }
        verify()
        withContext(Dispatchers.Main) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                change { it.copy(status = "允许安装后，请返回并再次点击安装") }
            } else {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
    companion object {
        @Volatile private var instance: AndroidUpdateManager? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: AndroidUpdateManager(context.applicationContext).also { instance = it } }
    }
}

class AppUpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val manager = AndroidUpdateManager.get(context)
        if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -2) != manager.state.value.downloadId) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch { try { manager.completed() } finally { pending.finish() } }
    }
}
