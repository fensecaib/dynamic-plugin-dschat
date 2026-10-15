package top.colter.dynamic.agent.chat

import top.colter.dynamic.agent.config.ChatConfig
import top.colter.dynamic.agent.ds.ChatMessage
import java.util.concurrent.ConcurrentHashMap

class SessionManager(private val config: ChatConfig) {

    data class SessionKey(val targetId: String, val senderId: String)

    class SessionEntry(
        val messages: ArrayDeque<ChatMessage> = ArrayDeque(),
        var lastAccessTime: Long = System.currentTimeMillis()
    )

    private val sessions = ConcurrentHashMap<SessionKey, SessionEntry>()

    private fun now() = System.currentTimeMillis()

    fun getOrCreate(key: SessionKey): SessionEntry = sessions.compute(key) { _, existing ->
        val ttl = config.memoryTtlMinutes * 60_000L
        if (existing == null) SessionEntry() else synchronized(existing) {
            val accessedAt = now()
            if (accessedAt - existing.lastAccessTime > ttl) {
                SessionEntry(lastAccessTime = accessedAt)
            } else {
                existing.lastAccessTime = accessedAt
                existing
            }
        }
    }!!

    fun clear(key: SessionKey) {
        sessions.remove(key)
    }

    internal class Turn(val messages: MutableList<ChatMessage>, internal val session: SessionEntry?)

    internal fun beginTurn(key: SessionKey, prompt: String, systemPrompt: String): Turn {
        val session = if (config.enableMemory) getOrCreate(key) else null
        val history = session?.let { synchronized(it) { it.messages.toList() } }.orEmpty()
        return Turn((listOf(ChatMessage("system", systemPrompt)) + history + ChatMessage("user", prompt)).toMutableList(), session)
    }

    fun buildMessages(key: SessionKey, prompt: String, systemPrompt: String): MutableList<ChatMessage> =
        beginTurn(key, prompt, systemPrompt).messages

    fun saveToSession(key: SessionKey, userPrompt: String, assistantReply: String) {
        if (!config.enableMemory) return
        sessions.computeIfPresent(key) { _, session ->
            synchronized(session) { append(session, userPrompt, assistantReply) }
            session
        }
    }

    /** A reply started before clear/expiry must never enter the replacement conversation. */
    internal fun saveTurn(key: SessionKey, turn: Turn, userPrompt: String, assistantReply: String) {
        if (!config.enableMemory || turn.session == null) return
        sessions.computeIfPresent(key) { _, session ->
            if (session === turn.session) synchronized(session) { append(session, userPrompt, assistantReply) }
            session
        }
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
}
