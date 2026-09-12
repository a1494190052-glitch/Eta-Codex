package io.github.mangi.eta.agent.roleplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterHtmlSegmentsTest {
    @Test
    fun htmlFenceBecomesHtmlSegmentAndSurroundingTextIsPreserved() {
        val content = "开场白\n```html\n<div class=\"status\">HP 100</div>\n```\n结束语"
        val segments = CharacterHtmlSegments.split(content)
        assertEquals(3, segments.size)
        assertTrue(segments[0] is CharacterMessageSegment.Text)
        assertEquals("开场白", (segments[0] as CharacterMessageSegment.Text).text)
        val html = segments[1] as CharacterMessageSegment.Html
        assertTrue(html.html.contains("HP 100"))
        assertTrue(segments[2] is CharacterMessageSegment.Text)
        assertTrue(CharacterHtmlSegments.containsHtml(content))
    }

    @Test
    fun nonHtmlFenceStaysPlainText() {
        val content = "代码\n```kotlin\nval x = 1\n```"
        val segments = CharacterHtmlSegments.split(content)
        assertTrue(segments.all { it is CharacterMessageSegment.Text })
        assertFalse(CharacterHtmlSegments.containsHtml(content))
    }

    @Test
    fun bareHtmlContainerMessageIsDetectedWithoutFence() {
        val content = "<div id=\"panel\"><span>状态：正常</span></div>"
        val segments = CharacterHtmlSegments.split(content)
        assertEquals(1, segments.size)
        assertTrue(segments.single() is CharacterMessageSegment.Html)
    }

    @Test
    fun ordinaryMarkdownIsNotTreatedAsHtml() {
        val content = "**你好**，这是普通回复 <微笑> 收尾"
        assertFalse(CharacterHtmlSegments.containsHtml(content))
    }

    @Test
    fun htmlFenceWithoutLanguageIsDetectedFromBody() {
        val content = "开场\n```\n<div style=\"border:1px\">面板</div>\n```"
        val segments = CharacterHtmlSegments.split(content)
        assertTrue(segments.any { it is CharacterMessageSegment.Html })
    }

    @Test
    fun trailingStatusPanelAfterProseIsDetected() {
        val content = "夜色降临，她靠在窗边。\n\n<div class=\"status\">\n<span>好感：10</span>\n<span>地点：咖啡馆</span>\n</div>"
        val segments = CharacterHtmlSegments.split(content)
        assertEquals(2, segments.size)
        assertTrue(segments[0] is CharacterMessageSegment.Text)
        assertTrue(segments[1] is CharacterMessageSegment.Html)
        assertTrue((segments[1] as CharacterMessageSegment.Html).html.contains("好感"))
    }

    @Test
    fun inlineMentionOfHtmlDoesNotTriggerPanel() {
        val content = "你可以用 <div> 标签排版，例如 <div class=\"x\">内容</div> 这样。"
        val segments = CharacterHtmlSegments.split(content)
        assertTrue(segments.all { it is CharacterMessageSegment.Text })
    }

    @Test
    fun spanLedBareHtmlMessageIsDetected() {
        val content = "<span class=\"hp\">HP</span><span>100 / 100</span>"
        val segments = CharacterHtmlSegments.split(content)
        assertEquals(1, segments.size)
        assertTrue(segments.single() is CharacterMessageSegment.Html)
    }

    @Test
    fun displayRegexOutputWithFenceIsRendered() {
        // 模拟真实卡"新开场白"：提示词里保留占位符，显示侧替换为 ```html 面板。
        val card = CharacterCardCodec.decodeJson(
            """{"spec":"chara_card_v2","data":{"name":"x","extensions":{"regex_scripts":[{"scriptName":"新开场白","findRegex":"\\[DM系统：实体降临\\]","replaceString":"```html\n<div class=\"opening\"><span>登入档案</span></div>\n```","placement":[2],"markdownOnly":true}]}}}""",
        )
        val scripts = CharacterRegexScripts.parse(card)
        val display = CharacterRegexScripts.apply(
            "[DM系统：实体降临]", scripts,
            CharacterRegexScripts.Placement.AI_OUTPUT, promptSide = false,
        )
        assertTrue(display.contains("<div"))
        assertTrue(CharacterHtmlSegments.split(display).any { it is CharacterMessageSegment.Html })
        // markdownOnly 不进模型侧。
        assertEquals(
            "[DM系统：实体降临]",
            CharacterRegexScripts.apply(
                "[DM系统：实体降临]", scripts,
                CharacterRegexScripts.Placement.AI_OUTPUT, promptSide = true,
            ),
        )
    }

    @Test
    fun blankContentProducesSingleBlankTextSegment() {
        val segments = CharacterHtmlSegments.split("   ")
        assertEquals(1, segments.size)
        assertTrue(segments.single() is CharacterMessageSegment.Text)
    }
}
