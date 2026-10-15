package top.colter.dynamic.agent.draw

import kotlinx.coroutines.*
import org.jetbrains.skia.Surface
import top.colter.dynamic.agent.chat.*
import top.colter.dynamic.agent.config.ImageConfig
import top.colter.dynamic.agent.util.AgentTrace
import top.colter.dynamic.agent.util.CacheUtils
import top.colter.dynamic.core.data.MessageContent
import top.colter.skiko.Fonts
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class ChatReviewRegressionTest {
    @Test fun `wide header-only table preserves every heading`() {
        val source = "| A | B | C | D | E |\n|---|---|---|---|---|"
        val doc = parseChatDocument(source)
        assertEquals("ABCDE", doc.blocks.flatMap { it.spans }.joinToString("") { it.text })
        assertTrue(doc.complex)
    }

    @Test fun `deep and oversized trees fall back before recursive conversion`() {
        assertFailsWith<IllegalArgumentException> { parseChatDocument("> ".repeat(100) + "嵌套引用") }
        assertFailsWith<IllegalArgumentException> { parseChatDocument("文字\n\n".repeat(3000)) }
    }

    @Test fun `long single line and unicode remain complete at small message budgets`() {
        for (limit in listOf(128, 1800)) {
            val source = "🙂abc你好".repeat(50000)
            val chunks = splitChatText(source, limit)
            assertTrue(chunks.all { it.length <= limit })
            assertEquals(source, chunks.joinToString(""))
            assertTrue(chunks.all { !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
        }
    }

    private suspend fun withCache(test: suspend (Path, CacheUtils) -> Unit) {
        val dir = Files.createTempDirectory("chat-review-").toAbsolutePath().normalize()
        try { test(dir, CacheUtils(dir)) } finally {
            check(dir.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
            check(dir.fileName.toString().startsWith("chat-review-"))
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }

    private fun assertNoImages(dir: Path) {
        Files.walk(dir).use { paths -> assertFalse(paths.anyMatch { Files.isRegularFile(it) }) }
    }

    @Test fun `render failure after writing a page removes partial files and returns a brief error`() = runBlocking {
        withCache { dir, cache ->
            val source = "完整回复🙂".repeat(600)
            val batches = prepareChatReply(source, 1, ImageConfig(), Fonts.default, cache) { _, _, _, consume ->
                Surface.makeRasterN32Premul(8, 8).use { surface -> surface.makeImageSnapshot().use { consume(it, 1, 1) } }
                error("encoding failed on page two")
            }
            assertEquals("回复图片生成失败或内容超出单张图片限制，请缩短报告后重试。", batches.flatMap { it.content }.joinToString("") { (it as MessageContent.Text).fallbackText })
            assertNoImages(dir)
        }
    }

    @Test fun `render failure identifies its stage without logging model text or exception details`() = runBlocking {
        withCache { dir, cache ->
            val logs = CopyOnWriteArrayList<String>()
            val secret = "secret-token-and-model-output"
            val batches = withContext(AgentTrace("chat-render-failed", sink = logs::add)) {
                prepareChatReply(secret, 1, ImageConfig(), Fonts.default, cache) { _, _, _, _ ->
                    throw IOException("$secret\nresponse_body=private-answer")
                }
            }
            assertEquals("回复图片生成失败或内容超出单张图片限制，请缩短报告后重试。",
                (batches.single().content.single() as MessageContent.Text).fallbackText)
            assertTrue(logs.any { "event=agent_stage" in it && "stage=chat_render" in it && "status=failed" in it })
            assertTrue(logs.any { "event=agent_image" in it && "status=failed" in it && "phase=draw" in it && "error_type=IOException" in it })
            assertTrue(logs.all { "trace_id=chat-render-failed" in it })
            assertFalse(logs.any { secret in it || "private-answer" in it || '\n' in it })
            assertNoImages(dir)
        }
    }

    @Test fun `cache write failure is distinguished from rendering and does not reveal filesystem paths`() = runBlocking {
        withCache { dir, _ ->
            val secret = "private-cache-path"
            val baseDir = Files.createDirectories(dir.resolve(secret))
            val cacheDir = Files.createDirectories(baseDir.resolve("cache"))
            val blockedDrawDir = Files.writeString(cacheDir.resolve("draw"), "keep-existing-file")
            val logs = CopyOnWriteArrayList<String>()
            val batches = withContext(AgentTrace("chat-write-failed", sink = logs::add)) {
                prepareChatReply("完整回复", 1, ImageConfig(), Fonts.default, CacheUtils(baseDir)) { _, _, _, consume ->
                    Surface.makeRasterN32Premul(8, 8).use { surface ->
                        surface.makeImageSnapshot().use { consume(it, 1, 1) }
                    }
                }
            }
            assertEquals("回复图片生成失败或内容超出单张图片限制，请缩短报告后重试。",
                (batches.single().content.single() as MessageContent.Text).fallbackText)
            assertTrue(logs.any { "event=agent_stage" in it && "stage=chat_render" in it && "status=failed" in it })
            assertTrue(logs.any { "event=agent_image" in it && "status=failed" in it && "phase=write" in it && "error_type=" in it })
            assertFalse(logs.any { secret in it || baseDir.toString() in it })
            assertEquals("keep-existing-file", Files.readString(blockedDrawDir))
            Files.walk(dir).use { paths -> assertEquals(1L, paths.filter { Files.isRegularFile(it) }.count()) }
        }
    }

    @Test fun `cancellation at dispatcher return removes files and releases render slot`() = runBlocking {
        withCache { dir, cache ->
            repeat(3) {
                val task = launch {
                    prepareChatReply("# 已绘制但取消", 1, ImageConfig(), Fonts.default, cache) { _, _, _, consume ->
                        Surface.makeRasterN32Premul(8, 8).use { surface -> surface.makeImageSnapshot().use { consume(it, 1, 1) } }
                        currentCoroutineContext().cancel()
                    }
                    fail("Cancelled result must not be delivered")
                }
                withTimeout(5000) { task.join() }
                assertTrue(task.isCancelled)
                assertNoImages(dir)
            }
            val next = withTimeout(5000) { prepareChatReply("正常回复", 1200, ImageConfig(), Fonts.default, cache) }
            assertEquals("正常回复", (next.single().content.single() as MessageContent.Text).fallbackText)
        }
    }

    @Test fun `invisible Markdown falls back instead of producing empty image`() = runBlocking {
        withCache { dir, cache ->
            val source = "[ref]: https://example.com/" + "x".repeat(2000)
            val batches = prepareChatReply(source, 1, ImageConfig(), Fonts.default, cache)
            assertEquals("回复图片生成失败或内容超出单张图片限制，请缩短报告后重试。", batches.flatMap { it.content }.joinToString("") { (it as MessageContent.Text).fallbackText })
            assertNoImages(dir)
        }
    }
}
