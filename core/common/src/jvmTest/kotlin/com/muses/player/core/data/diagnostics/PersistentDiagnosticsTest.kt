package com.muses.player.core.data.diagnostics

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.*
import kotlin.test.*

class PersistentDiagnosticsTest {
    private fun directory() = Files.createTempDirectory("诊断独立测试").toFile().apply { deleteOnExit() }
    private fun entries(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes().toString(Charsets.UTF_8)) }
        }
    }
    private fun records(dir: File) = dir.listFiles().orEmpty().filter { it.extension == "jsonl" }
        .flatMap { it.readLines().filter(String::isNotBlank) }.map { Json.parseToJsonElement(it).jsonObject }
    private fun JsonObject.value(key: String) = getValue(key).jsonPrimitive.content

    @Test fun `敏感输入不会进入导出且非法字段有计数`() {
        val dir = directory()
        PersistentDiagnostics(dir).use { log ->
            val secret = "原始歌名-secret https://192.168.1.9/a?key=签名 C:\\Users\\用户\\song.mp3 SSID-private"
            log.beginPlayback(secret, "user")
            log.record("request_open", mapOf("request" to log.anonymous("请求一"), "resource" to secret, "url" to secret, "reason" to secret))
            val output = ByteArrayOutputStream()
            val summary = log.export(output)
            val text = entries(output.toByteArray()).values.joinToString()
            listOf("原始歌名", "192.168.1.9", "key=", "Users", "SSID-private", "请求一").forEach { assertFalse(text.contains(it), it) }
            assertTrue(summary.truncated >= 3)
            assertTrue(entries(output.toByteArray()).getValue("摘要.txt").contains("truncated=${summary.truncated}"))
        }
    }

    @Test fun `同运行关联稳定而重启匿名值不同且时间可控`() {
        val dir = directory()
        var first = ""
        PersistentDiagnostics(dir, wallClockMs = { 123L }, monotonicNanos = { 456L }).use { log ->
            first = log.anonymous("原始歌曲")
            assertEquals(first, log.anonymous("原始歌曲"))
            log.beginPlayback("原始歌曲", "user")
            log.record("resolve", mapOf("request" to log.anonymous("请求")))
            assertTrue(log.flush())
            val rows = records(dir)
            assertEquals(2, rows.size)
            assertEquals(1, rows.map { it.value("run") }.distinct().size)
            assertEquals(1, rows.map { it.value("session") }.distinct().size)
            assertTrue(rows.all { it.value("song") == first && it.value("wall_ms") == "123" && it.value("mono_ns") == "456" })
        }
        PersistentDiagnostics(directory()).use { assertNotEquals(first, it.anonymous("原始歌曲")) }
    }

    @Test fun `新播放会话不同并保留歌曲匿名关联`() {
        val dir = directory()
        PersistentDiagnostics(dir).use { log ->
            log.beginPlayback("同一首", "user"); log.record("ready")
            log.beginPlayback("同一首", "user"); log.record("ready")
            assertTrue(log.flush())
            val rows = records(dir)
            assertEquals(2, rows.map { it.value("session") }.distinct().size)
            assertEquals(1, rows.map { it.value("song") }.distinct().size)
        }
    }

    @Test fun `并发写入序号按落盘顺序递增或明确报告丢失`() {
        val dir = directory()
        PersistentDiagnostics(dir, queueCapacity = 1024).use { log ->
            val pool = Executors.newFixedThreadPool(8)
            repeat(8) { pool.submit { repeat(100) { log.record("state", mapOf("state" to "ready")) } } }
            pool.shutdown(); assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS)); assertTrue(log.flush())
            val seq = records(dir).map { it.value("seq").toLong() }
            assertEquals(seq.distinct().size, seq.size)
            assertTrue(seq.zipWithNext().all { (a,b) -> a < b }, "并发事件的落盘顺序与序号不一致")
            val result = log.export(ByteArrayOutputStream())
            assertEquals(800L, seq.size.toLong() + result.dropped)
        }
    }

    @Test fun `轮转后的文件数和每文件大小有界且每行可解析`() {
        val dir = directory()
        PersistentDiagnostics(dir, maxFileBytes = 700, maxFiles = 3).use { log ->
            repeat(60) { log.record("state", mapOf("count" to "$it")); assertTrue(log.flush()) }
            val files = dir.listFiles().orEmpty().filter { it.extension == "jsonl" }
            assertEquals(3, files.size)
            assertTrue(files.all { it.length() <= 700 })
            assertTrue(records(dir).isNotEmpty())
        }
    }

    @Test fun `重启保留旧运行且不会重用运行标识`() {
        val dir = directory()
        PersistentDiagnostics(dir).use { it.record("ready"); assertTrue(it.flush()) }
        PersistentDiagnostics(dir).use { it.record("ended"); assertTrue(it.flush()) }
        assertEquals(2, records(dir).size)
        assertEquals(2, records(dir).map { it.value("run") }.distinct().size)
    }

    @Test fun `七天前日志被清理而保留期内日志仍可导出`() {
        val dir = directory()
        val now = System.currentTimeMillis()
        val old = File(dir, "diagnostic-1.jsonl").apply { writeText("旧日志"); assertTrue(setLastModified(now - 8L * 86400000)) }
        val recent = File(dir, "diagnostic-2.jsonl").apply { writeText("{}\n"); assertTrue(setLastModified(now - 86400000)) }
        PersistentDiagnostics(dir, wallClockMs = { now }).use { log ->
            val out = ByteArrayOutputStream(); log.export(out)
            assertFalse(old.exists()); assertTrue(recent.exists())
            assertTrue(entries(out.toByteArray()).containsKey(recent.name))
        }
    }

    @Test fun `导出排除旧错误与其他文件并且不关闭调用方流`() {
        val dir = directory()
        File(dir, "errors.log").writeText("秘密旧堆栈 token=abc")
        File(dir, "private.txt").writeText("原始私密文本")
        val out = object : ByteArrayOutputStream() { var wasClosed = false; override fun close() { wasClosed = true; super.close() } }
        PersistentDiagnostics(dir).use { log ->
            log.record("ready"); val result = log.export(out)
            val files = entries(out.toByteArray())
            assertEquals(setOf("diagnostic-0.jsonl", "摘要.txt"), files.keys)
            assertEquals(1, result.files); assertTrue(result.bytes > 0)
            assertFalse(out.wasClosed)
            assertFalse(files.values.joinToString().contains("秘密"))
        }
    }

    @Test fun `写入失败被隔离且在摘要可见`() {
        val dir = directory(); val invalid = File(dir, "普通文件").apply { writeText("占用目录位置") }
        PersistentDiagnostics(invalid).use { log ->
            repeat(4) { log.record("ready") }
            assertTrue(log.flush())
            val summary = log.export(ByteArrayOutputStream())
            assertEquals(4L, summary.writeFailures)
            assertEquals(0, summary.files)
        }
    }

    @Test fun `导出输出失败后记录仍然可用`() {
        val dir = directory()
        PersistentDiagnostics(dir).use { log ->
            log.record("ready")
            assertFailsWith<IOException> { log.export(object : OutputStream() { override fun write(b: Int) { throw IOException("注入输出失败") } }) }
            log.record("playing"); assertTrue(log.flush())
            val out = ByteArrayOutputStream(); log.export(out)
            assertEquals(2, records(dir).size)
        }
    }

    @Test fun `导出中断不得伤及后续记录`() {
        val dir = directory()
        PersistentDiagnostics(dir).use { log ->
            log.record("ready"); assertTrue(log.flush())
            Thread.currentThread().interrupt()
            try { assertFailsWith<java.io.InterruptedIOException> { log.export(ByteArrayOutputStream()) } }
            finally { Thread.interrupted() }
            log.record("playing"); assertTrue(log.flush())
            assertEquals(2, records(dir).size)
        }
    }

    @Test fun `超长和过量字段不会令单事件无限增大`() {
        val dir = directory()
        PersistentDiagnostics(dir).use { log ->
            val fields = (0..100).associate { "timing_" + "a".repeat(100) + it to "私密".repeat(10000) }
            log.record("a".repeat(10000), fields)
            assertTrue(log.flush())
            val out = ByteArrayOutputStream(); val summary = log.export(out)
            assertTrue(summary.truncated >= 100)
            assertTrue(records(dir).all { it.toString().toByteArray().size < 8192 })
            assertFalse(entries(out.toByteArray()).values.joinToString().contains("私密"))
        }
    }
    @Test fun `业务字段名称也不能携带敏感信息`() {
        val dir = directory()
        PersistentDiagnostics(dir).use { log ->
            log.record("ready", mapOf("timing_原始歌名_192.168.1.9" to "123", "timing_https://host/key=abc" to "456"))
            val out = ByteArrayOutputStream(); log.export(out)
            val text = entries(out.toByteArray()).values.joinToString()
            assertFalse(text.contains("原始歌名")); assertFalse(text.contains("192.168.1.9")); assertFalse(text.contains("key=abc"))
        }
    }    @Test fun `队列超载会计数且不会阻塞生产者`() {
        val dir = directory()
        val writerEntered = java.util.concurrent.CountDownLatch(1)
        val releaseWriter = java.util.concurrent.CountDownLatch(1)
        val now = System.currentTimeMillis()
        PersistentDiagnostics(dir, queueCapacity = 1, wallClockMs = {
            if (Thread.currentThread().name == "本地诊断写入") {
                writerEntered.countDown()
                check(releaseWriter.await(10, TimeUnit.SECONDS))
            }
            now
        }).use { log ->
            try {
                log.record("ready")
                assertTrue(writerEntered.await(5, TimeUnit.SECONDS))
                log.record("playing")
                log.record("buffering")
                releaseWriter.countDown()
                assertTrue(log.flush())
                val summary = log.export(ByteArrayOutputStream())
                assertEquals(1L, summary.dropped)
                assertEquals(2, records(dir).size)
            } finally { releaseWriter.countDown() }
        }
    }    @Test fun `环境字段仅保留受控状态而不接收设备名称地址`() {
        val dir = directory()
        PersistentDiagnostics(dir).use { log ->
            log.record("environment", mapOf("audio_output" to "8", "actual_audio_output" to "未知", "power_save" to "true", "device_idle" to "false", "interactive" to "true", "optimization_exempt" to "false", "visibility" to "foreground"))
            log.record("environment", mapOf("audio_output" to "私密耳机名称-aa:bb:cc:dd:ee:ff", "visibility" to "私密界面名称"))
            assertTrue(log.flush())
            val first = records(dir).first()
            assertEquals("8", first.value("audio_output")); assertEquals("foreground", first.value("visibility"))
            assertEquals("true", first.value("power_save")); assertEquals("未知", first.value("actual_audio_output"))
            val out = ByteArrayOutputStream(); val summary = log.export(out)
            val text = entries(out.toByteArray()).values.joinToString()
            assertFalse(text.contains("私密")); assertFalse(text.contains("aa:bb:cc")); assertTrue(summary.truncated >= 2)
        }
    }    @Test fun `外部导出流阻塞期间本地记录和刷新仍推进`() {
        val dir = directory()
        val exportEntered = java.util.concurrent.CountDownLatch(1)
        val releaseExport = java.util.concurrent.CountDownLatch(1)
        val exporter = Executors.newSingleThreadExecutor()
        PersistentDiagnostics(dir).use { log ->
            try {
                log.record("ready"); assertTrue(log.flush())
                val future = exporter.submit {
                    log.export(object : OutputStream() {
                        override fun write(b: Int) {
                            exportEntered.countDown()
                            check(releaseExport.await(10, TimeUnit.SECONDS))
                        }
                    })
                }
                assertTrue(exportEntered.await(5, TimeUnit.SECONDS))
                log.record("playing")
                assertTrue(log.flush(1000), "导出目标阻塞不应阻塞本地诊断写入")
                assertTrue(records(dir).any { it.value("event") == "playing" })
                releaseExport.countDown(); future.get(5, TimeUnit.SECONDS)
            } finally { releaseExport.countDown(); exporter.shutdownNow() }
        }
    }

    @Test fun `进程中断留下半行后重启仍保留新完整事件`() {
        val dir = directory()
        PersistentDiagnostics(dir).use { log -> log.record("ready"); assertTrue(log.flush()) }
        File(dir, "diagnostic-0.jsonl").appendText("{\"event\":\"中断半行")
        PersistentDiagnostics(dir).use { log ->
            log.record("playing"); assertTrue(log.flush())
            val rows = records(dir)
            assertTrue(rows.any { it.value("event") == "ready" })
            assertTrue(rows.any { it.value("event") == "playing" })
            val out = ByteArrayOutputStream(); val summary = log.export(out)
            assertTrue(summary.truncated >= 1, "半行丢失应计数")
        }
    }    @Test fun `目标身份核验拒绝借用当前另一首会话`() {
        PersistentDiagnostics(directory()).use { log ->
            log.bindTrack("私密甲URI", "甲ID"); log.bindTrack("私密乙URI", "乙ID")
            log.beginPlayback("甲ID", "user")
            val first = log.context()
            assertEquals(first["session"], log.contextForTrack("私密甲URI")["session"])
            assertEquals(first["song"], log.contextForTrack("私密甲URI")["song"])
            assertEquals("未知", log.contextForTrack("私密乙URI")["session"])
            assertEquals(log.anonymous("乙ID"), log.contextForTrack("私密乙URI")["song"])
            log.beginPlayback("乙ID", "user")
            assertEquals("未知", log.contextForTrack("私密甲URI")["session"])
            assertEquals(log.anonymous("甲ID"), log.contextForTrack("私密甲URI")["song"])
        }
    }

    @Test fun `未绑定目标仍有匿名身份且不借当前会话`() {
        PersistentDiagnostics(directory()).use { log ->
            log.beginPlayback("当前歌曲", "user")
            val target = log.contextForTrack("https://192.168.1.9/private?key=secret")
            assertEquals("未知", target["session"])
            assertEquals(log.anonymous("https://192.168.1.9/private?key=secret"), target["song"])
        }
    }    @Test fun `未绑定地址恰等当前歌曲标识也不能借用会话`() {
        PersistentDiagnostics(directory()).use { log ->
            val identical = "原始歌曲标识与地址相同"
            log.beginPlayback(identical, "user")
            val unbound = log.contextForTrack(identical)
            assertEquals("未知", unbound["session"])
            assertEquals(log.anonymous(identical), unbound["song"])
        }
    }    @Test fun `播放器实际仍甲而全局已乙时甲状态不可借乙会话`() {
        PersistentDiagnostics(directory()).use { log ->
            log.beginPlayback("甲ID", "user")
            val original = log.contextForSong("甲ID")
            assertEquals("未知", original["session"])
            log.beginPlayback("乙ID", "user")
            val actual = log.contextForSong("甲ID")
            assertEquals(log.anonymous("甲ID"), actual["song"])
            assertEquals("未知", actual["session"])
            assertNotEquals(log.context()["session"], actual["session"])
        }
    }    @Test fun `同歌重播无法判定回调代次时会话保持未知`() {
        PersistentDiagnostics(directory()).use { log ->
            log.beginPlayback("同歌", "user"); val first = log.context()
            log.beginPlayback("同歌", "user"); val second = log.context()
            assertNotEquals(first["session"], second["session"])
            val callback = log.contextForSong("同歌")
            assertEquals(first["song"], callback["song"])
            assertEquals("未知", callback["session"])
        }
    }    @Test fun `请求创建快照核验不借首开时同歌新代次`() {
        PersistentDiagnostics(directory()).use { log ->
            log.bindTrack("甲地址", "甲"); log.bindTrack("乙地址", "乙")
            log.beginPlayback("甲", "user"); val captured = log.context()
            log.beginPlayback("甲", "user"); val newer = log.context()
            val firstOpen = log.contextForTrack("甲地址", captured)
            assertNotEquals(newer["session"], firstOpen["session"])
            assertEquals(captured["song"], firstOpen["song"])
            val preload = log.contextForTrack("乙地址", captured)
            assertEquals("未知", preload["session"])
            assertEquals(log.anonymous("乙"), preload["song"])
        }
    }    @Test fun `会话依据保留候选标记且拒绝任意文本`() {
        val dir = directory()
        PersistentDiagnostics(dir).use { log ->
            log.bindTrack("甲地址", "甲"); log.beginPlayback("甲", "user")
            val captured = log.context()
            assertEquals("user_intent", captured["session_basis"])
            val candidate = log.contextForTrack("甲地址", captured)
            assertEquals("creation_candidate", candidate["session_basis"])
            assertEquals("unknown", log.contextForSong("甲")["session_basis"])
            assertEquals("unknown", log.contextForTrack("未绑定地址", captured)["session_basis"])
            log.record("resolve_start", candidate)
            log.record("resolve_end", mapOf("session_basis" to "私密原始歌名-IP-192.168.1.9"))
            val out = ByteArrayOutputStream(); val summary = log.export(out)
            val text = entries(out.toByteArray()).values.joinToString()
            assertTrue(text.contains("creation_candidate")); assertFalse(text.contains("私密")); assertFalse(text.contains("192.168.1.9"))
            assertTrue(summary.truncated >= 1)
        }
    }}

