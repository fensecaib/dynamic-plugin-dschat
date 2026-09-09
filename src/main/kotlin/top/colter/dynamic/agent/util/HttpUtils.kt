package top.colter.dynamic.agent.util

import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

object HttpUtils {
    /** 阻塞式 HttpClient.send 在 IO 线程执行，并在协程取消时中断请求。 */
    suspend fun httpGetAsync(url: String, headers: Map<String, String> = emptyMap(), params: Map<String, String> = emptyMap()): HttpResponse<String> =
        runInterruptible(Dispatchers.IO) { httpGet(url, headers, params) }

    suspend fun httpGetBytesAsync(url: String): HttpResponse<ByteArray> =
        runInterruptible(Dispatchers.IO) { httpGetBytes(url) }

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val defaultClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun createClient(connectTimeoutSec: Long = 15, requestTimeoutSec: Long = 30): HttpClient {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(connectTimeoutSec))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    fun httpGet(
        url: String,
        headers: Map<String, String> = emptyMap(),
        params: Map<String, String> = emptyMap(),
        timeoutSec: Long = 30,
        client: HttpClient = defaultClient
    ): HttpResponse<String> {
        val uriBuilder = StringBuilder(url)
        if (params.isNotEmpty()) {
            uriBuilder.append("?")
            uriBuilder.append(params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" })
        }
        val reqBuilder = HttpRequest.newBuilder()
            .uri(URI.create(uriBuilder.toString()))
            .timeout(Duration.ofSeconds(timeoutSec))
            .GET()
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        return client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString())
    }

    fun httpPost(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Long = 30,
        client: HttpClient = defaultClient
    ): HttpResponse<String> {
        val reqBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSec))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        return client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString())
    }

    fun httpGetBytes(
        url: String,
        timeoutSec: Long = 30,
        client: HttpClient = defaultClient
    ): HttpResponse<ByteArray> {
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSec))
            .GET()
            .build()
        return client.send(req, HttpResponse.BodyHandlers.ofByteArray())
    }
}
