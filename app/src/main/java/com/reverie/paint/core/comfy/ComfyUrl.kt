package com.reverie.paint.core.comfy

import java.net.URLEncoder

/** ComfyUI 连接凭据 */
internal data class ComfyCredentials(
    val baseUrl: String,
    val username: String?,
    val password: String?,
    val extraHeaders: Map<String, String> = emptyMap(),
)

/**
 * base URL 规范化与拼接。用户手填地址，缺 scheme / 带空白 / 带末尾斜杠都要能兜住。
 *
 * 协议缺失时：IP 或 localhost 用 `http`（局域网 ComfyUI 常见且通常没有证书），
 * 其余一律用 `https`。
 */
internal object ComfyUrl {

    sealed class UrlResult {
        /** 规范化后的 base，不带末尾斜杠 */
        data class Ok(val base: String) : UrlResult()

        data class Invalid(val reason: String) : UrlResult()
    }

    private val SCHEME_REGEX = Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*://""")
    private val IPV4_REGEX = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    fun normalize(input: String): UrlResult {
        var text = input.trim()
        if (text.isEmpty()) return UrlResult.Invalid("地址为空")

        // base URL 不应带查询串 / fragment，否则拼接路径会得到非法 URL
        text = text.substringBefore('#').substringBefore('?').trim()
        if (text.isEmpty()) return UrlResult.Invalid("地址为空")

        // 必须在剥末尾斜杠之前判断 scheme，否则 "http://" 会被削成 "http:"
        if (!SCHEME_REGEX.containsMatchIn(text)) {
            text = "${defaultScheme(hostNameOf(text))}://$text"
        }

        val scheme = text.substringBefore("://").lowercase()
        val authority = text.substringAfter("://").substringBefore('/')
        val normalized = when (scheme) {
            "ws" -> "http"
            "wss", "https" -> "https"
            "http" -> "http"
            else -> return UrlResult.Invalid("不支持的协议: $scheme")
        }
        if (authority.isEmpty()) return UrlResult.Invalid("缺少主机名")
        return UrlResult.Ok(rewriteScheme(trimTrailingSlashes(text), normalized))
    }

    /** base + path，正确处理斜杠，永远不产生 `//` */
    fun join(base: String, path: String): String {
        val head = base.trim().trimEnd('/')
        val tail = path.trim().trimStart('/')
        if (tail.isEmpty()) return head
        return "$head/$tail"
    }

    /** http -> ws, https -> wss，再拼接 WebSocket 端点 */
    fun webSocketUrl(base: String, clientId: String): String {
        val trimmed = base.trim()
        val ws = when {
            trimmed.startsWith("https://", ignoreCase = true) -> "wss://" + trimmed.substring(8)
            trimmed.startsWith("http://", ignoreCase = true) -> "ws://" + trimmed.substring(7)
            else -> trimmed
        }
        val id = try {
            URLEncoder.encode(clientId, "UTF-8")
        } catch (e: Exception) {
            clientId
        }
        return join(ws, "/ws?clientId=$id")
    }

    private fun defaultScheme(host: String): String = if (isLocalHost(host)) "http" else "https"

    private fun isLocalHost(host: String): Boolean {
        val name = hostNameOf(host).lowercase()
        if (name.isEmpty()) return false
        return IPV4_REGEX.matches(name) ||
            name == "0.0.0.0" ||
            name.startsWith("127.") ||
            name == "::1" ||
            name == "localhost" ||
            name.endsWith(".localhost")
    }

    /** 从 `scheme://user@host:port/path` / `host:port` / `host` 中取出主机名部分 */
    private fun hostNameOf(text: String): String {
        val rest = if (SCHEME_REGEX.containsMatchIn(text)) text.substringAfter("://") else text
        val authority = rest
            .substringAfterLast('@')
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        return if (authority.startsWith("[")) {
            authority.substringBefore(']').removePrefix("[")
        } else {
            authority.substringBefore(':')
        }
    }

    private fun rewriteScheme(url: String, scheme: String): String =
        "$scheme://" + url.substringAfter("://")

    /** 只剥离 host 之后那一段的末尾斜杠，保留 scheme 里的 `://` */
    private fun trimTrailingSlashes(url: String): String {
        val sep = url.indexOf("://")
        if (sep < 0) return url.trimEnd('/')
        return url.substring(0, sep + 3) + url.substring(sep + 3).trimEnd('/')
    }
}
