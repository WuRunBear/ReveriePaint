/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.comfy

import android.os.Handler
import android.os.Looper
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [ComfySocket] 上抛的事件。
 *
 * 与 [ComfyMessageParser.Event] 的区别只有一点, 但很关键: **每一条都带 `promptId`**,
 * 且 [Executed] 额外带 `node`。原因见 [ComfySocket] 的头注释 —— ComfyUI 的 `executed`
 * 消息按**产图节点**给 outputs, 而 bindings 里的 `outputNodeId` 未必等于那个节点,
 * 多任务并发时只靠 parser 的 Event 无法正确归属结果。
 */
internal sealed class ComfySocketEvent {
    data class ExecutionStart(val promptId: String?) : ComfySocketEvent()

    data class Progress(val promptId: String?, val value: Int, val max: Int) : ComfySocketEvent()

    data class Executing(val promptId: String?, val node: String?) : ComfySocketEvent()

    data class Executed(
        val promptId: String?,
        val node: String?,
        val images: List<ComfyImageRef>,
    ) : ComfySocketEvent()

    data class ExecutionError(
        val promptId: String?,
        val message: String,
        val type: String,
    ) : ComfySocketEvent()
}

/**
 * ComfyUI 进度 WebSocket 客户端。
 *
 * 只负责「连 / 收 / 解析 / 上抛 / 关」, 不持有任何 UI 状态。消息解析复用已提交的
 * [ComfyMessageParser]; 这里是**追加一层原始 JSON 解析**取 parser 没暴露的两个字段:
 *
 * - `data.node` —— `executed` 的产图节点 id (ISSUES.md P1: parser 的 `Executed` 不带节点 id,
 *   而它是已提交且有测试的文件, 不改它, 在 socket 层补);
 * - `data.prompt_id` —— 每条消息都带, 多任务并发时按它归属结果, 避免串批。
 *
 * 另外对 `executed` 的 outputs 做了**形状兜底**: parser 读 `data.outputs.images`, 实测部分
 * ComfyUI 版本发的是 `data.output.images` (单数); parser 拿到空列表时这里再读一次单数形式。
 *
 * 所有回调都经主线程 Handler 派发, 调用方可以直接写 Compose 状态。
 * [connect] 失败、[WebSocketListener.onFailure]、服务端 [WebSocketListener.onClosed] 三者
 * 只会触发 `onFailure` / `onClosed` 中的一个, 保证调用方不会把任务永远吊在「生成中」。
 */
internal class ComfySocket(
    private val client: ComfyClient,
    private val clientId: String,
    private val onEvent: (ComfySocketEvent) -> Unit,
    private val onFailure: (ComfyConnectionError, String?) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 已收到终止信号 (失败 / 正常关闭 / 主动 close), 防止重复回调 */
    private val terminated = AtomicBoolean(false)

    /** 主动关闭标记: 主动 close 后 OkHttp 还会回调 onClosed, 不能算「断线」 */
    @Volatile
    private var userClosed = false

    @Volatile
    private var webSocket: WebSocket? = null

    fun connect() {
        if (terminated.get()) return
        webSocket =
            try {
                client.newWebSocket(client.webSocketRequest(clientId), listener)
            } catch (t: Throwable) {
                notifyFailure(ComfyConnectionError.Unreachable, t.message)
                null
            }
    }

    fun close() {
        userClosed = true
        terminated.set(true)
        webSocket?.close(NORMAL_CLOSURE, "client closed")
        webSocket = null
    }

    // ---------------------------------------------------------------- 内部

    private val listener =
        object : WebSocketListener() {
            override fun onOpen(
                webSocket: WebSocket,
                response: Response,
            ) = Unit

            override fun onMessage(
                webSocket: WebSocket,
                text: String,
            ) {
                dispatch(text)
            }

            override fun onClosing(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) {
                webSocket.close(NORMAL_CLOSURE, null)
            }

            override fun onFailure(
                webSocket: WebSocket,
                t: Throwable,
                response: Response?,
            ) {
                if (!userClosed) notifyFailure(client.classifyHandshakeFailure(response), t.message)
            }

            override fun onClosed(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) {
                if (userClosed) return
                if (terminated.compareAndSet(false, true)) mainHandler.post { onClosed() }
            }
        }

    private fun notifyFailure(
        error: ComfyConnectionError,
        detail: String?,
    ) {
        if (!terminated.compareAndSet(false, true)) return
        mainHandler.post { onFailure(error, detail) }
    }

    private fun dispatch(text: String) {
        if (terminated.get()) return
        val raw = runCatching { JSONObject(text.trim()) }.getOrNull()
        val data = raw?.optJSONObject("data")
        val promptId = optString(data, "prompt_id")

        val mapped =
            when (val event = ComfyMessageParser.parse(text)) {
                is ComfyMessageParser.Event.ExecutionStart -> ComfySocketEvent.ExecutionStart(promptId)
                is ComfyMessageParser.Event.Progress ->
                    ComfySocketEvent.Progress(promptId, event.value, event.max)
                is ComfyMessageParser.Event.Executing -> ComfySocketEvent.Executing(promptId, event.node)
                is ComfyMessageParser.Event.Executed ->
                    ComfySocketEvent.Executed(
                        promptId = promptId,
                        node = optString(data, "node"),
                        images = event.images.ifEmpty { fallbackImages(data) },
                    )
                is ComfyMessageParser.Event.ExecutionError ->
                    ComfySocketEvent.ExecutionError(promptId, event.message, event.type)
                // status (queue) 与未知消息不驱动 UI: 排队数由本地任务表算
                else -> null
            }
        if (mapped != null) mainHandler.post { if (!terminated.get()) onEvent(mapped) }
    }

    /** parser 读不到 `data.outputs.images` 时, 再试实测的 `data.output.images` (单数) */
    private fun fallbackImages(data: JSONObject?): List<ComfyImageRef> {
        val array =
            data?.optJSONObject("output")?.optJSONArray("images")
                ?: data?.optJSONObject("outputs")?.optJSONArray("images")
                ?: return emptyList()
        return decodeImages(array)
    }

    private fun decodeImages(array: JSONArray): List<ComfyImageRef> {
        val out = ArrayList<ComfyImageRef>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val filename = optString(item, "filename") ?: continue
            if (filename.isBlank()) continue
            out +=
                ComfyImageRef(
                    filename = filename,
                    subfolder = optString(item, "subfolder") ?: "",
                    type = optString(item, "type") ?: "",
                )
        }
        return out
    }

    /** JSONObject.NULL 会读成字符串 "null", 统一处理 */
    private fun optString(
        obj: JSONObject?,
        key: String,
    ): String? {
        if (obj == null) return null
        val value = obj.opt(key)
        if (value == null || value === JSONObject.NULL) return null
        return when (value) {
            is String -> value.takeIf { it.isNotEmpty() }
            else -> value.toString().takeIf { it.isNotEmpty() }
        }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}
