package top.colter.dynamic.agent.ds

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val thinking: ThinkingConfig? = ThinkingConfig(),
    val stream: Boolean = false,
    @SerialName("max_tokens")
    val maxTokens: Int = 4096,
    val tools: List<Tool>? = null,
    @SerialName("tool_choice")
    val toolChoice: String? = null,
    @SerialName("response_format")
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val responseFormat: Map<String, String>? = null,
)

@Serializable
data class ThinkingConfig(
    val type: String = "disabled"
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null,
    val name: String? = null,
)

@Serializable
data class Tool(
    val type: String = "function",
    val function: FunctionDef,
)

@Serializable
data class FunctionDef(
    val name: String,
    val description: String,
    val parameters: ParametersDef,
)

@Serializable
data class ParametersDef(
    val type: String = "object",
    val properties: Map<String, PropertyDef>,
    val required: List<String> = emptyList(),
)

@Serializable
data class PropertyDef(
    val type: String,
    val description: String,
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall,
)

@Serializable
data class FunctionCall(
    val name: String,
    val arguments: String,
)

@Serializable
data class ChatResponse(
    val id: String = "",
    val choices: List<ChatChoice> = emptyList(),
    val usage: ChatUsage? = null,
)

@Serializable
data class ChatChoice(
    @SerialName("finish_reason")
    val finishReason: String = "",
    val message: ChatMessage? = null,
)

@Serializable
data class ChatUsage(
    @SerialName("completion_tokens")
    val completionTokens: Int = 0,
    @SerialName("prompt_tokens")
    val promptTokens: Int = 0,
    @SerialName("total_tokens")
    val totalTokens: Int = 0,
)
