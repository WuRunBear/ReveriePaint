package com.reverie.paint.core.comfy

import org.json.JSONObject

/** ComfyUI 输出图片的引用 */
internal data class ComfyImageRef(
    val filename: String,
    val subfolder: String,
    val type: String,
)

/**
 * ComfyUI WebSocket 消息解析器。
 *
 * **绝不抛异常**：数据来自不可信的网络，任何畸形 JSON / 未知 type 都降级为
 * [ComfyMessageParser.Event.Unknown]，调用方只需按类型分发即可。
 */
internal object ComfyMessageParser {

    sealed class Event {
        data object ExecutionStart : Event()

        data class Progress(val value: Int, val max: Int) : Event()

        /** [node] 为 null 表示执行结束 */
        data class Executing(val node: String?) : Event()

        data class Executed(val images: List<ComfyImageRef>) : Event()

        data class ExecutionError(val message: String, val type: String) : Event()

        data class QueueStatus(val remaining: Int) : Event()

        /** 未知或无法解析的消息，[type] 为原始 type（无法解析时为空串） */
        data class Unknown(val type: String) : Event()
    }

    fun parse(raw: String): Event {
        val root = try {
            JSONObject(raw.trim())
        } catch (e: Exception) {
            return Event.Unknown("")
        }
        val type = optString(root, "type") ?: return Event.Unknown("")
        val data = root.optJSONObject("data")

        return when (type) {
            "execution_start" -> Event.ExecutionStart
            "progress" -> progress(data)
            "executing" -> Event.Executing(optString(data, "node"))
            "executed" -> Event.Executed(images(data))
            "execution_error" -> Event.ExecutionError(
                message = optString(data, "exception_message") ?: "",
                type = optString(data, "exception_type") ?: "",
            )
            "status" -> Event.QueueStatus(
                optInt(data?.optJSONObject("status")?.optJSONObject("exec_info"), "queue_remaining") ?: 0,
            )
            // 含 execution_cached 等当前不消费的消息, 统一走 Unknown 分支并保留原始 type 便于诊断
            else -> Event.Unknown(type)
        }
    }

    /** 缺 max 时回退为 value, 保证调用方不会拿到 0 分母 */
    private fun progress(data: JSONObject?): Event.Progress {
        val value = (optInt(data, "value") ?: 0).coerceAtLeast(0)
        val rawMax = optInt(data, "max") ?: 0
        return Event.Progress(value, if (rawMax > 0) rawMax else value)
    }

    /** 跳过非对象项与空 filename, 只保留可用的输出图片 */
    private fun images(data: JSONObject?): List<ComfyImageRef> {
        val array = data?.optJSONObject("outputs")?.optJSONArray("images") ?: return emptyList()
        val out = ArrayList<ComfyImageRef>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val filename = optString(item, "filename") ?: continue
            if (filename.isBlank()) continue
            out += ComfyImageRef(
                filename = filename,
                subfolder = optString(item, "subfolder") ?: "",
                type = optString(item, "type") ?: "",
            )
        }
        return out
    }

    /** JSONObject.NULL 不能用 optString 读, 会拿到字符串 "null", 这里统一处理 */
    private fun optString(obj: JSONObject?, key: String): String? {
        if (obj == null) return null
        val value = obj.opt(key)
        if (value == null || value === JSONObject.NULL) return null
        return value as? String ?: value.toString()
    }

    private fun optInt(obj: JSONObject?, key: String): Int? {
        if (obj == null) return null
        return when (val value = obj.opt(key)) {
            null, JSONObject.NULL -> null
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }
    }
}
