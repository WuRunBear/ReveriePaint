/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.comfy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * 一次 ComfyUI 调用的结果。
 *
 * 不抛异常上抛，是因为调用方（ViewModel）需要把失败**分门别类**地翻译成用户可读的提示：
 * 401 要说「检查凭据」，超时要说「检查地址」——两者混为一谈会让用户白折腾。
 */
internal sealed class ComfyCall<out T> {
    data class Ok<T>(val value: T) : ComfyCall<T>()

    /** @param detail 服务器返回的原始响应片段（截断后），用于排查 */
    data class Fail(
        val error: ComfyConnectionError,
        val detail: String? = null,
    ) : ComfyCall<Nothing>()
}

/**
 * ComfyUI HTTP 客户端。
 *
 * 只做参数搬运与错误分类，不持有任何 UI 状态。所有方法都在 [Dispatchers.IO] 上执行，
 * 调用方不需要再包一层 IO。
 *
 * 端点（ComfyUI 0.37 实测）：
 * - `POST /upload/image`   multipart 上传输入图
 * - `POST /prompt`         提交工作流，返回 `prompt_id`
 * - `POST /interrupt`      取消当前执行
 * - `GET  /queue`          队列状态
 * - `GET  /view`           取结果图
 * - `GET  /system_stats`   连通性探测（也用于「测试连接」）
 */
internal class ComfyClient private constructor(
    private val baseUrl: String,
    private val headers: Map<String, String>,
    private val client: OkHttpClient,
) {

    /** 本次连接是否携带了认证头。用于区分「没配凭据」与「凭据不对」的 401 */
    private val sentAuth: Boolean =
        headers.keys.any { it.equals(ComfyAuth.HEADER_AUTHORIZATION, ignoreCase = true) }

    private val authHeaders: Headers by lazy {
        val builder = Headers.Builder()
        for ((key, value) in headers) builder.add(key, value)
        builder.build()
    }

    /** WebSocket 端点，进度推送用 */
    fun webSocketUrl(clientId: String): String = ComfyUrl.webSocketUrl(baseUrl, clientId)

    /** 结果图的可访问 URL */
    fun viewUrl(ref: ComfyImageRef): String =
        ComfyUrl.join(baseUrl, "/view") +
            "?filename=" + enc(ref.filename) +
            "&subfolder=" + enc(ref.subfolder) +
            "&type=" + enc(ref.type)

    // -----------------------------------------------------------------------
    // 连通性
    // -----------------------------------------------------------------------

    /**
     * 探测服务器。10 秒超时——比正常请求短，因为这是用户盯着等的交互。
     *
     * 成功返回 ComfyUI 版本号（拿不到就返回空串），失败返回分类后的错误。
     */
    suspend fun probe(): ComfyCall<String> {
        val probeClient =
            client
                .newBuilder()
                .callTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        return request(probeClient, "system_stats") { body ->
            val obj = runCatching { JSONObject(body) }.getOrNull()
            obj?.optJSONObject("system")?.optString("comfyui_version").orEmpty()
        }
    }

    // -----------------------------------------------------------------------
    // 输入图上传
    // -----------------------------------------------------------------------

    /**
     * 上传一张输入图，返回 ComfyUI 侧的文件引用（把它喂给 `LoadImage` 节点）。
     *
     * [mime] 用 `image/webp` 即可——实测目标实例支持。
     */
    suspend fun uploadImage(
        bytes: ByteArray,
        filename: String,
        mime: String = "image/webp",
    ): ComfyCall<ComfyImageRef> =
        withContext(Dispatchers.IO) {
            try {
                val part =
                    MultipartBody
                        .Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("image", filename, bytes.toRequestBody(mime.toMediaType()))
                        .addFormDataPart("type", "input")
                        .addFormDataPart("overwrite", "true")
                        .build()
                val request =
                    Request
                        .Builder()
                        .url(ComfyUrl.join(baseUrl, "/upload/image"))
                        .headers(authHeaders)
                        .post(part)
                        .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext ComfyCall.Fail(classify(response.code, response), snippet(body))
                    }
                    val obj = runCatching { JSONObject(body) }.getOrNull()
                        ?: return@withContext ComfyCall.Fail(
                            ComfyConnectionError.ServerError(response.code),
                            "上传响应不是合法 JSON",
                        )
                    val name = obj.optString("name")
                    if (name.isEmpty()) {
                        return@withContext ComfyCall.Fail(
                            ComfyConnectionError.ServerError(response.code),
                            "上传响应缺少 name",
                        )
                    }
                    ComfyCall.Ok(
                        ComfyImageRef(
                            filename = name,
                            subfolder = obj.optString("subfolder"),
                            type = obj.optString("type").ifEmpty { "input" },
                        ),
                    )
                }
            } catch (e: Exception) {
                ComfyCall.Fail(ComfyConnectionError.Unreachable, e.message)
            }
        }

    // -----------------------------------------------------------------------
    // 提交与取消
    // -----------------------------------------------------------------------

    /**
     * 提交工作流，返回 `prompt_id`。
     *
     * [promptJson] 是**已注入用户参数**的 API 格式工作流（见 [ComfyPromptBuilder]）。
     * [clientId] 必须与 WebSocket 用同一个，否则收不到这台客户端的进度推送。
     */
    suspend fun submitPrompt(
        promptJson: String,
        clientId: String,
    ): ComfyCall<String> =
        withContext(Dispatchers.IO) {
            try {
                val payload =
                    JSONObject().apply {
                        put("prompt", JSONObject(promptJson))
                        put("client_id", clientId)
                    }
                val body = payload.toString().toRequestBody(JSON_MEDIA)
                val request =
                    Request
                        .Builder()
                        .url(ComfyUrl.join(baseUrl, "/prompt"))
                        .headers(authHeaders)
                        .post(body)
                        .build()
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext ComfyCall.Fail(classify(response.code, response), snippet(text))
                    }
                    val obj = runCatching { JSONObject(text) }.getOrNull()
                    val id = obj?.optString("prompt_id").orEmpty()
                    if (id.isEmpty()) {
                        // ComfyUI 在节点校验失败时返回 200 + node_errors, 这里如实带出来
                        val nodeErrors = obj?.optJSONObject("node_errors")?.toString()
                        return@withContext ComfyCall.Fail(
                            ComfyConnectionError.ServerError(response.code),
                            nodeErrors ?: text,
                        )
                    }
                    ComfyCall.Ok(id)
                }
            } catch (e: Exception) {
                ComfyCall.Fail(ComfyConnectionError.Unreachable, e.message)
            }
        }

    /** 取消当前执行（`POST /interrupt`）。队列里没任务时 ComfyUI 也返回 200 */
    suspend fun interrupt(): ComfyCall<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val request =
                    Request
                        .Builder()
                        .url(ComfyUrl.join(baseUrl, "/interrupt"))
                        .headers(authHeaders)
                        .post(EMPTY_BODY)
                        .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        ComfyCall.Ok(Unit)
                    } else {
                        ComfyCall.Fail(classify(response.code, response))
                    }
                }
            } catch (e: Exception) {
                ComfyCall.Fail(ComfyConnectionError.Unreachable, e.message)
            }
        }

    // -----------------------------------------------------------------------
    // 队列与结果
    // -----------------------------------------------------------------------

    /** 队列里还剩几个任务（不含正在跑的那个） */
    suspend fun queueRemaining(): ComfyCall<Int> =
        request("queue") { body ->
            val obj = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
            obj.optJSONArray("queue_pending")?.length() ?: 0
        }

    /**
     * 取结果图字节。
     *
     * 单张结果可达数 MB，用独立的长超时 client，避免和探测共用短超时。
     */
    suspend fun fetchImage(ref: ComfyImageRef): ComfyCall<ByteArray> =
        withContext(Dispatchers.IO) {
            val fetchClient =
                client
                    .newBuilder()
                    .callTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build()
            try {
                val request = Request.Builder().url(viewUrl(ref)).headers(authHeaders).get().build()
                fetchClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext ComfyCall.Fail(classify(response.code, response))
                    }
                    val bytes = response.body?.bytes()
                    if (bytes == null || bytes.isEmpty()) {
                        return@withContext ComfyCall.Fail(
                            ComfyConnectionError.ServerError(response.code),
                            "结果图为空",
                        )
                    }
                    ComfyCall.Ok(bytes)
                }
            } catch (e: Exception) {
                ComfyCall.Fail(ComfyConnectionError.Unreachable, e.message)
            }
        }

    // -----------------------------------------------------------------------
    // 内部
    // -----------------------------------------------------------------------

    private suspend fun <T> request(
        path: String,
        parse: (String) -> T,
    ): ComfyCall<T> = request(client, path, parse)

    private suspend fun <T> request(
        httpClient: OkHttpClient,
        path: String,
        parse: (String) -> T,
    ): ComfyCall<T> =
        withContext(Dispatchers.IO) {
            try {
                val request =
                    Request
                        .Builder()
                        .url(ComfyUrl.join(baseUrl, "/$path"))
                        .headers(authHeaders)
                        .get()
                        .build()
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext ComfyCall.Fail(classify(response.code, response), snippet(body))
                    }
                    ComfyCall.Ok(parse(body))
                }
            } catch (e: SocketTimeoutException) {
                ComfyCall.Fail(ComfyConnectionError.Unreachable, "超时")
            } catch (e: Exception) {
                ComfyCall.Fail(ComfyConnectionError.Unreachable, e.message)
            }
        }

    /**
     * 把 HTTP 状态映射成用户可理解的错误。
     *
     * 实测：ComfyUI 开 Basic 认证后，**带错凭据与不带凭据返回一模一样的 401**
     * （含相同的 `WWW-Authenticate`），所以必须把「本次是否发了认证头」也喂给分类器。
     */
    private fun classify(
        code: Int,
        response: okhttp3.Response,
    ): ComfyConnectionError =
        ComfyErrorClassifier.classify(
            code = code,
            sentAuth = sentAuth,
            hasWwwAuthenticateHeader = response.header("WWW-Authenticate") != null,
        )

    private fun snippet(body: String): String? =
        body.trim().takeIf { it.isNotEmpty() }?.take(MAX_DETAIL_CHARS)

    private fun enc(value: String): String =
        try {
            java.net.URLEncoder.encode(value, "UTF-8")
        } catch (e: Exception) {
            value
        }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null, 0, 0)
        private const val PROBE_TIMEOUT_SECONDS = 10L
        private const val FETCH_TIMEOUT_SECONDS = 120L
        private const val MAX_DETAIL_CHARS = 400

        /**
         * 建客户端。[creds] 的 baseUrl 会先规范化；地址非法时返回 null，
         * 由调用方提示用户改地址（而不是在这里抛异常）。
         */
        fun create(
            creds: ComfyCredentials,
            httpClient: OkHttpClient,
        ): ComfyClient? {
            val normalized = ComfyUrl.normalize(creds.baseUrl)
            if (normalized !is ComfyUrl.UrlResult.Ok) return null
            return ComfyClient(
                baseUrl = normalized.base,
                headers = ComfyAuth.buildHeaders(creds),
                client = httpClient,
            )
        }
    }
}
