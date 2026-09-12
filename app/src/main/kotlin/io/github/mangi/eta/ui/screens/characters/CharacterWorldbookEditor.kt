package io.github.mangi.eta.ui.screens.characters

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.roleplay.CharacterBookEntryDraft
import io.github.mangi.eta.agent.roleplay.CharacterWorldbook
import io.github.mangi.eta.ui.app.CharacterLibraryStore
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun LazyListScope.characterWorldbookEditor(
    store: CharacterLibraryStore,
    expanded: Boolean,
    expandedEntry: Int?,
    onToggleExpanded: () -> Unit,
    onExpandEntry: (Int?) -> Unit,
) {
    val card = store.draft ?: return
    val book = card.worldbookDraft()
    val enabled = !store.busy
    item(key = "worldbook-title") { SmallTitle("世界书") }
    item(key = "worldbook-header") {
        Card(modifier = Modifier.padding(horizontal = CharacterCardPadding)) {
            ArrowPreference(
                title = "内嵌世界书",
                summary = "${book.entries.size} 个条目 · ${if (expanded) "收起" else "展开编辑"}",
                onClick = onToggleExpanded,
            )
        }
    }
    if (!expanded) return
    item(key = "worldbook-name") {
        CharacterTextField("世界书名称", book.name, { value -> store.updateWorldbook { it.copy(name = value) } }, enabled, singleLine = true)
    }
    item(key = "worldbook-depth") {
        CharacterTextField("扫描最近消息数（留空使用默认值）", book.scanDepth?.toString().orEmpty(), { value ->
            optionalNonNegativeInt(value) { number -> store.updateWorldbook { it.copy(scanDepth = number) } }
        }, enabled, singleLine = true)
    }
    item(key = "worldbook-budget") {
        CharacterTextField("Token 预算（留空自动分配）", book.tokenBudget?.toString().orEmpty(), { value ->
            optionalNonNegativeInt(value) { number -> store.updateWorldbook { it.copy(tokenBudget = number) } }
        }, enabled, singleLine = true)
    }
    item(key = "worldbook-recursive") {
        Card(modifier = Modifier.padding(horizontal = CharacterCardPadding, vertical = 6.dp)) {
            SwitchPreference(
                title = "递归匹配",
                summary = "使用已匹配条目的内容继续寻找相关条目",
                checked = book.recursiveScanning == true,
                enabled = enabled,
                onCheckedChange = { value -> store.updateWorldbook { it.copy(recursiveScanning = value) } },
            )
        }
    }
    item(key = "worldbook-entries-title") { SmallTitle("条目") }
    val unsupported = CharacterWorldbook.unsupportedEntries(card).associate { it.index to it.reasons }
    itemsIndexed(book.entries, key = { index, _ -> "worldbook-entry-$index" }) { index, entry ->
        Card(
            modifier = Modifier
                .padding(horizontal = CharacterCardPadding, vertical = 6.dp),
        ) {
            ArrowPreference(
                title = entry.name.take(120).ifBlank { "条目 ${index + 1}" },
                summary = when {
                    !entry.enabled -> "已停用"
                    !unsupported[index].isNullOrEmpty() -> "已跳过：${unsupported[index].orEmpty().joinToString("；")}"
                    entry.constant -> "始终参与上下文"
                    else -> entry.keys.take(4).joinToString("、").take(160).ifBlank { "尚未设置触发关键词" }
                },
                onClick = { onExpandEntry(if (expandedEntry == index) null else index) },
                endActions = {
                    Switch(
                        checked = entry.enabled,
                        enabled = enabled,
                        onCheckedChange = { value ->
                            store.updateWorldbook { current ->
                                current.copy(entries = current.entries.toMutableList().apply { this[index] = entry.copy(enabled = value) })
                            }
                        },
                    )
                },
            )
            if (expandedEntry == index) {
                CharacterWorldbookEntryEditor(
                    entry = entry,
                    enabled = enabled,
                    unsupported = unsupported[index].orEmpty(),
                    onChange = { replacement ->
                        store.updateWorldbook { current -> current.copy(entries = current.entries.toMutableList().apply { this[index] = replacement }) }
                    },
                    onDelete = {
                        onExpandEntry(null)
                        store.updateWorldbook { current -> current.copy(entries = current.entries.filterIndexed { i, _ -> i != index }) }
                    },
                )
            }
        }
    }
    item(key = "worldbook-add-entry") {
        TextButton(
            text = "添加条目",
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CharacterCardPadding, vertical = 8.dp),
            onClick = {
                onExpandEntry(book.entries.size)
                store.updateWorldbook { it.copy(entries = it.entries + CharacterBookEntryDraft(insertionOrder = it.entries.size)) }
            },
        )
    }
}

@Composable
private fun CharacterWorldbookEntryEditor(
    entry: CharacterBookEntryDraft,
    enabled: Boolean,
    unsupported: List<String>,
    onChange: (CharacterBookEntryDraft) -> Unit,
    onDelete: () -> Unit,
) {
    if (unsupported.isNotEmpty()) {
        Text(
            "此条目暂不参与匹配：${unsupported.joinToString("；")}",
            style = MiuixTheme.textStyles.body2,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
    CharacterFieldGroupLabel("内容")
    CharacterTextField("条目标题", entry.name, { onChange(entry.copy(name = it)) }, enabled, singleLine = true)
    CharacterTextField("内容", entry.content, { onChange(entry.copy(content = it)) }, enabled, minLines = 4)
    CharacterFieldGroupLabel("触发")
    CharacterTextField("主关键词（每行一个）", entry.keys.joinToString("\n"), { onChange(entry.copy(keys = it.lines())) }, enabled)
    CharacterTextField("次级关键词（每行一个）", entry.secondaryKeys.joinToString("\n"), { onChange(entry.copy(secondaryKeys = it.lines())) }, enabled)
    SwitchPreference(
        title = "同时匹配次级关键词",
        checked = entry.selective,
        enabled = enabled,
        onCheckedChange = { onChange(entry.copy(selective = it)) },
    )
    CharacterFieldGroupLabel("插入")
    SwitchPreference(
        title = "常驻上下文",
        checked = entry.constant,
        enabled = enabled,
        onCheckedChange = { onChange(entry.copy(constant = it)) },
    )
    val positionNames = listOf(
        "before_char", "after_char", "before_examples", "after_examples", "at_depth",
    )
    val positionLabels = listOf(
        "角色设定之前", "角色设定之后", "对话示例之前", "对话示例之后", "按深度注入对话",
    )
    WindowSpinnerPreference(
        items = positionLabels.map { DropdownItem(text = it) },
        selectedIndex = positionNames.indexOf(entry.position).coerceAtLeast(0),
        title = "插入位置",
        summary = when (entry.position) {
            "at_depth" -> "注入到最近对话中，距末尾 ${entry.depth ?: 4} 条消息"
            "before_examples" -> "放在对话示例之前"
            "after_examples" -> "放在对话示例之后"
            "before_char" -> "放在角色设定之前"
            else -> "放在角色设定之后"
        },
        enabled = enabled,
        onSelectedIndexChange = { index ->
            onChange(entry.copy(position = positionNames.getOrElse(index) { "after_char" }))
        },
    )
    if (entry.position == "at_depth") {
        CharacterTextField("注入深度（距最近对话末尾的消息数）", (entry.depth ?: 4).toString(), { value ->
            value.toIntOrNull()?.takeIf { it >= 0 }?.let { onChange(entry.copy(depth = it)) }
        }, enabled, singleLine = true)
        val roleNames = listOf("system", "user", "assistant")
        val roleLabels = listOf("系统提示", "以用户消息", "以角色消息")
        WindowSpinnerPreference(
            items = roleLabels.map { DropdownItem(text = it) },
            selectedIndex = roleNames.indexOf(entry.role).coerceAtLeast(0),
            title = "注入角色",
            summary = "决定该条目以哪种消息身份进入上下文",
            enabled = enabled,
            onSelectedIndexChange = { index ->
                onChange(entry.copy(role = roleNames.getOrElse(index) { "system" }))
            },
        )
    }
    CharacterTextField("插入顺序", entry.insertionOrder.toString(), { value ->
        value.toIntOrNull()?.takeIf { it >= 0 }?.let { onChange(entry.copy(insertionOrder = it)) }
    }, enabled, singleLine = true)
    CharacterTextField("优先级（越大越先保留，留空默认 0）", entry.priority?.toString().orEmpty(), { value ->
        value.trim().toIntOrNull()?.let { onChange(entry.copy(priority = it)) }
    }, enabled, singleLine = true)
    CharacterFieldGroupLabel("高级触发（来自酒馆/Tavo 卡）")
    CharacterTextField("触发概率 0-100（留空为 100）", entry.probability?.toString().orEmpty(), { value ->
        value.trim().toIntOrNull()?.takeIf { it in 0..100 }?.let { onChange(entry.copy(probability = it)) }
    }, enabled, singleLine = true)
    SwitchPreference(
        title = "全词匹配",
        summary = "关键词只在完整词语时命中",
        checked = entry.matchWholeWords,
        enabled = enabled,
        onCheckedChange = { onChange(entry.copy(matchWholeWords = it)) },
    )
    SwitchPreference(
        title = "忽略预算",
        summary = "始终注入，不占用世界书 Token 预算",
        checked = entry.ignoreBudget,
        enabled = enabled,
        onCheckedChange = { onChange(entry.copy(ignoreBudget = it)) },
    )
    CharacterTextField("分组（同组只保留一条，留空不分组）", entry.group, { onChange(entry.copy(group = it)) }, enabled, singleLine = true)
    if (entry.group.isNotBlank()) {
        CharacterTextField("组内权重（同优先级随机时使用）", entry.groupWeight?.toString().orEmpty(), { value ->
            value.trim().toDoubleOrNull()?.takeIf { it >= 0 }?.let { onChange(entry.copy(groupWeight = it)) }
        }, enabled, singleLine = true)
        SwitchPreference(
            title = "覆盖同组条目",
            summary = "触发时替代同组其他条目",
            checked = entry.groupOverride,
            enabled = enabled,
            onCheckedChange = { onChange(entry.copy(groupOverride = it)) },
        )
    }
    CharacterTextField("保持回合数 sticky（留空不启用）", entry.sticky?.toString().orEmpty(), { value ->
        value.trim().toIntOrNull()?.takeIf { it >= 0 }?.let { onChange(entry.copy(sticky = it)) }
    }, enabled, singleLine = true)
    CharacterTextField("冷却回合数 cooldown（留空不启用）", entry.cooldown?.toString().orEmpty(), { value ->
        value.trim().toIntOrNull()?.takeIf { it >= 0 }?.let { onChange(entry.copy(cooldown = it)) }
    }, enabled, singleLine = true)
    CharacterTextField("延迟回合数 delay（留空不启用）", entry.delay?.toString().orEmpty(), { value ->
        value.trim().toIntOrNull()?.takeIf { it >= 0 }?.let { onChange(entry.copy(delay = it)) }
    }, enabled, singleLine = true)
    TextButton(
        text = "移除条目",
        enabled = enabled,
        onClick = onDelete,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

private fun optionalNonNegativeInt(value: String, onValue: (Int?) -> Unit) {
    if (value.isBlank()) onValue(null)
    else value.toIntOrNull()?.takeIf { it >= 0 }?.let(onValue)
}
