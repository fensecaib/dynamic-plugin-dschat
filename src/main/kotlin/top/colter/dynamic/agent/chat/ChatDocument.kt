package top.colter.dynamic.agent.chat

import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.commonmark.ext.gfm.tables.*
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension

private const val MAX_MARKDOWN_NODES = 5000
private const val MAX_MARKDOWN_DEPTH = 64
private val fencePattern = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")

internal data class ChatSpan(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false, val link: Boolean = false)
internal data class ChatBlock(
    val spans: List<ChatSpan>, val heading: Int = 0, val indent: Int = 0,
    val quote: Boolean = false, val code: Boolean = false, val label: String = "",
    val cells: List<List<ChatSpan>> = emptyList(), val tableHeader: List<List<ChatSpan>> = emptyList(),
    val isHeader: Boolean = false,
)
internal data class ChatDocument(val blocks: List<ChatBlock>, val links: List<String>, val complex: Boolean)

/** Parse Markdown once; raw HTML and remote images remain inert text. */
internal fun parseChatMarkdown(markdown: String): Node {
    require(markdown.length <= 100_000) { "Reply exceeds image layout budget" }
    val root = Parser.builder().extensions(listOf(TablesExtension.create(), StrikethroughExtension.create())).build().parse(markdown)
    // Validate iteratively before recursive conversion; deeply nested model output must fall back safely.
    val pending = ArrayDeque<Pair<Node, Int>>()
    pending.add(root to 0)
    var nodeCount = 0
    while (pending.isNotEmpty()) {
        val (node, depth) = pending.removeLast()
        require(++nodeCount <= MAX_MARKDOWN_NODES && depth <= MAX_MARKDOWN_DEPTH) { "Markdown structure exceeds layout budget" }
        var child = node.firstChild
        while (child != null) { pending.add(child to depth + 1); child = child.next }
    }
    return root
}

internal fun parseChatDocument(markdown: String): ChatDocument {
    val root = parseChatMarkdown(markdown)
    val blocks = mutableListOf<ChatBlock>()
    val links = mutableListOf<String>()
    var hasTable = false
    fun children(node: Node): List<Node> = generateSequence(node.firstChild) { it.next }.toList()
    fun inline(node: Node, bold: Boolean = false, italic: Boolean = false, linked: Boolean = false): List<ChatSpan> {
        fun value(text: String, code: Boolean = false) = listOf(ChatSpan(text, bold, italic, code, linked))
        return when (node) {
            is Text -> value(node.literal)
            is Code -> value(node.literal, true)
            is SoftLineBreak -> value(" ")
            is HardLineBreak -> value("\n")
            is StrongEmphasis -> children(node).flatMap { inline(it, true, italic, linked) }
            is Emphasis -> children(node).flatMap { inline(it, bold, true, linked) }
            is Link -> {
                val dest = node.destination
                val index = if (dest.startsWith("https://") || dest.startsWith("http://")) {
                    if (dest !in links) links.add(dest)
                    links.indexOf(dest) + 1
                } else 0
                children(node).flatMap { inline(it, bold, italic, true) } + if (index > 0) value(" [$index]") else emptyList()
            }
            is Image -> value("[图片：") + children(node).flatMap { inline(it) } + value("]")
            is HtmlInline -> value(node.literal)
            else -> children(node).flatMap { inline(it, bold, italic, linked) }
        }
    }
    fun walk(node: Node, indent: Int = 0, quote: Boolean = false, prefix: String = "") {
        when (node) {
            is Paragraph, is Heading -> blocks.add(ChatBlock(
                (if (prefix.isNotEmpty()) listOf(ChatSpan(prefix)) else emptyList()) + inline(node),
                heading = (node as? Heading)?.level ?: 0, indent = indent.coerceAtMost(6), quote = quote))
            is FencedCodeBlock -> blocks.add(ChatBlock(listOf(ChatSpan(node.literal.trimEnd('\n'), code = true)), indent = indent.coerceAtMost(6), code = true, label = node.info.take(32).ifBlank { "CODE" }))
            is IndentedCodeBlock -> blocks.add(ChatBlock(listOf(ChatSpan(node.literal.trimEnd('\n'), code = true)), code = true, label = "CODE"))
            is BulletList, is OrderedList -> children(node).forEachIndexed { i, item ->
                children(item).forEachIndexed { j, child -> walk(child, indent + 1, quote,
                    if (j == 0) if (node is OrderedList) "${node.markerStartNumber + i}.  " else "•  " else "") }
            }
            is BlockQuote -> children(node).forEach { walk(it, indent, true) }
            is ThematicBreak -> blocks.add(ChatBlock(listOf(ChatSpan("────────────────────", link = true))))
            is HtmlBlock -> blocks.add(ChatBlock(listOf(ChatSpan(node.literal))))
            is TableBlock -> {
                hasTable = true
                val rows = children(node).flatMap { children(it) }.map { row -> children(row).map { inline(it) } }
                val header = rows.firstOrNull().orEmpty()
                if (header.size <= 4 && rows.flatten().all { cell -> cell.sumOf { it.text.length } <= 200 }) rows.forEachIndexed { i, cells ->
                    blocks.add(ChatBlock(emptyList(), cells = cells, tableHeader = header, isHeader = i == 0))
                } else if (rows.size == 1) {
                    // Header-only wide tables still contain user-visible content.
                    header.forEach { blocks.add(ChatBlock(it.map { span -> span.copy(bold = true) })) }
                } else rows.drop(1).forEachIndexed { i, cells ->
                    blocks.add(ChatBlock(listOf(ChatSpan("记录 ${i + 1}", bold = true))))
                    cells.forEachIndexed { c, spans -> blocks.add(ChatBlock(header.getOrElse(c) { emptyList() }.map { it.copy(bold = true) } + ChatSpan("：") + spans)) }
                }
            }
            else -> children(node).forEach { walk(it, indent, quote) }
        }
    }
    walk(root)
    return ChatDocument(blocks, links, hasTable || blocks.any { it.code })
}

/** Bound each outbound text message, preserving Unicode and closing/reopening fenced code. */
internal fun splitChatText(text: String, limit: Int = 1800): List<String> {
    require(limit >= 128)
    if (text.isEmpty()) return emptyList()
    val result = mutableListOf<String>()
    var current = StringBuilder()
    var fence: String? = null
    fun flush() {
        if (current.isEmpty()) return
        result.add(current.toString() + if (fence != null) "\n$fence" else "")
        current = StringBuilder(if (fence != null) "$fence\n" else "")
    }
    // Reserve room for synthetic fence delimiters; original line endings are retained.
    var offset = 0
    while (offset < text.length) {
        val newline = text.indexOf('\n', offset)
        val lineEnd = if (newline < 0) text.length else newline + 1
        // A fence is only recognized at an original line boundary, never at a synthetic chunk break.
        val marker = fencePattern.find(text.substring(offset, lineEnd).trimEnd('\n', '\r'))
        val nextFence = if (marker != null && marker.groupValues[1].length <= 32) {
            if (fence == null) marker.groupValues[1]
            else if (marker.groupValues[1][0] == fence[0] && marker.groupValues[1].length >= fence.length && marker.groupValues[2].isBlank()) null else fence
        } else fence
        val budget = limit - 40
        while (offset < lineEnd) {
            if (current.length >= budget) flush()
            if (lineEnd - offset > budget - current.length && current.isNotEmpty() && current.toString() != "$fence\n") flush()
            var end = minOf(lineEnd, offset + budget - current.length)
            if (end < lineEnd && end > offset && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            current.append(text, offset, end); offset = end
            if (offset < lineEnd) flush()
        }
        fence = nextFence
    }
    if (current.isNotEmpty()) result.add(current.toString() + if (fence != null) "\n$fence" else "")
    return result
}
