package io.github.mangi.eta.agent.roleplay

import java.util.concurrent.ConcurrentHashMap

/**
 * 每个角色扮演会话的可变运行状态：宏变量与世界书时序（sticky/cooldown/delay）。
 *
 * 语义对齐 SillyTavern/Tavo 生态：
 * - vars 对应 {{setvar}}/{{getvar}} 等对话局部变量；
 * - globalVars 对应 {{setglobalvar}} 等角色级变量（会话内共享）；
 * - 世界书状态按条目索引记录生效区间。
 *
 * 说明：当前实现为进程内存态（跨轮次、跨重开 App 进程前有效）。持久化到会话记录
 * 属于后续增强，不影响宏与世界的触发语义。
 */
internal data class CharacterSessionState(
    val vars: MutableMap<String, String> = mutableMapOf(),
    val globalVars: MutableMap<String, String> = mutableMapOf(),
    val worldbook: MutableMap<String, WorldbookEntryRuntime> = mutableMapOf(),
    /** 当前用户轮次（由投影层在每次组装时校准；世界书时序以此为基准）。 */
    var turn: Int = 0,
    /** pick/random 稳定种子源：随会话创建固定，保证 {{pick}} 在同一会话结果一致。 */
    val seed: Long = System.nanoTime() xor System.currentTimeMillis(),
) {
    internal fun snapshot(): CharacterSessionState = copy(
        vars = vars.toMutableMap(),
        globalVars = globalVars.toMutableMap(),
        worldbook = worldbook.toMutableMap(),
    )
}

/** 世界书条目的时序状态；stickyUntilTurn/cooldownUntilTurn 使用回合序号（1 起）。 */
internal data class WorldbookEntryRuntime(
    val stickyUntilTurn: Int = 0,
    val cooldownUntilTurn: Int = 0,
)

/** 会话状态注册表；键为 conversationId。LRU 上限避免长进程内存膨胀。 */
internal object CharacterSessionRegistry {
    private const val MAX_SESSIONS = 64
    private val sessions = ConcurrentHashMap<String, CharacterSessionState>()
    private val order = ArrayDeque<String>()

    @Synchronized
    fun get(conversationId: String): CharacterSessionState {
        val existing = sessions[conversationId]
        if (existing != null) return existing
        val fresh = CharacterSessionState()
        sessions[conversationId] = fresh
        order += conversationId
        while (order.size > MAX_SESSIONS) {
            val evicted = order.removeFirst()
            sessions.remove(evicted)
        }
        return fresh
    }

    @Synchronized
    fun reset(conversationId: String) {
        sessions.remove(conversationId)
        order.remove(conversationId)
    }
}
