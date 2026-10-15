package top.colter.dynamic.agent.draw

import kotlinx.coroutines.runBlocking
import top.colter.dynamic.agent.chat.*
import top.colter.dynamic.agent.config.ImageConfig
import top.colter.dynamic.agent.util.CacheUtils
import top.colter.dynamic.core.data.MessageContent
import top.colter.skiko.Fonts
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ChatMarkdownTest {
    @Test fun `threshold boundary and short structured Markdown stay text`() = runBlocking {
        val cache = CacheUtils(Path.of("build", "chat-preview", "boundary"))
        for (source in listOf("字".repeat(1200), "```kotlin\nprintln(1)\n```", "|A|B|\n|---|---|\n|一|二|")) {
            val result = prepareChatReply(source, 1200, ImageConfig(), Fonts.default, cache) { _, _, _, _ ->
                fail("Short replies must never invoke rendering")
            }
            assertTrue(result.single().content.single() is MessageContent.Text)
        }
        val long = prepareChatReply("字".repeat(1201), 1200, ImageConfig(), Fonts.default, cache)
        assertTrue(long.single().content.single() is MessageContent.Image)
    }

    @Test fun `two thousand character report produces exactly one image and no text`() = runBlocking {
        val section = "本报告分析团队在项目推进中的实际表现。首先，应明确每个阶段的目标，并将目标分解为可验证的任务。其次，应定期记录进度与风险，及时调整资源安排。最后，通过复盘持续改善沟通方式，保证结论有事实依据、建议能够执行。"
        val source = "# 项目实施报告\n\n" + (1..8).joinToString("\n\n") {
            "## 第 $it 阶段：实施与改进\n\n" + section.repeat(3)
        } + "\n\n**报告结束：全部内容已呈现。**\n\n[参考资料](https://example.com/report)"
        assertTrue(source.length in 2000..3000)
        val directory = Path.of("build", "chat-preview").toAbsolutePath()
        val reply = prepareChatReply(source, 1200, ImageConfig(factor = 1f), Fonts.default, CacheUtils(directory.resolve("report")))
        val image = assertIs<MessageContent.Image>(reply.single().content.single())
        Files.copy(Path.of(image.image.uri), directory.resolve("single-report.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertTrue(Files.size(directory.resolve("single-report.png")) > 1000)
    }

    private val sample = """
        # 让长回复更好读

        这是一份 **Markdown 排版预览**。同一套回复排版用于 `/ds` 与 @ 对话，保留完整内容，并按照实际高度生成长图。

        ## 01 · 有层次的文字

        正文支持 **重点强调**、*轻量强调* 和 `inline code`。中文、English 和数字 123 可以自然混排。访问 [项目仓库](https://github.com/fensecaib/dynamic-plugin-dschat) 查看说明。

        > 好的排版应该帮助理解：标题清晰，段落舒展，长文也能逐页阅读。

        - 短回复保留文本，方便复制。
        - 长回复转为一张完整图片，不再在 8000 字符处截断。
          - 嵌套列表保留缩进与项目符号。
        - 代码与参考链接统一放在图片中。

        ## 02 · 结构化对比

        | 回复类型 | 呈现方式 | 阅读体验 |
        | :--- | :--- | :--- |
        | 简短问答 | 原生文本 | 快速浏览、直接复制 |
        | 长文分析 | 完整长图 | 标题与段落层级清晰 |
        | 代码示例 | 代码卡片 + 文本 | 保留缩进、便于复用 |

        ## 03 · 可阅读的代码

        ```kotlin
        suspend fun reply(question: String) {
            val answer = chat(question)
            // 保留原始内容，由排版层决定输出方式
            val pages = renderMarkdown(answer)
            send(pages)
        }
        ```

        ### 从字符长度走向文档结构

        字数只是一个参考。相同长度的段落、表格和代码，需要不同的展示方式。我们先识别内容结构，再测量文字的实际宽度与高度，最后安排每一页。

        1. 解析 Markdown，保留语义。
        2. 测量文字与表格，生成单张长图。
        3. 绘图失败时给出简短错误提示。

        **最后一段也应该被保留。** 不截断、不漏行，让每次回答都完整结束。
    """.trimIndent()

    @Test fun `parse structured Markdown without executing html or fetching images`() {
        val doc = parseChatDocument(sample + "\n\n<script>alert(1)</script>\n\n![图示](https://example.com/a.png)")
        assertTrue(doc.blocks.any { it.heading == 1 })
        assertTrue(doc.blocks.any { it.quote })
        assertTrue(doc.blocks.any { it.indent == 2 })
        assertTrue(doc.blocks.any { it.code && it.spans.single().text.contains("    val answer") })
        assertTrue(doc.blocks.any { it.cells.size == 3 })
        assertEquals(listOf("https://github.com/fensecaib/dynamic-plugin-dschat"), doc.links)
        assertTrue(doc.blocks.flatMap { it.spans }.any { it.text.contains("<script>") })
    }

    @Test fun `safe text chunks preserve plain content unicode and bound fenced code`() {
        val plain = "你好🙂abc\n".repeat(1400)
        val chunks = splitChatText(plain)
        assertEquals(plain, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 1800 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
        val code = "```kotlin\n" + "    println(\"hello\")\n".repeat(500) + "```"
        val parts = splitChatText(code)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 1800 && it.startsWith("```") && it.trimEnd().endsWith("```") })
        assertEquals(500, parts.sumOf { Regex("println").findAll(it).count() })
    }

    @Test fun `render paragraphs and tables into one complete image`() = runBlocking {
        val output = Path.of("build", "chat-preview")
        Files.createDirectories(output)
        Files.writeString(output.resolve("sample.md"), sample)
        var pages = 0
        renderChatImage(parseChatDocument(sample), ImageConfig(factor = 1f)) { image, page, total ->
            assertTrue(image.width == 900 && image.height <= 16154)
            pages = total
            image.encodeToData()!!.use { Files.write(output.resolve("markdown-$page.png"), it.bytes) }
        }
        assertEquals(1, pages)
        val long = "# 连续长段落\n\n" + "分页应保留每一行文字，中英文 mixed content 不应被截断。".repeat(130)
        var longPages = 0
        renderChatImage(parseChatDocument(long), ImageConfig()) { image, page, total ->
            longPages = total
            image.encodeToData()!!.use { Files.write(output.resolve("long-$page.png"), it.bytes) }
        }
        assertEquals(1, longPages)
        val table = "# 跨页表格\n\n| 序号 | 说明 |\n|---|---|\n" + (1..35).joinToString("\n") { "| $it | 表格跨页时重复表头，保持阅读上下文。 |" }
        renderChatImage(parseChatDocument(table), ImageConfig()) { image, page, _ ->
            image.encodeToData()!!.use { Files.write(output.resolve("table-$page.png"), it.bytes) }
        }
    }

    @Test fun `shared reply planner honors forced text and reports image budget errors`() = runBlocking {
        val dir = Files.createTempDirectory("chat-reply-test-")
        try {
            val cache = CacheUtils(dir)
            val source = "完整原文🙂".repeat(17000)
            for (threshold in listOf(0, 1200)) {
                val batches = prepareChatReply(source, threshold, ImageConfig(), Fonts.default, cache)
                val texts = batches.flatMap { it.content }.map { (it as MessageContent.Text).fallbackText }
                assertEquals(if (threshold == 0) source else "回复图片生成失败或内容超出单张图片限制，请缩短报告后重试。", texts.joinToString(""))
                assertTrue(texts.all { it.length <= 1800 })
            }
            // Explicit paragraphs exceed the height budget regardless of CJK fallback font widths.
            val tooTall = (1..1000).joinToString("\n\n") { "Line $it" }
            val fallback = prepareChatReply(tooTall, 1200, ImageConfig(), Fonts.default, cache)
            assertEquals("回复图片生成失败或内容超出单张图片限制，请缩短报告后重试。", fallback.flatMap { it.content }.joinToString("") { (it as MessageContent.Text).fallbackText })
        } finally {
            val resolved = dir.toAbsolutePath().normalize()
            check(resolved.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
            check(dir.fileName.toString().startsWith("chat-reply-test-"))
            Files.walk(resolved).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }

    @Test fun `shared reply sends only one image including code and sources`() = runBlocking {
        val directory = Path.of("build", "chat-preview", "delivery").toAbsolutePath()
        val batches = prepareChatReply(sample, sample.length - 1, ImageConfig(), Fonts.default, CacheUtils(directory))
        val contents = batches.flatMap { it.content }
        val images = contents.filterIsInstance<MessageContent.Image>()
        assertEquals(1, images.size)
        images.forEach { assertTrue(Files.size(Path.of(it.image.uri)) > 1000) }
        val text = contents.filterIsInstance<MessageContent.Text>().joinToString("\n") { it.fallbackText }
        assertEquals("", text)
        assertEquals(1, batches.size)
        val short = prepareChatReply("**简短回答**", 1200, ImageConfig(), Fonts.default, CacheUtils(directory))
        assertEquals("简短回答", (short.single().content.single() as MessageContent.Text).fallbackText)
    }

    @Test fun `wide tables become readable records and long code stays in one image`() = runBlocking {
        val wide = "| A | B | C | D | E |\n|---|---|---|---|---|\n| 一 | 二 | 三 | 四 | 五 |"
        val document = parseChatDocument(wide)
        assertTrue(document.complex)
        assertTrue(document.blocks.none { it.cells.isNotEmpty() })
        assertContains(document.blocks.flatMap { it.spans }.joinToString("") { it.text }, "E：五")
        val source = "# 长代码分页\n\n```kotlin\n" + (1..85).joinToString("\n") { "println(\"第 $it 行：跨页完整保留\")" } + "\n```"
        var count = 0
        renderChatImage(parseChatDocument(source), ImageConfig()) { image, page, total ->
            count = total
            val output = Path.of("build", "chat-preview")
            Files.createDirectories(output)
            image.encodeToData()!!.use { Files.write(output.resolve("code-$page.png"), it.bytes) }
        }
        assertEquals(1, count)
    }
}
