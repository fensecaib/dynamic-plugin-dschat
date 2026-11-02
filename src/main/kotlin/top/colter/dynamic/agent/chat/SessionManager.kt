package top.colter.dynamic.agent.chat

import top.colter.dynamic.agent.config.ChatConfig
import top.colter.dynamic.agent.ds.ChatMessage

class SessionManager internal constructor(
    config: ChatConfig,
    private val now: () -> Long,
    private val maxSessions: Int = MAX_SESSIONS,
) {

    constructor(config: ChatConfig) : this(config, { System.nanoTime() / 1_000_000L })

    init {
        require(maxSessions > 0)
    }

    data class SessionKey(val targetId: String, val senderId: String)

    class SessionEntry(
        val messages: ArrayDeque<ChatMessage> = ArrayDeque(),
        var lastAccessTime: Long = System.nanoTime() / 1_000_000L,
    )

    // Only short in-memory operations hold this lock; model calls remain independent.
    private val lock = Any()
    private var config = config
    private val sessions = LinkedHashMap<SessionKey, SessionEntry>()
    private var lastCleanupTime = now()

    internal val sessionCount: Int get() = synchronized(lock) { sessions.size }

    fun getOrCreate(key: SessionKey): SessionEntry = synchronized(lock) {
        val accessedAt = now()
        if (config.enableMemory) getOrCreateLocked(key, accessedAt)
        else SessionEntry(lastAccessTime = accessedAt)
    }

    private fun getOrCreateLocked(key: SessionKey, accessedAt: Long): SessionEntry {
        if (accessedAt - lastCleanupTime >= CLEANUP_INTERVAL_MS) cleanupExpired(accessedAt)

        val existing = sessions[key]
        if (existing != null && !isExpired(existing, accessedAt)) {
            existing.lastAccessTime = accessedAt
            return existing
        }
        sessions.remove(key)

        if (sessions.size >= maxSessions) {
            // Capacity pressure must reclaim expired entries even between scheduled scans.
            cleanupExpired(accessedAt)
            if (sessions.size >= maxSessions) {
                val oldest = sessions.minBy { it.value.lastAccessTime }.key
                sessions.remove(oldest)
            }
        }

        return SessionEntry(lastAccessTime = accessedAt).also { sessions[key] = it }
    }

    private fun isExpired(session: SessionEntry, accessedAt: Long): Boolean =
        accessedAt - session.lastAccessTime >= config.memoryTtlMinutes.coerceAtLeast(0) * 60_000L

    private fun cleanupExpired(accessedAt: Long) {
        sessions.entries.removeIf { isExpired(it.value, accessedAt) }
        lastCleanupTime = accessedAt
    }

    fun clear(key: SessionKey) = synchronized(lock) {
        sessions.remove(key)
        Unit
    }

    fun updateConfig(next: ChatConfig) = synchronized(lock) {
        val previous = config
        config = next
        if (!next.enableMemory) {
            // Removing the entries also invalidates replies started before memory was disabled.
            sessions.clear()
        } else if (previous.memoryRounds != next.memoryRounds || previous.maxContextTokens != next.maxContextTokens) {
            sessions.values.forEach(::trimSession)
        }
    }

    internal class Turn(val messages: MutableList<ChatMessage>, internal val session: SessionEntry?)

    internal fun beginTurn(key: SessionKey, prompt: String, systemPrompt: String): Turn = synchronized(lock) {
        val session = if (config.enableMemory) getOrCreateLocked(key, now()) else null
        val history = session?.messages?.toList().orEmpty()
        Turn((listOf(ChatMessage("system", systemPrompt)) + history + ChatMessage("user", prompt)).toMutableList(), session)
    }

    fun buildMessages(key: SessionKey, prompt: String, systemPrompt: String): MutableList<ChatMessage> =
        beginTurn(key, prompt, systemPrompt).messages

    fun saveToSession(key: SessionKey, userPrompt: String, assistantReply: String) = synchronized(lock) {
        if (config.enableMemory) sessions[key]?.let { append(it, userPrompt, assistantReply) }
    }

    /** A reply started before clear/expiry must never enter the replacement conversation. */
    internal fun saveTurn(key: SessionKey, turn: Turn, userPrompt: String, assistantReply: String) = synchronized(lock) {
        if (!config.enableMemory || turn.session == null) return@synchronized
        val session = sessions[key]
        if (session === turn.session) append(session, userPrompt, assistantReply)
    }

    private fun append(session: SessionEntry, userPrompt: String, assistantReply: String) {
        session.messages.addLast(ChatMessage("user", userPrompt))
        session.messages.addLast(ChatMessage("assistant", assistantReply))
        session.lastAccessTime = now()
        trimSession(session)
    }

    private fun trimSession(session: SessionEntry) {
        val maxMessages = config.memoryRounds.coerceAtLeast(0).toLong() * 2

        while (session.messages.size > maxMessages) {
            session.messages.removeFirst()
            session.messages.removeFirst()
        }

        var tokens = session.messages.sumOf(::estimateTokens)
        while (tokens > config.maxContextTokens
            && session.messages.size >= 2
        ) {
            tokens -= estimateTokens(session.messages.removeFirst())
            tokens -= estimateTokens(session.messages.removeFirst())
        }
    }

    private fun estimateTokens(message: ChatMessage): Long = (message.content?.length ?: 0) * 3L / 2L

    private companion object {
        const val MAX_SESSIONS = 256
        const val CLEANUP_INTERVAL_MS = 5 * 60_000L
    }
}
