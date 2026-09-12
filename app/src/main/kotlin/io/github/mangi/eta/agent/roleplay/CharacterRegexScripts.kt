package io.github.mangi.eta.agent.roleplay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * SillyTavern/Tavo 生态 `extensions.regex_scripts` 的兼容执行引擎。
 *
 * 只执行"查找/替换"式正则，不做任何脚本求值；查找模式上限、脚本数量上限与
 * 文本上限用于控制 ReDoS 与内存风险。替换串支持 $0..$9 与 {{match}}。
 */
internal object CharacterRegexScripts {
    /** 与酒馆 placement 对齐：1=用户输入 2=AI 输出 3=快捷命令 4=世界书 5=思考。 */
    internal object Placement {
        const val USER_INPUT = 1
        const val AI_OUTPUT = 2
        const val SLASH_COMMAND = 3
        const val WORLD_INFO = 4
        const val REASONING = 5
    }

    internal data class Script(
        val name: String,
        val pattern: String,
        val replacement: String,
        val trimStrings: List<String>,
        val placements: Set<Int>,
        val markdownOnly: Boolean,
        val promptOnly: Boolean,
        val runOnEdit: Boolean,
        val disabled: Boolean,
    )

    private const val MAX_PATTERN_CHARS = 4096
    private const val MAX_SCRIPTS = 128
    private const val MAX_TEXT_CHARS = 2 * 1024 * 1024

    fun parse(card: CharacterCard): List<Script> {
        val raw = card.extensions["regex_scripts"] as? JsonArray ?: return emptyList()
        return raw.asSequence()
            .filterIsInstance<JsonObject>()
            .take(MAX_SCRIPTS)
            .mapNotNull(::parseScript)
            .filterNot { it.disabled }
            .toList()
    }

    /** 是否存在脚本；供兼容性提示使用。 */
    fun hasScripts(card: CharacterCard): Boolean =
        (card.extensions["regex_scripts"] as? JsonArray)?.any { it is JsonObject } == true

    /** 当前能力说明：脚本数据保留，仅执行查找/替换，不求值宏脚本字段。 */
    fun warnings(card: CharacterCard): List<String> = buildList {
        val scripts = (card.extensions["regex_scripts"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        if (scripts.isEmpty()) return@buildList
        val unsupportedScriptValues = scripts.count { script ->
            val substitution = (script["substituteRegex"] as? JsonPrimitive)?.content?.lowercase()
            substitution in setOf("1", "2") || (script["disabled"] as? JsonPrimitive)?.booleanOrNull == true
        }
        if (unsupportedScriptValues > 0) {
            add("正则脚本中 ${unsupportedScriptValues} 条处于禁用或需要宏转义模式，按原始文本执行查找/替换。")
        }
        val slashOnly = scripts.count { script ->
            val placement = placementsOf(script)
            placement.isNotEmpty() && placement.all { it == Placement.SLASH_COMMAND }
        }
        if (slashOnly > 0) add("有 $slashOnly 条正则脚本只用于快捷命令，本版不执行。")
    }

    private fun parseScript(source: JsonObject): Script? {
        val find = source.text("findRegex")
        if (find.isBlank() || find.length > MAX_PATTERN_CHARS) return null
        val disabled = (source["disabled"] as? JsonPrimitive)?.booleanOrNull == true
        return Script(
            name = source.text("scriptName").ifBlank { source.text("name") },
            pattern = find,
            replacement = source.text("replaceString"),
            trimStrings = (source["trimStrings"] as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonPrimitive }
                .map { it.content }
                .filter(String::isNotBlank),
            placements = placementsOf(source),
            markdownOnly = (source["markdownOnly"] as? JsonPrimitive)?.booleanOrNull == true,
            promptOnly = (source["promptOnly"] as? JsonPrimitive)?.booleanOrNull == true,
            runOnEdit = (source["runOnEdit"] as? JsonPrimitive)?.booleanOrNull == true,
            disabled = disabled,
        )
    }

    private fun placementsOf(source: JsonObject): Set<Int> {
        (source["placement"] as? JsonArray)?.let { array ->
            val values = array.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
            if (values.isNotEmpty()) return values.toSet()
        }
        return (source["placement"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?.let(::setOf)
            ?: setOf(Placement.USER_INPUT, Placement.AI_OUTPUT)
    }

    /**
     * 应用指定 placement 的脚本。
     *
     * @param promptSide true 表示当前处理的是送往模型的文本（过滤 promptOnly/markdownOnly），
     *                    false 表示显示文本（过滤 markdownOnly 的反面）。
     * @param macroExpand 对替换串尾部应用宏展开（{{match}} 已在此前处理）。
     */
    fun apply(
        text: String,
        scripts: List<Script>,
        placement: Int,
        promptSide: Boolean,
        macroExpand: (String) -> String = { it },
    ): String {
        if (text.isEmpty() || scripts.isEmpty()) return text
        if (text.length > MAX_TEXT_CHARS) return text
        var current = text
        scripts.forEach { script ->
            if (placement !in script.placements) return@forEach
            if (promptSide && script.markdownOnly) return@forEach
            if (!promptSide && script.promptOnly) return@forEach
            current = runCatching { applyOne(current, script, macroExpand) }
                .getOrDefault(current)
            if (current.length > MAX_TEXT_CHARS) current = current.take(MAX_TEXT_CHARS)
        }
        return current
    }

    private fun applyOne(text: String, script: Script, macroExpand: (String) -> String): String {
        val (pattern, flags) = splitPattern(script.pattern)
        val options = flagsToOptions(flags)
        val regex = Regex(pattern, options)
        if (!regex.containsMatchIn(text)) return text
        val replaced = regex.replace(text) { match ->
            expandReplacement(match, script.replacement, macroExpand)
        }
        if (script.trimStrings.isEmpty()) return replaced
        var trimmed = replaced
        script.trimStrings.forEach { trim -> trimmed = trimmed.replace(trim, "") }
        return trimmed
    }

    /** 拆分 `/pattern/flags` 形式；无分隔符时按裸模式处理。 */
    private fun splitPattern(raw: String): Pair<String, String> {
        if (!raw.startsWith('/')) return raw to ""
        val lastSlash = raw.lastIndexOf('/')
        if (lastSlash <= 0) return raw to ""
        val body = raw.substring(1, lastSlash)
        val flags = raw.substring(lastSlash + 1)
        return if (flags.all { it.isLetter() }) body to flags else raw to ""
    }

    private fun flagsToOptions(flags: String): Set<RegexOption> = buildSet {
        if (flags.contains('i')) add(RegexOption.IGNORE_CASE)
        if (flags.contains('m')) add(RegexOption.MULTILINE)
        if (flags.contains('s')) add(RegexOption.DOT_MATCHES_ALL)
        if (flags.contains('x')) add(RegexOption.COMMENTS)
    }

    private fun expandReplacement(
        match: MatchResult,
        replacement: String,
        macroExpand: (String) -> String,
    ): String {
        if (replacement.isEmpty()) return ""
        val withGroups = buildString {
            var index = 0
            while (index < replacement.length) {
                val char = replacement[index]
                if (char == '$' && index + 1 < replacement.length && replacement[index + 1].isDigit()) {
                    val group = replacement[index + 1].digitToInt()
                    append(match.groupValues.getOrElse(group) { "" })
                    index += 2
                } else {
                    append(char)
                    index += 1
                }
            }
        }
        val withMatch = withGroups.replace("{{match}}", match.value)
        return macroExpand(withMatch)
    }
}
