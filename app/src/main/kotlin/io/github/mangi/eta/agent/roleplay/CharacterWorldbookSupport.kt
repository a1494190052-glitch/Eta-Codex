package io.github.mangi.eta.agent.roleplay

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal data class UnsupportedWorldbookEntry(val index: Int, val reasons: List<String>)

/**
 * 同一判定同时用于导入后的能力说明和每轮投影；未支持的条件不退化为无条件触发。
 *
 * 位置 0-4、正则键、概率、分组、时序（sticky/cooldown/delay）、selectiveLogic、
 * 扩展匹配（人设/角色信息）、全词匹配与预算豁免均已由引擎执行，不再列为不支持。
 */
internal object CharacterWorldbookSupport {
    fun reasons(entry: JsonObject): List<String> = buildList {
        val extensions = entry["extensions"] as? JsonObject ?: JsonObject(emptyMap())
        if (entry.text("content").lineSequence().any { it.trimStart().startsWith("@@") }) add("世界书装饰器")
        if ((extensions["vectorized"] as? JsonPrimitive)?.booleanOrNull == true) add("向量检索匹配")
        if (extensions.text("automation_id").isNotBlank()) add("脚本自动化")
    }.distinct()

    private fun JsonObject.text(key: String): String =
        (get(key) as? JsonPrimitive)?.content.orEmpty()
}
