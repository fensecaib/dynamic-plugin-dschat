package top.colter.dynamic.agent.agent

import top.colter.dynamic.agent.ds.ChatMessage
import top.colter.dynamic.agent.ds.Tool

interface Agent {
    val name: String
    val tools: List<Tool>
    val maxRounds: Int

    suspend fun prepareMessages(
        messages: MutableList<ChatMessage>,
        prompt: String,
        systemPrompt: String
    )

    suspend fun executeTool(
        toolName: String,
        arguments: String,
        toolCallId: String
    ): String?

    fun shouldContinue(round: Int): Boolean
}
