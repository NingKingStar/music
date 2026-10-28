package com.muses.player.feature.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.muses.player.core.lxsdk.readLxScriptText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

@Composable
actual fun rememberLxScriptFilePicker(onPicked: (Result<String>) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val callback = rememberUpdatedState(onPicked)
    return {
        SwingUtilities.invokeLater {
            val chooser = JFileChooser().apply { dialogTitle = "选择音源脚本（.js / .txt）"; isMultiSelectionEnabled = false }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { chooser.selectedFile.inputStream().use(::readLxScriptText) }
                    }
                    callback.value(result)
                }
            }
        }
    }
}

@Composable
actual fun rememberLxScriptUrlImporter(onImported: (Result<String>) -> Unit): (String) -> Unit {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return { url -> scope.launch {
        onImported(runCatching { withContext(Dispatchers.IO) {
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
        } })
    } }
}
