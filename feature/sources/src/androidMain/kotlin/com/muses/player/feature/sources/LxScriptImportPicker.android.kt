package com.muses.player.feature.sources

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.muses.player.core.lxsdk.readLxScriptText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

@Composable
actual fun rememberLxScriptFilePicker(onPicked: (Result<String>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val callback = rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw IOException("无法打开所选文件，请将文件下载到本地后重试。")
                    input.use(::readLxScriptText)
                }
            }
            callback.value(result)
        }
    }
    // DocumentsProvider 对 js/txt 的 MIME 标记并不一致，选择阶段不据此禁用文件。
    // 正文读取与真实脚本引擎预检负责判断能否导入。
    return { launcher.launch(arrayOf("*/*")) }
}

@Composable
actual fun rememberLxScriptUrlImporter(onImported: (Result<String>) -> Unit): (String) -> Unit {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return { url ->
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    require(url.startsWith("https://") || url.startsWith("http://")) { "请输入有效的 HTTP/HTTPS 地址" }
                    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 30000
                    }
                    try {
                        val status = connection.responseCode
                        if (status !in 200..299) {
                            throw IOException(if (status == 404) "脚本地址不存在（HTTP 404）" else "下载脚本失败（HTTP $status）")
                        }
                        connection.inputStream.use(::readLxScriptText)
                    } finally {
                        connection.disconnect()
                    }
                }
            }
            onImported(result)
        }
    }
}
