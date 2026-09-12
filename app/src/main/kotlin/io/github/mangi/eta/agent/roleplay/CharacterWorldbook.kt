package io.github.mangi.eta.agent.roleplay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.random.Random

/** 世界书投影：按位置分组的正文与深度注入消息。 */
internal data class WorldbookProjection(
    val beforeCharacter: String = "",
    val afterCharacter: String = "",
    val beforeExamples: String = "",
    val afterExamples: String = "",
    val depthInjections: List<WorldbookDepthInjection> = emptyList(),
    val usedTokens: Int = 0,
)

/** position=4 的深度注入；depth 为距对话末尾的消息数。 */
internal data class WorldbookDepthInjection(
    val depth: Int,
    val role: String,
    val content: String,
    val order: Int,
    val index: Int,
)

/**
 * 世界书引擎，语义对齐 SillyTavern/Tavo：
 * - 位置 0=角色前 1=角色后 2=示例前 3=示例后 4=深度注入(@D)
 * - 概率、分组（group/group_override/group_weight）、时序（sticky/cooldown/delay）
 * - 正则关键字（/pattern/flags）、全词匹配、selectiveLogic 0..3、递归与排除规则
 * - 扩展匹配（人设/角色描述/性格/场景/创作者备注）
 */
internal object CharacterWorldbook {
    private const val POSITION_BEFORE_CHAR = 0
    private const val POSITION_AFTER_CHAR = 1
    private const val POSITION_BEFORE_EXAMPLES = 2
    private const val POSITION_AFTER_EXAMPLES = 3
    private const val POSITION_DEPTH = 4

    fun unsupportedEntries(card: CharacterCard): List<UnsupportedWorldbookEntry> =
        (card.characterBook?.get("entries") as? JsonArray).orEmpty().mapIndexedNotNull { index, value ->
            val entry = value as? JsonObject ?: return@mapIndexedNotNull null
            CharacterWorldbookSupport.reasons(entry).takeIf { it.isNotEmpty() }?.let { UnsupportedWorldbookEntry(index, it) }
        }

    private data class Entry(
        val index: Int,
        val data: JsonObject,
        val content: String,
        val constant: Boolean,
        val order: Int,
        val priority: Int,
        val position: Int,
        val depth: Int,
        val role: String,
        val caseSensitive: Boolean,
        val wholeWords: Boolean,
        val useRegex: Boolean,
        val selectiveLogic: Int,
        val probability: Int,
        val useProbability: Boolean,
        val group: String,
        val groupWeight: Double,
        val groupOverride: Boolean,
        val sticky: Int,
        val cooldown: Int,
        val delay: Int,
        val delayUntilRecursion: Boolean,
        val excludeRecursion: Boolean,
        val preventRecursion: Boolean,
        val ignoreBudget: Boolean,
        val matchPersona: Boolean,
        val matchCharacterInfo: Boolean,
        val triggersNormal: Boolean,
    ) {
        val extensions = data["extensions"] as? JsonObject ?: JsonObject(emptyMap())
    }

    fun resolve(
        card: CharacterCard,
        messages: List<String>,
        inputTokenBudget: Int,
        estimateTokens: (String) -> Int,
        session: CharacterSessionState? = null,
        personaTexts: List<String> = emptyList(),
        regexScripts: List<CharacterRegexScripts.Script> = emptyList(),
        macroExpand: (String) -> String = { it },
        random: Random = Random.Default,
    ): WorldbookProjection {
        val book = card.characterBook ?: return WorldbookProjection()
        val available = inputTokenBudget.coerceAtLeast(0)
        val budget = (book.number("token_budget") ?: (available / 4)).coerceIn(0, available)
        if (budget == 0 && !hasIgnoreBudgetEntries(book)) return WorldbookProjection()
        val depth = (book.number("scan_depth") ?: 2).coerceAtLeast(0)
        val recursive = book.bool("recursive_scanning") ?: false
        val turn = session?.turn ?: 0
        val entries = (book["entries"] as? JsonArray).orEmpty().mapIndexedNotNull { index, value ->
            val entry = value as? JsonObject ?: return@mapIndexedNotNull null
            if (entry.bool("enabled") == false) return@mapIndexedNotNull null
            if (CharacterWorldbookSupport.reasons(entry).isNotEmpty()) return@mapIndexedNotNull null
            parseEntry(index, entry, regexScripts, macroExpand).takeIf { it.content.isNotBlank() }
        }
        if (entries.isEmpty()) return WorldbookProjection()

        val staticScanText = buildStaticScanText(card, entries, personaTexts)
        val matched = linkedMapOf<Int, Entry>()
        // 时序状态（delay/sticky/cooldown）与激活集合。
        val stickyActive = entries.filter { entry ->
            val state = session?.worldbook?.get(entry.index.toString())
            entry.sticky > 0 && (state?.stickyUntilTurn ?: 0) >= turn
        }.associateBy { it.index }

        fun eligible(entry: Entry, inRecursion: Boolean): Boolean {
            if (entry.delay > 0 && turn < entry.delay) return false
            val cooldownUntil = session?.worldbook?.get(entry.index.toString())?.cooldownUntilTurn ?: 0
            if (cooldownUntil > turn) return false
            if (entry.delayUntilRecursion && !inRecursion) return false
            if (entry.excludeRecursion && inRecursion) return false
            return true
        }

        fun matchesEntry(entry: Entry, scanned: String): Boolean {
            if (entry.constant) return true
            if (!entry.triggersNormal) return false
            if (entry.probability < 100 && entry.useProbability) {
                if (random.nextInt(100) >= entry.probability) return false
            }
            val primary = entry.data.strings("keys").any { key ->
                matches(key, scanned, entry)
            }
            if (!primary) return false
            val secondaryKeys = entry.data.strings("secondary_keys")
            if (entry.data.bool("selective") != true) return true
            // 与酒馆语义一致：启用次级匹配但没有次级关键词时不激活。
            if (secondaryKeys.isEmpty()) return false
            val secondaryHits = secondaryKeys.count { key -> matches(key, scanned, entry) }
            return when (entry.selectiveLogic) {
                1 -> secondaryHits < secondaryKeys.size        // NOT_ALL
                2 -> secondaryHits == 0                        // NOT_ANY
                3 -> secondaryHits == secondaryKeys.size       // AND_ALL
                else -> secondaryHits > 0                      // AND_ANY
            }
        }

        var inRecursion = false
        do {
            val priorSize = matched.size
            val recursiveText = if (recursive) {
                matched.values.filterNot { it.preventRecursion }.joinToString("\n") { it.content }
            } else {
                ""
            }
            entries.forEach { entry ->
                if (entry.index in matched) return@forEach
                if (entry.index in stickyActive) {
                    matched[entry.index] = entry
                    return@forEach
                }
                if (!eligible(entry, inRecursion)) return@forEach
                val localDepth = (entry.extensions.number("scan_depth") ?: depth).coerceAtLeast(0)
                val scanned = messages.takeLast(localDepth).joinToString("\n") + "\n" + staticScanText + "\n" + recursiveText
                val hit = matchesEntry(entry, scanned)
                if (hit) {
                    matched[entry.index] = entry
                    val runtime = session?.worldbook?.get(entry.index.toString())
                    if (session != null && (entry.sticky > 0 || entry.cooldown > 0)) {
                        session.worldbook[entry.index.toString()] = WorldbookEntryRuntime(
                            stickyUntilTurn = if (entry.sticky > 0) {
                                maxOf(runtime?.stickyUntilTurn ?: 0, turn + entry.sticky)
                            } else {
                                runtime?.stickyUntilTurn ?: 0
                            },
                            cooldownUntilTurn = if (entry.cooldown > 0) turn + entry.cooldown else runtime?.cooldownUntilTurn ?: 0,
                        )
                    }
                }
            }
            inRecursion = true
        } while (recursive && matched.size > priorSize)

        val selected = applyGroups(matched.values.toList(), random)
        val orderedForBudget = selected.sortedWith(
            compareByDescending<Entry> { it.constant }
                .thenByDescending { it.priority }
                .thenBy { it.order }
                .thenBy { it.index },
        )
        val kept = mutableListOf<Entry>()
        var tokens = 0
        orderedForBudget.forEach { entry ->
            if (entry.ignoreBudget) {
                kept += entry
                return@forEach
            }
            val cost = estimateTokens(entry.content).coerceAtLeast(0)
            if (tokens + cost <= budget) {
                kept += entry
                tokens += cost
            }
        }
        val ordered = kept.sortedWith(compareBy<Entry> { it.order }.thenBy { it.index })
        return WorldbookProjection(
            beforeCharacter = join(ordered.filter { it.position == POSITION_BEFORE_CHAR }),
            afterCharacter = join(ordered.filter { it.position == POSITION_AFTER_CHAR }),
            beforeExamples = join(ordered.filter { it.position == POSITION_BEFORE_EXAMPLES }),
            afterExamples = join(ordered.filter { it.position == POSITION_AFTER_EXAMPLES }),
            depthInjections = ordered.filter { it.position == POSITION_DEPTH }.map { entry ->
                WorldbookDepthInjection(
                    depth = entry.depth,
                    role = entry.role,
                    content = entry.content,
                    order = entry.order,
                    index = entry.index,
                )
            },
            usedTokens = tokens,
        )
    }

    private fun hasIgnoreBudgetEntries(book: JsonObject): Boolean =
        (book["entries"] as? JsonArray).orEmpty().any { value ->
            val extensions = (value as? JsonObject)?.get("extensions") as? JsonObject
            (extensions?.get("ignore_budget") as? JsonPrimitive)?.booleanOrNull == true
        }

    private fun parseEntry(
        index: Int,
        entry: JsonObject,
        regexScripts: List<CharacterRegexScripts.Script>,
        macroExpand: (String) -> String,
    ): Entry {
        val extensions = entry["extensions"] as? JsonObject ?: JsonObject(emptyMap())
        val rawContent = entry.text("content")
        val expanded = macroExpand(rawContent)
        val content = if (regexScripts.isEmpty()) {
            expanded
        } else {
            CharacterRegexScripts.apply(
                text = expanded,
                scripts = regexScripts,
                placement = CharacterRegexScripts.Placement.WORLD_INFO,
                promptSide = true,
                macroExpand = macroExpand,
            )
        }
        val position = when (val ext = extensions.number("position")) {
            0 -> POSITION_BEFORE_CHAR
            1 -> POSITION_AFTER_CHAR
            2 -> POSITION_BEFORE_EXAMPLES
            3 -> POSITION_AFTER_EXAMPLES
            4 -> POSITION_DEPTH
            else -> when (entry.text("position")) {
                "before_char" -> POSITION_BEFORE_CHAR
                "after_char" -> POSITION_AFTER_CHAR
                "before_example", "before_an" -> POSITION_BEFORE_EXAMPLES
                "after_example", "after_an" -> POSITION_AFTER_EXAMPLES
                "at_depth", "at_depth_char" -> POSITION_DEPTH
                else -> ext?.let { POSITION_AFTER_CHAR } ?: POSITION_AFTER_CHAR
            }
        }
        val role = when (extensions.number("role")) {
            0 -> "system"
            1 -> "user"
            2 -> "assistant"
            else -> "system"
        }
        val triggers = (extensions["triggers"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
        return Entry(
            index = index,
            data = entry,
            content = content,
            constant = entry.bool("constant") ?: false,
            order = entry.number("order") ?: entry.number("insertion_order") ?: index,
            priority = entry.number("priority") ?: 0,
            position = position,
            depth = (extensions.number("depth") ?: 4).coerceAtLeast(0),
            role = role,
            caseSensitive = entry.bool("case_sensitive") ?: extensions.bool("case_sensitive") ?: false,
            wholeWords = extensions.bool("match_whole_words") == true,
            useRegex = entry.bool("use_regex") ?: true,
            selectiveLogic = extensions.number("selectiveLogic") ?: extensions.number("selective_logic") ?: 0,
            probability = (extensions.double("probability")?.toInt() ?: 100).coerceIn(0, 100),
            useProbability = extensions.bool("useProbability") ?: extensions.bool("use_probability") ?: true,
            group = extensions.text("group"),
            groupWeight = extensions.double("group_weight") ?: 1.0,
            groupOverride = extensions.bool("group_override") == true,
            sticky = (extensions.number("sticky") ?: 0).coerceAtLeast(0),
            cooldown = (extensions.number("cooldown") ?: 0).coerceAtLeast(0),
            delay = (extensions.number("delay") ?: 0).coerceAtLeast(0),
            delayUntilRecursion = extensions.bool("delay_until_recursion") == true,
            excludeRecursion = extensions.bool("exclude_recursion") == true,
            preventRecursion = extensions.bool("prevent_recursion") == true,
            ignoreBudget = extensions.bool("ignore_budget") == true,
            matchPersona = extensions.bool("match_persona_description") == true,
            matchCharacterInfo = listOf(
                "match_character_description", "match_character_personality",
                "match_character_depth_prompt", "match_scenario", "match_creator_notes",
            ).any { extensions.bool(it) == true },
            triggersNormal = triggers.isNullOrEmpty() || 0 in triggers,
        )
    }

    private fun buildStaticScanText(
        card: CharacterCard,
        entries: List<Entry>,
        personaTexts: List<String>,
    ): String = buildString {
        if (entries.any { it.matchCharacterInfo }) {
            appendLine(card.description)
            appendLine(card.personality)
            appendLine(card.scenario)
            appendLine(card.depthPrompt?.prompt.orEmpty())
            appendLine(card.creatorNotes)
        }
        if (entries.any { it.matchPersona }) {
            personaTexts.forEach(::appendLine)
        }
    }

    /** 分组与覆盖：override 条目独占；否则组内按优先级取一条（同优先级按权重随机）。 */
    private fun applyGroups(entries: List<Entry>, random: Random): List<Entry> {
        val ungrouped = entries.filter { it.group.isBlank() }
        val grouped = entries.filter { it.group.isNotBlank() }.groupBy { it.group }
        val winners = grouped.values.mapNotNull { group ->
            val override = group.filter { it.groupOverride }.maxWithOrNull(
                compareBy<Entry> { it.priority }.thenBy { -it.order },
            )
            if (override != null) return@mapNotNull override
            val best = group.maxOf { it.priority }
            val top = group.filter { it.priority == best }
            if (top.size == 1) top.first() else weightedPick(top, random)
        }
        return ungrouped + winners
    }

    private fun weightedPick(entries: List<Entry>, random: Random): Entry {
        val weightSum = entries.sumOf { it.groupWeight.coerceAtLeast(0.0) }
        if (weightSum <= 0.0) return entries[random.nextInt(entries.size)]
        var target = random.nextDouble() * weightSum
        entries.forEach { entry ->
            target -= entry.groupWeight.coerceAtLeast(0.0)
            if (target <= 0.0) return entry
        }
        return entries.last()
    }

    private fun join(entries: List<Entry>): String =
        entries.joinToString("\n\n") { it.content }

    private fun matches(key: String, text: String, entry: Entry): Boolean {
        if (key.isEmpty()) return false
        val patterns = if (entry.useRegex && key.startsWith("/")) {
            val lastSlash = key.lastIndexOf('/')
            if (lastSlash > 0) {
                val body = key.substring(1, lastSlash)
                val flags = key.substring(lastSlash + 1)
                val options = buildSet {
                    if (flags.contains('i') || !entry.caseSensitive) add(RegexOption.IGNORE_CASE)
                    if (flags.contains('m')) add(RegexOption.MULTILINE)
                    if (flags.contains('s')) add(RegexOption.DOT_MATCHES_ALL)
                }
                runCatching { listOf(Regex(body, options)) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }
        if (patterns.isNotEmpty()) return patterns.any { it.containsMatchIn(text) }
        if (entry.useRegex && looksLikeRegexLiteral(key)) return false
        return if (entry.wholeWords) {
            val pattern = Regex(
                "(?<![\\p{L}\\p{N}_])${Regex.escape(key)}(?![\\p{L}\\p{N}_])",
                if (entry.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE),
            )
            pattern.containsMatchIn(text)
        } else {
            text.contains(key, ignoreCase = !entry.caseSensitive)
        }
    }

    private fun looksLikeRegexLiteral(key: String): Boolean =
        key.startsWith("/") && key.length > 2

    private fun JsonObject.bool(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.intOrNull
    private fun JsonObject.double(key: String) = (get(key) as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.text(key: String): String {
        val value = get(key) ?: return ""
        if (value is JsonNull) return ""
        return (value as? JsonPrimitive)?.content.orEmpty()
    }
}
