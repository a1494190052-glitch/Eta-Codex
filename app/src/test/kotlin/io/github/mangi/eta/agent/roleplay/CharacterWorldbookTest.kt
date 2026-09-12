package io.github.mangi.eta.agent.roleplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterWorldbookTest {
    @Test
    fun keywordsSecondaryKeysAndRecursionProduceOrderedUniqueEntries() {
        val card = card(""""recursive_scanning":true,"scan_depth":2,"entries":[
          {"id":1,"keys":["小镇"],"content":"灯塔","enabled":true,"insertion_order":2},
          {"id":1,"keys":["灯塔"],"content":"小镇与海","enabled":true,"insertion_order":1,"position":"before_char"},
          {"keys":["小镇"],"secondary_keys":["夜晚"],"selective":true,"content":"夜景","enabled":true,"insertion_order":3},
          {"constant":true,"content":"禁用","enabled":false},
          {"keys":["不存在"],"content":"不出现","enabled":true}
        ]""")
        val result = CharacterWorldbook.resolve(card, listOf("我走进小镇", "夜晚"), 1000) { it.length }
        assertEquals("小镇与海", result.beforeCharacter)
        assertEquals("灯塔\n\n夜景", result.afterCharacter)
    }

    @Test
    fun disabledRecursionAndEmptySecondaryKeysDoNotActivateEntries() {
        val card = card(""""entries":[
          {"keys":["入口"],"content":"暗号","enabled":true},
          {"keys":["暗号"],"content":"隐藏","enabled":true},
          {"keys":["入口"],"secondary_keys":[],"selective":true,"content":"空条件","enabled":true}
        ]""")
        assertEquals("暗号", CharacterWorldbook.resolve(card, listOf("入口"), 100) { it.length }.afterCharacter)
    }

    @Test
    fun budgetKeepsCompleteConstantEntriesAndHonorsExplicitZero() {
        val body = """"token_budget":6,"entries":[
          {"constant":true,"content":"常驻","enabled":true,"insertion_order":9},
          {"keys":["x"],"content":"过长的完整条目","enabled":true,"priority":100},
          {"keys":["x"],"content":"短句","enabled":true,"insertion_order":1}
        ]"""
        val result = CharacterWorldbook.resolve(card(body), listOf("x"), 100) { it.length }
        assertEquals("短句\n\n常驻", result.afterCharacter)
        assertFalse(result.afterCharacter.contains("过长"))
        assertEquals("", CharacterWorldbook.resolve(card(body.replace("\"token_budget\":6", "\"token_budget\":0")), listOf("x"), 100) { it.length }.afterCharacter)
    }

    @Test
    fun regexKeysMatchWhilePlainKeysStayLiteral() {
        val card = card(""""entries":[
          {"keys":["A.B"],"use_regex":true,"content":"字面词","enabled":true,"extensions":{"position":0,"case_sensitive":true}},
          {"keys":["/hello/i"],"content":"表达式","enabled":true},
          {"keys":["/[bad/"],"content":"无效","enabled":true}
        ]""")
        val result = CharacterWorldbook.resolve(card, listOf("A.B HELLO"), 100) { it.length }
        assertEquals("字面词", result.beforeCharacter)
        assertEquals("表达式", result.afterCharacter)
        assertTrue(CharacterWorldbook.resolve(card, listOf("a.b"), 100) { it.length }.beforeCharacter.isEmpty())
        assertTrue(CharacterWorldbook.unsupportedEntries(card).isEmpty())
    }

    @Test
    fun advancedConditionsFireWithTheirDocumentedSemantics() {
        val card = card(""""entries":[
          {"constant":true,"content":"深度","enabled":true,"extensions":{"position":4,"depth":1}},
          {"constant":true,"content":"逻辑","enabled":true,"extensions":{"selectiveLogic":2}},
          {"constant":true,"content":"不触","enabled":true,"extensions":{"useProbability":true,"probability":0}},
          {"constant":true,"content":"分组","enabled":true,"extensions":{"group":"g1"}},
          {"constant":true,"content":"冷却","enabled":true,"extensions":{"cooldown":2}}
        ]""")
        val result = CharacterWorldbook.resolve(card, listOf("a".repeat(64) + "!"), 1000) { it.length }
        assertEquals("逻辑\n\n分组\n\n冷却", result.afterCharacter)
        assertEquals(1, result.depthInjections.size)
        assertEquals("深度", result.depthInjections.single().content)
        assertEquals(1, result.depthInjections.single().depth)
        assertTrue(CharacterWorldbook.unsupportedEntries(card).isEmpty())
    }

    @Test
    fun unsupportedConditionsPreventEvenConstantEntriesFromFiring() {
        val card = card(""""entries":[
          {"constant":true,"content":"@@demo 正文","enabled":true,"extensions":{}},
          {"constant":true,"content":"向量","enabled":true,"extensions":{"vectorized":true}},
          {"constant":true,"content":"自动化","enabled":true,"extensions":{"automation_id":"auto-1"}},
          {"constant":true,"content":"正文","enabled":true}
        ]""")
        val result = CharacterWorldbook.resolve(card, listOf("无关"), 1000) { it.length }
        // 装饰器（@@）、向量检索与脚本自动化仍不支持，不参与匹配；普通条目正常注入。
        assertFalse(result.afterCharacter.contains("@@"))
        assertFalse(result.afterCharacter.contains("向量"))
        assertFalse(result.afterCharacter.contains("自动化"))
        assertTrue(result.afterCharacter.contains("正文"))
        assertEquals(3, CharacterWorldbook.unsupportedEntries(card).size)
    }

    private fun card(book: String) = CharacterCardCodec.decodeJson(
        """{"spec":"chara_card_v2","data":{"name":"角色","character_book":{$book}}}""",
    )
}
