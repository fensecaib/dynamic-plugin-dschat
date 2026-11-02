package top.colter.dynamic.agent.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.agent.config.ChatConfig
import kotlin.test.*

class SessionManagerLifecycleTest {
    private val key = SessionManager.SessionKey("group", "user")

    @Test fun `current conversation expires at its deadline between global scans`() {
        var time = 0L
        val sessions = SessionManager(ChatConfig(memoryTtlMinutes = 1), now = { time })
        val old = save(sessions, key)

        time = 60_000L
        val fresh = sessions.beginTurn(key, "next", "system")
        assertEquals(listOf("system", "next"), fresh.messages.map { it.content })
        assertNotSame(old.session, fresh.session)
        assertEquals(1, sessions.sessionCount)
        sessions.saveTurn(key, old, "late", "late answer")
        assertEquals(2, sessions.buildMessages(key, "check", "system").size)
    }

    @Test fun `conversation triggers other expired entries cleanup only every five minutes`() {
        var time = 0L
        val sessions = SessionManager(ChatConfig(memoryTtlMinutes = 1), now = { time })
        save(sessions, key)
        val activeKey = key.copy(senderId = "active")

        time = 60_000L
        sessions.beginTurn(activeKey, "first", "system")
        assertEquals(2, sessions.sessionCount)
        time = 299_999L
        val active = save(sessions, activeKey)
        assertEquals(2, sessions.sessionCount)

        time = 300_000L
        assertSame(active.session, sessions.beginTurn(activeKey, "next", "system").session)
        assertEquals(1, sessions.sessionCount)

        time = 360_000L
        sessions.beginTurn(key.copy(senderId = "new"), "new", "system")
        assertEquals(2, sessions.sessionCount)
        time = 600_000L
        sessions.beginTurn(key.copy(senderId = "trigger"), "trigger", "system")
        assertEquals(1, sessions.sessionCount)
    }

    @Test fun `cleanup removes inactive entries and preserves recently used history`() {
        var time = 0L
        val sessions = SessionManager(ChatConfig(memoryTtlMinutes = 1), now = { time })
        val expired = save(sessions, key)
        val recentKey = key.copy(senderId = "recent")
        time = 270_000L
        val recent = save(sessions, recentKey)

        time = 300_000L
        sessions.beginTurn(key.copy(senderId = "trigger"), "trigger", "system")
        assertEquals(2, sessions.sessionCount)
        val retained = sessions.beginTurn(recentKey, "next", "system")
        assertSame(recent.session, retained.session)
        assertEquals(listOf("system", "question", "answer", "next"), retained.messages.map { it.content })

        val replacement = sessions.beginTurn(key, "fresh", "system")
        sessions.saveTurn(key, expired, "expired", "expired answer")
        assertNotSame(expired.session, replacement.session)
        assertEquals(2, sessions.buildMessages(key, "check", "system").size)
    }

    @Test fun `capacity evicts least recently used conversation and rejects its pending reply`() {
        var time = 0L
        val sessions = SessionManager(ChatConfig(), now = { time }, maxSessions = 2)
        val retained = save(sessions, key)
        val evictedKey = key.copy(senderId = "evicted")
        time = 1_000L
        val evicted = save(sessions, evictedKey)
        time = 2_000L
        sessions.beginTurn(key, "touch", "system")
        time = 3_000L
        sessions.beginTurn(key.copy(senderId = "new"), "new", "system")

        assertEquals(2, sessions.sessionCount)
        assertSame(retained.session, sessions.beginTurn(key, "next", "system").session)
        val replacement = sessions.beginTurn(evictedKey, "fresh", "system")
        assertNotSame(evicted.session, replacement.session)
        sessions.saveTurn(evictedKey, evicted, "late", "late answer")
        assertEquals(2, sessions.buildMessages(evictedKey, "check", "system").size)
        assertEquals(2, sessions.sessionCount)
    }

    @Test fun `capacity pressure reclaims all expired conversations before five minutes`() {
        var time = 0L
        val sessions = SessionManager(ChatConfig(memoryTtlMinutes = 1), now = { time }, maxSessions = 3)
        save(sessions, key)
        time = 1_000L
        save(sessions, key.copy(senderId = "also expired"))
        time = 50_000L
        val liveKey = key.copy(senderId = "live")
        val live = save(sessions, liveKey)

        time = 61_000L
        sessions.beginTurn(key.copy(senderId = "new"), "new", "system")
        assertEquals(2, sessions.sessionCount)
        assertSame(live.session, sessions.beginTurn(liveKey, "next", "system").session)
    }

    @Test fun `default capacity bounds retained conversations`() {
        val sessions = SessionManager(ChatConfig(), now = { 0L })
        repeat(300) { user -> save(sessions, key.copy(senderId = "$user")) }
        assertEquals(256, sessions.sessionCount)
    }

    @Test fun `concurrent admissions and replies never exceed capacity`() = runBlocking {
        val sessions = SessionManager(ChatConfig(), now = { 0L }, maxSessions = 8)
        (0 until 128).map { user -> async(Dispatchers.Default) {
            save(sessions, key.copy(senderId = "$user"))
            assertTrue(sessions.sessionCount <= 8)
        } }.awaitAll()
        assertEquals(8, sessions.sessionCount)
    }

    @Test fun `shorter ttl applies at next conversation without waiting for cleanup`() {
        var time = 0L
        val config = ChatConfig(memoryTtlMinutes = 30)
        val sessions = SessionManager(config, now = { time })
        val old = save(sessions, key)
        time = 120_000L
        sessions.updateConfig(config.copy(memoryTtlMinutes = 1))

        val fresh = sessions.beginTurn(key, "next", "system")
        assertEquals(2, fresh.messages.size)
        assertNotSame(old.session, fresh.session)
        sessions.saveTurn(key, old, "old", "old answer")
        assertEquals(2, sessions.buildMessages(key, "check", "system").size)
    }

    @Test fun `extending ttl preserves existing history under the new policy`() {
        var time = 0L
        val config = ChatConfig(memoryTtlMinutes = 1)
        val sessions = SessionManager(config, now = { time })
        val old = save(sessions, key)
        time = 30_000L
        sessions.updateConfig(config.copy(memoryTtlMinutes = 30))
        time = 120_000L

        val next = sessions.beginTurn(key, "next", "system")
        assertSame(old.session, next.session)
        assertEquals(listOf("system", "question", "answer", "next"), next.messages.map { it.content })
    }

    @Test fun `config changes trim without renewing idle deadline and cannot restore discarded rounds`() {
        var time = 0L
        val config = ChatConfig(memoryTtlMinutes = 1)
        val sessions = SessionManager(config, now = { time })
        val latest = (0..2).map { save(sessions, key, "q$it", "a$it") }.last()
        time = 59_000L
        sessions.updateConfig(config.copy(memoryRounds = 1))
        sessions.updateConfig(config.copy(memoryRounds = 10, maxTokens = 2048))

        // Updating policy alone must neither count as activity nor bring discarded rounds back.
        assertEquals(listOf("q2", "a2"), latest.session!!.messages.map { it.content })
        time = 60_000L
        assertEquals(2, sessions.buildMessages(key, "next", "system").size)
    }

    @Test fun `memory disabled never allocates retained entries or saves disabled turns after reenable`() {
        val config = ChatConfig(enableMemory = false)
        val sessions = SessionManager(config, now = { 0L })
        val disabled = sessions.beginTurn(key, "disabled", "system")
        sessions.getOrCreate(key)
        sessions.saveToSession(key, "disabled", "disabled answer")
        assertEquals(0, sessions.sessionCount)
        assertNull(disabled.session)

        sessions.updateConfig(config.copy(enableMemory = true))
        save(sessions, key)
        sessions.saveTurn(key, disabled, "disabled", "disabled answer")
        assertEquals(listOf("system", "question", "answer", "next"),
            sessions.buildMessages(key, "next", "system").map { it.content })
    }

    private fun save(
        sessions: SessionManager,
        key: SessionManager.SessionKey,
        question: String = "question",
        answer: String = "answer",
    ): SessionManager.Turn = sessions.beginTurn(key, question, "system").also {
        sessions.saveTurn(key, it, question, answer)
    }
}
