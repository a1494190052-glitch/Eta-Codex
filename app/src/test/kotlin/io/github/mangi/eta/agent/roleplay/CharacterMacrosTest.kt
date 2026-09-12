package io.github.mangi.eta.agent.roleplay

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterMacrosTest {
    private val card = CharacterCardCodec.create("林")

    @Test
    fun randomPickAndRollUseSeededRandomness() {
        val random = Random(42)
        val randomOut = CharacterMacros.expand("{{random:甲,乙,丙}}", card, context = CharacterMacros.Context(random = random))
        assertTrue(randomOut in setOf("甲", "乙", "丙"))

        val rolled = CharacterMacros.expand("{{roll:2d6+3}}", card, context = CharacterMacros.Context(random = Random(7)))
        assertTrue(rolled.toInt() in 5..15)
        assertEquals("", CharacterMacros.expand("{{roll:banana}}", card))
    }

    @Test
    fun pickStaysStableWithinTheSameSession() {
        val session = CharacterSessionState()
        val first = CharacterMacros.expand("{{pick:一,二,三}}", card, context = CharacterMacros.Context(session = session))
        val second = CharacterMacros.expand("{{pick:一,二,三}}", card, context = CharacterMacros.Context(session = session))
        assertEquals(first, second)
        assertTrue(first in setOf("一", "二", "三"))
    }

    @Test
    fun variableMacrosReadWriteAndUpdateSessionState() {
        val session = CharacterSessionState()
        val context = CharacterMacros.Context(session = session)
        // setvar 本身展开为空串，紧随其后的 getvar 能读到刚写入的值。
        assertEquals("平静", CharacterMacros.expand("{{setvar::心情::平静}}{{getvar::心情}}", card, context = context))
        assertEquals("平静", CharacterMacros.expand("{{getvar::心情}}", card, context = context))
        // 数字变量支持 incvar/decvar/addvar。
        CharacterMacros.expand("{{setvar::好感::2}}{{incvar::好感}}{{addvar::好感::3}}{{decvar::好感}}", card, context = context)
        assertEquals("5", CharacterMacros.expand("{{getvar::好感}}", card, context = context))
        CharacterMacros.expand("{{setglobalvar::世界::黄昏}}", card, context = context)
        assertEquals("黄昏", CharacterMacros.expand("{{getglobalvar::世界}}", card, context = context))
    }

    @Test
    fun contextMacrosExposeRecentMessagesAndInput() {
        val context = CharacterMacros.Context(
            lastMessage = "最近一条",
            lastUserMessage = "用户说",
            lastCharMessage = "角色说",
            input = "当前输入",
        )
        assertEquals(
            "最近一条/用户说/角色说/当前输入",
            CharacterMacros.expand("{{lastMessage}}/{{lastUserMessage}}/{{lastCharMessage}}/{{input}}", card, context = context),
        )
    }

    @Test
    fun unknownMacrosRemainUntouched() {
        assertEquals("{{mystery_macro}}", CharacterMacros.expand("{{mystery_macro}}", card))
        assertTrue(CharacterMacros.hasUnsupportedMacros("{{mystery_macro}}", card))
        assertTrue(!CharacterMacros.hasUnsupportedMacros("{{random:a,b}} {{setvar::x::1}} {{lastMessage}}", card))
    }
}
