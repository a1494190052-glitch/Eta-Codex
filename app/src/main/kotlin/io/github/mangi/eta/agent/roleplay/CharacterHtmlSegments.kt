package io.github.mangi.eta.agent.roleplay

/**
 * 角色卡"人机交互界面"（前端卡/状态栏卡）的消息分段：
 * - ```html 围栏代码块 → 渲染为沙盒界面
 * - 整条消息即为 HTML 容器结构（无围栏的常见前端卡写法）→ 渲染为沙盒界面
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
    private val htmlContainerStart = Regex(
        "^\\s*<(?:html|body|div|details|section|style|table|center|svg)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val htmlContainerEnd = Regex(
        "</(?:html|body|div|details|section|table|style|center|svg)>",
        RegexOption.IGNORE_CASE,
    )

    fun containsHtml(content: String): Boolean =
        split(content).any { it is CharacterMessageSegment.Html }

    fun split(content: String): List<CharacterMessageSegment> {
        if (content.isBlank()) return listOf(CharacterMessageSegment.Text(content))
        val segments = mutableListOf<CharacterMessageSegment>()
        var cursor = 0
        fence.findAll(content).forEach { match ->
            val language = match.groups[1]?.value?.lowercase().orEmpty()
            val body = match.groups[2]?.value.orEmpty()
            val isHtmlFence = language in setOf("html", "htm")
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
        val normalized = segments.ifEmpty { listOf(CharacterMessageSegment.Text(content)) }
            .map { segment ->
                if (segment is CharacterMessageSegment.Text) {
                    CharacterMessageSegment.Text(segment.text.trim('\n'))
                } else {
                    segment
                }
            }
            .filterNot { it is CharacterMessageSegment.Text && it.text.isBlank() }
            .ifEmpty { listOf(CharacterMessageSegment.Text("")) }

        // 无围栏但整条消息是 HTML 容器结构（前端卡直接写入 first_mes 的写法）。
        if (normalized.size == 1) {
            val only = normalized.single()
            if (only is CharacterMessageSegment.Text) {
                val trimmed = only.text.trim()
                if (htmlContainerStart.containsMatchIn(trimmed) && htmlContainerEnd.containsMatchIn(trimmed)) {
                    return listOf(CharacterMessageSegment.Html(trimmed))
                }
            }
        }
        return normalized
    }
}
