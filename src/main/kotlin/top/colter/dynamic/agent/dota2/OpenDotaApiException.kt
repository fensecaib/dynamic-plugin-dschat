package top.colter.dynamic.agent.dota2

import java.io.IOException
import java.net.http.HttpTimeoutException

internal class OpenDotaApiException(val userMessage: String, cause: Throwable? = null) : RuntimeException(userMessage, cause)

internal fun openDotaHttpFailure(status: Int): OpenDotaApiException {
    val reason = when (status) {
        522 -> "Cloudflare 连接 OpenDota 源服务器超时"
        524 -> "OpenDota 源服务器响应超时"
        429 -> "请求受到 OpenDota 限流"
        401, 403 -> "OpenDota 拒绝访问，请检查接口访问权限或网络出口限制"
        in 500..599 -> "OpenDota 服务端或网关异常"
        else -> "OpenDota 接口请求失败"
    }
    return OpenDotaApiException("当前 OpenDota 服务不可用：$reason（HTTP $status），请稍后重试。")
}

internal fun openDotaNetworkFailure(error: IOException): OpenDotaApiException {
    val reason = if (error is HttpTimeoutException) "请求超时，可能是服务响应缓慢或网络连接异常"
        else "无法连接或读取接口响应，请检查宿主网络、DNS 或代理连接"
    return OpenDotaApiException("当前无法访问 OpenDota 服务：$reason，请稍后重试。", error)
}
