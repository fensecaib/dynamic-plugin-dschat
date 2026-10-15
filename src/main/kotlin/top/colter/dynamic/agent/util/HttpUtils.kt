package top.colter.dynamic.agent.util

import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

object HttpUtils {
    /** Bound the complete body as well as headers; JDK request timeout alone only covers headers. */
    suspend fun httpGetAsync(url: String, headers: Map<String, String> = emptyMap(), params: Map<String, String> = emptyMap(), timeoutSec: Long = 30, client: HttpClient = defaultClient): HttpResponse<String> =
        withHttpTimeout(timeoutSec) { httpGet(url, headers, params, timeoutSec, client) }

    suspend fun httpGetBytesAsync(url: String, timeoutSec: Long = 30, client: HttpClient = defaultClient): HttpResponse<ByteArray> =
        withHttpTimeout(timeoutSec) { httpGetBytes(url, timeoutSec, client) }

    suspend fun httpPostAsync(url: String, body: String, headers: Map<String, String> = emptyMap(), timeoutSec: Long = 30, client: HttpClient = defaultClient): HttpResponse<String> =
        withHttpTimeout(timeoutSec) { httpPost(url, body, headers, timeoutSec, client) }

    private suspend fun <T : Any> withHttpTimeout(timeoutSec: Long, block: () -> T): T {
        require(timeoutSec > 0) { "HTTP timeout must be positive" }
        return withTimeoutOrNull(timeoutSec.seconds) {
            runInterruptible(Dispatchers.IO) { block() }
        } ?: throw HttpTimeoutException("HTTP response deadline exceeded")
    }

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
