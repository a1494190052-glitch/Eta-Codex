package io.github.mangi.eta.agent.roleplay

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random

/**
 * 角色卡宏展开。对齐 SillyTavern/Tavo 常用宏集合：
 * - 变量：{{setvar}} {{getvar}} {{addvar}} {{incvar}} {{decvar}} 及 global 变体
 * - 随机：{{random:a,b}} {{random:a::b}} {{random}}；稳定随机 {{pick:a,b}}
 * - 骰点：{{roll:1d20}} {{roll:d6}} {{roll:2d6+3}}
 * - 上下文：{{lastMessage}} {{lastUserMessage}} {{lastCharMessage}} {{input}}
 * 未识别的宏保留原文，不执行条件、循环或脚本。
 */
internal object CharacterMacros {
    const val MAX_EXPANDED_CHARS = 2 * 1024 * 1024
    // Android 的 ICU 正则要求字面量右花括号也转义，否则对象初始化会失败。
    private val token = Regex("\\{\\{([^{}]+)\\}\\}|<(USER|CHAR|BOT)>", RegexOption.IGNORE_CASE)
    private val opening = Regex("\\{\\{\\s*([^\\s:{}]+)")
    private val rollPattern = Regex("^(\\d*)d(\\d+)([+-]\\d+)?$", RegexOption.IGNORE_CASE)

    /** 展开上下文：会话状态、最近消息与随机源；全部可选，缺省时相关宏退化为空或默认值。 */
    internal data class Context(
        val session: CharacterSessionState? = null,
        val lastMessage: String = "",
        val lastUserMessage: String = "",
        val lastCharMessage: String = "",
        val input: String = "",
        val random: Random = Random.Default,
    )

    private val specialPrefixes = setOf(
        "random", "pick", "roll",
        "setvar", "getvar", "addvar", "incvar", "decvar",
        "setglobalvar", "getglobalvar", "addglobalvar", "incglobalvar", "decglobalvar",
        "lastmessage", "lastusermessage", "lastcharmessage", "input",
    )

    fun hasUnsupportedMacros(text: String, card: CharacterCard): Boolean {
        val names = values(card, "用户", "", "").keys
        return token.findAll(text).any { match ->
            val raw = (match.groups[1]?.value ?: match.groups[2]?.value.orEmpty()).trim()
            val key = raw.substringBefore("::").substringBefore(":").trim().lowercase(Locale.ROOT)
            !key.startsWith("//") && key !in names && key !in specialPrefixes
        } || opening.findAll(text).any { match ->
            val key = match.groupValues[1].lowercase(Locale.ROOT)
            !key.startsWith("//") && key !in names && key !in specialPrefixes
        }
    }

    fun expand(
        text: String,
        card: CharacterCard,
        userName: String = "用户",
        userDescription: String = "",
        original: String = "",
        context: Context = Context(),
    ): String {
        val values = values(card, userName, userDescription, original)
        fun resolve(source: String, active: Set<String>, depth: Int): String {
            checkSize(source.length)
            if (depth >= 8) return source
            val output = StringBuilder(minOf(source.length, 8192))
            var offset = 0
            token.findAll(source).forEach { match ->
                appendBounded(output, source, offset, match.range.first)
                val raw = (match.groups[1]?.value ?: match.groups[2]?.value.orEmpty())
                val key = raw.trim().lowercase(Locale.ROOT)
                val replacement = when {
                    key.startsWith("//") -> ""
                    key in active -> match.value
                    else -> {
                        val special = resolveSpecial(raw, context) { nested, nestedActive ->
                            resolve(nested, active + key + nestedActive, depth + 1)
                        }
                        when {
                            special != null -> special
                            key in values -> resolve(values.getValue(key), active + key, depth + 1)
                            else -> match.value
                        }
                    }
                }
                appendBounded(output, replacement)
                offset = match.range.last + 1
            }
            appendBounded(output, source, offset, source.length)
            return output.toString()
        }
        return resolve(text, emptySet(), 0)
    }

    /**
     * 处理带参数或基于会话状态的宏；返回 null 表示不属于特殊宏。
     * [resolve] 供参数/取值再次宏展开（如 getvar 结果含宏、random 候选含宏）。
     */
    private fun resolveSpecial(
        raw: String,
        context: Context,
        resolve: (String, String) -> String,
    ): String? {
        val normalized = raw.trim()
        val head = normalized.substringBefore("::").substringBefore(":").trim().lowercase(Locale.ROOT)
        if (head !in specialPrefixes) return null
        val args = parseArgs(normalized.substring(head.length))
        return when (head) {
            "random" -> resolve(randomOf(args, context.random), "random")
            "pick" -> resolve(pickOf(args, normalized, context), "pick")
            "roll" -> rollOf(args.firstOrNull().orEmpty(), context.random)
            "setvar" -> setVar(context.session, local = true, args, resolve) ?: ""
            "getvar" -> getVar(context.session, local = true, args)?.let { resolve(it, "getvar") } ?: ""
            "addvar" -> addVar(context.session, local = true, args, resolve) ?: ""
            "incvar" -> incVar(context.session, local = true, args, 1)
            "decvar" -> incVar(context.session, local = true, args, -1)
            "setglobalvar" -> setVar(context.session, local = false, args, resolve) ?: ""
            "getglobalvar" -> getVar(context.session, local = false, args)?.let { resolve(it, "getglobalvar") } ?: ""
            "addglobalvar" -> addVar(context.session, local = false, args, resolve) ?: ""
            "incglobalvar" -> incVar(context.session, local = false, args, 1)
            "decglobalvar" -> incVar(context.session, local = false, args, -1)
            "lastmessage" -> context.lastMessage
            "lastusermessage" -> context.lastUserMessage
            "lastcharmessage" -> context.lastCharMessage
            "input" -> context.input
            else -> null
        }
    }

    /** 参数切分：优先 `::`（酒馆），否则按 `,` 或 `:`。 */
    private fun parseArgs(tail: String): List<String> {
        if (tail.isEmpty()) return emptyList()
        val body = tail.removePrefix("::").removePrefix(":")
        return when {
            "::" in body -> body.split("::")
            "," in body -> body.split(",")
            else -> listOf(body)
        }
    }

    private fun randomOf(args: List<String>, random: Random): String {
        val candidates = args.map(String::trim).filter { it.isNotEmpty() }
        if (candidates.isEmpty()) return random.nextDouble().toString()
        return candidates[random.nextInt(candidates.size)]
    }

    /** {{pick}} 在同一会话内对同一宏返回稳定值（种子 = 会话 seed ⊕ 宏文本）。 */
    private fun pickOf(args: List<String>, raw: String, context: Context): String {
        val candidates = args.map(String::trim).filter { it.isNotEmpty() }
        if (candidates.isEmpty()) return ""
        val seed = (context.session?.seed ?: 0L) xor raw.hashCode().toLong()
        val random = Random(seed)
        return candidates[random.nextInt(candidates.size)]
    }

    private fun rollOf(expression: String, random: Random): String {
        val match = rollPattern.matchEntire(expression.trim()) ?: return ""
        val count = match.groupValues[1].ifEmpty { "1" }.toIntOrNull()?.coerceIn(1, 100) ?: 1
        val sides = match.groupValues[2].toIntOrNull()?.coerceIn(1, 1000) ?: return ""
        val modifier = match.groupValues[3].toIntOrNull() ?: 0
        var total = 0
        repeat(count) { total += random.nextInt(sides) + 1 }
        return (total + modifier).toString()
    }

    private fun setVar(
        session: CharacterSessionState?,
        local: Boolean,
        args: List<String>,
        resolve: (String, String) -> String,
    ): String? {
        if (session == null || args.size < 2) return ""
        val name = args[0].trim().ifEmpty { return "" }
        val value = resolve(args.drop(1).joinToString("::"), "setvar")
        store(session, local)[name] = value
        return ""
    }

    private fun getVar(session: CharacterSessionState?, local: Boolean, args: List<String>): String? {
        if (session == null || args.isEmpty()) return ""
        val name = args[0].trim().ifEmpty { return "" }
        return store(session, local)[name] ?: ""
    }

    private fun addVar(
        session: CharacterSessionState?,
        local: Boolean,
        args: List<String>,
        resolve: (String, String) -> String,
    ): String? {
        if (session == null || args.size < 2) return ""
        val name = args[0].trim().ifEmpty { return "" }
        val delta = resolve(args[1], "addvar").trim().toDoubleOrNull() ?: return ""
        val store = store(session, local)
        val current = store[name]?.trim()?.toDoubleOrNull() ?: 0.0
        store[name] = formatNumber(current + delta)
        return ""
    }

    private fun incVar(session: CharacterSessionState?, local: Boolean, args: List<String>, delta: Int): String {
        if (session == null || args.isEmpty()) return ""
        val name = args[0].trim().ifEmpty { return "" }
        val store = store(session, local)
        val current = store[name]?.trim()?.toDoubleOrNull() ?: 0.0
        store[name] = formatNumber(current + delta)
        return ""
    }

    private fun store(session: CharacterSessionState, local: Boolean): MutableMap<String, String> =
        if (local) session.vars else session.globalVars

    private fun formatNumber(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    private fun appendBounded(output: StringBuilder, value: String, start: Int = 0, end: Int = value.length) {
        checkSize(output.length.toLong() + end - start)
        output.append(value, start, end)
    }

    private fun checkSize(length: Number) {
        if (length.toLong() > MAX_EXPANDED_CHARS) {
            throw CharacterCardException("CARD_MACRO_EXPANSION_LIMIT", "角色宏展开超过长度上限，请精简循环引用或重复宏")
        }
    }

    private fun values(card: CharacterCard, userName: String, userDescription: String, original: String): Map<String, String> {
        val now = LocalDateTime.now()
        return mapOf(
            "char" to card.nickname,
            "user" to userName.ifBlank { "用户" },
            "bot" to card.nickname,
            "original" to original,
            "description" to card.description,
            "personality" to card.personality,
            "scenario" to card.scenario,
            "persona" to userDescription,
            "mesexamples" to card.exampleMessages,
            "mesexamplesraw" to card.exampleMessages,
            "charfirstmessage" to card.firstMessage,
            "charprompt" to card.systemPrompt,
            "charinstruction" to card.postHistoryInstructions,
            "chardepthprompt" to card.depthPrompt?.prompt.orEmpty(),
            "group" to card.nickname,
            "charifnotgroup" to card.nickname,
            "notchar" to userName.ifBlank { "用户" },
            "date" to now.format(DateTimeFormatter.ISO_LOCAL_DATE),
            "isodate" to now.format(DateTimeFormatter.ISO_LOCAL_DATE),
            "time" to now.format(DateTimeFormatter.ofPattern("HH:mm")),
            "isotime" to now.format(DateTimeFormatter.ofPattern("HH:mm")),
            "weekday" to now.format(DateTimeFormatter.ofPattern("EEEE", Locale.SIMPLIFIED_CHINESE)),
            "newline" to "\n",
            "noop" to "",
        )
    }
}
