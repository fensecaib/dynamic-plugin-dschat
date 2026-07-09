package top.colter.dynamic.agent.agent

import top.colter.dynamic.agent.ds.ChatMessage
import top.colter.dynamic.agent.ds.Tool

class ChatAgent : Agent {
    override val name = "chat"
    override val tools: List<Tool> = emptyList()
    override val maxRounds = 0

    override suspend fun prepareMessages(
        messages: MutableList<ChatMessage>,
        prompt: String,
        systemPrompt: String
    ) {}

    override suspend fun executeTool(
        toolName: String,
        arguments: String,
        toolCallId: String
    ): String? = null

    override fun shouldContinue(round: Int): Boolean = false
}
