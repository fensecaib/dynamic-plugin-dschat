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

    fun getOrCreate(key: SessionKey): SessionEntry {
        val ttl = config.memoryTtlMinutes * 60_000L
        val existing = sessions[key]

        if (existing != null) {
            if (now() - existing.lastAccessTime > ttl) {
                sessions.remove(key)
                return SessionEntry().also { sessions[key] = it }
            }
            existing.lastAccessTime = now()
            return existing
        }

        return SessionEntry().also { sessions[key] = it }
    }

    fun clear(key: SessionKey) {
        sessions.remove(key)
    }

    fun buildMessages(key: SessionKey, prompt: String, systemPrompt: String): MutableList<ChatMessage> {
        if (!config.enableMemory) {
            return mutableListOf(
                ChatMessage("system", systemPrompt),
                ChatMessage("user", prompt)
            )
        }

        val session = getOrCreate(key)
        val messages = mutableListOf<ChatMessage>()
        messages.add(ChatMessage("system", systemPrompt))
        messages.addAll(session.messages)
        messages.add(ChatMessage("user", prompt))
        return messages
    }

    fun saveToSession(key: SessionKey, userPrompt: String, assistantReply: String) {
        if (!config.enableMemory) return
        val session = sessions[key] ?: return

        session.messages.addLast(ChatMessage("user", userPrompt))
        session.messages.addLast(ChatMessage("assistant", assistantReply))
        session.lastAccessTime = now()

        trimSession(session)
    }

    private fun trimSession(session: SessionEntry) {
        val maxMessages = config.memoryRounds * 2

        while (session.messages.size > maxMessages) {
            session.messages.removeFirst()
            session.messages.removeFirst()
        }

        while (estimateTokens(session.messages) > config.maxContextTokens
            && session.messages.size >= 2
        ) {
            session.messages.removeFirst()
            session.messages.removeFirst()
        }
    }

    private fun estimateTokens(messages: ArrayDeque<ChatMessage>): Int {
        return messages.sumOf { ((it.content ?: "").length * 3L / 2L).toInt() }
    }
}
