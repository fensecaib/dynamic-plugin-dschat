package top.colter.dynamic.agent.chat

import org.commonmark.node.*
import org.commonmark.ext.gfm.tables.TableBlock

/** Convert presentation only; never alter the model response stored in conversation memory. */
internal fun plainChatText(markdown: String): String {
    val root = try { parseChatMarkdown(markdown) } catch (_: IllegalArgumentException) { return markdown }
    fun children(node: Node): List<Node> = generateSequence(node.firstChild) { it.next }.toList()
    fun inline(node: Node): String = when (node) {
        is Text -> node.literal
        is Code -> node.literal // Backslashes and literal Markdown inside code are data.
        is SoftLineBreak, is HardLineBreak -> "\n"
        is HtmlInline -> node.literal
        is Link -> {
            val label = children(node).joinToString("") { inline(it) }
            if (label == node.destination) label else "$label (${node.destination})"
        }
        is Image -> "[图片：${children(node).joinToString("") { inline(it) }}] (${node.destination})"
        else -> children(node).joinToString("") { inline(it) }
    }
    fun block(node: Node): String = when (node) {
        is Paragraph, is Heading -> inline(node)
        is FencedCodeBlock -> node.literal.removeSuffix("\n")
        is IndentedCodeBlock -> node.literal.removeSuffix("\n")
        is HtmlBlock -> node.literal.removeSuffix("\n")
        is ThematicBreak -> "────────"
        is BlockQuote -> children(node).joinToString("\n\n") { block(it) }.lineSequence().joinToString("\n") { "│ $it" }
        is BulletList, is OrderedList -> children(node).mapIndexed { i, item ->
            val prefix = if (node is OrderedList) "${node.markerStartNumber + i}. " else "• "
            val body = children(item).joinToString("\n") { block(it) }
            prefix + body.replace("\n", "\n" + " ".repeat(prefix.length))
        }.joinToString("\n")
        is TableBlock -> {
            val rows = children(node).flatMap { children(it) }.map { row -> children(row).map { inline(it) } }
            val headers = rows.firstOrNull().orEmpty()
            if (rows.size == 1) headers.joinToString(" / ")
            else rows.drop(1).joinToString("\n\n") { row -> row.mapIndexed { i, value -> "${headers.getOrElse(i) { "列 ${i + 1}" }}：$value" }.joinToString("\n") }
        }
        else -> children(node).map { block(it) }.filter { it.isNotEmpty() }.joinToString("\n\n")
    }
    // Reference definitions have no visible AST nodes; preserve them rather than silently losing output.
    return block(root).ifEmpty { markdown }
}

/** Split AFTER transport escaping, without separating a Unicode code point or escape pair. */
internal fun splitPlainChatText(text: String, limit: Int = 1800, discord: Boolean = false): List<String> {
    require(limit >= 16)
    val result = mutableListOf<String>()
    var current = StringBuilder()
    var offset = 0
    val special = "\\`*_~|>#[]()!+-".toSet()
    while (offset < text.length) {
        // Keep normal URLs intact and clickable, even when their paths contain underscores or parentheses.
        if (discord && (text.startsWith("https://", offset) || text.startsWith("http://", offset))) {
            var end = offset
            while (end < text.length && !text[end].isWhitespace() && text[end] !in "<>") end++
            if (offset > 0 && text[offset - 1] == '(' && end > offset && text[end - 1] == ')') end--
            while (end > offset && text[end - 1] in "。，，；！") end--
            val url = text.substring(offset, end)
            if (url.length + 2 <= limit) {
                if (current.length + url.length + 2 > limit) {
                    if (current.isNotEmpty()) result.add(current.toString())
                    current = StringBuilder()
                }
                current.append('<').append(url).append('>')
                offset = end
                continue
            }
        }
        val codePoint = text.codePointAt(offset)
        val value = String(Character.toChars(codePoint))
        val atom = if (discord && value.length == 1 && value[0] in special) "\\$value" else value
        if (current.length + atom.length > limit) {
            result.add(current.toString()); current = StringBuilder()
        }
        current.append(atom)
        offset += Character.charCount(codePoint)
    }
    if (current.isNotEmpty()) result.add(current.toString())
    return result
}
