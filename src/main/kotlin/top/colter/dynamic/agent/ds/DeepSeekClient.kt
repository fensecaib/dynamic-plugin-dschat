package top.colter.dynamic.agent.ds

import kotlinx.coroutines.delay
import top.colter.dynamic.agent.util.HttpUtils
import java.net.http.HttpClient
import java.net.http.HttpResponse
import kotlin.time.Duration.Companion.seconds

class DeepSeekClient(private val apiKey: String, private val apiUrl: String) {

    private val client: HttpClient = HttpUtils.createClient(
        connectTimeoutSec = 30,
        requestTimeoutSec = 120
    )

    suspend fun chat(request: ChatRequest, retries: Int = 2): Result<ChatResponse> {
        val requestBody = HttpUtils.json.encodeToString(ChatRequest.serializer(), request)

        var lastException: Exception? = null
        repeat(retries + 1) { attempt ->
            if (attempt > 0) {
                delay((attempt * 2L).seconds)
            }
            try {
                val response = HttpUtils.httpPost(
                    url = apiUrl,
                    body = requestBody,
                    headers = mapOf("Authorization" to "Bearer $apiKey"),
                    timeoutSec = 120,
                    client = client
                )

                if (response.statusCode() in 200..299) {
                    val chatResponse = HttpUtils.json.decodeFromString(
                        ChatResponse.serializer(), response.body()
                    )
                    return Result.success(chatResponse)
                }

                val statusCode = response.statusCode()
                val errorBody = response.body()

                if (statusCode in 500..599) {
                    lastException = RuntimeException("Server error: $statusCode")
                    return@repeat
                }

                return Result.failure(
                    RuntimeException("API 请求失败 ($statusCode): $errorBody")
                )
            } catch (e: Exception) {
                lastException = e
            }
        }
        return Result.failure(lastException ?: RuntimeException("请求失败"))
    }

    fun close() {
        // HttpClient doesn't need explicit close
    }
}
