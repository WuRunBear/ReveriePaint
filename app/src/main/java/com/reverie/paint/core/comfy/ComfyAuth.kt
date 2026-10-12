package com.reverie.paint.core.comfy

/**
 * 认证头构造。
 *
 * Base64 自行实现而不使用 `java.util.Base64`（API 26+）或 `android.util.Base64`（单元测试不可用），
 * 以兼容 minSdk 23 且保持纯 Kotlin 可单测。
 */
internal object ComfyAuth {

    const val HEADER_AUTHORIZATION = "Authorization"
    private const val SCHEME_BASIC = "Basic"
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** 用户名与密码都存在时才生成 Basic 头；[ComfyCredentials.extraHeaders] 同名（含大小写差异）时覆盖它 */
    fun buildHeaders(creds: ComfyCredentials): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        basicHeader(creds)?.let { out[HEADER_AUTHORIZATION] = it }
        for ((key, value) in creds.extraHeaders) {
            out.keys.firstOrNull { it.equals(key, ignoreCase = true) }?.let { out.remove(it) }
            out[key] = value
        }
        return out
    }

    internal fun basicHeader(creds: ComfyCredentials): String? {
        val user = creds.username
        val password = creds.password
        if (user.isNullOrBlank() || password == null) return null
        return "$SCHEME_BASIC ${encodeBase64("$user:$password")}"
    }

    /** 标准 Base64（含 `=` 填充），按 UTF-8 编码输入 */
    internal fun encodeBase64(input: String): String {
        val bytes = input.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) return ""
        val out = StringBuilder((bytes.size + 2) / 3 * 4)

        var i = 0
        while (i + 2 < bytes.size) {
            val chunk = (byteAt(bytes, i) shl 16) or (byteAt(bytes, i + 1) shl 8) or byteAt(bytes, i + 2)
            out.append(ALPHABET[chunk ushr 18 and 0x3F])
            out.append(ALPHABET[chunk ushr 12 and 0x3F])
            out.append(ALPHABET[chunk ushr 6 and 0x3F])
            out.append(ALPHABET[chunk and 0x3F])
            i += 3
        }

        when (bytes.size - i) {
            1 -> {
                val chunk = byteAt(bytes, i) shl 16
                out.append(ALPHABET[chunk ushr 18 and 0x3F])
                out.append(ALPHABET[chunk ushr 12 and 0x3F])
                out.append("==")
            }
            2 -> {
                val chunk = (byteAt(bytes, i) shl 16) or (byteAt(bytes, i + 1) shl 8)
                out.append(ALPHABET[chunk ushr 18 and 0x3F])
                out.append(ALPHABET[chunk ushr 12 and 0x3F])
                out.append(ALPHABET[chunk ushr 6 and 0x3F])
                out.append('=')
            }
        }
        return out.toString()
    }

    private fun byteAt(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and 0xFF
}
