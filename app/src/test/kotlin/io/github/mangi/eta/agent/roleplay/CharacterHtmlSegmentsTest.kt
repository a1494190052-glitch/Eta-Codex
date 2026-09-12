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
    fun blankContentProducesSingleBlankTextSegment() {
        val segments = CharacterHtmlSegments.split("   ")
        assertEquals(1, segments.size)
        assertTrue(segments.single() is CharacterMessageSegment.Text)
    }
}
