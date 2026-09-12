package io.github.mangi.eta.agent.roleplay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

internal data class CharacterBookDraft(
    val name: String = "",
    val scanDepth: Int? = null,
    val recursiveScanning: Boolean? = null,
    val tokenBudget: Int? = null,
    val entries: List<CharacterBookEntryDraft> = emptyList(),
    val raw: JsonObject = JsonObject(emptyMap()),
)

internal data class CharacterBookEntryDraft(
    val name: String = "",
    val content: String = "",
    val keys: List<String> = emptyList(),
    val secondaryKeys: List<String> = emptyList(),
    val enabled: Boolean = true,
    val constant: Boolean = false,
    val selective: Boolean = false,
    /** before_char / after_char / before_examples / after_examples / at_depth */
    val position: String = "after_char",
    val depth: Int? = null,
    /** system / user / assistant */
    val role: String = "system",
    val probability: Int? = null,
    val group: String = "",
    val groupWeight: Double? = null,
    val groupOverride: Boolean = false,
    val sticky: Int? = null,
    val cooldown: Int? = null,
    val delay: Int? = null,
    val matchWholeWords: Boolean = false,
    val ignoreBudget: Boolean = false,
    val priority: Int? = null,
    val insertionOrder: Int = 0,
    val raw: JsonObject = JsonObject(emptyMap()),
)

internal object CharacterWorldbookDraftCodec {
    private val positionNames = listOf(
        "before_char", "after_char", "before_examples", "after_examples", "at_depth",
    )

    fun read(card: CharacterCard): CharacterBookDraft {
        val book = card.characterBook ?: return CharacterBookDraft()
        return CharacterBookDraft(
            name = book.text("name"),
            scanDepth = (book["scan_depth"] as? JsonPrimitive)?.intOrNull,
            recursiveScanning = (book["recursive_scanning"] as? JsonPrimitive)?.booleanOrNull,
            tokenBudget = (book["token_budget"] as? JsonPrimitive)?.intOrNull,
            entries = (book["entries"] as? JsonArray).orEmpty().mapIndexedNotNull { index, value ->
                val entry = value as? JsonObject ?: return@mapIndexedNotNull null
                val extensions = entry["extensions"] as? JsonObject ?: JsonObject(emptyMap())
                val extensionPosition = (extensions["position"] as? JsonPrimitive)?.intOrNull
                val rawPosition = entry.text("position")
                CharacterBookEntryDraft(
                    name = entry.text("name").ifEmpty { entry.text("comment") },
                    content = entry.text("content"),
                    keys = entry.strings("keys"),
                    secondaryKeys = entry.strings("secondary_keys"),
                    enabled = (entry["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
                    constant = (entry["constant"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    selective = (entry["selective"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    position = when {
                        extensionPosition != null -> positionNames.getOrElse(extensionPosition) { "after_char" }
                        rawPosition.isNotBlank() -> rawPosition
                        else -> "after_char"
                    },
                    depth = (extensions["depth"] as? JsonPrimitive)?.intOrNull,
                    role = when ((extensions["role"] as? JsonPrimitive)?.intOrNull) {
                        1 -> "user"
                        2 -> "assistant"
                        else -> "system"
                    },
                    probability = (extensions["probability"] as? JsonPrimitive)?.doubleOrNull?.toInt(),
                    group = extensions.text("group"),
                    groupWeight = (extensions["group_weight"] as? JsonPrimitive)?.doubleOrNull,
                    groupOverride = (extensions["group_override"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    sticky = (extensions["sticky"] as? JsonPrimitive)?.intOrNull,
                    cooldown = (extensions["cooldown"] as? JsonPrimitive)?.intOrNull,
                    delay = (extensions["delay"] as? JsonPrimitive)?.intOrNull,
                    matchWholeWords = (extensions["match_whole_words"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    ignoreBudget = (extensions["ignore_budget"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    priority = (entry["priority"] as? JsonPrimitive)?.intOrNull,
                    insertionOrder = (entry["insertion_order"] as? JsonPrimitive)?.intOrNull ?: index,
                    raw = entry,
                )
            },
            raw = book,
        )
    }

    fun write(card: CharacterCard, draft: CharacterBookDraft): CharacterCard {
        require(draft.scanDepth == null || draft.scanDepth >= 0) { "扫描深度不能为负数" }
        require(draft.tokenBudget == null || draft.tokenBudget >= 0) { "世界书预算不能为负数" }
        require(draft.entries.all { it.position in positionNames }) { "不支持的世界书插入位置" }
        val book = draft.raw.toMutableMap().apply {
            if (draft.name != draft.raw.text("name")) put("name", JsonPrimitive(draft.name))
            if (draft.scanDepth != (draft.raw["scan_depth"] as? JsonPrimitive)?.intOrNull) {
                if (draft.scanDepth == null) remove("scan_depth") else put("scan_depth", JsonPrimitive(draft.scanDepth))
            }
            if (draft.tokenBudget != (draft.raw["token_budget"] as? JsonPrimitive)?.intOrNull) {
                if (draft.tokenBudget == null) remove("token_budget") else put("token_budget", JsonPrimitive(draft.tokenBudget))
            }
            if (draft.recursiveScanning != (draft.raw["recursive_scanning"] as? JsonPrimitive)?.booleanOrNull) {
                if (draft.recursiveScanning == null) remove("recursive_scanning") else put("recursive_scanning", JsonPrimitive(draft.recursiveScanning))
            }
            if (draft.raw.isEmpty()) put("extensions", JsonObject(emptyMap()))
            if (draft.raw.isEmpty() || draft.raw.containsKey("entries") || draft.entries.isNotEmpty()) {
                put("entries", JsonArray(draft.entries.map(::writeEntry)))
            }
        }
        return CharacterCard(JsonObject(card.raw.toMutableMap().apply {
            put("data", JsonObject(card.data.toMutableMap().apply { put("character_book", JsonObject(book)) }))
        }))
    }

    private fun writeEntry(entry: CharacterBookEntryDraft): JsonObject {
        val raw = entry.raw
        val fresh = raw.isEmpty()
        val extensions = ((raw["extensions"] as? JsonObject)?.toMutableMap() ?: mutableMapOf())
        val output = raw.toMutableMap()

        if (fresh || entry.name != raw.text("name").ifEmpty { raw.text("comment") }) {
            output["name"] = JsonPrimitive(entry.name)
            if (output.containsKey("comment")) output["comment"] = JsonPrimitive(entry.name)
        }
        if (fresh || entry.content != raw.text("content")) output["content"] = JsonPrimitive(entry.content)
        if (fresh || entry.keys != raw.strings("keys")) output["keys"] = JsonArray(entry.keys.map(::JsonPrimitive))
        if (fresh || entry.secondaryKeys != raw.strings("secondary_keys")) {
            output["secondary_keys"] = JsonArray(entry.secondaryKeys.map(::JsonPrimitive))
        }
        if (fresh || entry.enabled != ((raw["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true)) {
            output["enabled"] = JsonPrimitive(entry.enabled)
        }
        if (fresh || entry.constant != ((raw["constant"] as? JsonPrimitive)?.booleanOrNull ?: false)) {
            output["constant"] = JsonPrimitive(entry.constant)
        }
        if (fresh || entry.selective != ((raw["selective"] as? JsonPrimitive)?.booleanOrNull ?: false)) {
            output["selective"] = JsonPrimitive(entry.selective)
        }
        if (entry.priority == null) {
            if (raw.containsKey("priority")) output.remove("priority")
        } else if (fresh || entry.priority != (raw["priority"] as? JsonPrimitive)?.intOrNull) {
            output["priority"] = JsonPrimitive(entry.priority)
        }
        if (fresh || entry.insertionOrder != ((raw["insertion_order"] as? JsonPrimitive)?.intOrNull ?: entry.insertionOrder)) {
            output["insertion_order"] = JsonPrimitive(entry.insertionOrder)
        }
        val storedPosition = (extensions["position"] as? JsonPrimitive)?.intOrNull
        val rawPositionText = raw.text("position")
        val originalPosition = when {
            storedPosition != null -> positionNames.getOrElse(storedPosition) { "after_char" }
            rawPositionText.isNotBlank() -> rawPositionText
            else -> "after_char"
        }
        if (fresh || entry.position != originalPosition) {
            val index = positionNames.indexOf(entry.position)
            output["position"] = JsonPrimitive(entry.position)
            if (extensions.containsKey("position") || index >= 0) {
                extensions["position"] = JsonPrimitive(index.coerceAtLeast(0))
            }
        }
        // 以下字段仅存在于 extensions；全部按"值变化才写"保持无损。
        fun extPut(key: String, value: kotlinx.serialization.json.JsonElement?) {
            val original = extensions[key]
            if (value == null) {
                if (original != null && original != JsonNull) extensions.remove(key)
            } else if (fresh || original != value) {
                extensions[key] = value
            }
        }
        extPut("depth", entry.depth?.let(::JsonPrimitive))
        extPut(
            "role",
            when (entry.role) {
                "user" -> JsonPrimitive(1)
                "assistant" -> JsonPrimitive(2)
                else -> null
            },
        )
        extPut("probability", entry.probability?.let(::JsonPrimitive))
        extPut("group", entry.group.takeIf(String::isNotBlank)?.let(::JsonPrimitive))
        extPut("group_weight", entry.groupWeight?.let(::JsonPrimitive))
        extPut("group_override", if (entry.groupOverride) JsonPrimitive(true) else null)
        extPut("sticky", entry.sticky?.let(::JsonPrimitive))
        extPut("cooldown", entry.cooldown?.let(::JsonPrimitive))
        extPut("delay", entry.delay?.let(::JsonPrimitive))
        extPut("match_whole_words", if (entry.matchWholeWords) JsonPrimitive(true) else null)
        extPut("ignore_budget", if (entry.ignoreBudget) JsonPrimitive(true) else null)

        if (fresh || output.containsKey("extensions") || extensions.isNotEmpty()) {
            output["extensions"] = JsonObject(extensions)
        }
        return JsonObject(output)
    }

    private fun JsonObject.text(key: String): String =
        ((get(key) as? JsonPrimitive))?.content.orEmpty()
}
