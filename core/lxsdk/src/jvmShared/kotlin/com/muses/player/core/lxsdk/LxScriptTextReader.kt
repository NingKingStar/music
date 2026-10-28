package com.muses.player.core.lxsdk

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** 文件与 URL 共用有界文本读取；后缀/MIME 不决定脚本是否有效，后续仍须引擎预检。 */
fun readLxScriptText(input: InputStream): String {
    val limit = 8 * 1024 * 1024
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit) { "脚本文件超过 8 MB，请确认选择的是音源脚本。" }
        output.write(buffer, 0, count)
    }
    val data = output.toByteArray()
    fun starts(vararg bytes: Int) = data.size >= bytes.size && bytes.indices.all {
        (data[it].toInt() and 255) == bytes[it]
    }
    val (charset, offset) = when {
        starts(0xFF, 0xFE) -> Charsets.UTF_16LE to 2
        starts(0xFE, 0xFF) -> Charsets.UTF_16BE to 2
        starts(0xEF, 0xBB, 0xBF) -> Charsets.UTF_8 to 3
        else -> Charsets.UTF_8 to 0
    }
    val text = try {
        charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data, offset, data.size - offset)).toString()
    } catch (e: java.nio.charset.CharacterCodingException) {
        throw IllegalArgumentException("无法识别文本编码，请使用 UTF-8 或带 BOM 的 UTF-16 脚本。", e)
    }
    require(text.isNotBlank()) { "脚本文件为空，请选择包含音源脚本的 .js 或 .txt 文件。" }
    require(text.none { it.code < 32 && it !in "\t\r\n" }) {
        "所选文件不是可读取的脚本文本，请选择音源 .js 或 .txt 文件。"
    }
    return text
}
