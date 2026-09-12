package io.github.mangi.eta.agent.roleplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterRegexScriptsTest {
    private fun cardWithScripts(scripts: String) = CharacterCardCodec.decodeJson(
        """{"spec":"chara_card_v2","data":{"name":"角色","extensions":{"regex_scripts":[$scripts]}}}""",
    )

    @Test
    fun parseAndApplyFindReplaceWithGroupsAndMatch() {
        val card = cardWithScripts(
            """{"scriptName":"括号反转","findRegex":"/(\\w+)@(\\w+)/g","replaceString":"$2 在 $1","placement":[2]}""",
        )
        val scripts = CharacterRegexScripts.parse(card)
        assertEquals(1, scripts.size)
        assertEquals(
            "alice 在 example",
            CharacterRegexScripts.apply("example@alice", scripts, CharacterRegexScripts.Placement.AI_OUTPUT, promptSide = true),
        )

        val matchCard = cardWithScripts(
            """{"findRegex":"/\\d+/","replaceString":"[{{match}}]","placement":[1,2]}""",
        )
        assertEquals(
            "编号[42]",
            CharacterRegexScripts.apply("编号42", CharacterRegexScripts.parse(matchCard), CharacterRegexScripts.Placement.USER_INPUT, promptSide = true),
        )
    }

    @Test
    fun placementFiltersAndPromptSideFlagsAreHonored() {
        val userOnly = cardWithScripts("""{"findRegex":"/秘密/","replaceString":"***","placement":[1]}""")
        assertEquals(
            "秘密",
            CharacterRegexScripts.apply("秘密", CharacterRegexScripts.parse(userOnly), CharacterRegexScripts.Placement.AI_OUTPUT, promptSide = true),
        )
        val markdownOnly = cardWithScripts("""{"findRegex":"/粗体/","replaceString":"<b>","markdownOnly":true,"placement":[2]}""")
        // markdownOnly 脚本不进入模型侧（promptSide）文本。
        assertEquals(
            "粗体",
            CharacterRegexScripts.apply("粗体", CharacterRegexScripts.parse(markdownOnly), CharacterRegexScripts.Placement.AI_OUTPUT, promptSide = true),
        )
    }

    @Test
    fun trimStringsRemoveResidualFragments() {
        val card = cardWithScripts(
            """{"findRegex":"/台词/","replaceString":"台词(附注)","trimStrings":["(附注)"],"placement":[2]}""",
        )
        assertEquals(
            "台词",
            CharacterRegexScripts.apply("台词", CharacterRegexScripts.parse(card), CharacterRegexScripts.Placement.AI_OUTPUT, promptSide = true),
        )
    }

    @Test
    fun invalidPatternsAreSkippedWithoutThrowing() {
        val card = cardWithScripts("""{"findRegex":"/[unclosed/","replaceString":"x","placement":[2]}""")
        val scripts = CharacterRegexScripts.parse(card)
        assertEquals(
            "原文不动",
            CharacterRegexScripts.apply("原文不动", scripts, CharacterRegexScripts.Placement.AI_OUTPUT, promptSide = true),
        )
        assertTrue(CharacterRegexScripts.hasScripts(card))
    }
}
