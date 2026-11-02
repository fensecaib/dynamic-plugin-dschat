package top.colter.dynamic.agent.config

import java.net.URI

/** Only a direct, secure official endpoint may donate its chat credential to official tools. */
internal fun isOfficialDeepSeekEndpoint(apiUrl: String): Boolean = runCatching {
    val endpoint = URI(apiUrl.trim())
    endpoint.scheme.equals("https", ignoreCase = true) &&
        endpoint.host.equals("api.deepseek.com", ignoreCase = true) &&
        endpoint.rawUserInfo == null && endpoint.port in listOf(-1, 443)
}.getOrDefault(false)

/** An explicitly configured official Key takes priority; third-party credentials never leave their provider. */
internal fun resolveSearchApiKey(config: AgentPluginConfig): String =
    config.webSearch.officialApiKey.trim().ifEmpty {
        if (isOfficialDeepSeekEndpoint(config.api.apiUrl)) config.api.apiKey.trim() else ""
    }
