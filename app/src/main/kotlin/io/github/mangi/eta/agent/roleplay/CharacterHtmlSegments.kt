package io.github.mangi.eta.agent.roleplay

/**
 * 角色卡"人机交互界面"（前端卡/状态栏卡）的消息分段。
 *
 * 覆盖的真实卡写法：
 * - ```html 围栏；无语言围栏但内容是 HTML；xml 围栏
 * - 整条消息即 HTML（任意标签起头，只要成对闭合）
 * - 正文尾部粘贴状态栏/面板块（逐行 HTML 区域）
 *
 * 其他内容原样保留给 Markdown 渲染。分段逻辑与渲染完全解耦，便于测试。
 */
internal sealed interface CharacterMessageSegment {
    data class Text(val text: String) : CharacterMessageSegment
    data class Html(val html: String) : CharacterMessageSegment
}

internal object CharacterHtmlSegments {
    private val fence = Regex(
        "(?s)```[ \\t]*([A-Za-z0-9_+-]*)[ \\t]*\\r?\\n(.*?)```",
    )
    /** 任意开始标签（用于"整段是 HTML"判定）。 */
    private val anyTag = Regex("<([A-Za-z][A-Za-z0-9-]{0,24})(\\s[^<>]*)?>")
    private val closingTag = Regex("</[A-Za-z][A-Za-z0-9-]*\\s*>")
    private val selfClosing = Regex("/>")
    /** 面板类块起始标签（用于尾部块识别）。 */
    private val blockTagStart = Regex(
        "<(div|details|section|table|style|center|svg|span|p|h[1-6]|ul|ol|font|b|i|strong|em|label|figure|aside|main)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun containsHtml(content: String): Boolean =
        split(content).any { it is CharacterMessageSegment.Html }

    fun split(content: String): List<CharacterMessageSegment> {
        if (content.isBlank()) return listOf(CharacterMessageSegment.Text(content))
        val fenced = splitFences(content)
        val normalized = fenced
            .map { segment ->
                if (segment is CharacterMessageSegment.Text) {
                    CharacterMessageSegment.Text(segment.text.trim('\n'))
                } else {
                    segment
                }
            }
            .filterNot { it is CharacterMessageSegment.Text && it.text.isBlank() }
            .ifEmpty { listOf(CharacterMessageSegment.Text("")) }

        // 无围栏：整条是 HTML，或正文尾部/内部存在面板块。
        if (normalized.size == 1) {
            val only = normalized.single()
            if (only is CharacterMessageSegment.Text) {
                return splitBareHtml(only.text)
            }
        }
        return normalized
    }

    private fun splitFences(content: String): List<CharacterMessageSegment> {
        val segments = mutableListOf<CharacterMessageSegment>()
        var cursor = 0
        fence.findAll(content).forEach { match ->
            val language = match.groups[1]?.value?.lowercase().orEmpty()
            val body = match.groups[2]?.value.orEmpty()
            val isHtmlFence = when (language) {
                "html", "htm", "xml" -> true
                "" -> looksLikeHtmlBlock(body)
                else -> false
            }
            if (!isHtmlFence) return@forEach
            if (match.range.first > cursor) {
                segments += CharacterMessageSegment.Text(content.substring(cursor, match.range.first))
            }
            if (body.isNotBlank()) segments += CharacterMessageSegment.Html(body)
            cursor = match.range.last + 1
        }
        if (cursor < content.length) {
            segments += CharacterMessageSegment.Text(content.substring(cursor))
        }
        return if (segments.isEmpty()) listOf(CharacterMessageSegment.Text(content)) else segments
    }

    private fun splitBareHtml(text: String): List<CharacterMessageSegment> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return listOf(CharacterMessageSegment.Text(text))
        // 整条消息就是 HTML。
        if (looksLikeHtmlBlock(trimmed)) return listOf(CharacterMessageSegment.Html(trimmed))
        // 正文中部/尾部粘贴面板块（状态栏最常见形态）：块起始标签必须位于行首，
        // 并对其后全文做闭合校验，避免把句子中提及的 <div> 误判为界面。
        val end = text.trimEnd()
        blockTagStart.findAll(end).forEach { match ->
            val atLineStart = match.range.first == 0 || end[match.range.first - 1] == '\n'
            if (!atLineStart) return@forEach
            val head = end.substring(0, match.range.first).trimEnd()
            if (head.isBlank()) return@forEach
            val tail = end.substring(match.range.first)
            if (looksLikeTailBlock(tail)) {
                return listOf(
                    CharacterMessageSegment.Text(head),
                    CharacterMessageSegment.Html(tail.trim()),
                )
            }
        }
        return listOf(CharacterMessageSegment.Text(text))
    }

    /** 整段 HTML：以标签开头、成对闭合（或含自闭合），且不是单个孤立尖括号词。 */
    private fun looksLikeHtmlBlock(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 12 || !trimmed.startsWith("<")) return false
        if (!anyTag.containsMatchIn(trimmed)) return false
        if (!trimmed.endsWith(">")) return false
        val hasClosing = closingTag.containsMatchIn(trimmed)
        val hasSelfClosing = selfClosing.containsMatchIn(trimmed)
        if (!hasClosing && !hasSelfClosing) return false
        // 至少两个标签（开/闭配对或一个带属性的标签 + 闭合），排除 `<微笑>` 之类。
        val tagCount = anyTag.findAll(trimmed).count() + closingTag.findAll(trimmed).count()
        return tagCount >= 2
    }

    /** 尾部块：包含面板起始标签、成对闭合且长度可观。 */
    private fun looksLikeTailBlock(tail: String): Boolean {
        if (tail.length < 20) return false
        if (!blockTagStart.containsMatchIn(tail)) return false
        if (!closingTag.containsMatchIn(tail)) return false
        val tagCount = anyTag.findAll(tail).count()
        return tagCount >= 2
    }
}
