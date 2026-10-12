package com.reverie.paint.core.comfy

/** ComfyUI 连接/调用错误分类 */
internal sealed class ComfyConnectionError {
    /** 已发送凭据但仍被拒：凭据错误或服务端未开启 Basic 认证 */
    data object AuthFailed : ComfyConnectionError()

    /** 服务端要求认证，但本次请求根本没带认证头 */
    data object AuthNotConfigured : ComfyConnectionError()

    /** 连接失败 / 超时；由调用方在捕获 IO 异常后映射 */
    data object Unreachable : ComfyConnectionError()

    data class ServerError(val code: Int) : ComfyConnectionError()
}

/**
 * 错误分类。
 *
 * 实测教训：ComfyUI 开启 Basic 认证后，**带错凭据和不带凭据都返回一模一样的 401**，
 * 因此不能只看状态码，必须结合「本次请求是否真的发了认证头」与响应头判断。
 */
internal object ComfyErrorClassifier {

    fun classify(
        code: Int,
        sentAuth: Boolean,
        hasWwwAuthenticateHeader: Boolean,
    ): ComfyConnectionError {
        // 401 且没发认证头 -> 用户根本没配凭据，去引导配置而不是提示「密码错误」
        if (code == 401 && !sentAuth) return ComfyConnectionError.AuthNotConfigured
        // 401/403 且（发了认证头 或 服务端回了 WWW-Authenticate 挑战）-> 认证失败
        if (code == 401 || code == 403) {
            if (sentAuth || hasWwwAuthenticateHeader) return ComfyConnectionError.AuthFailed
        }
        return ComfyConnectionError.ServerError(code)
    }
}
