package com.muses.player.core.lxsdk

import java.io.ByteArrayInputStream
import kotlin.test.*

class LxScriptTextReaderTest {
    private val source = "/* @name 音源🎵 */\nlx.send('inited', {sources:{}});"
    private fun read(bytes: ByteArray) = ByteArrayInputStream(bytes).use(::readLxScriptText)

    @Test fun `UTF8与带BOM的文本还原同一脚本`() {
        assertEquals(source, read(source.toByteArray(Charsets.UTF_8)))
        assertEquals(source, read(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + source.toByteArray()))
    }
    @Test fun `带BOM的UTF16大小端均正确解码`() {
        assertEquals(source, read(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + source.toByteArray(Charsets.UTF_16LE)))
        assertEquals(source, read(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + source.toByteArray(Charsets.UTF_16BE)))
    }
    @Test fun `空白和仅BOM的文件给出空文件提示`() {
        for (bytes in listOf(byteArrayOf(), " \r\n".toByteArray(), byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))) {
            assertTrue(assertFailsWith<IllegalArgumentException> { read(bytes) }.message!!.contains("为空"))
        }
    }
    @Test fun `二进制和损坏编码不会静默替换后送入引擎`() {
        assertTrue(assertFailsWith<IllegalArgumentException> { read(byteArrayOf(0, 1, 2)) }.message!!.contains("脚本文本"))
        assertTrue(assertFailsWith<IllegalArgumentException> { read(byteArrayOf(0xC3.toByte(), 0x28)) }.message!!.contains("编码"))
    }
    @Test fun `超大文件在读取阶段停止`() {
        assertTrue(assertFailsWith<IllegalArgumentException> { read(ByteArray(8 * 1024 * 1024 + 1) { 65 }) }.message!!.contains("8 MB"))
    }
}
